package com.macro.mall.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.sun.net.httpserver.HttpServer;

import com.macro.mall.agent.model.ModelClient;
import com.macro.mall.agent.model.ModelException;
import com.macro.mall.agent.model.ModelMessage;
import com.macro.mall.agent.model.ModelRequest;
import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.storefront.PortalException;

/**
 * 用真实 loopback HTTP 服务端验证生产 {@link AgentRuntimeConfiguration} 构造的模型/门户客户端
 * <strong>不跟随 HTTP 重定向</strong>。
 *
 * <p>第一个服务端对所有请求返回跨域 302（{@code Location} 指向第二个服务端），第二个服务端
 * 返回合法成功响应并记录收到的 {@code Authorization}。若客户端跟随跳转，跳转目标就会收到请求
 * （甚至带上 Bearer Key/会员 Token）；本测试断言目标端 <strong>0 次请求</strong>，且客户端按固定安全
 * 错误失败、异常不携带合成标记或凭据。
 *
 * <p>测试刻意走 {@link AgentRuntimeConfiguration#modelClient} 与
 * {@link AgentRuntimeConfiguration#mallPortalClient} 这两个生产工厂方法（而非仅
 * {@code MockRestServiceServer}），因此能覆盖生产 {@code HttpClient.Redirect} 配置。
 * 不使用真实模型/门户/Redis，也不使用真实凭据；Key 与 Token 均为合成占位值。
 */
class AgentRuntimeConfigurationHttpBoundaryTest {

    /** 合成占位模型 Key；不是真实凭据，只用于脱敏断言。 */
    private static final String API_KEY = "sk-redirect-boundary-synthetic-key";
    /** 合成占位会员 Token；不是真实凭据，只用于脱敏断言。 */
    private static final String MEMBER_TOKEN = "Bearer redirect-boundary-synthetic-token";
    /** 合成重定向响应正文标记；用于断言异常不泄漏上游正文。 */
    private static final String REDIRECT_MARKER = "redirect-upstream-secret-must-not-leak";

    private static final String MODEL_TARGET_BODY =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
    private static final String PORTAL_TARGET_BODY =
            "{\"code\":200,\"message\":\"操作成功\",\"data\":[]}";

    private HttpServer redirectServer;
    private HttpServer targetServer;
    private ExecutorService serverExecutor;

    private final AtomicInteger redirectHits = new AtomicInteger();
    private final AtomicInteger targetHits = new AtomicInteger();
    private final AtomicReference<String> targetAuthorization = new AtomicReference<>();
    private final AtomicReference<String> targetBaseUrl = new AtomicReference<>();
    private final AtomicReference<String> targetBody = new AtomicReference<>(PORTAL_TARGET_BODY);

    @BeforeEach
    void startServers() throws IOException {
        serverExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread worker = new Thread(runnable, "redirect-boundary-server");
            worker.setDaemon(true);
            return worker;
        });

        targetServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        targetServer.setExecutor(serverExecutor);
        targetServer.createContext("/", exchange -> {
            targetHits.incrementAndGet();
            targetAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = targetBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        targetServer.start();
        targetBaseUrl.set("http://127.0.0.1:" + targetServer.getAddress().getPort());

        redirectServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirectServer.setExecutor(serverExecutor);
        redirectServer.createContext("/", exchange -> {
            redirectHits.incrementAndGet();
            byte[] body = ("{\"redirect\":\"" + REDIRECT_MARKER + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Location", targetBaseUrl.get() + exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(302, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        redirectServer.start();
    }

    @AfterEach
    void stopServers() {
        if (redirectServer != null) {
            redirectServer.stop(0);
        }
        if (targetServer != null) {
            targetServer.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        redirectHits.set(0);
        targetHits.set(0);
        targetAuthorization.set(null);
    }

    @Test
    @DisplayName("模型客户端不跟随跨域 302：首端 1 次、目标端 0 次，Bearer 不泄漏，按固定错误失败")
    void modelClientDoesNotFollowCrossOriginRedirect() {
        targetBody.set(MODEL_TARGET_BODY);
        AgentProperties properties = AgentProperties.from(new MockEnvironment()
                .withProperty(AgentProperties.MODEL_MODE, AgentProperties.MODEL_MODE_OPENAI)
                .withProperty(AgentProperties.OPENAI_BASE_URL, redirectBaseUrl())
                .withProperty(AgentProperties.OPENAI_API_KEY, API_KEY)
                .withProperty(AgentProperties.OPENAI_MODEL, "demo-model"));
        ModelClient client = new AgentRuntimeConfiguration().modelClient(properties);

        ModelException failure = catchThrowableOfType(
                () -> client.complete(ModelRequest.of(List.of(ModelMessage.user("有哪些手机")))),
                ModelException.class);

        assertThat(redirectHits.get()).as("首端应只收到一次请求").isEqualTo(1);
        assertThat(targetHits.get()).as("重定向目标端不应收到任何请求").isZero();
        assertThat(targetAuthorization.get()).as("Bearer Key 不得泄漏到跳转目标").isNull();
        assertThat(failure).as("3xx 应按固定安全错误失败").isNotNull();
        assertThat(failure.kind()).isEqualTo(ModelException.Kind.PROTOCOL);
        assertThat(failure.getCause()).as("不得链式持有上游正文或原始异常").isNull();
        assertThat(failure.getMessage())
                .doesNotContain(REDIRECT_MARKER)
                .doesNotContain(API_KEY)
                .doesNotContain("Bearer");
    }

    @Test
    @DisplayName("门户客户端不跟随跨域 302：首端 1 次、目标端 0 次，会员 Token 不泄漏，按固定错误失败")
    void portalClientDoesNotFollowCrossOriginRedirect() {
        targetBody.set(PORTAL_TARGET_BODY);
        AgentProperties properties = AgentProperties.from(new MockEnvironment()
                .withProperty(AgentProperties.PORTAL_BASE_URL, redirectBaseUrl()));
        MallPortalClient client = new AgentRuntimeConfiguration().mallPortalClient(properties);

        PortalException failure = catchThrowableOfType(
                () -> client.listUnusedCouponHistory(MEMBER_TOKEN), PortalException.class);

        assertThat(redirectHits.get()).as("首端应只收到一次请求").isEqualTo(1);
        assertThat(targetHits.get()).as("重定向目标端不应收到任何请求").isZero();
        assertThat(targetAuthorization.get()).as("会员 Token 不得泄漏到跳转目标").isNull();
        assertThat(failure).as("3xx 应按固定安全错误失败").isNotNull();
        assertThat(failure.kind()).isEqualTo(PortalException.Kind.PROTOCOL);
        assertThat(failure.getCause()).as("不得链式持有上游正文或原始异常").isNull();
        assertThat(failure.getMessage())
                .doesNotContain(REDIRECT_MARKER)
                .doesNotContain(MEMBER_TOKEN);
    }

    private String redirectBaseUrl() {
        return "http://127.0.0.1:" + redirectServer.getAddress().getPort();
    }
}

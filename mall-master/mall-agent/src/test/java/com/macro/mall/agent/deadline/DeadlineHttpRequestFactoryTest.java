package com.macro.mall.agent.deadline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.storefront.PortalException;

/**
 * 用 JDK {@link HttpServer} 在 <strong>loopback</strong> 上验证请求级 deadline 对真实 socket 的约束。
 *
 * <p>不访问任何外部服务、不使用真实模型/门户/Redis。服务端 {@code /slow} 与 {@code /product/search}
 * 延迟 {@value #SERVER_DELAY_MILLIS} 毫秒才响应，因此断言「在服务端响应之前就终止」等价于断言
 * 「按 deadline 预算截断了这次真实网络调用」；{@code /fast} 立即响应。
 *
 * <p>覆盖：
 * <ul>
 *   <li>deadline 预算短于 per-client 配置时，单次请求的完整超时取剩余预算，真实 socket 在预算处终止；</li>
 *   <li>剩余预算耗尽时在<strong>网络调用之前</strong>失败（服务端未收到任何请求）；</li>
 *   <li>没有 deadline 时使用 per-client 配置的超时；</li>
 *   <li>deadline 预算长于 per-client 配置时仍取 per-client 配置（min 语义），且不置「被 deadline 截断」标记；</li>
 *   <li><strong>请求级</strong>截断标记：先一次被 deadline 截断的真实请求成功，后续一次未被总预算截断的
 *       per-client 超时不得被误记为 deadline 截断（标记每次请求重置，不累计）；</li>
 *   <li>生产 {@link MallPortalClient} 走同一条请求工厂：短 deadline 下的真实读超时映射为
 *       {@link PortalException.Kind#TIMEOUT}，deadline 耗尽则透传 deadline 失败而不被误分类为门户错误。</li>
 * </ul>
 */
class DeadlineHttpRequestFactoryTest {

    private static final long SERVER_DELAY_MILLIS = 3_000L;
    private static final long SHORT_DEADLINE_MILLIS = 400L;
    private static final long TERMINATION_UPPER_BOUND_MILLIS = 2_500L;
    private static final String ENVELOPE = "{\"code\":200,\"message\":\"操作成功\",\"data\":null}";

    private HttpServer server;
    private ExecutorService serverExecutor;
    private String baseUrl;
    private final AtomicInteger handled = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread worker = new Thread(runnable, "deadline-loopback-server");
            worker.setDaemon(true);
            return worker;
        });
        server.setExecutor(serverExecutor);
        // 慢路径：真实客户端必须在此响应前按 deadline 或 per-client 超时终止
        registerSlowContext("/slow");
        // 生产门户搜索路径：MallPortalClient 只请求 /product/search
        registerSlowContext("/product/search");
        // 快路径：用于「先一次被截断的请求成功」场景
        server.createContext("/fast", exchange -> {
            handled.incrementAndGet();
            byte[] body = ENVELOPE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (IOException ignored) {
                // 客户端已断开，写回失败是预期情况
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 注册一个延迟 {@value #SERVER_DELAY_MILLIS} 毫秒才响应的上下文，返回固定成功 envelope。
     * 客户端若在服务端响应前终止，说明超时确实作用在这次真实网络调用上。
     */
    private void registerSlowContext(String path) {
        server.createContext(path, exchange -> {
            handled.incrementAndGet();
            try {
                Thread.sleep(SERVER_DELAY_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            byte[] body = ENVELOPE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (IOException ignored) {
                // 客户端已按 deadline 断开，写回失败是预期情况
            }
        });
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        handled.set(0);
    }

    @Test
    @DisplayName("短 deadline 把单次请求超时收紧到剩余预算，真实 socket 在服务端响应前终止")
    void shortDeadlineTerminatesRealSocketWithinBudget() {
        RequestDeadline deadline = RequestDeadline.start(SHORT_DEADLINE_MILLIS / 1000.0);
        RestClient client = clientWithPerCallTimeout(Duration.ofSeconds(30));

        long started = System.nanoTime();
        Throwable thrown = catchThrowable(() -> RequestDeadlineContext.callWith(
                deadline, () -> exchangeValue(client)));
        long elapsedMillis = elapsedMillisSince(started);

        assertThat(thrown).as("短 deadline 必须终止这次真实网络调用").isNotNull();
        assertThat(hasInCauseChain(thrown, HttpTimeoutException.class))
                .as("真实 socket 因单次请求超时而终止，异常链应包含 HttpTimeoutException")
                .isTrue();
        assertThat(elapsedMillis)
                .as("必须在服务端 %d ms 延迟之前终止", SERVER_DELAY_MILLIS)
                .isLessThan(TERMINATION_UPPER_BOUND_MILLIS);
        assertThat(handled.get()).as("请求确实发到了 loopback 服务端").isGreaterThanOrEqualTo(1);
        assertThat(deadline.isLastRequestClamped()).as("本次调用被 deadline 提前截断").isTrue();
    }

    @Test
    @DisplayName("剩余预算耗尽时在网络调用之前失败，服务端收不到请求")
    void expiredDeadlineFailsBeforeAnyNetworkCall() {
        AtomicLong ticker = new AtomicLong(0L);
        RequestDeadline expired = RequestDeadline.start(1.0, ticker::get);
        ticker.set(2_000_000_000L);
        RestClient client = clientWithPerCallTimeout(Duration.ofSeconds(30));

        Throwable thrown = catchThrowable(() -> RequestDeadlineContext.callWith(
                expired, () -> exchangeValue(client)));

        assertThat(thrown).isNotNull();
        assertThat(hasInCauseChain(thrown, RequestDeadlineExceededException.class)).isTrue();
        assertThat(handled.get()).as("剩余预算耗尽必须在网络调用前失败").isZero();
    }

    @Test
    @DisplayName("没有 deadline 时使用 per-client 配置的完整超时")
    void withoutDeadlineUsesConfiguredPerCallTimeout() {
        RestClient client = clientWithPerCallTimeout(Duration.ofMillis(SHORT_DEADLINE_MILLIS));

        long started = System.nanoTime();
        Throwable thrown = catchThrowable(() -> exchangeValue(client));
        long elapsedMillis = elapsedMillisSince(started);

        assertThat(thrown).isNotNull();
        assertThat(hasInCauseChain(thrown, HttpTimeoutException.class)).isTrue();
        assertThat(elapsedMillis).isLessThan(TERMINATION_UPPER_BOUND_MILLIS);
        assertThat(handled.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("deadline 预算长于 per-client 配置时仍取 per-client 配置，且不置截断标记")
    void longerDeadlineKeepsConfiguredTimeout() {
        RestClient client = clientWithPerCallTimeout(Duration.ofMillis(SHORT_DEADLINE_MILLIS));
        RequestDeadline deadline = RequestDeadline.start(30.0);

        long started = System.nanoTime();
        Throwable thrown = catchThrowable(() -> RequestDeadlineContext.callWith(
                deadline, () -> exchangeValue(client)));
        long elapsedMillis = elapsedMillisSince(started);

        assertThat(thrown).isNotNull();
        assertThat(hasInCauseChain(thrown, HttpTimeoutException.class)).isTrue();
        assertThat(elapsedMillis).isLessThan(TERMINATION_UPPER_BOUND_MILLIS);
        assertThat(handled.get()).isGreaterThanOrEqualTo(1);
        assertThat(deadline.isLastRequestClamped()).as("本次调用未被 deadline 截断").isFalse();
    }

    @Test
    @DisplayName("先一次被 deadline 截断的请求成功后，后续未被截断的 per-client 超时不再被记为 deadline 截断")
    void clampedRequestDoesNotPolluteLaterPerCallTimeout() {
        // 总预算充足：第一次请求的 per-client 配置远大于剩余预算 → 被 deadline 截断；
        // 第二次请求的 per-client 配置远小于剩余预算 → 未被截断（min 取 per-client）。
        RequestDeadline deadline = RequestDeadline.start(30.0);
        RestClient bigConfigured = clientWithPerCallTimeout(Duration.ofSeconds(300));
        RestClient smallConfigured = clientWithPerCallTimeout(Duration.ofMillis(100));
        AtomicBoolean afterFirstRequest = new AtomicBoolean();
        AtomicBoolean afterSecondRequest = new AtomicBoolean();

        Throwable thrown = catchThrowable(() -> RequestDeadlineContext.callWith(deadline, () -> {
            // 第一次：被 deadline 截断，但服务端很快就返回，因此这次请求“成功”
            int firstStatus = exchangeValue(bigConfigured, "/fast");
            assertThat(firstStatus).as("第一次被截断的请求应在剩余预算内成功").isEqualTo(200);
            afterFirstRequest.set(deadline.isLastRequestClamped());

            // 第二次：per-client 配置远小于剩余预算 → 未被 deadline 截断，因 per-client 超时而失败。
            // 无论该请求以何种抛错路径终止（含非 RuntimeException），都在 finally 中绝对采集“最近一次
            // 请求”的标记；否则异常一旦绕开采集点，断言只会读到 AtomicBoolean 默认 false，形成假绿。
            try {
                exchangeValue(smallConfigured, "/slow");
            } finally {
                afterSecondRequest.set(deadline.isLastRequestClamped());
            }
            return firstStatus;
        }));

        assertThat(afterFirstRequest.get()).as("第一次请求确实被 deadline 截断").isTrue();
        assertThat(thrown).isNotNull();
        assertThat(hasInCauseChain(thrown, HttpTimeoutException.class))
                .as("第二次请求因 per-client 超时而终止")
                .isTrue();
        assertThat(afterSecondRequest.get())
                .as("请求级标记必须被本次未截断的请求重置，不得保留上一次的 true")
                .isFalse();
        assertThat(deadline.isExpired()).as("总预算尚未耗尽，第二次是 per-client 自身超时").isFalse();
    }

    @Test
    @DisplayName("生产门户客户端的真实读超时映射为 TIMEOUT，且被 deadline 截断")
    void portalClientMapsRealReadTimeoutToTimeoutWithinDeadline() {
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(new DeadlineClientHttpRequestFactory(
                        sharedClient(), Duration.ofSeconds(30)));
        MallPortalClient portal = new MallPortalClient(builder, baseUrl);
        RequestDeadline deadline = RequestDeadline.start(SHORT_DEADLINE_MILLIS / 1000.0);

        Throwable thrown = catchThrowable(() -> RequestDeadlineContext.callWith(
                deadline, () -> portal.searchProducts(new MallPortalClient.SearchParams(
                        null, null, null, 0, 1))));

        assertThat(thrown).isInstanceOf(PortalException.class);
        assertThat(((PortalException) thrown).kind()).isEqualTo(PortalException.Kind.TIMEOUT);
        assertThat(deadline.isLastRequestClamped()).isTrue();
        assertThat(handled.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("生产门户客户端在剩余预算耗尽时透传 deadline 失败，不误分类为门户 TIMEOUT")
    void portalClientPropagatesExpiredDeadlineBeforeNetwork() {
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(new DeadlineClientHttpRequestFactory(
                        sharedClient(), Duration.ofSeconds(30)));
        MallPortalClient portal = new MallPortalClient(builder, baseUrl);
        AtomicLong ticker = new AtomicLong(0L);
        RequestDeadline expired = RequestDeadline.start(1.0, ticker::get);
        ticker.set(2_000_000_000L);

        Throwable thrown = catchThrowable(() -> RequestDeadlineContext.callWith(
                expired, () -> portal.searchProducts(new MallPortalClient.SearchParams(
                        null, null, null, 0, 1))));

        assertThat(thrown).isNotNull();
        assertThat(hasInCauseChain(thrown, RequestDeadlineExceededException.class))
                .as("deadline 预算耗尽必须透传 deadline 失败信号")
                .isTrue();
        assertThat(hasInCauseChain(thrown, HttpTimeoutException.class)).isFalse();
        assertThat(handled.get()).isZero();
    }

    // ------------------------------------------------------------------ //
    // 辅助
    // ------------------------------------------------------------------ //

    private static RestClient clientWithPerCallTimeout(Duration perCallTimeout) {
        return RestClient.builder()
                .requestFactory(new DeadlineClientHttpRequestFactory(sharedClient(), perCallTimeout))
                .build();
    }

    private static HttpClient sharedClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private int exchangeValue(RestClient client) {
        return exchangeValue(client, "/slow");
    }

    private int exchangeValue(RestClient client, String path) {
        return client.get()
                .uri(baseUrl + path)
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private static long elapsedMillisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static boolean hasInCauseChain(Throwable thrown, Class<? extends Throwable> type) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}

package com.macro.mall.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import com.macro.mall.agent.agent.AgentOrchestrator;
import com.macro.mall.agent.agent.AgentTurnRequest;
import com.macro.mall.agent.agent.AgentTurnResult;
import com.macro.mall.agent.agent.ToolRoundLimitException;
import com.macro.mall.agent.config.AgentProperties;
import com.macro.mall.agent.deadline.DeadlineClientHttpRequestFactory;
import com.macro.mall.agent.deadline.RequestDeadline;
import com.macro.mall.agent.deadline.RequestDeadlineContext;
import com.macro.mall.agent.deadline.RequestDeadlineExceededException;
import com.macro.mall.agent.model.ModelException;
import com.macro.mall.agent.safety.RefusalPolicy;
import com.macro.mall.agent.safety.ShoppingIntentClassifier;
import com.macro.mall.agent.session.IdentityResolver;
import com.macro.mall.agent.session.InFlightGuard;
import com.macro.mall.agent.session.RateLimitExceededException;
import com.macro.mall.agent.session.RateLimiter;
import com.macro.mall.agent.session.SessionRepository;
import com.macro.mall.agent.session.SessionSnapshot;
import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.storefront.PortalException;
import com.macro.mall.agent.storefront.dto.MemberInfoResponse;

/**
 * {@code POST /agent/chat} 的接线与错误映射测试。
 *
 * <p>使用真实 {@link DefaultAgentChatService}：身份解析用真实 {@link IdentityResolver}，
 * 并发防重用真实 {@link InFlightGuard}，只把 {@code AgentOrchestrator}、{@code RateLimiter}、
 * {@code SessionRepository}、{@code MallPortalClient} 与 {@code AgentProperties} 替换为
 * {@link Wiring} 里 {@code @Bean} 提供的 Mockito 替身。
 *
 * <p>这些替身是 Spring 测试上下文中的单例，会被同类多个用例复用，Spring 不会自动重置它们，
 * 因此 {@link #stubDefaults()} 在每个用例开始时先 {@code Mockito.reset} 这些 mock
 * （真实 {@link InFlightGuard} 不在其中），再重新安装默认 stubbing，避免上一个用例的调用记录与
 * 旧 stubbing 泄漏到下一个用例。由此验证「同一身份 + 同一问题并发返回 409」这类真实服务层行为，
 * 而不是只模拟服务。
 *
 * <p>覆盖边界：成功 ApiEnvelope、模型未配置 503、{@link ModelException} 超时/上游 502、
 * 门户失败 502、限流 429 + {@code Retry-After}、工具轮数超限 422、错误响应不泄漏 Key/Token/
 * 上游正文/堆栈；请求 IP 仅在请求对端等于配置的可信代理地址时才取 {@code X-Real-IP}
 * （否则回退 {@code remoteAddr}，且始终忽略 {@code X-Forwarded-For}）；身份只来自 Authorization
 * 头，Cookie 与请求体伪造的 memberId 无效。
 *
 * <p>错误映射刻意区分两个阶段：身份解析阶段（{@code /sso/info}）的非 401 门户失败按 Python
 * {@code _resolve_identity} 行为<strong>不</strong>在业务层捕获，交由 {@code ApiExceptionHandler}
 * 收敛为固定内部 500；只有编排阶段（商品/会员券门户读取）的 {@link PortalException} 才沿用既定的 502 固定文案。
 * 身份解析返回空对象时同样归一为固定内部 500，不泄漏 NPE 细节。
 *
 * <p>请求级 deadline（{@code MALL_AGENT_REQUEST_TIMEOUT_SECONDS}）超时统一映射 502
 * {@code AGENT_TIMEOUT}（文案「智能导购响应超时，请稍后再试。」），且<strong>绝不落库</strong>、
 * 必须释放 {@link InFlightGuard} 防重键；该预算只包住编排阶段，模型未配置 503 与身份解析不受其约束。
 *
 * <p>不建立 Redis、mall-portal 或模型连接。
 */
@WebMvcTest(AgentController.class)
@Import(AgentChatControllerTest.Wiring.class)
class AgentChatControllerTest {

    private static final String SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c";
    private static final String GUEST_KEY = "mall:agent:session:guest:" + SESSION_ID;
    private static final String MEMBER_KEY = "mall:agent:session:member:7:" + SESSION_ID;

    private static final String CLIENT_IP = "203.0.113.9";
    private static final String FORWARDED_IP = "198.51.100.7";
    private static final String REMOTE_ADDR = "127.0.0.1";
    /**
     * 直连场景的对端地址：与可信代理地址、伪造的 {@link #CLIENT_IP} 与 {@link #FORWARDED_IP}
     * 均不同，使「按对端分桶」与「既不采信伪造头」两条断言可以同时成立。
     */
    private static final String DIRECT_REMOTE_ADDR = "192.0.2.55";
    /** 显式配置的可信反向代理地址，用于「可信来源」用例；与 MockMvc 默认对端地址不同。 */
    private static final String TRUSTED_PROXY_ADDR = "10.0.0.5";

    private static final String TOKEN = "Bearer unit-test-token-must-not-leak";
    private static final String MODEL_KEY = "sk-unit-test-secret-must-not-leak";
    private static final String UPSTREAM_TEXT = "upstream-body-must-not-leak";

    private static final String ANSWER = "候选商品的名称与价格均来自商城数据。";

    private static final String CHAT_BODY = """
            {"sessionId":"2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c","message":"有哪些手机"}""";

    private static final String MODEL_UNAVAILABLE_MESSAGE = "智能导购暂时不可用：模型服务未配置或不可用。";
    private static final String MODEL_FAILED_MESSAGE = "智能导购响应失败，请稍后再试。";
    private static final String STOREFRONT_FAILED_MESSAGE = "商品数据暂时无法获取，请稍后再试。";
    /** 身份解析阶段非 401 门户失败：按 Python 行为收敛为 ApiExceptionHandler 的固定内部 500 信封。 */
    private static final String INTERNAL_ERROR_MESSAGE = "服务内部错误，请稍后再试。";
    /** 请求级 deadline 超时的固定 502 文案，逐字符对齐 Python {@code AGENT_TIMEOUT_MESSAGE}。 */
    private static final String AGENT_TIMEOUT_MESSAGE = "智能导购响应超时，请稍后再试。";
    private static final String RATE_LIMITED_MESSAGE = "请求过于频繁，请稍后再试";
    private static final String DUPLICATE_MESSAGE = "相同请求正在处理中，请勿重复提交。";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentProperties properties;

    @Autowired
    private AgentOrchestrator orchestrator;

    @Autowired
    private MallPortalClient portal;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private InFlightGuard inFlightGuard;

    /**
     * 每个用例开始前先重置共享 mock，再安装默认 stubbing。
     *
     * <p>这些替身是 Spring 测试上下文中的 {@code @Bean} 单例，Spring 不会自动重置，必须显式
     * {@code Mockito.reset(...)} 才能清除上一用例留下的调用记录与旧 stubbing（例如某个用例给
     * {@code rateLimiter.check} 装的 {@code willThrow} 会污染后续用例）。真实
     * {@link InFlightGuard} 不是 mock，不参与重置，其键由各用例在 {@code finally} 中正常释放。
     */
    @BeforeEach
    void stubDefaults() {
        reset(properties, orchestrator, portal, sessionRepository, rateLimiter);
        given(properties.isModelAvailable()).willReturn(true);
        given(properties.getSessionMaxMessages()).willReturn(20);
        // 默认把 MockMvc 对端地址（127.0.0.1）视为可信代理，使带 X-Real-IP 的既有用例按头分桶
        given(properties.getTrustedProxyIp()).willReturn(REMOTE_ADDR);
        // 请求级 deadline 的预算取自配置；缺省给足余量，使「per-client 超时」用例不被 deadline 干扰
        given(properties.getRequestTimeoutSeconds()).willReturn(30.0);
        given(sessionRepository.load(anyString())).willReturn(SessionSnapshot.empty());
        given(orchestrator.buildSessionUpdate(any(AgentTurnRequest.class), any(AgentTurnResult.class)))
                .willReturn(SessionSnapshot.empty());
        given(orchestrator.run(any(AgentTurnRequest.class))).willReturn(result(ANSWER, List.of(), false, List.of()));
    }

    // ------------------------------------------------------------------ //
    // 成功路径
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("游客聊天返回完整 ApiEnvelope，并把纯文本消息保存到游客命名空间")
    void guestChatReturnsEnvelopeAndSavesGuestSession() throws Exception {
        ProductCard card = new ProductCard(
                27L,
                "示例手机 B",
                "http://localhost:9000/mall/example-27.jpg",
                "2999.00",
                "示例副标题 B",
                "IN_STOCK",
                112,
                "/pages/product/product?id=27");
        given(orchestrator.run(any(AgentTurnRequest.class)))
                .willReturn(result(ANSWER, List.of(card), false, List.of("这个商品支持哪些优惠？")));

        MvcResult response = mockMvc.perform(chatRequest().header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.answer").value(ANSWER))
                .andExpect(jsonPath("$.data.requiresLogin").value(false))
                .andExpect(jsonPath("$.data.products[0].id").value(27))
                .andExpect(jsonPath("$.data.products[0].name").value("示例手机 B"))
                .andExpect(jsonPath("$.data.products[0].price").value("2999.00"))
                .andExpect(jsonPath("$.data.products[0].stockStatus").value("IN_STOCK"))
                .andExpect(jsonPath("$.data.products[0].availableStock").value(112))
                .andExpect(jsonPath("$.data.products[0].detailPath").value("/pages/product/product?id=27"))
                .andExpect(jsonPath("$.data.suggestedQuestions[0]").value("这个商品支持哪些优惠？"))
                .andReturn();

        String messageId = JsonPath.read(body(response), "$.data.messageId");
        assertThat(UUID.fromString(messageId)).as("messageId 必须是服务端生成的 UUID").isNotNull();

        ArgumentCaptor<AgentTurnRequest> turn = ArgumentCaptor.forClass(AgentTurnRequest.class);
        then(orchestrator).should().run(turn.capture());
        assertThat(turn.getValue().identity().isMember()).isFalse();
        assertThat(turn.getValue().identity().sessionKey()).isEqualTo(GUEST_KEY);
        assertThat(turn.getValue().authorization()).as("游客请求绝不携带会员凭据").isNull();
        assertThat(turn.getValue().message()).isEqualTo("有哪些手机");

        then(sessionRepository).should().load(GUEST_KEY);
        then(sessionRepository).should().save(eq(GUEST_KEY), any(SessionSnapshot.class));
        then(rateLimiter).should().checkIp(CLIENT_IP);
        then(rateLimiter).should().checkSession(GUEST_KEY);
    }

    // ------------------------------------------------------------------ //
    // 游客个人券登录门槛与写拒绝优先级
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("游客个人券问法返回登录门槛，零模型与零门户调用，并保留原有对话与商品卡")
    void guestPersonalCouponRequiresLoginWithoutModelOrPortal() throws Exception {
        ProductCard existingCard = new ProductCard(
                27L,
                "示例手机 B",
                "http://localhost:9000/mall/example-27.jpg",
                "2999.00",
                "示例副标题 B",
                "IN_STOCK",
                112,
                "/pages/product/product?id=27");
        given(sessionRepository.load(GUEST_KEY)).willReturn(new SessionSnapshot(
                List.of(new SessionMessage("user", "之前的问题"), new SessionMessage("assistant", "之前的回答")),
                List.of(existingCard)));

        String message = "我有哪些优惠券";
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon(message))
                .as("个人券正例必须命中分类器")
                .isTrue();

        MvcResult response = mockMvc.perform(chatRequest(message).header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.requiresLogin").value(true))
                .andExpect(jsonPath("$.data.products").isEmpty())
                .andExpect(jsonPath("$.data.answer").value(AgentOrchestrator.LOGIN_REQUIRED_ANSWER))
                .andExpect(jsonPath("$.data.suggestedQuestions[0]").value("登录后我的优惠券怎么用？"))
                .andReturn();

        assertThat(UUID.fromString(JsonPath.read(body(response), "$.data.messageId"))).isNotNull();

        // 个人券命中是纯确定性服务层短路：编排器（模型入口）与门户都不得有任何调用
        then(orchestrator).should(never()).run(any(AgentTurnRequest.class));
        then(orchestrator).should(never())
                .buildSessionUpdate(any(AgentTurnRequest.class), any(AgentTurnResult.class));
        verifyNoInteractions(portal);

        // 本轮 user question + assistant 登录提示写入游客命名空间，且保留原有对话与商品卡
        ArgumentCaptor<SessionSnapshot> saved = ArgumentCaptor.forClass(SessionSnapshot.class);
        then(sessionRepository).should().save(eq(GUEST_KEY), saved.capture());
        then(sessionRepository).should().load(GUEST_KEY);

        SessionSnapshot snapshot = saved.getValue();
        assertThat(snapshot.messages()).extracting(SessionMessage::role)
                .containsExactly("user", "assistant", "user", "assistant");
        assertThat(snapshot.messages().get(2).content()).isEqualTo(message);
        assertThat(snapshot.messages().get(3).content()).isEqualTo(AgentOrchestrator.LOGIN_REQUIRED_ANSWER);
        assertThat(snapshot.products()).extracting(ProductCard::id).containsExactly(27L);
    }

    @Test
    @DisplayName("游客个人券写入按配置的会话消息上限裁剪，保留最近两条")
    void guestPersonalCouponAppliesSessionMessageLimit() throws Exception {
        given(properties.getSessionMaxMessages()).willReturn(2);
        given(sessionRepository.load(GUEST_KEY)).willReturn(new SessionSnapshot(
                List.of(new SessionMessage("user", "之前的问题"), new SessionMessage("assistant", "之前的回答")),
                List.of()));

        mockMvc.perform(chatRequest("我的优惠券能用在哪个商品上").header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresLogin").value(true));

        ArgumentCaptor<SessionSnapshot> saved = ArgumentCaptor.forClass(SessionSnapshot.class);
        then(sessionRepository).should().save(eq(GUEST_KEY), saved.capture());
        assertThat(saved.getValue().messages()).extracting(SessionMessage::role)
                .containsExactly("user", "assistant");
        assertThat(saved.getValue().messages().get(1).content())
                .isEqualTo(AgentOrchestrator.LOGIN_REQUIRED_ANSWER);
    }

    @Test
    @DisplayName("游客个人券会话写入失败仍返回 200 requiresLogin，且不泄漏异常正文")
    void guestPersonalCouponSaveFailureStillReturnsLoginRequired() throws Exception {
        String secret = "session-store-down-must-not-leak";
        given(sessionRepository.load(GUEST_KEY)).willReturn(SessionSnapshot.empty());
        willThrow(new IllegalStateException(secret))
                .given(sessionRepository).save(eq(GUEST_KEY), any(SessionSnapshot.class));

        MvcResult response = mockMvc.perform(chatRequest("我有哪些优惠券").header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.requiresLogin").value(true))
                .andExpect(jsonPath("$.data.products").isEmpty())
                .andReturn();

        assertNoLeak(body(response), secret);
        then(orchestrator).should(never()).run(any(AgentTurnRequest.class));
        verifyNoInteractions(portal);
    }

    @Test
    @DisplayName("领券类请求确定性命中写拒绝，不返回 requiresLogin 也不调用模型或门户")
    void couponClaimRefusalDoesNotRequireLogin() throws Exception {
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon("帮我领优惠券"))
                .as("领券动作不是个人券问法")
                .isFalse();

        MvcResult response = mockMvc.perform(chatRequest("帮我领优惠券").header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.requiresLogin").value(false))
                .andExpect(jsonPath("$.data.products").isEmpty())
                .andReturn();

        String responseBody = body(response);
        assertThat(responseBody).contains(RefusalPolicy.REFUSAL_NOTICE);
        assertThat(responseBody).doesNotContain(AgentOrchestrator.LOGIN_REQUIRED_ANSWER);

        then(orchestrator).should(never()).run(any(AgentTurnRequest.class));
        verifyNoInteractions(portal);
    }

    @Test
    @DisplayName("写拒绝优先于个人券身份门槛：同时命中时返回拒绝而非登录要求")
    void writeRefusalTakesPrecedenceOverPersonalCouponGate() throws Exception {
        String message = "帮我领取我的优惠券";
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon(message))
                .as("该问法同时命中个人券分类")
                .isTrue();
        assertThat(RefusalPolicy.detectWriteRefusal(message))
                .as("该问法同时命中写拒绝")
                .isPresent();

        MvcResult response = mockMvc.perform(chatRequest(message).header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresLogin").value(false))
                .andReturn();

        String responseBody = body(response);
        assertThat(responseBody).contains(RefusalPolicy.REFUSAL_NOTICE);
        assertThat(responseBody).doesNotContain(AgentOrchestrator.LOGIN_REQUIRED_ANSWER);

        then(orchestrator).should(never()).run(any(AgentTurnRequest.class));
        verifyNoInteractions(portal);
    }

    @Test
    @DisplayName("公开优惠券问法不误触游客登录门槛，仍走正常编排流程")
    void publicCouponQuestionIsNotGatedAsPersonalCoupon() throws Exception {
        String message = "商城有哪些公开优惠券";
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon(message)).isFalse();
        given(orchestrator.run(any(AgentTurnRequest.class)))
                .willReturn(result(ANSWER, List.of(), false, List.of("这个商品支持哪些优惠？")));

        mockMvc.perform(chatRequest(message).header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresLogin").value(false))
                .andExpect(jsonPath("$.data.answer").value(ANSWER))
                .andExpect(jsonPath("$.data.suggestedQuestions[0]").value("这个商品支持哪些优惠？"));

        ArgumentCaptor<AgentTurnRequest> turn = ArgumentCaptor.forClass(AgentTurnRequest.class);
        then(orchestrator).should().run(turn.capture());
        assertThat(turn.getValue().message()).isEqualTo(message);
        verifyNoInteractions(portal);
    }

    @Test
    @DisplayName("会员的个人券请求不被游客登录门槛拦截，正常进入编排")
    void memberPersonalCouponIsNotBlockedByGuestLoginGate() throws Exception {
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));

        mockMvc.perform(chatRequest("我有哪些优惠券")
                        .header(HttpHeaders.AUTHORIZATION, TOKEN)
                        .header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresLogin").value(false))
                .andExpect(jsonPath("$.data.answer").value(ANSWER));

        ArgumentCaptor<AgentTurnRequest> turn = ArgumentCaptor.forClass(AgentTurnRequest.class);
        then(orchestrator).should().run(turn.capture());
        assertThat(turn.getValue().identity().isMember()).isTrue();
        assertThat(turn.getValue().identity().sessionKey()).isEqualTo(MEMBER_KEY);
        then(rateLimiter).should().checkIp(CLIENT_IP);
        then(rateLimiter).should().checkSession(MEMBER_KEY);
    }

    @Test
    @DisplayName("有效会员的写拒绝返回 200 拒绝答复：零模型、零商品/券门户读取，仅一次 /sso/info")
    void memberWriteRefusalReturns200WithoutModelOrProductPortal() throws Exception {
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));

        String message = "帮我下单买这个商品";
        assertThat(RefusalPolicy.detectWriteRefusal(message))
                .as("该问法必须命中写拒绝")
                .isPresent();

        MvcResult response = mockMvc.perform(chatRequest(message)
                        .header(HttpHeaders.AUTHORIZATION, TOKEN)
                        .header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.requiresLogin").value(false))
                .andExpect(jsonPath("$.data.products").isEmpty())
                .andReturn();

        String responseBody = body(response);
        assertThat(responseBody).contains(RefusalPolicy.REFUSAL_NOTICE);
        assertThat(responseBody).doesNotContain(AgentOrchestrator.LOGIN_REQUIRED_ANSWER);

        // 写拒绝不进入模型；会员身份仍需一次 /sso/info，但绝不触达商品或会员券门户接口
        then(orchestrator).should(never()).run(any(AgentTurnRequest.class));
        then(portal).should().resolveMember(TOKEN);
        then(portal).should(never()).searchProducts(any());
        then(portal).should(never()).getProductDetail(anyLong());
        then(portal).should(never()).listUnusedCouponHistory(anyString());
        then(portal).should(never()).listProductCoupons(anyLong(), anyString());
        then(portal).shouldHaveNoMoreInteractions();

        // 写拒绝仍按既有语义写回会话，键为会员命名空间
        then(sessionRepository).should().save(eq(MEMBER_KEY), any(SessionSnapshot.class));
    }

    // ------------------------------------------------------------------ //
    // 错误映射与不泄漏
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("模型未配置时返回 503 且不触达身份、限流、会话与编排")
    void modelUnavailableReturns503BeforeAnyBusinessCall() throws Exception {
        given(properties.isModelAvailable()).willReturn(false);

        MvcResult response = mockMvc.perform(chatRequest()
                        .header(HttpHeaders.AUTHORIZATION, TOKEN)
                        .header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value(MODEL_UNAVAILABLE_MESSAGE))
                .andReturn();

        assertNoLeak(body(response), TOKEN, MODEL_KEY, UPSTREAM_TEXT);
        verifyNoInteractions(orchestrator, rateLimiter, sessionRepository, portal);
    }

    @Test
    @DisplayName("ModelException 超时映射 502 且不回传内部文案")
    void modelTimeoutMapsTo502WithoutLeakingInternalText() throws Exception {
        given(orchestrator.run(any(AgentTurnRequest.class)))
                .willThrow(ModelException.timeout("模型服务响应超时 " + UPSTREAM_TEXT));

        // 本用例只验证 ModelException 的错误映射，与会员鉴权无关：不带 Authorization 走游客路径，
        // 避免未经 stub 的 portal.resolveMember 返回 null 触发 NPE，把断言从 502 带偏成 500。
        MvcResult response = mockMvc.perform(chatRequest())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.message").value(MODEL_FAILED_MESSAGE))
                .andReturn();

        assertNoLeak(body(response), TOKEN, MODEL_KEY, UPSTREAM_TEXT);
    }

    @Test
    @DisplayName("ModelException 上游失败映射 502 且不回传内部文案")
    void modelUpstreamFailureMapsTo502WithoutLeakingInternalText() throws Exception {
        given(orchestrator.run(any(AgentTurnRequest.class)))
                .willThrow(ModelException.upstream("模型服务连接失败 " + UPSTREAM_TEXT));

        MvcResult response = mockMvc.perform(chatRequest())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.message").value(MODEL_FAILED_MESSAGE))
                .andReturn();

        assertNoLeak(body(response), MODEL_KEY, UPSTREAM_TEXT);
    }

    @Test
    @DisplayName("ModelException 不可用映射 503")
    void modelUnavailableExceptionMapsTo503() throws Exception {
        given(orchestrator.run(any(AgentTurnRequest.class)))
                .willThrow(ModelException.unavailable("模型服务未配置或凭据不可用"));

        mockMvc.perform(chatRequest())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value(MODEL_UNAVAILABLE_MESSAGE));
    }

    @Test
    @DisplayName("门户失败统一映射 502 固定文案，不回传 Token 或上游地址")
    void portalFailuresMapTo502WithFixedMessage() throws Exception {
        // 本用例带 Authorization，必须先成功解析会员身份才能抵达编排层的门户失败分支；
        // 不 stub 会让 portal.resolveMember 返回 null，在身份解析处 NPE，偏离 502 断言。
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));

        for (PortalException failure : List.of(
                PortalException.unavailable(), PortalException.timeout(), PortalException.notFound(),
                PortalException.protocol())) {
            given(orchestrator.run(any(AgentTurnRequest.class))).willThrow(failure);

            MvcResult response = mockMvc.perform(chatRequest().header(HttpHeaders.AUTHORIZATION, TOKEN))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value(502))
                    .andExpect(jsonPath("$.message").value(STOREFRONT_FAILED_MESSAGE))
                    .andReturn();

            assertNoLeak(body(response), TOKEN, MODEL_KEY, UPSTREAM_TEXT, "http://");
        }
    }

    @Test
    @DisplayName("编排期会员券 Token 失效（MEMBER_UNAUTHORIZED）按 Python StorefrontError 映射固定 502，不返回 requiresLogin")
    void orchestrationMemberUnauthorizedMapsToFixed502Storefront() throws Exception {
        // 身份解析成功（有效会员），随后编排期的商品/会员券读取才返回 MEMBER_UNAUTHORIZED：
        // Python api/chat.py 对编排期 StorefrontError（含 MemberUnauthorizedError）统一 502 storefront，
        // 不转 requiresLogin，也不回传门户 Token 失效细节。
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));
        given(orchestrator.run(any(AgentTurnRequest.class)))
                .willThrow(PortalException.memberUnauthorized());

        MvcResult response = mockMvc.perform(chatRequest().header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.message").value(STOREFRONT_FAILED_MESSAGE))
                .andReturn();

        String responseBody = body(response);
        assertThat(responseBody).doesNotContain(AgentOrchestrator.LOGIN_REQUIRED_ANSWER);
        assertNoLeak(responseBody, TOKEN, MODEL_KEY, UPSTREAM_TEXT, "http://");
        then(sessionRepository).should(never()).save(anyString(), any(SessionSnapshot.class));
    }

    @Test
    @DisplayName("身份解析阶段门户超时按 Python 行为固定 500，不进入编排也不回传门户细节")
    void identityResolutionTimeoutMapsToFixed500() throws Exception {
        given(portal.resolveMember(TOKEN)).willThrow(PortalException.timeout());

        MvcResult response = mockMvc.perform(chatRequest().header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value(INTERNAL_ERROR_MESSAGE))
                .andReturn();

        assertNoLeak(body(response), TOKEN, MODEL_KEY, UPSTREAM_TEXT, "http://");
        then(orchestrator).should(never()).run(any(AgentTurnRequest.class));
        // 预认证 IP 门槛早于身份解析，因此 IP 桶已被消耗；身份解析失败则绝不消耗会话桶
        then(rateLimiter).should().checkIp(REMOTE_ADDR);
        then(rateLimiter).should(never()).checkSession(anyString());
        then(sessionRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("门户身份解析返回空对象时归一为固定内部 500，不泄漏 NPE 细节")
    void nullMemberInfoMapsToFixed500WithoutNpeLeak() throws Exception {
        given(portal.resolveMember(TOKEN)).willReturn(null);

        MvcResult response = mockMvc.perform(chatRequest().header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value(INTERNAL_ERROR_MESSAGE))
                .andReturn();

        assertNoLeak(body(response), TOKEN, MODEL_KEY, UPSTREAM_TEXT);
        then(orchestrator).should(never()).run(any(AgentTurnRequest.class));
        then(sessionRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("编排阶段门户超时若已越过请求级 deadline，统一映射 502 AGENT_TIMEOUT 且不落库")
    void orchestrationTimeoutAfterRequestDeadlineMapsToAgentTimeout() throws Exception {
        given(properties.getRequestTimeoutSeconds()).willReturn(0.2);
        given(orchestrator.run(any(AgentTurnRequest.class))).willAnswer(invocation -> {
            Thread.sleep(400L);
            throw PortalException.timeout();
        });

        MvcResult response = mockMvc.perform(chatRequest().header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.message").value(AGENT_TIMEOUT_MESSAGE))
                .andReturn();

        assertNoLeak(body(response), TOKEN, MODEL_KEY, UPSTREAM_TEXT);
        then(sessionRepository).should(never()).save(anyString(), any(SessionSnapshot.class));
        assertThat(inFlightGuard.keys()).as("超时后必须释放防重键").isEmpty();
    }

    @Test
    @DisplayName("请求级 deadline 超时返回固定 502 AGENT_TIMEOUT，绝不落库且释放防重键")
    void requestDeadlineTimeoutReturnsFixed502WithoutSavingSession() throws Exception {
        given(properties.getRequestTimeoutSeconds()).willReturn(0.2);
        given(orchestrator.run(any(AgentTurnRequest.class))).willAnswer(invocation -> {
            Thread.sleep(400L);
            return result(ANSWER, List.of(), false, List.of());
        });

        MvcResult response = mockMvc.perform(chatRequest().header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.message").value(AGENT_TIMEOUT_MESSAGE))
                .andReturn();

        assertNoLeak(body(response), TOKEN, MODEL_KEY, UPSTREAM_TEXT);
        then(sessionRepository).should(never()).save(anyString(), any(SessionSnapshot.class));
        assertThat(inFlightGuard.keys()).as("超时后必须释放防重键").isEmpty();
    }

    @Test
    @DisplayName("编排期直接抛 RequestDeadlineExceededException 映射固定 502 AGENT_TIMEOUT，不落库且释放防重键")
    void requestDeadlineExceededExceptionMapsToFixed502WithoutSavingSession() throws Exception {
        // 该异常由 DeadlineClientHttpRequestFactory 在剩余预算耗尽、发出网络调用前抛出；
        // 服务层必须按类型映射固定 502 AGENT_TIMEOUT，且不落库、finally 释放 inflight 键。
        given(orchestrator.run(any(AgentTurnRequest.class)))
                .willThrow(new RequestDeadlineExceededException());

        MvcResult response = mockMvc.perform(chatRequest().header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.message").value(AGENT_TIMEOUT_MESSAGE))
                .andReturn();

        assertNoLeak(body(response), TOKEN, MODEL_KEY, UPSTREAM_TEXT);
        then(rateLimiter).should().checkIp(CLIENT_IP);
        then(rateLimiter).should().checkSession(GUEST_KEY);
        then(sessionRepository).should(never()).save(anyString(), any(SessionSnapshot.class));
        assertThat(inFlightGuard.keys()).as("deadline 失败后必须释放防重键").isEmpty();
    }

    @Test
    @DisplayName("前一次被 deadline 截断的请求构建不污染后续 per-client 门户超时：仍映射 storefront 502")
    void clampedRequestDoesNotPolluteLaterPortalTimeoutMapping() throws Exception {
        AtomicBoolean afterClampedRequest = new AtomicBoolean();
        AtomicBoolean afterUnclampedRequest = new AtomicBoolean();
        given(orchestrator.run(any(AgentTurnRequest.class))).willAnswer(invocation -> {
            RequestDeadline deadline = RequestDeadlineContext.current();
            // 本用例只调用 createRequest 触发请求级标记的重置/设置，**未真正发网**（不执行请求、无真实 socket，
            // 也不产生真实超时）；真实网络层面的 deadline 截断证据见 DeadlineHttpRequestFactoryTest 的 loopback 用例。
            // 第一次请求构建：per-client 配置(30s) 大于剩余预算 → 标记为被 deadline 截断
            new DeadlineClientHttpRequestFactory(deadlineHttpClient(), Duration.ofSeconds(30))
                    .createRequest(URI.create("http://127.0.0.1:1/probe"), HttpMethod.GET);
            afterClampedRequest.set(deadline.isLastRequestClamped());
            // 第二次请求构建：per-client 配置(1ms) 小于剩余预算 → 未被截断，createRequest 重置请求级标记
            new DeadlineClientHttpRequestFactory(deadlineHttpClient(), Duration.ofMillis(1))
                    .createRequest(URI.create("http://127.0.0.1:1/probe"), HttpMethod.GET);
            afterUnclampedRequest.set(deadline.isLastRequestClamped());
            // 该次 per-client 超时归类为门户超时，且总 deadline 尚未耗尽
            throw PortalException.timeout();
        });

        mockMvc.perform(chatRequest())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.message").value(STOREFRONT_FAILED_MESSAGE));

        assertThat(afterClampedRequest.get()).as("第一次请求被 deadline 截断").isTrue();
        assertThat(afterUnclampedRequest.get())
                .as("第二次未被截断的请求必须重置标记，不能被前一次截断污染")
                .isFalse();
    }

    @Test
    @DisplayName("限流返回 429 与 Retry-After，且发生在模型调用之前")
    void rateLimitedReturns429WithRetryAfterBeforeModelCall() throws Exception {
        // 预认证 IP 阶段就超限：在身份解析、会话访问与模型调用之前返回 429
        willThrow(new RateLimitExceededException(42)).given(rateLimiter).checkIp(anyString());

        MvcResult response = mockMvc.perform(chatRequest())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "42"))
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value(RATE_LIMITED_MESSAGE))
                .andReturn();

        assertNoLeak(body(response), TOKEN, MODEL_KEY, UPSTREAM_TEXT);
        verifyNoInteractions(orchestrator);
        then(rateLimiter).should(never()).checkSession(anyString());
    }

    @Test
    @DisplayName("失效 Token 超 IP 配额返回 429：零门户解析、零会话读写，且释放防重键")
    void expiredTokenOverIpQuotaReturns429WithoutPortalOrSessionAccess() throws Exception {
        willThrow(new RateLimitExceededException(100)).given(rateLimiter).checkIp(anyString());

        MvcResult response = mockMvc.perform(chatRequest()
                        .header(HttpHeaders.AUTHORIZATION, TOKEN)
                        .header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "100"))
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value(RATE_LIMITED_MESSAGE))
                .andReturn();

        assertNoLeak(body(response), TOKEN, MODEL_KEY, UPSTREAM_TEXT);
        // 伪造 Token 无法借失效 Token 路径绕过 IP 门槛：超限时门户 /sso/info 与会话读写都不发生
        verifyNoInteractions(portal);
        then(sessionRepository).should(never()).load(anyString());
        then(sessionRepository).should(never()).save(anyString(), any(SessionSnapshot.class));
        then(rateLimiter).should(never()).checkSession(anyString());
        verifyNoInteractions(orchestrator);
        assertThat(inFlightGuard.keys()).as("429 后必须释放占用键").isEmpty();
    }

    @Test
    @DisplayName("工具轮数超限映射 422 固定文案")
    void toolRoundLimitMapsTo422() throws Exception {
        ToolRoundLimitException limit = new ToolRoundLimitException(4);
        given(orchestrator.run(any(AgentTurnRequest.class))).willThrow(limit);

        MvcResult response = mockMvc.perform(chatRequest())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value(limit.getMessage()))
                .andReturn();

        assertNoLeak(body(response), TOKEN, MODEL_KEY, UPSTREAM_TEXT);
    }

    // ------------------------------------------------------------------ //
    // 并发防重（真实 service + 真实 InFlightGuard）
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("同一身份与同一问题的并发请求第二次返回 409，且不消耗限流预算")
    void concurrentDuplicateRequestReturns409() throws Exception {
        CountDownLatch enteredOrchestrator = new CountDownLatch(1);
        CountDownLatch releaseOrchestrator = new CountDownLatch(1);
        given(orchestrator.run(any(AgentTurnRequest.class))).willAnswer(invocation -> {
            enteredOrchestrator.countDown();
            releaseOrchestrator.await(5, TimeUnit.SECONDS);
            return result(ANSWER, List.of(), false, List.of());
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = executor.submit(() -> mockMvc.perform(chatRequest()).andReturn());
            assertThat(enteredOrchestrator.await(5, TimeUnit.SECONDS))
                    .as("首个请求应已进入编排阶段并持有防重键")
                    .isTrue();

            mockMvc.perform(chatRequest())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(409))
                    .andExpect(jsonPath("$.message").value(DUPLICATE_MESSAGE));

            releaseOrchestrator.countDown();
            MvcResult firstResponse = first.get(5, TimeUnit.SECONDS);
            assertThat(firstResponse.getResponse().getStatus())
                    .as("首个请求正常完成")
                    .isEqualTo(200);
        } finally {
            releaseOrchestrator.countDown();
            executor.shutdownNow();
        }

        // 重复请求在身份解析与限流之前被拦截：只有首个请求消耗一次 IP 与会话预算
        then(rateLimiter).should(times(1)).checkIp(REMOTE_ADDR);
        then(rateLimiter).should(times(1)).checkSession(GUEST_KEY);
        then(orchestrator).should(times(1)).run(any(AgentTurnRequest.class));
        assertThat(inFlightGuard.keys()).as("请求完成后必须释放防重键").isEmpty();
    }

    // ------------------------------------------------------------------ //
    // 客户端 IP 与身份边界
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("限流使用 X-Real-IP")
    void rateLimitUsesXRealIp() throws Exception {
        mockMvc.perform(chatRequest().header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk());

        then(rateLimiter).should().checkIp(CLIENT_IP);
        then(rateLimiter).should().checkSession(GUEST_KEY);
    }

    @Test
    @DisplayName("直连（remoteAddr 非可信代理）时伪造的 X-Real-IP 被忽略，按 remoteAddr 分桶")
    void directConnectionIgnoresForgedXRealIpAndUsesRemoteAddr() throws Exception {
        // 攻击者直连 agent 端口并伪造 X-Real-IP，remoteAddr 与可信代理地址不一致：
        // 必须忽略请求头、改按对端地址分桶，否则可轮换伪造 IP 绕过 IP 维度限流。
        mockMvc.perform(chatRequest()
                        .with(request -> {
                            request.setRemoteAddr(DIRECT_REMOTE_ADDR);
                            return request;
                        })
                        .header("X-Real-IP", CLIENT_IP)
                        .header("X-Forwarded-For", FORWARDED_IP))
                .andExpect(status().isOk());

        then(rateLimiter).should().checkIp(DIRECT_REMOTE_ADDR);
        then(rateLimiter).should(never()).checkIp(CLIENT_IP);
        then(rateLimiter).should(never()).checkIp(FORWARDED_IP);
    }

    @Test
    @DisplayName("可信代理来源且 X-Real-IP 合法时按该头分桶，且忽略 X-Forwarded-For")
    void trustedProxySourceUsesValidXRealIpAndIgnoresForwardedFor() throws Exception {
        given(properties.getTrustedProxyIp()).willReturn(TRUSTED_PROXY_ADDR);

        mockMvc.perform(chatRequest()
                        .with(request -> {
                            request.setRemoteAddr(TRUSTED_PROXY_ADDR);
                            return request;
                        })
                        .header("X-Real-IP", CLIENT_IP)
                        .header("X-Forwarded-For", FORWARDED_IP))
                .andExpect(status().isOk());

        then(rateLimiter).should().checkIp(CLIENT_IP);
        then(rateLimiter).should(never()).checkIp(TRUSTED_PROXY_ADDR);
        then(rateLimiter).should(never()).checkIp(FORWARDED_IP);
    }

    @Test
    @DisplayName("可信代理来源但 X-Real-IP 非法（hostname/端口/拼接/越界/zone id）时回退合法 remoteAddr")
    void illegalXRealIpFromTrustedProxyFallsBackToRemoteAddr() throws Exception {
        given(properties.getTrustedProxyIp()).willReturn(TRUSTED_PROXY_ADDR);
        List<String> illegalHeaders = List.of(
                "evil.example.com", "1.2.3.4:80", "1.2.3.4, 5.6.7.8", "1.2.3.999", "fe80::1%eth0");

        for (String illegal : illegalHeaders) {
            mockMvc.perform(chatRequest()
                            .with(request -> {
                                request.setRemoteAddr(TRUSTED_PROXY_ADDR);
                                return request;
                            })
                            .header("X-Real-IP", illegal))
                    .andExpect(status().isOk());
        }

        then(rateLimiter).should(times(illegalHeaders.size())).checkIp(TRUSTED_PROXY_ADDR);
        then(rateLimiter).should(never()).checkIp(CLIENT_IP);
        then(rateLimiter).should(never()).checkIp(FORWARDED_IP);
    }

    @Test
    @DisplayName("可信代理按地址字节等价识别：非规范 IPv6 对端地址同样采信合法 IPv6 X-Real-IP")
    void ipv6TrustedProxyMatchesEquivalentAddressAndUsesValidHeader() throws Exception {
        given(properties.getTrustedProxyIp()).willReturn("2001:0db8:0000:0000:0000:0000:0000:0001");

        mockMvc.perform(chatRequest()
                        .with(request -> {
                            request.setRemoteAddr("2001:db8::1");
                            return request;
                        })
                        .header("X-Real-IP", "2001:db8::9"))
                .andExpect(status().isOk());

        then(rateLimiter).should().checkIp("2001:db8::9");
    }

    @Test
    @DisplayName("remoteAddr 非法时返回空串，交由 RateLimiter 的 unknown 桶")
    void illegalRemoteAddrFallsBackToUnknownBucket() throws Exception {
        given(properties.getTrustedProxyIp()).willReturn(TRUSTED_PROXY_ADDR);

        mockMvc.perform(chatRequest()
                        .with(request -> {
                            request.setRemoteAddr("not-an-ip");
                            return request;
                        })
                        .header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk());

        then(rateLimiter).should().checkIp("");
        then(rateLimiter).should(never()).checkIp(CLIENT_IP);
    }

    @Test
    @DisplayName("缺少 X-Real-IP 时回退 remoteAddr")
    void rateLimitFallsBackToRemoteAddr() throws Exception {
        mockMvc.perform(chatRequest())
                .andExpect(status().isOk());

        then(rateLimiter).should().checkIp(REMOTE_ADDR);
        then(rateLimiter).should().checkSession(GUEST_KEY);
    }

    @Test
    @DisplayName("忽略任意 X-Forwarded-For，仍使用 remoteAddr")
    void rateLimitIgnoresXForwardedFor() throws Exception {
        mockMvc.perform(chatRequest().header("X-Forwarded-For", FORWARDED_IP))
                .andExpect(status().isOk());

        then(rateLimiter).should().checkIp(REMOTE_ADDR);
        then(rateLimiter).should().checkSession(GUEST_KEY);
        then(rateLimiter).should(never()).checkIp(FORWARDED_IP);
    }

    @Test
    @DisplayName("Cookie 中的 memberId 不影响游客身份")
    void cookieMemberIdDoesNotChangeGuestIdentity() throws Exception {
        mockMvc.perform(chatRequest()
                        .header(HttpHeaders.COOKIE, "memberId=999")
                        .header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk());

        then(rateLimiter).should().checkIp(CLIENT_IP);
        then(rateLimiter).should().checkSession(GUEST_KEY);
        then(sessionRepository).should().load(GUEST_KEY);
        then(portal).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("请求体中的 memberId 被拒绝且不触达业务层")
    void bodyMemberIdIsRejectedWithoutReachingBusinessLayer() throws Exception {
        String forged = """
                {"sessionId":"2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c","message":"有哪些手机","memberId":999}""";

        MvcResult response = mockMvc.perform(post("/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forged))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andReturn();

        assertThat(body(response)).doesNotContain("memberId").doesNotContain("999");
        verifyNoInteractions(orchestrator, rateLimiter, portal);
    }

    @Test
    @DisplayName("会员身份只由 Authorization 经门户解析，Cookie 伪造的 memberId 无效")
    void memberIdentityComesFromAuthorizationNotCookie() throws Exception {
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));

        mockMvc.perform(chatRequest()
                        .header(HttpHeaders.AUTHORIZATION, TOKEN)
                        .header(HttpHeaders.COOKIE, "memberId=999")
                        .header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk());

        then(portal).should().resolveMember(TOKEN);
        then(rateLimiter).should().checkIp(CLIENT_IP);
        then(rateLimiter).should().checkSession(MEMBER_KEY);

        ArgumentCaptor<AgentTurnRequest> turn = ArgumentCaptor.forClass(AgentTurnRequest.class);
        then(orchestrator).should().run(turn.capture());
        assertThat(turn.getValue().identity().isMember()).isTrue();
        assertThat(turn.getValue().identity().memberId()).isEqualTo(7L);
        assertThat(turn.getValue().identity().sessionKey()).isEqualTo(MEMBER_KEY);
        assertThat(turn.getValue().authorization())
                .as("Authorization 逐字透传给编排层（供会员只读工具使用）")
                .isEqualTo(TOKEN);
    }

    // ------------------------------------------------------------------ //
    // 辅助
    // ------------------------------------------------------------------ //

    private static MockHttpServletRequestBuilder chatRequest() {
        return post("/agent/chat").contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY);
    }

    /** 使用固定 sessionId 与指定消息构造聊天请求（测试消息不含需要转义的 JSON 字符）。 */
    private static MockHttpServletRequestBuilder chatRequest(String message) {
        return post("/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + SESSION_ID + "\",\"message\":\"" + message + "\"}");
    }

    private static AgentTurnResult result(
            String answer, List<ProductCard> products, boolean requiresLogin, List<String> questions) {
        return new AgentTurnResult(
                answer, products, requiresLogin, questions, List.of(), 1, AgentTurnResult.StoppedReason.ANSWER);
    }

    /**
     * 供编排桩内构造 deadline 感知请求对象使用：仅创建 {@code ClientHttpRequest}（不真正发网），
     * 目的只是触发 {@link DeadlineClientHttpRequestFactory} 对请求级截断标记的重置与设置。
     */
    private static HttpClient deadlineHttpClient() {
        return HttpClient.newHttpClient();
    }

    private static void assertNoLeak(String body, String... secrets) {
        assertThat(body)
                .doesNotContain("Exception")
                .doesNotContain("java.")
                .doesNotContain("stackTrace")
                .doesNotContain("at com.");
        for (String secret : secrets) {
            assertThat(body).as("错误响应不得包含 %s", secret).doesNotContain(secret);
        }
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 真实 service 装配：只把外部依赖替换为测试替身，身份解析与并发防重用真实实现。
     */
    @TestConfiguration
    static class Wiring {

        @Bean
        AgentProperties agentProperties() {
            return mock(AgentProperties.class);
        }

        @Bean
        MallPortalClient mallPortalClient() {
            return mock(MallPortalClient.class);
        }

        @Bean
        SessionRepository sessionRepository() {
            return mock(SessionRepository.class);
        }

        @Bean
        RateLimiter rateLimiter() {
            return mock(RateLimiter.class);
        }

        @Bean
        AgentOrchestrator agentOrchestrator() {
            return mock(AgentOrchestrator.class);
        }

        @Bean
        InFlightGuard inFlightGuard() {
            return new InFlightGuard();
        }

        @Bean
        IdentityResolver identityResolver(MallPortalClient portal, SessionRepository repository) {
            return new IdentityResolver(portal, repository);
        }

        @Bean
        AgentChatService agentChatService(
                AgentProperties properties,
                IdentityResolver identityResolver,
                RateLimiter rateLimiter,
                InFlightGuard inFlightGuard,
                SessionRepository sessionRepository,
                AgentOrchestrator orchestrator) {
            return new DefaultAgentChatService(
                    properties, identityResolver, rateLimiter, inFlightGuard, sessionRepository, orchestrator);
        }
    }
}

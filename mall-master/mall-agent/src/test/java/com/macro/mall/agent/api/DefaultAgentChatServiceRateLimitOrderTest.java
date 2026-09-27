package com.macro.mall.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macro.mall.agent.agent.AgentOrchestrator;
import com.macro.mall.agent.agent.AgentTurnRequest;
import com.macro.mall.agent.agent.AgentTurnResult;
import com.macro.mall.agent.config.AgentProperties;
import com.macro.mall.agent.model.ModelException;
import com.macro.mall.agent.session.AgentIdentity;
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
 * 安全返工的服务层契约测试：限流分阶段顺序（缺陷 A）与同 sessionId 生命周期占用键（缺陷 B）。
 *
 * <p>被测对象是真实 {@link DefaultAgentChatService} 与真实 {@link InFlightGuard}、
 * 真实 {@link IdentityResolver}；门户、编排器与配置用 Mockito 替身，会话仓储与限流器用进程内
 * 记录型 fake，<strong>不连接任何共享 Redis、门户或模型</strong>。
 *
 * <p>固定的新顺序：先按规范 sessionId 占用 {@link InFlightGuard} 键 →
 * 预认证按真实客户端 IP 消耗一次 IP 桶 → 身份解析 → 身份确定后按 member/guest 会话键消耗一次
 * 会话桶。由此：
 * <ul>
 *   <li>失效 Token 超 IP 配额时必须 429，且<strong>零门户调用、零会话读写</strong>；</li>
 *   <li>失效 Token 未超额时只占 IP 配额，会话桶不被消耗（身份未确定）；</li>
 *   <li>游客与会员正常路径每次各占 IP 与会话各一次，不重复计 IP；</li>
 *   <li>同一 sessionId 的不同问题并发时第二个直接 409，第二个绝不 load/save，因而不覆盖首个写入；</li>
 *   <li>不同 sessionId 的并发请求互不影响；</li>
 *   <li>请求结束或异常一律释放占用键。</li>
 * </ul>
 */
class DefaultAgentChatServiceRateLimitOrderTest {

    private static final String SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c";
    private static final String OTHER_SESSION_ID = "11111111-2222-4333-8444-555555555555";
    private static final String GUEST_KEY = AgentIdentity.GUEST_SESSION_PREFIX + SESSION_ID;
    private static final String OTHER_GUEST_KEY = AgentIdentity.GUEST_SESSION_PREFIX + OTHER_SESSION_ID;
    private static final String MEMBER_KEY = AgentIdentity.MEMBER_SESSION_PREFIX + "7:" + SESSION_ID;

    private static final String CLIENT_IP = "203.0.113.5";
    private static final String EXPIRED_TOKEN = "Bearer fake-expired-token-not-a-real-credential";
    private static final String VALID_TOKEN = "Bearer fake-valid-token-not-a-real-credential";

    private static final String ANSWER = "候选商品的名称与价格均来自商城数据。";

    private AgentProperties properties;
    private MallPortalClient portal;
    private RecordingRateLimiter rateLimiter;
    private InFlightGuard inFlightGuard;
    private RecordingSessionRepository sessionRepository;
    private AgentOrchestrator orchestrator;
    private DefaultAgentChatService service;

    @BeforeEach
    void setUp() {
        properties = mock(AgentProperties.class);
        given(properties.isModelAvailable()).willReturn(true);
        given(properties.getSessionMaxMessages()).willReturn(20);
        given(properties.getRequestTimeoutSeconds()).willReturn(30.0);

        portal = mock(MallPortalClient.class);
        rateLimiter = new RecordingRateLimiter();
        inFlightGuard = new InFlightGuard();
        sessionRepository = new RecordingSessionRepository();
        orchestrator = mock(AgentOrchestrator.class);

        // 写回的会话快照带上来路消息，用于断言「第二个并发请求绝不覆盖首个写入」
        given(orchestrator.buildSessionUpdate(any(AgentTurnRequest.class), any(AgentTurnResult.class)))
                .willAnswer(invocation -> new SessionSnapshot(
                        List.of(new SessionMessage("user", invocation.<AgentTurnRequest>getArgument(0).message())),
                        List.of()));

        IdentityResolver identityResolver = new IdentityResolver(portal, sessionRepository);
        service = new DefaultAgentChatService(
                properties, identityResolver, rateLimiter, inFlightGuard, sessionRepository, orchestrator);
    }

    // ------------------------------------------------------------------ //
    // 缺陷 A：分阶段限流
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("失效 Token 超 IP 配额返回 429：零门户调用、零会话读写、占用键释放")
    void expiredTokenOverIpQuotaReturns429WithoutPortalOrSessionAccess() {
        sessionRepository.seed(GUEST_KEY, new SessionSnapshot(
                List.of(new SessionMessage("user", "登录前的历史提问")), List.of()));
        rateLimiter.failIpWith(100);

        AgentApiException failure = catchApiException(
                () -> service.chat(chatRequest(SESSION_ID, "有哪些手机"), EXPIRED_TOKEN, CLIENT_IP));

        assertThat(failure).isInstanceOf(RateLimitExceededException.class);
        assertThat(failure.getStatus()).isEqualTo(429);
        assertThat(((RateLimitExceededException) failure).getRetryAfterSeconds()).isEqualTo(100);

        verifyNoInteractions(portal);
        verifyNoInteractions(orchestrator);
        assertThat(rateLimiter.ipCalls()).containsExactly(CLIENT_IP);
        assertThat(rateLimiter.sessionCalls()).as("身份未确定，不得消耗会话桶").isEmpty();
        assertThat(sessionRepository.loads()).as("超限发生在身份解析与会话访问之前").isEmpty();
        assertThat(sessionRepository.saves()).isEmpty();
        assertThat(inFlightGuard.keys()).as("429 后必须释放占用键").isEmpty();
    }

    @Test
    @DisplayName("失效 Token 未超 IP 配额：只占 IP 配额，会话桶不被消耗")
    void expiredTokenWithinIpQuotaConsumesOnlyIpBucket() {
        given(portal.resolveMember(EXPIRED_TOKEN)).willThrow(PortalException.memberUnauthorized());

        ChatData data = service.chat(chatRequest(SESSION_ID, "有哪些手机"), EXPIRED_TOKEN, CLIENT_IP);

        assertThat(data.requiresLogin()).isTrue();
        assertThat(data.answer()).isEqualTo(DefaultAgentChatService.LOGIN_REQUIRED_ANSWER);
        assertThat(rateLimiter.ipCalls()).containsExactly(CLIENT_IP);
        assertThat(rateLimiter.sessionCalls()).as("失效 Token 没有确定的会话身份").isEmpty();
        // 失效 Token 的提问记入游客命名空间，登录回跳后可迁移恢复
        assertThat(sessionRepository.saves()).containsExactly(GUEST_KEY);
        then(orchestrator).should(never()).run(any(AgentTurnRequest.class));
        assertThat(inFlightGuard.keys()).isEmpty();
    }

    @Test
    @DisplayName("游客正常路径：每次请求 IP 桶与会话桶各占一次，不重复计 IP")
    void guestNormalPathConsumesIpAndGuestSessionOnce() {
        given(orchestrator.run(any(AgentTurnRequest.class))).willReturn(result());

        service.chat(chatRequest(SESSION_ID, "有哪些手机"), null, CLIENT_IP);

        assertThat(rateLimiter.ipCalls()).containsExactly(CLIENT_IP);
        assertThat(rateLimiter.sessionCalls()).containsExactly(GUEST_KEY);
        verifyNoInteractions(portal);
        assertThat(inFlightGuard.keys()).isEmpty();
    }

    @Test
    @DisplayName("会员正常路径：每次请求 IP 桶与会话桶各占一次，会话键为会员命名空间")
    void memberNormalPathConsumesIpAndMemberSessionOnce() {
        given(portal.resolveMember(VALID_TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));
        given(orchestrator.run(any(AgentTurnRequest.class))).willReturn(result());

        service.chat(chatRequest(SESSION_ID, "有哪些手机"), VALID_TOKEN, CLIENT_IP);

        assertThat(rateLimiter.ipCalls()).containsExactly(CLIENT_IP);
        assertThat(rateLimiter.sessionCalls()).containsExactly(MEMBER_KEY);
        then(portal).should(times(1)).resolveMember(VALID_TOKEN);
        assertThat(inFlightGuard.keys()).isEmpty();
    }

    // ------------------------------------------------------------------ //
    // GET / DELETE：身份解析之前的 IP 桶消耗（复用 chat 的可信客户端 IP）
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("GET 超 IP 配额返回 429：零门户、零会话读取、占用键释放")
    void getOverIpQuotaReturns429WithoutPortalOrSessionAccess() {
        sessionRepository.seed(GUEST_KEY, new SessionSnapshot(
                List.of(new SessionMessage("user", "游客历史提问")), List.of()));
        rateLimiter.failIpWith(100);

        AgentApiException failure = catchApiException(
                () -> service.getSession(SESSION_ID, EXPIRED_TOKEN, CLIENT_IP));

        assertThat(failure).isInstanceOf(RateLimitExceededException.class);
        assertThat(failure.getStatus()).isEqualTo(429);
        assertThat(((RateLimitExceededException) failure).getRetryAfterSeconds()).isEqualTo(100);

        verifyNoInteractions(portal);
        assertThat(rateLimiter.ipCalls()).containsExactly(CLIENT_IP);
        assertThat(rateLimiter.sessionCalls()).as("GET 不消耗会话桶").isEmpty();
        assertThat(sessionRepository.loads()).as("超限早于身份解析与会话读取").isEmpty();
        assertThat(inFlightGuard.keys()).as("429 后必须释放占用键").isEmpty();
    }

    @Test
    @DisplayName("DELETE 超 IP 配额返回 429：零门户、零会话删除、占用键释放")
    void deleteOverIpQuotaReturns429WithoutPortalOrSessionDeletion() {
        sessionRepository.seed(GUEST_KEY, new SessionSnapshot(
                List.of(new SessionMessage("user", "游客历史提问")), List.of()));
        rateLimiter.failIpWith(7);

        AgentApiException failure = catchApiException(
                () -> service.deleteSession(SESSION_ID, EXPIRED_TOKEN, CLIENT_IP));

        assertThat(failure).isInstanceOf(RateLimitExceededException.class);
        assertThat(failure.getStatus()).isEqualTo(429);
        assertThat(((RateLimitExceededException) failure).getRetryAfterSeconds()).isEqualTo(7);

        verifyNoInteractions(portal);
        assertThat(rateLimiter.ipCalls()).containsExactly(CLIENT_IP);
        assertThat(rateLimiter.sessionCalls()).as("DELETE 不消耗会话桶").isEmpty();
        assertThat(sessionRepository.deletes()).isEmpty();
        assertThat(inFlightGuard.keys()).isEmpty();
    }

    @Test
    @DisplayName("游客 GET 正常路径只消耗一次 IP 桶：读取 guest 命名空间且不消耗会话桶")
    void guestGetConsumesOnlyIpBucket() {
        sessionRepository.seed(GUEST_KEY, new SessionSnapshot(
                List.of(new SessionMessage("user", "游客历史提问")), List.of()));

        SessionData data = service.getSession(SESSION_ID, null, CLIENT_IP);

        assertThat(data.requiresLogin()).isFalse();
        assertThat(data.messages()).extracting(SessionMessage::content).containsExactly("游客历史提问");
        assertThat(rateLimiter.ipCalls()).containsExactly(CLIENT_IP);
        assertThat(rateLimiter.sessionCalls()).isEmpty();
        verifyNoInteractions(portal);
        assertThat(inFlightGuard.keys()).isEmpty();
    }

    @Test
    @DisplayName("失效 Token GET 未超 IP 配额：只占 IP 桶不占会话桶，返回 requiresLogin")
    void invalidTokenGetConsumesOnlyIpBucket() {
        given(portal.resolveMember(EXPIRED_TOKEN)).willThrow(PortalException.memberUnauthorized());

        SessionData data = service.getSession(SESSION_ID, EXPIRED_TOKEN, CLIENT_IP);

        assertThat(data.requiresLogin()).isTrue();
        assertThat(data.messages()).isEmpty();
        assertThat(rateLimiter.ipCalls()).containsExactly(CLIENT_IP);
        assertThat(rateLimiter.sessionCalls()).as("身份未确定，不得消耗会话桶").isEmpty();
        then(portal).should(times(1)).resolveMember(EXPIRED_TOKEN);
        assertThat(inFlightGuard.keys()).isEmpty();
    }

    @Test
    @DisplayName("失效 Token DELETE 未超 IP 配额：deleted=false，只占 IP 桶不占会话桶且不删 guest")
    void invalidTokenDeleteConsumesOnlyIpBucketAndKeepsGuestKey() {
        sessionRepository.seed(GUEST_KEY, new SessionSnapshot(
                List.of(new SessionMessage("user", "游客历史提问")), List.of()));
        given(portal.resolveMember(EXPIRED_TOKEN)).willThrow(PortalException.memberUnauthorized());

        DeleteSessionData data = service.deleteSession(SESSION_ID, EXPIRED_TOKEN, CLIENT_IP);

        assertThat(data.deleted()).isFalse();
        assertThat(data.requiresLogin()).isTrue();
        assertThat(rateLimiter.ipCalls()).containsExactly(CLIENT_IP);
        assertThat(rateLimiter.sessionCalls()).isEmpty();
        assertThat(sessionRepository.deletes()).isEmpty();
        assertThat(sessionRepository.stored(GUEST_KEY)).isNotNull();
        assertThat(inFlightGuard.keys()).isEmpty();
    }

    // ------------------------------------------------------------------ //
    // 缺陷 B：同 sessionId 生命周期占用键
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("同一 sessionId 并发提交不同问题：第二个 409，且不覆盖首个写入")
    void concurrentDifferentQuestionsOnSameSessionReturn409WithoutOverwrite() throws Exception {
        CountDownLatch enteredOrchestrator = new CountDownLatch(1);
        CountDownLatch releaseOrchestrator = new CountDownLatch(1);
        given(orchestrator.run(any(AgentTurnRequest.class))).willAnswer(invocation -> {
            enteredOrchestrator.countDown();
            releaseOrchestrator.await(5, TimeUnit.SECONDS);
            return result();
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChatData> first =
                    executor.submit(() -> service.chat(chatRequest(SESSION_ID, "有哪些手机"), null, CLIENT_IP));
            assertThat(enteredOrchestrator.await(5, TimeUnit.SECONDS))
                    .as("首个请求应已进入编排阶段并持有 sessionId 占用键")
                    .isTrue();

            AgentApiException duplicate = catchApiException(
                    () -> service.chat(chatRequest(SESSION_ID, "有哪些耳机"), null, CLIENT_IP));
            assertThat(duplicate.getStatus()).isEqualTo(409);
            assertThat(duplicate.getMessage()).isEqualTo(AgentApiException.DUPLICATE_REQUEST_MESSAGE);

            releaseOrchestrator.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releaseOrchestrator.countDown();
            executor.shutdownNow();
        }

        // 第二个请求在身份解析/限流/编排之前被拦截：只有首个请求写入，且写入内容属于首个问题
        assertThat(sessionRepository.saves()).containsExactly(GUEST_KEY);
        assertThat(sessionRepository.stored(GUEST_KEY).messages().get(0).content()).isEqualTo("有哪些手机");
        assertThat(rateLimiter.ipCalls()).containsExactly(CLIENT_IP);
        assertThat(rateLimiter.sessionCalls()).containsExactly(GUEST_KEY);
        then(orchestrator).should(times(1)).run(any(AgentTurnRequest.class));
        assertThat(inFlightGuard.keys()).as("请求完成后必须释放占用键").isEmpty();
    }

    @Test
    @DisplayName("不同 sessionId 并发提交互不影响，各自完成编排与写入")
    void concurrentChatsOnDifferentSessionsDoNotBlockEachOther() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch releaseOrchestrator = new CountDownLatch(1);
        given(orchestrator.run(any(AgentTurnRequest.class))).willAnswer(invocation -> {
            bothEntered.countDown();
            releaseOrchestrator.await(5, TimeUnit.SECONDS);
            return result();
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ChatData> first =
                    executor.submit(() -> service.chat(chatRequest(SESSION_ID, "有哪些手机"), null, CLIENT_IP));
            Future<ChatData> second =
                    executor.submit(() -> service.chat(chatRequest(OTHER_SESSION_ID, "有哪些耳机"), null, CLIENT_IP));
            assertThat(bothEntered.await(5, TimeUnit.SECONDS))
                    .as("不同 sessionId 必须能同时进入编排阶段")
                    .isTrue();
            releaseOrchestrator.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(second.get(5, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releaseOrchestrator.countDown();
            executor.shutdownNow();
        }

        assertThat(sessionRepository.saves()).containsExactlyInAnyOrder(GUEST_KEY, OTHER_GUEST_KEY);
        then(orchestrator).should(times(2)).run(any(AgentTurnRequest.class));
        assertThat(inFlightGuard.keys()).isEmpty();
    }

    @Test
    @DisplayName("编排异常后占用键必须释放，且失败不落库")
    void guardIsReleasedAfterOrchestrationFailure() {
        given(orchestrator.run(any(AgentTurnRequest.class))).willThrow(ModelException.timeout("上游超时"));

        AgentApiException failure = catchApiException(
                () -> service.chat(chatRequest(SESSION_ID, "有哪些手机"), null, CLIENT_IP));

        assertThat(failure.getStatus()).isEqualTo(502);
        assertThat(sessionRepository.saves()).as("失败不落库").isEmpty();
        assertThat(inFlightGuard.keys()).as("异常后必须释放占用键").isEmpty();
    }

    @Test
    @DisplayName("会话桶超限时返回 429 并释放占用键（IP 已在预认证阶段消耗）")
    void sessionQuotaRejectionReleasesGuardAndKeepsIpConsumed() {
        rateLimiter.failSessionWith(57);

        AgentApiException failure = catchApiException(
                () -> service.chat(chatRequest(SESSION_ID, "有哪些手机"), null, CLIENT_IP));

        assertThat(failure).isInstanceOf(RateLimitExceededException.class);
        assertThat(((RateLimitExceededException) failure).getRetryAfterSeconds()).isEqualTo(57);
        assertThat(rateLimiter.ipCalls()).as("前置 IP 门槛已消耗一次").containsExactly(CLIENT_IP);
        assertThat(sessionRepository.saves()).isEmpty();
        assertThat(inFlightGuard.keys()).isEmpty();
    }

    // ------------------------------------------------------------------ //
    // 辅助
    // ------------------------------------------------------------------ //

    private static ChatRequest chatRequest(String sessionId, String message) {
        return new ChatRequest(sessionId, message);
    }

    private static AgentTurnResult result() {
        return new AgentTurnResult(
                ANSWER, List.of(), false, List.of(), List.of(), 1, AgentTurnResult.StoppedReason.ANSWER);
    }

    private static AgentApiException catchApiException(ThrowingCallable callable) {
        Throwable thrown = catchThrowable(callable);
        assertThat(thrown).isInstanceOf(AgentApiException.class);
        return (AgentApiException) thrown;
    }

    /** 记录两阶段调用，并可让任一阶段按需失败；聚合 {@code check} 一旦被调用即视为接线错误。 */
    private static final class RecordingRateLimiter implements RateLimiter {

        private final List<String> ipCalls = new ArrayList<>();
        private final List<String> sessionCalls = new ArrayList<>();
        private RateLimitExceededException ipFailure;
        private RateLimitExceededException sessionFailure;

        @Override
        public synchronized void check(String sessionKey, String clientIp) {
            throw new AssertionError("chat 流程不得使用聚合 check，应使用分阶段方法");
        }

        @Override
        public synchronized void checkIp(String clientIp) {
            ipCalls.add(clientIp);
            if (ipFailure != null) {
                throw ipFailure;
            }
        }

        @Override
        public synchronized void checkSession(String sessionKey) {
            sessionCalls.add(sessionKey);
            if (sessionFailure != null) {
                throw sessionFailure;
            }
        }

        synchronized void failIpWith(int retryAfterSeconds) {
            this.ipFailure = new RateLimitExceededException(retryAfterSeconds);
        }

        synchronized void failSessionWith(int retryAfterSeconds) {
            this.sessionFailure = new RateLimitExceededException(retryAfterSeconds);
        }

        synchronized List<String> ipCalls() {
            return List.copyOf(ipCalls);
        }

        synchronized List<String> sessionCalls() {
            return List.copyOf(sessionCalls);
        }
    }

    /** 进程内会话仓储 fake：记录读写键与内容，供断言顺序与「不覆盖」。 */
    private static final class RecordingSessionRepository implements SessionRepository {

        private final Map<String, SessionSnapshot> store = new LinkedHashMap<>();
        private final List<String> loads = new ArrayList<>();
        private final List<String> saves = new ArrayList<>();
        private final List<String> deletes = new ArrayList<>();

        @Override
        public synchronized SessionSnapshot load(String key) {
            loads.add(key);
            return store.getOrDefault(key, SessionSnapshot.empty());
        }

        @Override
        public synchronized void save(String key, SessionSnapshot snapshot) {
            saves.add(key);
            store.put(key, snapshot);
        }

        @Override
        public synchronized boolean delete(String key) {
            deletes.add(key);
            return store.remove(key) != null;
        }

        @Override
        public synchronized void copy(String sourceKey, String targetKey) {
            SessionSnapshot source = store.getOrDefault(sourceKey, SessionSnapshot.empty());
            if (source.isEmpty() || !store.getOrDefault(targetKey, SessionSnapshot.empty()).isEmpty()) {
                return;
            }
            store.put(targetKey, source);
        }

        void seed(String key, SessionSnapshot snapshot) {
            store.put(key, snapshot);
        }

        SessionSnapshot stored(String key) {
            return store.get(key);
        }

        List<String> loads() {
            return List.copyOf(loads);
        }

        List<String> saves() {
            return List.copyOf(saves);
        }

        List<String> deletes() {
            return List.copyOf(deletes);
        }
    }
}

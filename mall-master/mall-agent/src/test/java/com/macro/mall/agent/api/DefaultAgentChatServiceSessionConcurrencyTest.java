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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macro.mall.agent.agent.AgentOrchestrator;
import com.macro.mall.agent.agent.AgentTurnRequest;
import com.macro.mall.agent.agent.AgentTurnResult;
import com.macro.mall.agent.config.AgentProperties;
import com.macro.mall.agent.session.AgentIdentity;
import com.macro.mall.agent.session.IdentityResolver;
import com.macro.mall.agent.session.InFlightGuard;
import com.macro.mall.agent.session.RateLimiter;
import com.macro.mall.agent.session.SessionRepository;
import com.macro.mall.agent.session.SessionSnapshot;
import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.storefront.PortalException;
import com.macro.mall.agent.storefront.dto.MemberInfoResponse;

/**
 * {@code chat}、{@code getSession}、{@code deleteSession} 对同一规范 sessionId 共用
 * {@link InFlightGuard} 占用键的并发回归测试（TDD）。
 *
 * <p>被测对象是真实 {@link DefaultAgentChatService}、真实 {@link InFlightGuard} 与真实
 * {@link IdentityResolver}；门户、编排器与配置用 Mockito 替身，会话仓储与限流器用进程内 fake，
 * <strong>不连接任何共享 Redis、门户或模型</strong>。
 *
 * <p>所有并发用例都靠 {@link CountDownLatch} 同步屏障制造确定的「持锁窗口」，
 * <strong>不依赖 {@code sleep} 或时序猜测</strong>：先让持锁方进入其临界区（编排器或仓储），
 * 主线程确认屏障后再发起竞争请求。
 *
 * <p>覆盖边界：
 * <ul>
 *   <li>chat 持锁时 GET 立即 409，且竞争请求零门户（身份解析）与零仓储访问；</li>
 *   <li>chat 持锁时 DELETE 立即 409，同样零身份解析与零仓储访问；</li>
 *   <li>GET 占锁期间 chat 被 409 拒绝（同一规范键）；</li>
 *   <li>DELETE 占锁期间 chat 被 409 拒绝；</li>
 *   <li>竞争请求不得误释放持锁方的占用键（连续两次竞争后持锁方仍持锁）；</li>
 *   <li>身份解析异常（GET/DELETE）后占用键已释放，可再次成功占用；</li>
 *   <li>不同 sessionId 互不阻塞。</li>
 * </ul>
 */
class DefaultAgentChatServiceSessionConcurrencyTest {

    private static final String SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c";
    private static final String OTHER_SESSION_ID = "11111111-2222-4333-8444-555555555555";
    private static final String GUEST_KEY = AgentIdentity.GUEST_SESSION_PREFIX + SESSION_ID;
    private static final String OTHER_GUEST_KEY = AgentIdentity.GUEST_SESSION_PREFIX + OTHER_SESSION_ID;

    private static final String CLIENT_IP = "203.0.113.11";
    private static final String TOKEN = "Bearer fake-token-not-a-real-credential";

    private static final String ANSWER = "候选商品的名称与价格均来自商城数据。";
    private static final long AWAIT_SECONDS = 5L;

    private AgentProperties properties;
    private MallPortalClient portal;
    private RateLimiter rateLimiter;
    private InFlightGuard inFlightGuard;
    private GatedRecordingSessionRepository sessionRepository;
    private AgentOrchestrator orchestrator;
    private DefaultAgentChatService service;

    @BeforeEach
    void setUp() {
        properties = mock(AgentProperties.class);
        given(properties.isModelAvailable()).willReturn(true);
        given(properties.getSessionMaxMessages()).willReturn(20);
        given(properties.getRequestTimeoutSeconds()).willReturn(30.0);

        portal = mock(MallPortalClient.class);
        rateLimiter = mock(RateLimiter.class);
        inFlightGuard = new InFlightGuard();
        sessionRepository = new GatedRecordingSessionRepository();
        orchestrator = mock(AgentOrchestrator.class);

        given(orchestrator.buildSessionUpdate(any(AgentTurnRequest.class), any(AgentTurnResult.class)))
                .willAnswer(invocation -> new SessionSnapshot(
                        List.of(new SessionMessage("user", invocation.<AgentTurnRequest>getArgument(0).message())),
                        List.of()));

        IdentityResolver identityResolver = new IdentityResolver(portal, sessionRepository);
        service = new DefaultAgentChatService(
                properties, identityResolver, rateLimiter, inFlightGuard, sessionRepository, orchestrator);
    }

    // ------------------------------------------------------------------ //
    // chat 持锁 → GET / DELETE 立即 409，零身份解析、零仓储访问
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("chat 持锁时 GET 立即 409，且零身份解析、零仓储访问，竞争不误释放持锁方")
    void getReturns409WhileChatHoldsGuard() throws Exception {
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));
        CountDownLatch enteredOrchestrator = new CountDownLatch(1);
        CountDownLatch releaseOrchestrator = new CountDownLatch(1);
        given(orchestrator.run(any(AgentTurnRequest.class))).willAnswer(invocation -> {
            enteredOrchestrator.countDown();
            awaitOrFalse(releaseOrchestrator);
            return result();
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChatData> chat =
                    executor.submit(() -> service.chat(chatRequest("有哪些手机"), null, CLIENT_IP));
            assertBarrier(enteredOrchestrator, "chat 应已进入编排阶段并持有 sessionId 占用键");

            int loadsBefore = sessionRepository.loads().size();
            int savesBefore = sessionRepository.saves().size();

            assertDuplicate(() -> service.getSession(SESSION_ID, TOKEN, CLIENT_IP));
            // 连续第二次竞争仍须 409：竞争请求不得释放持锁方的占用键
            assertDuplicate(() -> service.getSession(SESSION_ID, TOKEN, CLIENT_IP));

            verifyNoInteractions(portal);
            assertThat(sessionRepository.loads()).as("GET 被拒后不得读写仓储").hasSize(loadsBefore);
            assertThat(sessionRepository.saves()).as("GET 被拒后不得写仓储").hasSize(savesBefore);

            releaseOrchestrator.countDown();
            assertThat(chat.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releaseOrchestrator.countDown();
            executor.shutdownNow();
        }

        // chat 结束后占用键释放，同一 sessionId 的 GET 恢复可进入
        SessionData recovered = service.getSession(SESSION_ID, null, CLIENT_IP);
        assertThat(recovered.sessionId()).isEqualTo(SESSION_ID);
        assertThat(inFlightGuard.keys()).isEmpty();
    }

    @Test
    @DisplayName("chat 持锁时 DELETE 立即 409，且零身份解析、零仓储删除")
    void deleteReturns409WhileChatHoldsGuard() throws Exception {
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));
        CountDownLatch enteredOrchestrator = new CountDownLatch(1);
        CountDownLatch releaseOrchestrator = new CountDownLatch(1);
        given(orchestrator.run(any(AgentTurnRequest.class))).willAnswer(invocation -> {
            enteredOrchestrator.countDown();
            awaitOrFalse(releaseOrchestrator);
            return result();
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChatData> chat =
                    executor.submit(() -> service.chat(chatRequest("有哪些手机"), null, CLIENT_IP));
            assertBarrier(enteredOrchestrator, "chat 应已进入编排阶段并持有 sessionId 占用键");

            int deletesBefore = sessionRepository.deletes().size();

            assertDuplicate(() -> service.deleteSession(SESSION_ID, TOKEN, CLIENT_IP));

            verifyNoInteractions(portal);
            assertThat(sessionRepository.deletes()).as("DELETE 被拒后不得触达仓储删除").hasSize(deletesBefore);

            releaseOrchestrator.countDown();
            assertThat(chat.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releaseOrchestrator.countDown();
            executor.shutdownNow();
        }

        assertThat(inFlightGuard.keys()).isEmpty();
    }

    // ------------------------------------------------------------------ //
    // GET / DELETE 占锁 → chat 立即 409
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("GET 占锁期间 chat 立即 409，且 chat 零限流/零编排/零仓储写入")
    void chatReturns409WhileGetHoldsGuard() throws Exception {
        // 修复前 chat 会无视被占用的键并完整跑完编排；预置成功的编排结果，让红灯表现为「未抛 409」
        given(orchestrator.run(any(AgentTurnRequest.class))).willReturn(result());
        CountDownLatch loadEntered = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        sessionRepository.blockNextLoad(loadEntered, releaseLoad);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<SessionData> get = executor.submit(() -> service.getSession(SESSION_ID, null, CLIENT_IP));
            assertBarrier(loadEntered, "GET 应已持有占用键并进入仓储读取");

            assertDuplicate(() -> service.chat(chatRequest("有哪些手机"), null, CLIENT_IP));

            // 持锁的 GET 已按新语义消耗一次 IP 桶；被拒的 chat 不得再消耗任何限流预算
            then(rateLimiter).should(times(1)).checkIp(CLIENT_IP);
            then(rateLimiter).should(never()).checkSession(anyString());
            verifyNoInteractions(orchestrator);
            assertThat(sessionRepository.loads()).as("被拒的 chat 不得读仓储").isEmpty();
            assertThat(sessionRepository.saves()).as("被拒的 chat 不得写仓储").isEmpty();

            releaseLoad.countDown();
            assertThat(get.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releaseLoad.countDown();
            executor.shutdownNow();
        }

        assertThat(inFlightGuard.keys()).isEmpty();
    }

    @Test
    @DisplayName("DELETE 占锁期间 chat 立即 409，且 chat 零限流/零编排/零仓储写入")
    void chatReturns409WhileDeleteHoldsGuard() throws Exception {
        // 修复前 chat 会无视被占用的键并完整跑完编排；预置成功的编排结果，让红灯表现为「未抛 409」
        given(orchestrator.run(any(AgentTurnRequest.class))).willReturn(result());
        CountDownLatch deleteEntered = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        sessionRepository.blockNextDelete(deleteEntered, releaseDelete);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DeleteSessionData> delete = executor.submit(() -> service.deleteSession(SESSION_ID, null, CLIENT_IP));
            assertBarrier(deleteEntered, "DELETE 应已持有占用键并进入仓储删除");

            assertDuplicate(() -> service.chat(chatRequest("有哪些手机"), null, CLIENT_IP));

            // 持锁的 DELETE 已按新语义消耗一次 IP 桶；被拒的 chat 不得再消耗任何限流预算
            then(rateLimiter).should(times(1)).checkIp(CLIENT_IP);
            then(rateLimiter).should(never()).checkSession(anyString());
            verifyNoInteractions(orchestrator);
            assertThat(sessionRepository.loads()).isEmpty();
            assertThat(sessionRepository.saves()).isEmpty();

            releaseDelete.countDown();
            assertThat(delete.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releaseDelete.countDown();
            executor.shutdownNow();
        }

        assertThat(inFlightGuard.keys()).isEmpty();
    }

    // ------------------------------------------------------------------ //
    // 异常路径释放 + 不同 sessionId 不阻塞
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("GET 身份解析异常后占用键已释放，随后可再次成功占用")
    void guardIsReleasedAfterGetIdentityFailure() {
        given(portal.resolveMember(TOKEN)).willThrow(PortalException.unavailable());

        Throwable failure = catchThrowable(() -> service.getSession(SESSION_ID, TOKEN, CLIENT_IP));

        assertThat(failure).isInstanceOf(PortalException.class);
        assertThat(inFlightGuard.keys()).as("GET 异常后必须释放占用键").isEmpty();

        assertThat(service.getSession(SESSION_ID, null, CLIENT_IP)).isNotNull();
        assertThat(inFlightGuard.keys()).isEmpty();
    }

    @Test
    @DisplayName("DELETE 身份解析异常后占用键已释放，随后可再次成功占用")
    void guardIsReleasedAfterDeleteIdentityFailure() {
        given(portal.resolveMember(TOKEN)).willThrow(PortalException.unavailable());

        Throwable failure = catchThrowable(() -> service.deleteSession(SESSION_ID, TOKEN, CLIENT_IP));

        assertThat(failure).isInstanceOf(PortalException.class);
        assertThat(inFlightGuard.keys()).as("DELETE 异常后必须释放占用键").isEmpty();

        assertThat(service.deleteSession(SESSION_ID, null, CLIENT_IP)).isNotNull();
        assertThat(inFlightGuard.keys()).isEmpty();
    }

    @Test
    @DisplayName("一个 sessionId 占用期间，不同 sessionId 的请求不被阻塞")
    void heldGuardOnOneSessionDoesNotBlockDifferentSession() throws Exception {
        CountDownLatch loadEntered = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        sessionRepository.blockNextLoad(loadEntered, releaseLoad);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<SessionData> held = executor.submit(() -> service.getSession(SESSION_ID, null, CLIENT_IP));
            assertBarrier(loadEntered, "GET 应已持有 SESSION_ID 的占用键");

            SessionData other = service.getSession(OTHER_SESSION_ID, null, CLIENT_IP);

            assertThat(other.sessionId()).isEqualTo(OTHER_SESSION_ID);
            assertThat(other.requiresLogin()).isFalse();
            assertThat(sessionRepository.loads()).contains(OTHER_GUEST_KEY);

            releaseLoad.countDown();
            assertThat(held.get(AWAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releaseLoad.countDown();
            executor.shutdownNow();
        }

        assertThat(inFlightGuard.keys()).isEmpty();
    }

    // ------------------------------------------------------------------ //
    // 辅助
    // ------------------------------------------------------------------ //

    private static ChatRequest chatRequest(String message) {
        return new ChatRequest(SESSION_ID, message);
    }

    private static AgentTurnResult result() {
        return new AgentTurnResult(
                ANSWER, List.of(), false, List.of(), List.of(), 1, AgentTurnResult.StoppedReason.ANSWER);
    }

    private static void assertDuplicate(ThrowingCallable callable) {
        Throwable thrown = catchThrowable(callable);
        assertThat(thrown).isInstanceOf(AgentApiException.class);
        AgentApiException failure = (AgentApiException) thrown;
        assertThat(failure.getStatus()).isEqualTo(409);
        assertThat(failure.getMessage()).isEqualTo(AgentApiException.DUPLICATE_REQUEST_MESSAGE);
    }

    private static void assertBarrier(CountDownLatch latch, String description) throws InterruptedException {
        assertThat(latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as(description).isTrue();
    }

    private static void awaitOrFalse(CountDownLatch latch) {
        try {
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 进程内会话仓储 fake：记录读写键，并可在第一次 {@code load}/{@code delete} 上设置同步屏障，
     * 让调用方进入临界区后保持占用状态，供并发用例确定性地制造「持锁窗口」。
     */
    private static final class GatedRecordingSessionRepository implements SessionRepository {

        private final Map<String, SessionSnapshot> store = new ConcurrentHashMap<>();
        private final List<String> loads = Collections.synchronizedList(new ArrayList<>());
        private final List<String> saves = Collections.synchronizedList(new ArrayList<>());
        private final List<String> deletes = Collections.synchronizedList(new ArrayList<>());

        private final AtomicBoolean loadGatePending = new AtomicBoolean(false);
        private volatile CountDownLatch loadEntered = new CountDownLatch(0);
        private volatile CountDownLatch loadRelease = new CountDownLatch(0);

        private final AtomicBoolean deleteGatePending = new AtomicBoolean(false);
        private volatile CountDownLatch deleteEntered = new CountDownLatch(0);
        private volatile CountDownLatch deleteRelease = new CountDownLatch(0);

        void blockNextLoad(CountDownLatch entered, CountDownLatch release) {
            this.loadEntered = entered;
            this.loadRelease = release;
            this.loadGatePending.set(true);
        }

        void blockNextDelete(CountDownLatch entered, CountDownLatch release) {
            this.deleteEntered = entered;
            this.deleteRelease = release;
            this.deleteGatePending.set(true);
        }

        @Override
        public SessionSnapshot load(String key) {
            if (loadGatePending.compareAndSet(true, false)) {
                loadEntered.countDown();
                awaitOrFalse(loadRelease);
            }
            loads.add(key);
            return store.getOrDefault(key, SessionSnapshot.empty());
        }

        @Override
        public void save(String key, SessionSnapshot snapshot) {
            saves.add(key);
            store.put(key, snapshot);
        }

        @Override
        public boolean delete(String key) {
            if (deleteGatePending.compareAndSet(true, false)) {
                deleteEntered.countDown();
                awaitOrFalse(deleteRelease);
            }
            deletes.add(key);
            return store.remove(key) != null;
        }

        @Override
        public void copy(String sourceKey, String targetKey) {
            SessionSnapshot source = store.getOrDefault(sourceKey, SessionSnapshot.empty());
            if (source.isEmpty() || !store.getOrDefault(targetKey, SessionSnapshot.empty()).isEmpty()) {
                return;
            }
            store.put(targetKey, source);
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

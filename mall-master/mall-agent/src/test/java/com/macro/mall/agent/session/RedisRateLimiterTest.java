package com.macro.mall.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.macro.mall.agent.api.AgentApiException;
import com.macro.mall.agent.api.ApiEnvelope;
import com.macro.mall.agent.api.ApiExceptionHandler;

/**
 * 固定窗口限流、Lua 原子计数与并发防重的 TDD 契约测试（计划 Task 6 Step 3）。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.safety.rate_limit} 与
 * {@code mall_shopping_agent.api.deps.InFlightGuard}：
 * <ul>
 *   <li>会话维度默认 300 秒 20 次、IP 维度默认 300 秒 60 次；</li>
 *   <li>限流键 {@code mall:agent:rate:{session|ip}:{sha256[:32]}:{bucket}}，桶为
 *       {@code floor(epochSeconds / windowSeconds)}；原始 sessionKey 与原始 IP 绝不进入键或日志；</li>
 *   <li>兼容入口 {@link RateLimiter#check(String, String)} 先会话后 IP，会话已超限时不再消耗 IP 预算；
 *       该性质只属于兼容入口，chat 主流程改用 {@code checkIp} + {@code checkSession} 分阶段调用，
 *       会话超限的请求其 IP 预算已先被消耗；</li>
 *   <li>计数与首次设置 TTL 在一条原子 Lua 脚本内完成（不重置已有 TTL）；</li>
 *   <li>超限抛固定文案 429 并通过 HTTP {@code Retry-After} 暴露整数秒；</li>
 *   <li>并发防重是单实例进程内保护：同一规范化 sessionId 上只允许一个在飞请求（chat / GET /
 *       DELETE session 共用同一占用键），第二个并发请求固定 409，不按问题摘要区分；不同 sessionId
 *       可并行；请求结束或异常后释放占用键；<strong>不提供跨实例互斥</strong>，限流与前端去重都不等同于跨实例锁。</li>
 * </ul>
 *
 * <p>期望值来自一次性 Python 探针实测：固定时钟 {@code 1_700_000_000} 秒时桶为 {@code 5666666}，
 * 会话摘要 {@code 510efb84ea1fdb67f05f4a13a6a591e7}，IP 摘要 {@code 631f08140b24b7274d12df3c37a1a80c}，
 * {@code unknown} 摘要 {@code b23a6a8439c0dde5515893e7c90c1e32}，第 21 次会话请求的
 * {@code Retry-After} 为 {@code 100}。
 */
class RedisRateLimiterTest {

    private static final String SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c";
    private static final String OTHER_SESSION_ID = "11111111-2222-4333-8444-555555555555";
    private static final String SESSION_KEY = "mall:agent:session:guest:" + SESSION_ID;
    private static final String OTHER_SESSION_KEY = "mall:agent:session:member:9:" + SESSION_ID;
    private static final String CLIENT_IP = "203.0.113.10";
    private static final String OTHER_IP = "198.51.100.7";

    private static final String SESSION_HASH = "510efb84ea1fdb67f05f4a13a6a591e7";
    private static final String IP_HASH = "631f08140b24b7274d12df3c37a1a80c";
    private static final String UNKNOWN_IP_HASH = "b23a6a8439c0dde5515893e7c90c1e32";
    private static final long BUCKET = 5666666L;
    private static final long NOW_EPOCH_SECONDS = 1_700_000_000L;

    /**
     * Python {@code mall_shopping_agent.safety.rate_limit.RateLimitExceededError.message} 的精确取值：
     * 业务限流 429 文案<strong>不带句号</strong>（对照 {@code rate_limit.py} 第 48-49 行），
     * 与 {@code api/errors.py} 中 {@code _HTTP_STATUS_MESSAGES[429]} 的通用兜底文案（带句号）刻意区分。
     */
    private static final String PYTHON_RATE_LIMITED_MESSAGE = "请求过于频繁，请稍后再试";

    private static final RateLimiter.Rule SESSION_RULE = new RateLimiter.Rule(20, 300);
    private static final RateLimiter.Rule IP_RULE = new RateLimiter.Rule(60, 300);

    private static final String SESSION_RATE_KEY = "mall:agent:rate:session:" + SESSION_HASH + ":" + BUCKET;

    /** Redis 计数器返回 null 时失败关闭的固定文案（不得含限流键、URL 或凭据）。 */
    private static final String COUNTER_UNAVAILABLE_MESSAGE = "限流计数器未返回计数，拒绝放行";

    // ------------------------------------------------------------------ //
    // 键布局与规则
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("限流键布局与文档一致：mall:agent:rate:{kind}:{sha256[:32]}:{bucket}")
    void documentedKeyLayoutMatchesPython() {
        InMemoryRateCounter counter = new InMemoryRateCounter();

        new RedisRateLimiter(counter, SESSION_RULE, IP_RULE, fixedClock()).check(SESSION_KEY, CLIENT_IP);

        assertThat(counter.keys()).containsExactlyInAnyOrder(
                "mall:agent:rate:session:" + SESSION_HASH + ":" + BUCKET,
                "mall:agent:rate:ip:" + IP_HASH + ":" + BUCKET);
    }

    @Test
    @DisplayName("规则 Retry-After 兜底为窗口秒数，窗口非正时至少为 1")
    void ruleRetryAfterSecondsIsClamped() {
        assertThat(SESSION_RULE.retryAfterSeconds()).isEqualTo(300);
        assertThat(new RateLimiter.Rule(20, 0).retryAfterSeconds()).isEqualTo(1);
        assertThat(new RateLimiter.Rule(20, -5).retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("原始 IP 与原始 sessionId 绝不进入限流键")
    void rawIpAndRawSessionIdNeverAppearInKeys() {
        InMemoryRateCounter counter = new InMemoryRateCounter();

        new RedisRateLimiter(counter, SESSION_RULE, IP_RULE, fixedClock()).check(SESSION_KEY, CLIENT_IP);

        assertThat(counter.keys()).hasSize(2);
        for (String key : counter.keys()) {
            assertThat(key).startsWith("mall:agent:rate:")
                    .doesNotContain(CLIENT_IP)
                    .doesNotContain(SESSION_ID)
                    .doesNotContain("203.0.113");
        }
    }

    @Test
    @DisplayName("缺失或空 IP 回落到摘要后的 unknown 占位符")
    void missingClientIpFallsBackToHashedPlaceholder() {
        InMemoryRateCounter counter = new InMemoryRateCounter();
        RedisRateLimiter limiter = new RedisRateLimiter(counter, SESSION_RULE, IP_RULE, fixedClock());

        limiter.check(SESSION_KEY, null);
        limiter.check(SESSION_KEY, "");

        assertThat(counter.keys()).anyMatch(key -> key.contains(":ip:" + UNKNOWN_IP_HASH + ":"));
        assertThat(counter.keys()).noneMatch(key -> key.contains(":ip:unknown"));
    }

    // ------------------------------------------------------------------ //
    // 固定窗口边界
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("同一会话每窗口恰好允许 20 次，第 21 次 429 且 Retry-After 与 Python 一致")
    void sessionAllowsExactlyTwentyRequestsPerWindow() {
        InMemoryRateCounter counter = new InMemoryRateCounter();
        RedisRateLimiter limiter = new RedisRateLimiter(counter, SESSION_RULE, IP_RULE, fixedClock());

        for (int index = 0; index < 20; index++) {
            limiter.check(SESSION_KEY, CLIENT_IP);
        }

        assertThatThrownBy(() -> limiter.check(SESSION_KEY, CLIENT_IP))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(exception -> assertThat(((RateLimitExceededException) exception).getRetryAfterSeconds())
                        .isEqualTo(100))
                .hasMessage(RateLimitExceededException.MESSAGE);
        assertThat(counter.count("mall:agent:rate:session:" + SESSION_HASH + ":" + BUCKET)).isEqualTo(21L);
    }

    @Test
    @DisplayName("会话已超限时不再消耗 IP 预算")
    void rejectedSessionRequestDoesNotConsumeIpBudget() {
        InMemoryRateCounter counter = new InMemoryRateCounter();
        RedisRateLimiter limiter = new RedisRateLimiter(counter, SESSION_RULE, IP_RULE, fixedClock());

        for (int index = 0; index < 20; index++) {
            limiter.check(SESSION_KEY, CLIENT_IP);
        }

        assertThatThrownBy(() -> limiter.check(SESSION_KEY, OTHER_IP))
                .isInstanceOf(RateLimitExceededException.class);

        assertThat(counter.keys())
                .noneMatch(key -> key.contains(":ip:" + AgentIdentity.fingerprint(OTHER_IP)));
    }

    @Test
    @DisplayName("同一 IP 跨会话恰好允许 60 次，第 61 次 429")
    void ipAllowsExactlySixtyRequestsAcrossSessions() {
        InMemoryRateCounter counter = new InMemoryRateCounter();
        RedisRateLimiter limiter = new RedisRateLimiter(counter, SESSION_RULE, IP_RULE, fixedClock());

        for (int index = 0; index < 60; index++) {
            limiter.check(SESSION_KEY + ":" + index, CLIENT_IP);
        }

        assertThatThrownBy(() -> limiter.check(SESSION_KEY + ":final", CLIENT_IP))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(exception -> assertThat(((RateLimitExceededException) exception).getRetryAfterSeconds())
                        .isEqualTo(100));
    }

    @Test
    @DisplayName("窗口滚动后预算恢复，且使用新的窗口桶")
    void windowResetRestoresBudget() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(NOW_EPOCH_SECONDS));
        InMemoryRateCounter counter = new InMemoryRateCounter();
        RedisRateLimiter limiter = new RedisRateLimiter(counter, SESSION_RULE, IP_RULE, clock);

        for (int index = 0; index < 20; index++) {
            limiter.check(SESSION_KEY, CLIENT_IP);
        }
        assertThatThrownBy(() -> limiter.check(SESSION_KEY, CLIENT_IP))
                .isInstanceOf(RateLimitExceededException.class);

        clock.advance(Duration.ofSeconds(300));
        limiter.check(SESSION_KEY, CLIENT_IP);

        assertThat(counter.keys())
                .anyMatch(key -> key.equals("mall:agent:rate:session:" + SESSION_HASH + ":" + (BUCKET + 1)));
    }

    @Test
    @DisplayName("会话与 IP 两个维度的额度互相独立")
    void limitsAreIndependentPerSessionAndPerIp() {
        InMemoryRateCounter counter = new InMemoryRateCounter();
        RedisRateLimiter limiter = new RedisRateLimiter(counter, SESSION_RULE, IP_RULE, fixedClock());

        // 手工推导的四个桶键：原会话 SESSION_KEY 与原 IP CLIENT_IP 各在各自键上计 20；
        // 新会话 OTHER_SESSION_KEY 与新 IP OTHER_IP 各在各自键上计 1；四键互不覆盖。
        String originalSessionRateKey = SESSION_RATE_KEY;
        String originalIpRateKey = "mall:agent:rate:ip:" + IP_HASH + ":" + BUCKET;
        String otherSessionRateKey =
                "mall:agent:rate:session:" + AgentIdentity.fingerprint(OTHER_SESSION_KEY) + ":" + BUCKET;
        String otherIpRateKey = "mall:agent:rate:ip:" + AgentIdentity.fingerprint(OTHER_IP) + ":" + BUCKET;

        for (int index = 0; index < 20; index++) {
            limiter.check(SESSION_KEY, CLIENT_IP);
        }

        limiter.check(OTHER_SESSION_KEY, OTHER_IP);

        // 原会话键与原 IP 键仍各为 20，未被换成新键的那一次请求触碰
        assertThat(counter.count(originalSessionRateKey)).isEqualTo(20L);
        assertThat(counter.count(originalIpRateKey)).isEqualTo(20L);
        // 新会话键与新 IP 键各为 1
        assertThat(counter.count(otherSessionRateKey)).isEqualTo(1L);
        assertThat(counter.count(otherIpRateKey)).isEqualTo(1L);
        // 四个桶互不重叠：会话维度与 IP 维度、原键与新键彼此独立
        assertThat(counter.keys()).containsExactlyInAnyOrder(
                originalSessionRateKey, originalIpRateKey, otherSessionRateKey, otherIpRateKey);
        assertThat(otherSessionRateKey).isNotEqualTo(originalSessionRateKey);
        assertThat(otherIpRateKey).isNotEqualTo(originalIpRateKey);
        assertThat(otherSessionRateKey).isNotEqualTo(otherIpRateKey);
    }

    // ------------------------------------------------------------------ //
    // 分阶段限流：预认证 IP 桶 + 身份确定后的会话桶
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("分阶段调用只消耗各自维度：checkIp 一个 IP 键、checkSession 一个会话键")
    void stagedChecksConsumeSeparateBuckets() {
        InMemoryRateCounter counter = new InMemoryRateCounter();
        RedisRateLimiter limiter = new RedisRateLimiter(counter, SESSION_RULE, IP_RULE, fixedClock());

        limiter.checkIp(CLIENT_IP);
        limiter.checkSession(SESSION_KEY);

        assertThat(counter.keys()).containsExactlyInAnyOrder(
                "mall:agent:rate:ip:" + IP_HASH + ":" + BUCKET,
                "mall:agent:rate:session:" + SESSION_HASH + ":" + BUCKET);
        assertThat(counter.count("mall:agent:rate:ip:" + IP_HASH + ":" + BUCKET)).isEqualTo(1L);
        assertThat(counter.count("mall:agent:rate:session:" + SESSION_HASH + ":" + BUCKET)).isEqualTo(1L);
    }

    @Test
    @DisplayName("正常请求分阶段调用恰好各占一次，不重复计 IP")
    void stagedNormalRequestConsumesEachBucketExactlyOnce() {
        InMemoryRateCounter counter = new InMemoryRateCounter();
        RedisRateLimiter limiter = new RedisRateLimiter(counter, SESSION_RULE, IP_RULE, fixedClock());

        // 一次正常 chat 只调用一次 checkIp 与一次 checkSession
        limiter.checkIp(CLIENT_IP);
        limiter.checkSession(SESSION_KEY);

        assertThat(counter.keys()).hasSize(2);
        assertThat(counter.count("mall:agent:rate:ip:" + IP_HASH + ":" + BUCKET)).isEqualTo(1L);
        assertThat(counter.count("mall:agent:rate:session:" + SESSION_HASH + ":" + BUCKET)).isEqualTo(1L);
    }

    @Test
    @DisplayName("checkIp 对同一 IP 恰好允许 60 次，第 61 次 429 且 Retry-After 与 Python 一致")
    void ipStageAllowsExactlySixtyRequestsPerWindow() {
        InMemoryRateCounter counter = new InMemoryRateCounter();
        RedisRateLimiter limiter = new RedisRateLimiter(counter, SESSION_RULE, IP_RULE, fixedClock());

        for (int index = 0; index < 60; index++) {
            limiter.checkIp(CLIENT_IP);
        }

        assertThatThrownBy(() -> limiter.checkIp(CLIENT_IP))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(exception -> assertThat(((RateLimitExceededException) exception).getRetryAfterSeconds())
                        .isEqualTo(100))
                .hasMessage(RateLimitExceededException.MESSAGE);
        assertThat(counter.keys()).containsExactly("mall:agent:rate:ip:" + IP_HASH + ":" + BUCKET);
    }

    @Test
    @DisplayName("checkSession 对同一会话恰好允许 20 次，第 21 次 429")
    void sessionStageAllowsExactlyTwentyRequestsPerWindow() {
        InMemoryRateCounter counter = new InMemoryRateCounter();
        RedisRateLimiter limiter = new RedisRateLimiter(counter, SESSION_RULE, IP_RULE, fixedClock());

        for (int index = 0; index < 20; index++) {
            limiter.checkSession(SESSION_KEY);
        }

        assertThatThrownBy(() -> limiter.checkSession(SESSION_KEY))
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(counter.keys()).containsExactly("mall:agent:rate:session:" + SESSION_HASH + ":" + BUCKET);
    }

    @Test
    @DisplayName("分阶段检查在计数器不可用时同样失败关闭，绝不按 0 放行")
    void stagedChecksFailClosedWhenCounterUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        given(redis.execute(any(RedisScript.class), anyList(), any())).willReturn(null);
        RedisRateLimiter limiter =
                new RedisRateLimiter(new RedisRateCounter(redis), SESSION_RULE, IP_RULE, fixedClock());

        assertThatThrownBy(() -> limiter.checkIp(CLIENT_IP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(COUNTER_UNAVAILABLE_MESSAGE);
        assertThatThrownBy(() -> limiter.checkSession(SESSION_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(COUNTER_UNAVAILABLE_MESSAGE);

        // 两次失败关闭各一次 Redis 往返，绝无「失败后按 0 放行」的第三次
        then(redis).should(times(2)).execute(any(RedisScript.class), anyList(), any());
    }

    // ------------------------------------------------------------------ //
    // TTL 语义：仅首次设置且不重置
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("计数 TTL 只在首次计数时设置，后续计数不重置已有 TTL")
    void ttlIsSetOnlyOnFirstIncrement() {
        InMemoryRateCounter counter = new InMemoryRateCounter();
        String key = "mall:agent:rate:session:" + SESSION_HASH + ":" + BUCKET;

        assertThat(counter.increment(key, 300)).isEqualTo(1L);
        assertThat(counter.ttlSeconds(key)).isEqualTo(300);

        assertThat(counter.increment(key, 999)).isEqualTo(2L);
        assertThat(counter.ttlSeconds(key)).isEqualTo(300);
        assertThat(counter.count(key)).isEqualTo(2L);
    }

    @Test
    @DisplayName("Redis 计数器用一条原子 Lua 脚本完成 INCR + 首次 EXPIRE，只有一次往返")
    void redisCounterUsesSingleAtomicLuaOperation() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        given(redis.execute(any(RedisScript.class), anyList(), any())).willReturn(7L);

        long count = new RedisRateCounter(redis).increment(
                "mall:agent:rate:session:" + SESSION_HASH + ":" + BUCKET, 300);

        assertThat(count).isEqualTo(7L);
        then(redis).should(times(1)).execute(any(RedisScript.class), anyList(), any());
        then(redis).should(never()).opsForValue();

        String script = RedisRateCounter.INCREMENT_WITH_FIRST_TTL_SCRIPT.toUpperCase(Locale.ROOT);
        assertThat(script).contains("INCR").contains("TTL").contains("EXPIRE");
        assertThat(RedisRateCounter.INCREMENT_WITH_FIRST_TTL_SCRIPT)
                .isEqualTo("local count = redis.call('INCR', KEYS[1]) "
                        + "if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                        + "return count");
    }

    // ------------------------------------------------------------------ //
    // 计数器故障时的失败关闭语义（禁止 fail-open 按 0 放行）
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Redis execute 返回 null 时计数器失败关闭，绝不按 0 放行")
    void nullCounterResultFailsClosed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        given(redis.execute(any(RedisScript.class), anyList(), any())).willReturn(null);

        // 生产常量与测试期望文案逐字符一致，防止失败文案被悄悄改动
        assertThat(RedisRateCounter.UNAVAILABLE_MESSAGE).isEqualTo(COUNTER_UNAVAILABLE_MESSAGE);

        assertThatThrownBy(() -> new RedisRateCounter(redis).increment(SESSION_RATE_KEY, 300))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(COUNTER_UNAVAILABLE_MESSAGE)
                // 失败信息不得泄漏限流键、摘要、Redis URL 或凭据
                .hasMessageNotContaining("mall:agent:rate:")
                .hasMessageNotContaining(SESSION_HASH)
                .hasMessageNotContaining("redis://")
                .hasMessageNotContaining("redis:");
    }

    @Test
    @DisplayName("计数器不可用时限流器失败关闭：不返回 429、不消耗 IP 预算、不放行")
    void limiterFailsClosedWhenCounterUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        given(redis.execute(any(RedisScript.class), anyList(), any())).willReturn(null);
        RedisRateLimiter limiter =
                new RedisRateLimiter(new RedisRateCounter(redis), SESSION_RULE, IP_RULE, fixedClock());

        assertThatThrownBy(() -> limiter.check(SESSION_KEY, CLIENT_IP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(COUNTER_UNAVAILABLE_MESSAGE);
        // 第一个会话计数就已失败关闭，绝无第二次（IP）计数，也就不会误判为放行
        then(redis).should(times(1)).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("Redis execute 抛出的故障原样传播，不被包装成计数器不可用")
    void redisExecuteFailurePropagatesUnchanged() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RuntimeException failure = new RuntimeException("redis connection refused");
        given(redis.execute(any(RedisScript.class), anyList(), any())).willThrow(failure);

        assertThatThrownBy(() -> new RedisRateCounter(redis).increment(SESSION_RATE_KEY, 300))
                .isSameAs(failure)
                .hasMessage("redis connection refused")
                .hasMessageNotContaining(COUNTER_UNAVAILABLE_MESSAGE);
    }

    // ------------------------------------------------------------------ //
    // 429 / 409 的 HTTP 映射
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("限流错误映射为 429 并带整数秒 Retry-After 头，固定文案")
    void rateLimitExceededMapsTo429WithRetryAfterHeader() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        ResponseEntity<ApiEnvelope<Void>> response = handler.handleRateLimit(new RateLimitExceededException(100));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("100");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(429);
        assertThat(response.getBody().message()).isEqualTo(PYTHON_RATE_LIMITED_MESSAGE);
        assertThat(response.getBody().data()).isNull();

        RateLimitExceededException clamped = new RateLimitExceededException(0);
        assertThat(clamped.getRetryAfterSeconds()).isEqualTo(1);
        assertThat(clamped.getStatus()).isEqualTo(429);
        assertThat(handler.handleRateLimit(clamped).getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("业务 429 文案精确等于 Python（不带句号），常量与信封逐字符一致")
    void businessRateLimitMessageMatchesPythonExactly() {
        // 常量层：业务枚举与限流异常的 message 必须逐字符等于 Python RateLimitExceededError.message
        assertThat(AgentApiException.RATE_LIMITED_MESSAGE).isEqualTo(PYTHON_RATE_LIMITED_MESSAGE);
        assertThat(RateLimitExceededException.MESSAGE).isEqualTo(PYTHON_RATE_LIMITED_MESSAGE);
        assertThat(PYTHON_RATE_LIMITED_MESSAGE).doesNotEndWith("。");

        // 信封层：业务限流 429 body 必须与 Python 完全一致（无句号），不得含任何额外字符
        ResponseEntity<ApiEnvelope<Void>> response =
                new ApiExceptionHandler().handleRateLimit(new RateLimitExceededException(100));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("100");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(429);
        assertThat(response.getBody().message()).isEqualTo(PYTHON_RATE_LIMITED_MESSAGE);
        assertThat(response.getBody().message()).doesNotEndWith("。");
        assertThat(response.getBody().data()).isNull();
    }

    @Test
    @DisplayName("重复请求错误是 409 与固定文案，由统一处理器原样输出")
    void duplicateRequestExceptionIs409WithFixedMessage() {
        AgentApiException duplicate = AgentApiException.duplicateRequest();

        assertThat(duplicate.getStatus()).isEqualTo(409);
        assertThat(duplicate.getMessage()).isEqualTo("相同请求正在处理中，请勿重复提交。");

        ResponseEntity<ApiEnvelope<Void>> response = new ApiExceptionHandler().handleAgentApiException(duplicate);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("相同请求正在处理中，请勿重复提交。");
    }

    @Test
    @DisplayName("经真实 Spring 异常派发，最具体的限流处理器生效并保留 Retry-After 与无句号文案")
    void rateLimitExceededIsDispatchedToMostSpecificHandler() throws Exception {
        // 直接调用 handleRateLimit 无法证明 Spring 会在父/子两个处理器里选中更具体的那个，
        // 这里用 standalone MockMvc 触发真实异常派发，锁定 429 + Retry-After 不被静默降级。
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RateLimitProbeController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(get("/__probe__/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "100"))
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value(PYTHON_RATE_LIMITED_MESSAGE));
    }

    /** 只用于经真实 Spring 派发触发限流异常；不注册到生产上下文，也不建任何连接。 */
    @RestController
    static final class RateLimitProbeController {

        @GetMapping("/__probe__/rate-limited")
        public void trigger() {
            throw new RateLimitExceededException(100);
        }
    }

    // ------------------------------------------------------------------ //
    // InFlightGuard：进程内并发防重（按规范 sessionId 占用整个请求生命周期）
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("同一 sessionId 第二次 acquire 被拒绝，release 后可再次获取")
    void secondAcquireIsRejectedUntilReleased() {
        InFlightGuard guard = new InFlightGuard();
        String key = InFlightGuard.keyForSession(SESSION_ID);

        assertThat(guard.acquire(key)).isTrue();
        assertThat(guard.acquire(key)).isFalse();
        assertThat(guard.keys()).containsExactly(key);

        guard.release(key);

        assertThat(guard.acquire(key)).isTrue();
        guard.release(key);
        assertThat(guard.keys()).isEmpty();
    }

    @Test
    @DisplayName("占用键由规范 sessionId 派生：同 sessionId 不同问题共用一键，键内不含问题文本")
    void inFlightKeyIsDerivedFromCanonicalSessionId() {
        String key = InFlightGuard.keyForSession(SESSION_ID);

        assertThat(key).isEqualTo(InFlightGuard.keyForSession(SESSION_ID.toUpperCase(Locale.ROOT)))
                .as("大写 sessionId 必须归一为同一占用键")
                .doesNotContain("有什么手机")
                .doesNotContain("有什么耳机");

        // 同一 sessionId 的不同问题指向同一占用键，因此第二个并发请求必定被拒绝
        InFlightGuard guard = new InFlightGuard();
        assertThat(guard.acquire(InFlightGuard.keyForSession(SESSION_ID))).isTrue();
        assertThat(guard.acquire(InFlightGuard.keyForSession(SESSION_ID))).isFalse();
        assertThat(guard.keys()).containsExactly(key);
    }

    @Test
    @DisplayName("不同 sessionId 互不阻塞")
    void differentSessionsDoNotCollide() {
        InFlightGuard guard = new InFlightGuard();

        assertThat(guard.acquire(InFlightGuard.keyForSession(SESSION_ID))).isTrue();
        assertThat(guard.acquire(InFlightGuard.keyForSession(OTHER_SESSION_ID))).isTrue();
        assertThat(guard.keys()).hasSize(2);
    }

    @Test
    @DisplayName("并发 acquire 同一 sessionId 时只有一个赢家")
    void concurrentAcquireHasExactlyOneWinner() throws Exception {
        InFlightGuard guard = new InFlightGuard();
        String key = InFlightGuard.keyForSession(SESSION_ID);
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int index = 0; index < attempts; index++) {
                results.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return guard.acquire(key);
                }));
            }
            start.countDown();

            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("释放未知键是无害的幂等操作")
    void releaseUnknownKeyIsIdempotent() {
        InFlightGuard guard = new InFlightGuard();

        guard.release(InFlightGuard.keyForSession(SESSION_ID));

        assertThat(guard.keys()).isEmpty();
    }

    // ------------------------------------------------------------------ //
    // 辅助
    // ------------------------------------------------------------------ //

    private static Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochSecond(NOW_EPOCH_SECONDS), ZoneOffset.UTC);
    }

    /** 可推进的固定时区时钟，用于窗口滚动测试。 */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}

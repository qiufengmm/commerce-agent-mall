package com.macro.mall.agent.session;

import java.time.Clock;
import java.util.Objects;

/**
 * 固定窗口限流，对齐 Python {@code mall_shopping_agent.safety.rate_limit.ChatRateLimiter}。
 *
 * <ul>
 *   <li>窗口桶：{@code floor(epochSeconds / windowSeconds)}；</li>
 *   <li>限流键：{@code mall:agent:rate:{session|ip}:{sha256[:32]}:{bucket}}，
 *       原始 sessionKey 与原始 IP 绝不出现在键里；</li>
 *   <li>默认阈值：会话 300 秒 20 次、IP 300 秒 60 次；</li>
 *   <li>{@code Retry-After} 为当前窗口剩余秒数（向下取整）且至少 1 秒，与 Python 一致。</li>
 * </ul>
 *
 * <p>{@link #checkIp(String)} 与 {@link #checkSession(String)} 是分阶段入口，各自只命中一个维度：
 * 前者供身份解析前使用，后者供身份确定后使用。{@link #check(String, String)} 保留既有语义
 * （先会话后 IP、会话超限时不再消耗 IP 预算），仅供兼容消费者使用。
 *
 * <p>时间来自注入的 {@link Clock}，便于确定性测试；生产装配使用 UTC 系统时钟。
 */
public final class RedisRateLimiter implements RateLimiter {

    public static final String RATE_LIMIT_KEY_PREFIX = "mall:agent:rate:";

    private static final String KIND_SESSION = "session";
    private static final String KIND_IP = "ip";
    private static final String UNKNOWN_CLIENT_IP = "unknown";

    private final RateCounter counter;
    private final Rule sessionRule;
    private final Rule ipRule;
    private final Clock clock;

    public RedisRateLimiter(RateCounter counter, Rule sessionRule, Rule ipRule, Clock clock) {
        this.counter = Objects.requireNonNull(counter, "counter 不能为空");
        this.sessionRule = Objects.requireNonNull(sessionRule, "sessionRule 不能为空");
        this.ipRule = Objects.requireNonNull(ipRule, "ipRule 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Override
    public void checkIp(String clientIp) {
        String ip = clientIp == null || clientIp.isEmpty() ? UNKNOWN_CLIENT_IP : clientIp;
        Decision perIp = hit(KIND_IP, AgentIdentity.fingerprint(ip), ipRule);
        if (!perIp.allowed()) {
            throw new RateLimitExceededException(perIp.retryAfterSeconds());
        }
    }

    @Override
    public void checkSession(String sessionKey) {
        Decision session = hit(KIND_SESSION, AgentIdentity.fingerprint(sessionKey), sessionRule);
        if (!session.allowed()) {
            throw new RateLimitExceededException(session.retryAfterSeconds());
        }
    }

    @Override
    public void check(String sessionKey, String clientIp) {
        checkSession(sessionKey);
        // 先判会话，会话已超限时不再消耗 IP 预算（与 Python 一致；分阶段入口不受此约束）
        checkIp(clientIp);
    }


    private Decision hit(String kind, String identifierHash, Rule rule) {
        double nowSeconds = clock.millis() / 1000.0;
        int windowSeconds = Math.max(1, rule.windowSeconds());
        long bucket = (long) Math.floor(nowSeconds / windowSeconds);
        String key = RATE_LIMIT_KEY_PREFIX + kind + ":" + identifierHash + ":" + bucket;

        long count = counter.increment(key, windowSeconds);

        double elapsed = nowSeconds - bucket * (double) windowSeconds;
        int retryAfterSeconds = (int) Math.max((long) (windowSeconds - elapsed), 1L);
        return new Decision(
                count <= rule.limit(),
                count,
                Math.max(rule.limit() - count, 0L),
                retryAfterSeconds);
    }

    /** 单次判定的结果，仅在本类内部使用。 */
    private record Decision(boolean allowed, long count, long remaining, int retryAfterSeconds) {
    }
}

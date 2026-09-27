package com.macro.mall.agent.session;

import java.util.List;
import java.util.Objects;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 限流计数器，对齐 Python {@code RedisRateCounter} 的「计数 + 首次设置 TTL」原子语义。
 *
 * <p>Python 用事务管道执行 {@code INCR} + {@code EXPIRE key ttl NX}。这里用等价的 Lua 脚本
 * 在<strong>一次</strong> {@code EVAL} 往返内完成：
 * <pre>
 * local count = redis.call('INCR', KEYS[1])
 * if redis.call('TTL', KEYS[1]) &lt; 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
 * return count
 * </pre>
 *
 * <p>用 {@code TTL < 0}（-1 无过期、-2 键不存在，而 {@code INCR} 之后键必存在）表达
 * {@code EXPIRE ... NX} 的「只在没有 TTL 时设置」，因此已有 TTL 不会被重置；
 * 不依赖 Redis 7.0 才支持的 {@code EXPIRE ... NX} 语法。
 *
 * <p>窗口秒数由调用方（{@link RedisRateLimiter}）按 {@code max(1, windowSeconds)} 归一后传入。
 *
 * <p>失败语义：Redis 未返回计数时抛 {@link #UNAVAILABLE_MESSAGE}（失败关闭，不按 0 放行）；
 * Redis 连接/命令故障不捕获，原样向上传播。
 */
public final class RedisRateCounter implements RateCounter {

    /** 计数与首次 TTL 的原子脚本；测试会断言其内容与「只有一次往返」。 */
    public static final String INCREMENT_WITH_FIRST_TTL_SCRIPT = "local count = redis.call('INCR', KEYS[1]) "
            + "if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
            + "return count";

    /**
     * Redis 未返回计数（{@code execute} 返回 {@code null}）时的固定失败文案。
     *
     * <p>限流计数拿不到结果就必须<strong>失败关闭</strong>：若按 0 放行会变成 fail-open，
     * Redis 故障时等于取消限流。文案固定且不含限流键、Redis URL 或任何凭据。
     */
    public static final String UNAVAILABLE_MESSAGE = "限流计数器未返回计数，拒绝放行";

    private static final RedisScript<Long> SCRIPT =
            RedisScript.of(INCREMENT_WITH_FIRST_TTL_SCRIPT, Long.class);

    private final StringRedisTemplate template;

    public RedisRateCounter(StringRedisTemplate template) {
        this.template = Objects.requireNonNull(template, "template 不能为空");
    }

    @Override
    public long increment(String key, int windowSeconds) {
        Long count = template.execute(SCRIPT, List.of(key), Integer.toString(windowSeconds));
        if (count == null) {
            // 失败关闭：宁可拒绝请求也不能按 0 放行。Redis 自身抛出的连接/命令故障不在此处理，
            // 会原样向上传播（由统一异常处理器兜底）。
            throw new IllegalStateException(UNAVAILABLE_MESSAGE);
        }
        return count;
    }
}

package com.macro.mall.agent.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单进程限流计数器，仅用于单元测试与本地演示，语义对齐 Python {@code InMemoryRateCounter}。
 *
 * <p>与 Python 版本一致：窗口由键名中的 bucket 承载，计数只做累加。
 * 额外的 {@link #ttlSeconds(String)} 记录「首次计数时的窗口秒数」且<strong>永不覆盖</strong>，
 * 用来在离线测试中验证 Redis Lua 脚本 {@code EXPIRE ... NX} 的语义（已有 TTL 不重置）。
 */
public final class InMemoryRateCounter implements RateCounter {

    private final Map<String, Long> counts = new LinkedHashMap<>();
    private final Map<String, Integer> windows = new LinkedHashMap<>();

    @Override
    public synchronized long increment(String key, int windowSeconds) {
        // 首次计数设置 TTL；已有 TTL 不重置（对应 Redis EXPIRE ... NX）
        windows.putIfAbsent(key, windowSeconds);
        long count = counts.getOrDefault(key, 0L) + 1L;
        counts.put(key, count);
        return count;
    }

    public synchronized List<String> keys() {
        return new ArrayList<>(counts.keySet());
    }

    public synchronized Long count(String key) {
        return counts.get(key);
    }

    public synchronized Integer ttlSeconds(String key) {
        return windows.get(key);
    }
}

package com.macro.mall.agent.session;

/**
 * 限流计数器 SPI，对齐 Python {@code mall_shopping_agent.safety.rate_limit.RateCounter}。
 *
 * <p>窗口由键名中的 bucket 承载，因此实现只需要「累加并返回当前计数」；
 * 首次计数时设置窗口 TTL，且<strong>不重置</strong>已有 TTL。
 */
public interface RateCounter {

    /**
     * @param key           已含窗口桶的限流键
     * @param windowSeconds 首次计数时要设置的 TTL 秒数
     * @return 累加后的计数
     */
    long increment(String key, int windowSeconds);
}

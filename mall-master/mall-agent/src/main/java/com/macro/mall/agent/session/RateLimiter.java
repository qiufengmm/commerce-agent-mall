package com.macro.mall.agent.session;

/**
 * 聊天限流入口，对齐 Python {@code mall_shopping_agent.safety.rate_limit.ChatRateLimiter}。
 *
 * <p>实现必须同时施加会话维度与 IP 维度限制。超限统一抛 {@link RateLimitExceededException}
 * （固定文案 429）。
 *
 * <p>自安全返工起，{@code POST /agent/chat} 使用<strong>分阶段</strong>调用：
 * 身份解析之前先用 {@link #checkIp(String)} 按真实客户端 IP 消耗一次 IP 桶（挡住
 * 「伪造 Token + 每次新 UUID」这类绕过身份维度的滥用），身份确定后再用
 * {@link #checkSession(String)} 按 member/guest 会话键消耗一次会话桶。一次正常请求因此恰好
 * 各消耗一次 IP 与会话预算，不重复计 IP。
 *
 * <p>{@link #check(String, String)} 保留给既有消费者，语义仍是「先会话后 IP、会话超限时不再消耗
 * IP 预算」。该语义与分阶段调用的前置 IP 门槛<strong>不可同时维持</strong>：分阶段顺序下 IP 已在
 * 身份解析前消耗，因此随后会话超限的请求也已经计过 IP。两者并存只是兼容承诺，新代码应使用分阶段方法。
 */
public interface RateLimiter {

    /**
     * 预认证阶段：只消耗一次 IP 桶。
     *
     * @param clientIp 客户端 IP；缺失或为空时按 {@code unknown} 处理
     * @throws RateLimitExceededException IP 维度超限
     */
    void checkIp(String clientIp);

    /**
     * 身份确定后：只消耗一次会话桶。
     *
     * @param sessionKey 当前身份的会话键（已含游客/会员命名空间）
     * @throws RateLimitExceededException 会话维度超限
     */
    void checkSession(String sessionKey);

    /**
     * 兼容既有消费者：先判会话，再判 IP，会话超限时不再消耗 IP 预算。
     *
     * @param sessionKey 当前身份的会话键（已含游客/会员命名空间）
     * @param clientIp   客户端 IP；缺失或为空时按 {@code unknown} 处理
     * @throws RateLimitExceededException 任一维度超限
     */
    void check(String sessionKey, String clientIp);

    /** 固定窗口规则；{@code retryAfterSeconds} 与 Python 一致地兜底为至少 1 秒。 */
    record Rule(int limit, int windowSeconds) {

        public int retryAfterSeconds() {
            return Math.max(1, windowSeconds);
        }
    }
}

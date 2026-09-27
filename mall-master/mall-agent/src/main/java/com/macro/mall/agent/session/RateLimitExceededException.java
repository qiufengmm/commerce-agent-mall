package com.macro.mall.agent.session;

import com.macro.mall.agent.api.AgentApiException;

/**
 * 达到限流：固定文案 429，并携带整数秒 {@code Retry-After}。
 *
 * <p>文案与状态码固定，不携带 IP、sessionKey、限流键或 Redis 细节。
 */
public final class RateLimitExceededException extends AgentApiException {

    private static final long serialVersionUID = 1L;

    public static final String MESSAGE = AgentApiException.RATE_LIMITED_MESSAGE;

    private final int retryAfterSeconds;

    public RateLimitExceededException(int retryAfterSeconds) {
        super(429, MESSAGE);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** HTTP {@code Retry-After} 的整数秒取值，至少 1。 */
    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

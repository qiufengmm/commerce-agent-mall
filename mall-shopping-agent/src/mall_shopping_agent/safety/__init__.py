"""安全层：限流、不可信数据围栏与交易拒绝策略。"""

from __future__ import annotations

from mall_shopping_agent.safety.rate_limit import (
    DEFAULT_WINDOW_SECONDS,
    MAX_REQUESTS_PER_IP_PER_WINDOW,
    MAX_REQUESTS_PER_SESSION_PER_WINDOW,
    ChatRateLimiter,
    InMemoryRateCounter,
    RateCounter,
    RateLimitDecision,
    RateLimitExceededError,
    RateLimitRule,
    RedisRateCounter,
    rate_limit_key,
)

__all__ = [
    "DEFAULT_WINDOW_SECONDS",
    "MAX_REQUESTS_PER_IP_PER_WINDOW",
    "MAX_REQUESTS_PER_SESSION_PER_WINDOW",
    "ChatRateLimiter",
    "InMemoryRateCounter",
    "RateCounter",
    "RateLimitDecision",
    "RateLimitExceededError",
    "RateLimitRule",
    "RedisRateCounter",
    "rate_limit_key",
]

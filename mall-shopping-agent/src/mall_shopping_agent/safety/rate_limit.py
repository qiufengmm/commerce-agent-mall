"""固定窗口限流。

默认限制：同一会话 5 分钟最多 20 次聊天请求，同一来源 IP 摘要 5 分钟最多 60 次。
原始 IP 与原始 sessionId 不写入 Redis 键，只使用不可逆摘要。
"""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Literal, Protocol, runtime_checkable

from mall_shopping_agent.session.identity import fingerprint

RateLimitKind = Literal["session", "ip"]

MAX_REQUESTS_PER_SESSION_PER_WINDOW = 20
MAX_REQUESTS_PER_IP_PER_WINDOW = 60
DEFAULT_WINDOW_SECONDS = 300


@dataclass(frozen=True, slots=True)
class RateLimitRule:
    limit: int
    window_seconds: int

    @property
    def retry_after_seconds(self) -> int:
        return max(1, self.window_seconds)


@dataclass(frozen=True, slots=True)
class RateLimitDecision:
    allowed: bool
    count: int
    remaining: int
    retry_after_seconds: int


class RateLimitExceededError(Exception):
    """达到限流，聊天接口映射为 HTTP 429，且不再调用模型。"""

    http_status = 429
    code = "RATE_LIMITED"

    def __init__(self, retry_after_seconds: int) -> None:
        super().__init__("请求过于频繁，请稍后再试")
        self.message = "请求过于频繁，请稍后再试"
        self.retry_after_seconds = max(1, int(retry_after_seconds))


def rate_limit_key(kind: RateLimitKind, identifier_hash: str, window: int) -> str:
    return f"mall:agent:rate:{kind}:{identifier_hash}:{window}"


@runtime_checkable
class RateCounter(Protocol):
    async def incr(self, key: str, window_seconds: int) -> int: ...


class InMemoryRateCounter:
    """单进程计数器，仅用于测试与本地 Stub 演示。"""

    def __init__(self) -> None:
        self._counters: dict[str, int] = {}

    async def incr(self, key: str, window_seconds: int) -> int:
        # window_seconds 已由键名中的窗口桶承载，这里只需要累加
        del window_seconds
        count = self._counters.get(key, 0) + 1
        self._counters[key] = count
        return count

    @property
    def keys(self) -> list[str]:
        return list(self._counters)


class RedisRateCounter:
    """基于 Redis 的原子计数器，首次写入时设置窗口 TTL。"""

    def __init__(self, client: Any) -> None:
        self._client = client

    async def incr(self, key: str, window_seconds: int) -> int:
        async with self._client.pipeline(transaction=True) as pipe:
            pipe.incr(key)
            pipe.expire(key, window_seconds, nx=True)
            result = await pipe.execute()
        return int(result[0])


class ChatRateLimiter:
    """同时施加会话维度与 IP 维度限制。"""

    def __init__(
        self,
        *,
        counter: RateCounter,
        session_rule: RateLimitRule,
        ip_rule: RateLimitRule,
        clock: Callable[[], float] | None = None,
    ) -> None:
        self._counter = counter
        self._session_rule = session_rule
        self._ip_rule = ip_rule
        self._clock = clock or time.time

    async def check(self, *, session_key: str, client_ip: str) -> None:
        session_decision = await self._hit("session", fingerprint(session_key), self._session_rule)
        if not session_decision.allowed:
            raise RateLimitExceededError(session_decision.retry_after_seconds)

        # 会话已超限时不再消耗 IP 预算
        ip_decision = await self._hit("ip", fingerprint(client_ip or "unknown"), self._ip_rule)
        if not ip_decision.allowed:
            raise RateLimitExceededError(ip_decision.retry_after_seconds)

    async def _hit(
        self,
        kind: RateLimitKind,
        identifier_hash: str,
        rule: RateLimitRule,
    ) -> RateLimitDecision:
        now = self._clock()
        window_seconds = max(1, rule.window_seconds)
        bucket = int(now // window_seconds)
        key = rate_limit_key(kind, identifier_hash, bucket)
        count = await self._counter.incr(key, window_seconds)
        elapsed = now - bucket * window_seconds
        return RateLimitDecision(
            allowed=count <= rule.limit,
            count=count,
            remaining=max(rule.limit - count, 0),
            retry_after_seconds=max(int(window_seconds - elapsed), 1),
        )

"""内存会话仓储。

与 ``RedisSessionRepository`` 保持一致的语义（TTL 续期、消息裁剪、
删除幂等、迁移后删除源键），用作绝大多数单元测试的替身。
"""

from __future__ import annotations

import time
from collections.abc import Callable

from mall_shopping_agent.session.repository import SessionSnapshot


class MemorySessionRepository:
    """不依赖 Redis 的会话仓储实现。"""

    def __init__(
        self,
        *,
        ttl_seconds: int = 86400,
        max_messages: int = 20,
        clock: Callable[[], float] | None = None,
    ) -> None:
        self._ttl_seconds = ttl_seconds
        self._max_messages = max_messages
        self._clock = clock or time.monotonic
        self._docs: dict[str, tuple[str, float]] = {}

    # -- 内部 -------------------------------------------------------------

    def _purge(self, key: str) -> None:
        entry = self._docs.get(key)
        if entry is not None and self._clock() >= entry[1]:
            self._docs.pop(key, None)

    # -- 协议实现 ---------------------------------------------------------

    async def load(self, key: str) -> SessionSnapshot:
        self._purge(key)
        entry = self._docs.get(key)
        if entry is None:
            return SessionSnapshot()

        payload, _ = entry
        # 每次有效访问续期
        self._docs[key] = (payload, self._clock() + self._ttl_seconds)
        try:
            return SessionSnapshot.model_validate_json(payload)
        except ValueError:
            self._docs.pop(key, None)
            return SessionSnapshot()

    async def save(self, key: str, snapshot: SessionSnapshot) -> None:
        trimmed = snapshot.trimmed(self._max_messages)
        self._docs[key] = (trimmed.model_dump_json(), self._clock() + self._ttl_seconds)

    async def delete(self, key: str) -> bool:
        self._purge(key)
        return self._docs.pop(key, None) is not None

    async def copy(self, source_key: str, target_key: str) -> None:
        self._purge(source_key)
        entry = self._docs.get(source_key)
        if entry is not None:
            self._docs[target_key] = (entry[0], self._clock() + self._ttl_seconds)
        self._docs.pop(source_key, None)

    # -- 测试辅助 ---------------------------------------------------------

    @property
    def keys(self) -> list[str]:
        for key in list(self._docs):
            self._purge(key)
        return list(self._docs)

    def raw(self, key: str) -> str | None:
        entry = self._docs.get(key)
        return None if entry is None else entry[0]

    def ttl_seconds(self, key: str) -> float | None:
        self._purge(key)
        entry = self._docs.get(key)
        if entry is None:
            return None
        return max(entry[1] - self._clock(), 0.0)

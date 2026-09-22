"""Redis 会话仓储。

每次会话写入一个 JSON 字符串键，并用 ``SET ... EX`` 原子设置 TTL；
读取时用 ``GETEX ... EX`` 原子续期，避免读改写竞态。
"""

from __future__ import annotations

from typing import Any

from mall_shopping_agent.session.repository import SessionSnapshot


class RedisSessionRepository:
    def __init__(
        self,
        client: Any,
        *,
        ttl_seconds: int = 86400,
        max_messages: int = 20,
    ) -> None:
        self._client = client
        self._ttl_seconds = ttl_seconds
        self._max_messages = max_messages

    async def load(self, key: str) -> SessionSnapshot:
        try:
            raw = await self._client.getex(key, ex=self._ttl_seconds)
        except Exception:
            raise

        if raw is None:
            return SessionSnapshot()

        if isinstance(raw, bytes):
            raw = raw.decode("utf-8", errors="replace")

        try:
            return SessionSnapshot.model_validate_json(raw)
        except ValueError:
            await self._client.delete(key)
            return SessionSnapshot()

    async def save(self, key: str, snapshot: SessionSnapshot) -> None:
        trimmed = snapshot.trimmed(self._max_messages)
        await self._client.set(key, trimmed.model_dump_json(), ex=self._ttl_seconds)

    async def delete(self, key: str) -> bool:
        return bool(await self._client.delete(key))

    async def copy(self, source_key: str, target_key: str) -> None:
        snapshot = await self.load(source_key)
        if snapshot.messages or snapshot.products:
            await self.save(target_key, snapshot)
        await self.delete(source_key)

"""会话仓储协议与持久化数据结构。

会话文档只保存「用户消息 / 助手摘要 / 最近一组商品卡片」，
不保存原始工具响应、模型原始响应、Token 或 Authorization。
"""

from __future__ import annotations

from typing import Any, Literal, Protocol, runtime_checkable

from pydantic import BaseModel, ConfigDict, Field

MAX_MESSAGE_CHARS = 4000
MAX_STORED_PRODUCTS = 5


class SessionMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=MAX_MESSAGE_CHARS)


class SessionSnapshot(BaseModel):
    """一次会话的持久化内容。"""

    model_config = ConfigDict(extra="forbid")

    messages: list[SessionMessage] = Field(default_factory=list)
    products: list[dict[str, Any]] = Field(default_factory=list)

    def trimmed(self, max_messages: int) -> SessionSnapshot:
        """只保留最近 ``max_messages`` 条消息和最近一组商品卡片。"""

        max_messages = max(1, max_messages)
        messages = self.messages[-max_messages:]
        products = self.products[:MAX_STORED_PRODUCTS]
        if messages == self.messages and products == self.products:
            return self
        return self.model_copy(update={"messages": messages, "products": products})

    def with_appended(self, messages: list[SessionMessage], max_messages: int) -> SessionSnapshot:
        return SessionSnapshot(
            messages=[*self.messages, *messages],
            products=self.products,
        ).trimmed(max_messages)


@runtime_checkable
class SessionRepository(Protocol):
    """会话仓储协议，默认测试替身为 ``MemorySessionRepository``。"""

    async def load(self, key: str) -> SessionSnapshot: ...

    async def save(self, key: str, snapshot: SessionSnapshot) -> None: ...

    async def delete(self, key: str) -> bool: ...

    async def copy(self, source_key: str, target_key: str) -> None: ...

"""模型层的规范化数据结构。

上层只接触这里定义的稳定结构，不感知任何供应商原始响应字段。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal

MessageRole = Literal["system", "user", "assistant", "tool"]

ToolChoice = Literal["auto", "none"]


@dataclass(frozen=True, slots=True)
class ToolCall:
    """模型请求的一次工具调用，``arguments`` 保持供应商原始字符串。"""

    id: str
    name: str
    arguments: str


@dataclass(frozen=True, slots=True)
class ModelMessage:
    role: MessageRole
    content: str | None = None
    tool_call_id: str | None = None
    tool_calls: tuple[ToolCall, ...] = ()

    @classmethod
    def system(cls, content: str) -> ModelMessage:
        return cls(role="system", content=content)

    @classmethod
    def user(cls, content: str) -> ModelMessage:
        return cls(role="user", content=content)

    @classmethod
    def assistant(cls, content: str | None, tool_calls: tuple[ToolCall, ...] = ()) -> ModelMessage:
        return cls(role="assistant", content=content, tool_calls=tool_calls)

    @classmethod
    def tool(cls, tool_call_id: str, content: str) -> ModelMessage:
        return cls(role="tool", content=content, tool_call_id=tool_call_id)


@dataclass(slots=True)
class ModelRequest:
    messages: list[ModelMessage]
    tools: list[dict[str, Any]] = field(default_factory=list)
    tool_choice: ToolChoice = "auto"
    temperature: float = 0.2
    max_tokens: int | None = None


@dataclass(frozen=True, slots=True)
class ModelUsage:
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    total_tokens: int | None = None


@dataclass(frozen=True, slots=True)
class ModelResponse:
    content: str | None
    tool_calls: tuple[ToolCall, ...] = ()
    usage: ModelUsage = field(default_factory=ModelUsage)
    finish_reason: str | None = None

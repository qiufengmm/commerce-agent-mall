"""ModelClient 的确定性替身。"""

from __future__ import annotations

import json
from collections.abc import Sequence
from typing import Any

from mall_shopping_agent.model.client import ModelClient
from mall_shopping_agent.model.schemas import (
    ModelMessage,
    ModelRequest,
    ModelResponse,
    ModelUsage,
    ToolCall,
)

DEFAULT_TOOLS_SCHEMA: list[dict[str, Any]] = []


def tool_call(name: str, arguments: Any = None, call_id: str = "call_1") -> ToolCall:
    if isinstance(arguments, str):
        raw = arguments
    else:
        raw = json.dumps(arguments or {}, ensure_ascii=False)
    return ToolCall(id=call_id, name=name, arguments=raw)


def text_response(content: str) -> ModelResponse:
    return ModelResponse(content=content, usage=ModelUsage(total_tokens=10))


def tool_response(*calls: ToolCall) -> ModelResponse:
    return ModelResponse(content=None, tool_calls=tuple(calls), usage=ModelUsage(total_tokens=10))


class FakeModel(ModelClient):
    """按顺序返回预设响应；元素可以是异常，用于模拟模型故障。"""

    def __init__(
        self,
        responses: Sequence[ModelResponse | Exception] | None = None,
        *,
        default_content: str = "好的，我了解了。",
    ) -> None:
        self.requests: list[ModelRequest] = []
        self.responses: list[ModelResponse | Exception] = list(responses or [])
        self.default_content = default_content

    async def complete(self, request: ModelRequest) -> ModelResponse:
        self.requests.append(request)
        if self.responses:
            item = self.responses.pop(0)
            if isinstance(item, Exception):
                raise item
            return item
        return text_response(self.default_content)

    @property
    def call_count(self) -> int:
        return len(self.requests)

    @property
    def last_messages(self) -> list[ModelMessage]:
        return list(self.requests[-1].messages)

    @property
    def last_tools(self) -> list[dict[str, Any]]:
        return list(self.requests[-1].tools)

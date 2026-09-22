"""工具注册表相关的测试辅助。"""

from __future__ import annotations

from typing import Any

from mall_shopping_agent.model.schemas import ToolCall
from mall_shopping_agent.tools.registry import ToolContext, ToolRegistry, ToolResult

MEMBER_TOKEN = "Bearer placeholder-member-token"


def call(name: str, arguments: Any = "{}", call_id: str = "call_1") -> ToolCall:
    if isinstance(arguments, str):
        raw = arguments
    else:
        import json

        raw = json.dumps(arguments, ensure_ascii=False)
    return ToolCall(id=call_id, name=name, arguments=raw)


async def invoke(
    registry: ToolRegistry,
    backend: Any,
    name: str,
    arguments: Any = "{}",
    *,
    authorization: str | None = None,
) -> ToolResult:
    return await registry.invoke(
        call(name, arguments),
        context=ToolContext(backend=backend, authorization=authorization),
    )

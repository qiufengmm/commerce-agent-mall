"""确定性 Stub 模型。

只在 ``MALL_AGENT_MODEL_MODE=stub`` 时启用，用于没有真实模型凭据时的演示与离线评测。
它不会访问网络，也不会读取任何密钥。
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from mall_shopping_agent.model.schemas import (
    ModelRequest,
    ModelResponse,
    ToolCall,
)

DEFAULT_STUB_ANSWER = (
    "我根据商城搜索结果为您列出候选商品，名称、价格和库存均来自商城实时数据；"
    "如需比较或查看库存，可以继续告诉我商品名称。"
)

STUB_KEYWORD_HINTS: tuple[str, ...] = (
    "手机",
    "平板",
    "笔记本",
    "耳机",
    "手表",
    "电视",
    "洗衣机",
    "空调",
    "相机",
    "男装",
    "女装",
    "鞋",
    "包",
)

STUB_SEARCH_TOOL = "searchProducts"
STUB_TOOL_CALL_ID = "stub_call_1"


def _last_user_text(request: ModelRequest) -> str:
    for message in reversed(request.messages):
        if message.role == "user" and message.content:
            return message.content
    return ""


def extract_stub_keyword(message: str) -> str | None:
    """从用户问题里提取一个演示用关键词；提取不到时返回 ``None``。"""

    for hint in STUB_KEYWORD_HINTS:
        if hint in (message or ""):
            return hint
    return None


def build_stub_search_arguments(message: str) -> dict[str, Any]:
    arguments: dict[str, Any] = {"pageNum": 1, "sort": 0}
    keyword = extract_stub_keyword(message)
    if keyword:
        arguments["keyword"] = keyword
    return arguments


def build_responses_from_script(entries: Sequence[Mapping[str, Any]]) -> list[ModelResponse]:
    """把夹具脚本转换为规范化的模型响应。"""

    responses: list[ModelResponse] = []
    for entry in entries:
        tool_calls: list[ToolCall] = []
        for index, item in enumerate(entry.get("toolCalls") or []):
            name = str(item.get("name") or "")
            if not name:
                raise ValueError("Stub 脚本的 toolCalls 缺少工具名")
            arguments = item.get("arguments", {})
            raw = (
                arguments
                if isinstance(arguments, str)
                else json.dumps(arguments, ensure_ascii=False)
            )
            tool_calls.append(
                ToolCall(
                    id=str(item.get("id") or f"stub_call_{index + 1}"), name=name, arguments=raw
                )
            )
        content = entry.get("content")
        responses.append(
            ModelResponse(
                content=None if content is None else str(content),
                tool_calls=tuple(tool_calls),
            )
        )
    return responses


def load_stub_conversations(path: Path | str) -> dict[str, list[ModelResponse]]:
    """读取 Stub 对话脚本夹具，返回 ``{conversationId: responses}``。"""

    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    conversations: dict[str, list[ModelResponse]] = {}
    for entry in payload.get("conversations") or []:
        conversation_id = str(entry.get("id") or "")
        if not conversation_id:
            raise ValueError("Stub 脚本缺少 id")
        conversations[conversation_id] = build_responses_from_script(entry.get("responses") or [])
    return conversations


class StubModelClient:
    """按脚本顺序返回响应的确定性模型客户端。"""

    def __init__(
        self,
        responses: Sequence[ModelResponse] | None = None,
        *,
        default_answer: str = DEFAULT_STUB_ANSWER,
        keyword_hints: Sequence[str] = STUB_KEYWORD_HINTS,
    ) -> None:
        self._responses: list[ModelResponse] = list(responses or [])
        self._default_answer = default_answer
        self._keyword_hints = tuple(keyword_hints)
        self._default_script: list[ModelResponse] | None = None
        self.requests: list[ModelRequest] = []

    @property
    def mode(self) -> str:
        return "stub"

    @property
    def call_count(self) -> int:
        return len(self.requests)

    def _build_default_script(self, request: ModelRequest) -> list[ModelResponse]:
        message = _last_user_text(request)
        arguments = {"pageNum": 1, "sort": 0}
        for hint in self._keyword_hints:
            if hint in message:
                arguments["keyword"] = hint
                break
        return [
            ModelResponse(
                content=None,
                tool_calls=(
                    ToolCall(
                        id=STUB_TOOL_CALL_ID,
                        name=STUB_SEARCH_TOOL,
                        arguments=json.dumps(arguments, ensure_ascii=False),
                    ),
                ),
            ),
            ModelResponse(content=self._default_answer, tool_calls=()),
        ]

    async def complete(self, request: ModelRequest) -> ModelResponse:
        self.requests.append(request)

        if self._responses:
            return self._responses.pop(0)

        if self._default_script is None:
            self._default_script = self._build_default_script(request)
        if self._default_script:
            return self._default_script.pop(0)

        return ModelResponse(content=self._default_answer, tool_calls=())

    async def aclose(self) -> None:
        return None

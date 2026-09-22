"""只读工具注册表。

工具名、参数模型和数量限制全部由代码定义，JSON Schema 直接由 Pydantic 参数模型
生成，因此模型永远不能传入基础地址、HTTP 方法、请求头、超时或 SQL 之类的控制字段。
"""

from __future__ import annotations

import json
from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ValidationError

from mall_shopping_agent.model.schemas import ToolCall
from mall_shopping_agent.safety.fencing import fence_json
from mall_shopping_agent.storefront.backend import StorefrontBackend

MAX_TOOL_RESULT_CHARS = 4000


class ToolStatus(StrEnum):
    OK = "OK"
    LOGIN_REQUIRED = "LOGIN_REQUIRED"
    PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND"
    ERROR = "ERROR"


class ToolError(Exception):
    """工具调用被拒绝（未注册或参数非法），不会触发任何后端调用。"""

    code = "TOOL_ERROR"

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class UnknownToolError(ToolError):
    code = "UNKNOWN_TOOL"


class InvalidToolArgumentsError(ToolError):
    code = "INVALID_TOOL_ARGUMENTS"


@dataclass(frozen=True, slots=True)
class ToolContext:
    """一次工具调用可用的只读依赖与会员凭据。"""

    backend: StorefrontBackend
    authorization: str | None = None


@dataclass(slots=True)
class ToolResult:
    name: str
    status: ToolStatus = ToolStatus.OK
    payload: dict[str, Any] = field(default_factory=dict)
    facts: list[str] = field(default_factory=list)
    error_code: str | None = None
    error_message: str | None = None

    @property
    def ok(self) -> bool:
        return self.status is ToolStatus.OK

    def to_model_content(self, *, max_chars: int = MAX_TOOL_RESULT_CHARS) -> str:
        body: dict[str, Any] = {
            "tool": self.name,
            "status": str(self.status),
            "data": self.payload,
        }
        if self.error_code:
            body["errorCode"] = self.error_code
            body["errorMessage"] = self.error_message
        return fence_json(body, label=f"tool_result:{self.name}", max_chars=max_chars)


ToolHandler = Callable[[Any, ToolContext], Awaitable[ToolResult]]


@dataclass(frozen=True, slots=True)
class ToolDefinition:
    name: str
    description: str
    params_model: type[BaseModel]
    handler: ToolHandler
    requires_member: bool = False

    def json_schema(self) -> dict[str, Any]:
        schema = self.params_model.model_json_schema(by_alias=True)
        schema.pop("title", None)
        schema["additionalProperties"] = False
        return schema

    def openai_tool(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.json_schema(),
            },
        }


class ToolRegistry:
    def __init__(self, definitions: Iterable[ToolDefinition]) -> None:
        self._definitions: dict[str, ToolDefinition] = {}
        for definition in definitions:
            if definition.name in self._definitions:
                raise ValueError(f"重复注册的工具：{definition.name}")
            self._definitions[definition.name] = definition

    @property
    def names(self) -> tuple[str, ...]:
        return tuple(self._definitions)

    def get(self, name: str) -> ToolDefinition | None:
        return self._definitions.get(name)

    def json_schema(self, name: str) -> dict[str, Any] | None:
        definition = self._definitions.get(name)
        return None if definition is None else definition.json_schema()

    def openai_tools(self) -> list[dict[str, Any]]:
        return [definition.openai_tool() for definition in self._definitions.values()]

    @staticmethod
    def _validate_arguments(definition: ToolDefinition, raw: str) -> BaseModel:
        text = (raw or "").strip()
        payload: Any = {}
        if text:
            try:
                payload = json.loads(text)
            except ValueError as exc:
                raise InvalidToolArgumentsError("工具参数不是合法 JSON") from exc

        if not isinstance(payload, dict):
            raise InvalidToolArgumentsError("工具参数必须是 JSON 对象")

        try:
            return definition.params_model.model_validate(payload)
        except ValidationError as exc:
            raise InvalidToolArgumentsError("工具参数不符合受控约束") from exc

    async def invoke(self, call: ToolCall, *, context: ToolContext) -> ToolResult:
        definition = self._definitions.get(call.name)
        if definition is None:
            raise UnknownToolError(f"未注册的工具：{call.name}")

        params = self._validate_arguments(definition, call.arguments)
        return await definition.handler(params, context)

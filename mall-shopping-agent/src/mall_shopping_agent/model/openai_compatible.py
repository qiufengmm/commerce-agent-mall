"""OpenAI 兼容 ``/v1/chat/completions`` 客户端。

只做四件事：发送受控请求、解析规范化响应、对可重试错误重试一次、
把其余失败转换成不泄露凭据与上游正文的领域异常。
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import Awaitable, Callable
from typing import Any

import httpx

from mall_shopping_agent.model.client import (
    ModelProtocolError,
    ModelTimeoutError,
    ModelUnavailableError,
    ModelUpstreamError,
)
from mall_shopping_agent.model.schemas import (
    ModelMessage,
    ModelRequest,
    ModelResponse,
    ModelUsage,
    ToolCall,
)

_RETRYABLE_STATUS = frozenset({429, 502, 503, 504})
_TOOL_SUPPORT_MARKERS = ("tool_choice", "tool call", "tool_calls", "function calling", "tools")
_DEFAULT_RETRY_DELAY_SECONDS = 0.5


def _as_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    return None


class OpenAICompatibleClient:
    """基于 HTTPX 的 OpenAI 兼容模型客户端。"""

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        timeout_seconds: float = 30.0,
        max_retries: int = 1,
        retry_delay_seconds: float = _DEFAULT_RETRY_DELAY_SECONDS,
        transport: httpx.AsyncBaseTransport | None = None,
        sleep: Callable[[float], Awaitable[None]] | None = None,
    ) -> None:
        self._chat_url = f"{base_url.rstrip('/')}/chat/completions"
        self._api_key = api_key
        self._model = model
        self._max_retries = max(0, max_retries)
        self._retry_delay_seconds = retry_delay_seconds
        self._sleep = sleep or asyncio.sleep
        timeout = httpx.Timeout(
            timeout_seconds,
            connect=timeout_seconds,
            read=timeout_seconds,
            write=timeout_seconds,
            pool=timeout_seconds,
        )
        self._client = httpx.AsyncClient(timeout=timeout, transport=transport)

    async def aclose(self) -> None:
        await self._client.aclose()

    # -- 请求构造 ---------------------------------------------------------

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        return headers

    @staticmethod
    def _serialise_message(message: ModelMessage) -> dict[str, Any]:
        payload: dict[str, Any] = {"role": message.role}
        payload["content"] = message.content
        if message.tool_calls:
            payload["tool_calls"] = [
                {
                    "id": call.id,
                    "type": "function",
                    "function": {"name": call.name, "arguments": call.arguments},
                }
                for call in message.tool_calls
            ]
        if message.tool_call_id is not None:
            payload["tool_call_id"] = message.tool_call_id
        return payload

    def _build_payload(self, request: ModelRequest) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "model": self._model,
            "messages": [self._serialise_message(item) for item in request.messages],
            "stream": False,
            "temperature": request.temperature,
        }
        if request.tools:
            payload["tools"] = request.tools
            payload["tool_choice"] = request.tool_choice
        if request.max_tokens is not None:
            payload["max_tokens"] = request.max_tokens
        return payload

    # -- 主流程 -----------------------------------------------------------

    async def complete(self, request: ModelRequest) -> ModelResponse:
        payload = self._build_payload(request)

        for attempt in range(self._max_retries + 1):
            try:
                response = await self._client.post(
                    self._chat_url,
                    json=payload,
                    headers=self._headers(),
                )
            except httpx.TimeoutException as exc:
                if isinstance(exc, httpx.ConnectTimeout) and attempt < self._max_retries:
                    await self._sleep(self._retry_delay_seconds)
                    continue
                raise ModelTimeoutError("模型服务响应超时") from None
            except httpx.TransportError:
                if attempt < self._max_retries:
                    await self._sleep(self._retry_delay_seconds)
                    continue
                raise ModelUpstreamError("模型服务连接失败") from None

            if response.status_code in _RETRYABLE_STATUS:
                if attempt < self._max_retries:
                    await self._sleep(self._retry_delay_seconds)
                    continue
                raise ModelUpstreamError("模型服务暂时不可用")

            return self._parse_response(response, request)

        raise ModelUpstreamError("模型服务暂时不可用")

    # -- 响应解析 ---------------------------------------------------------

    def _parse_response(self, response: httpx.Response, request: ModelRequest) -> ModelResponse:
        status = response.status_code

        if status in {401, 403}:
            raise ModelUnavailableError("模型服务鉴权失败，请检查服务端配置")
        if status == 404:
            raise ModelUnavailableError("模型服务地址或模型名不可用")
        if status >= 500:
            raise ModelUpstreamError("模型服务暂时不可用")
        if status >= 400:
            if request.tools and self._mentions_tool_support(response):
                raise ModelProtocolError("模型服务不支持工具调用")
            raise ModelUpstreamError("模型服务拒绝了本次请求")

        try:
            body = response.json()
        except ValueError:
            raise ModelProtocolError("模型服务返回了无法解析的响应") from None

        if not isinstance(body, dict):
            raise ModelProtocolError("模型服务返回了无法解析的响应")

        if body.get("error") is not None:
            if request.tools and self._mentions_tool_support(response):
                raise ModelProtocolError("模型服务不支持工具调用")
            raise ModelUpstreamError("模型服务返回了错误结果")

        choices = body.get("choices")
        if not isinstance(choices, list) or not choices:
            raise ModelProtocolError("模型服务返回结构缺少 choices")

        first = choices[0]
        if not isinstance(first, dict):
            raise ModelProtocolError("模型服务返回结构缺少 choices")

        message = first.get("message")
        if not isinstance(message, dict):
            raise ModelProtocolError("模型服务返回结构缺少 message")

        content = message.get("content")
        if content is not None and not isinstance(content, str):
            raise ModelProtocolError("模型服务返回的 content 类型非法")

        tool_calls = self._parse_tool_calls(message.get("tool_calls"))

        if not tool_calls and not (content or "").strip():
            raise ModelProtocolError("模型服务返回了空消息")

        finish_reason = first.get("finish_reason")

        return ModelResponse(
            content=content,
            tool_calls=tuple(tool_calls),
            usage=self._parse_usage(body.get("usage")),
            finish_reason=finish_reason if isinstance(finish_reason, str) else None,
        )

    @staticmethod
    def _mentions_tool_support(response: httpx.Response) -> bool:
        """只用于判定错误类型，文本内容不会写入异常或日志。"""

        try:
            text = response.text.lower()
        except Exception:
            return False
        return any(marker in text for marker in _TOOL_SUPPORT_MARKERS)

    @classmethod
    def _parse_tool_calls(cls, raw: Any) -> list[ToolCall]:
        if raw is None:
            return []
        if not isinstance(raw, list):
            raise ModelProtocolError("模型服务返回的 tool_calls 结构非法")

        calls: list[ToolCall] = []
        for index, item in enumerate(raw):
            if not isinstance(item, dict):
                raise ModelProtocolError("模型服务返回的 tool_calls 结构非法")

            function = item.get("function")
            if not isinstance(function, dict):
                raise ModelProtocolError("模型服务返回的 tool_calls 缺少 function")

            name = function.get("name")
            if not isinstance(name, str) or not name.strip():
                raise ModelProtocolError("模型服务返回的 tool_calls 缺少名称")

            arguments = function.get("arguments")
            if arguments is None:
                arguments = "{}"
            elif not isinstance(arguments, str):
                arguments = json.dumps(arguments, ensure_ascii=False)

            call_id = item.get("id")
            if not isinstance(call_id, str) or not call_id:
                call_id = f"call_{index}"

            calls.append(ToolCall(id=call_id, name=name.strip(), arguments=arguments))

        return calls

    @staticmethod
    def _parse_usage(raw: Any) -> ModelUsage:
        if not isinstance(raw, dict):
            return ModelUsage()
        return ModelUsage(
            prompt_tokens=_as_int(raw.get("prompt_tokens")),
            completion_tokens=_as_int(raw.get("completion_tokens")),
            total_tokens=_as_int(raw.get("total_tokens")),
        )

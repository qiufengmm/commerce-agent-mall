from __future__ import annotations

import json
from collections.abc import Callable
from typing import Any

import httpx
import pytest

from mall_shopping_agent.model.client import (
    ModelMessage,
    ModelProtocolError,
    ModelRequest,
    ModelTimeoutError,
    ModelUnavailableError,
    ModelUpstreamError,
)
from mall_shopping_agent.model.openai_compatible import OpenAICompatibleClient

API_KEY = "sk-local-development-value"
SECRET_BODY = "upstream-secret-body-must-not-leak"

TOOLS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "searchProducts",
            "description": "搜索商品",
            "parameters": {"type": "object", "properties": {}},
        },
    }
]


class Recorder:
    def __init__(self, handler: Callable[[httpx.Request], httpx.Response]) -> None:
        self.requests: list[httpx.Request] = []
        self._handler = handler

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return self._handler(request)

    @property
    def calls(self) -> int:
        return len(self.requests)


def build_client(
    handler: Callable[[httpx.Request], httpx.Response],
    *,
    timeout_seconds: float = 30.0,
    max_retries: int = 1,
) -> OpenAICompatibleClient:
    async def _no_sleep(_seconds: float) -> None:
        return None

    return OpenAICompatibleClient(
        base_url="https://model.example.com/v1",
        api_key=API_KEY,
        model="demo-model",
        timeout_seconds=timeout_seconds,
        max_retries=max_retries,
        transport=httpx.MockTransport(handler),
        sleep=_no_sleep,
    )


def request_with_tools() -> ModelRequest:
    return ModelRequest(
        messages=[
            ModelMessage(role="system", content="系统规则"),
            ModelMessage(role="user", content="3000 元左右的手机"),
        ],
        tools=TOOLS,
    )


def text_response(content: str = "为您找到三款商品") -> httpx.Response:
    return httpx.Response(
        200,
        json={
            "id": "chatcmpl-1",
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": content},
                    "finish_reason": "stop",
                }
            ],
            "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18},
        },
    )


async def test_posts_json_to_chat_completions_with_bearer_and_non_streaming() -> None:
    recorder = Recorder(lambda request: text_response())
    client = build_client(recorder)

    await client.complete(request_with_tools())

    assert recorder.calls == 1
    request = recorder.requests[0]
    assert request.method == "POST"
    assert str(request.url) == "https://model.example.com/v1/chat/completions"
    assert request.headers["authorization"] == f"Bearer {API_KEY}"
    assert request.headers["content-type"].startswith("application/json")

    payload = json.loads(request.content)
    assert payload["stream"] is False
    assert payload["model"] == "demo-model"
    assert payload["tool_choice"] == "auto"
    assert payload["tools"] == TOOLS
    assert payload["messages"][0] == {"role": "system", "content": "系统规则"}


async def test_returns_normalised_text_and_usage() -> None:
    client = build_client(lambda request: text_response("候选来自当前搜索结果"))

    response = await client.complete(request_with_tools())

    assert response.content == "候选来自当前搜索结果"
    assert response.tool_calls == ()
    assert response.usage.total_tokens == 18
    assert response.finish_reason == "stop"


async def test_parses_single_tool_call() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": "call_1",
                                    "type": "function",
                                    "function": {
                                        "name": "searchProducts",
                                        "arguments": '{"keyword": "手机"}',
                                    },
                                }
                            ],
                        },
                        "finish_reason": "tool_calls",
                    }
                ]
            },
        )

    client = build_client(handler)
    response = await client.complete(request_with_tools())

    assert response.content is None
    assert len(response.tool_calls) == 1
    call = response.tool_calls[0]
    assert call.id == "call_1"
    assert call.name == "searchProducts"
    assert json.loads(call.arguments) == {"keyword": "手机"}
    assert response.usage.total_tokens is None


async def test_parses_multiple_tool_calls_in_order() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "tool_calls": [
                                {
                                    "id": "call_a",
                                    "function": {
                                        "name": "getProductDetail",
                                        "arguments": '{"productId": 1}',
                                    },
                                },
                                {
                                    "id": "call_b",
                                    "function": {
                                        "name": "getProductDetail",
                                        "arguments": '{"productId": 2}',
                                    },
                                },
                            ]
                        }
                    }
                ]
            },
        )

    client = build_client(handler)
    response = await client.complete(request_with_tools())

    assert [call.id for call in response.tool_calls] == ["call_a", "call_b"]
    assert [call.name for call in response.tool_calls] == [
        "getProductDetail",
        "getProductDetail",
    ]


async def test_invalid_json_arguments_are_preserved_without_crashing() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "tool_calls": [
                                {
                                    "id": "call_1",
                                    "function": {
                                        "name": "searchProducts",
                                        "arguments": "{not-json",
                                    },
                                }
                            ]
                        }
                    }
                ]
            },
        )

    client = build_client(handler)
    response = await client.complete(request_with_tools())

    # 客户端不解析参数，参数合法性由工具注册表用 Pydantic 校验
    assert response.tool_calls[0].arguments == "{not-json"


async def test_retries_once_on_429_then_succeeds() -> None:
    counter = {"n": 0}

    def handler(_request: httpx.Request) -> httpx.Response:
        counter["n"] += 1
        if counter["n"] == 1:
            return httpx.Response(429, json={"error": {"message": SECRET_BODY}})
        return text_response("重试后成功")

    client = build_client(handler)
    response = await client.complete(request_with_tools())

    assert counter["n"] == 2
    assert response.content == "重试后成功"


async def test_retries_once_on_503_then_raises_upstream_error() -> None:
    recorder = Recorder(lambda request: httpx.Response(503, text=SECRET_BODY))
    client = build_client(recorder)

    with pytest.raises(ModelUpstreamError) as excinfo:
        await client.complete(request_with_tools())

    assert recorder.calls == 2
    assert SECRET_BODY not in str(excinfo.value)
    assert API_KEY not in str(excinfo.value)


async def test_does_not_retry_on_401() -> None:
    recorder = Recorder(lambda request: httpx.Response(401, text=SECRET_BODY))
    client = build_client(recorder)

    with pytest.raises(ModelUnavailableError) as excinfo:
        await client.complete(request_with_tools())

    assert recorder.calls == 1
    assert SECRET_BODY not in str(excinfo.value)
    assert API_KEY not in str(excinfo.value)


async def test_read_timeout_maps_to_timeout_error_without_retry() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("read timed out", request=request)

    recorder = Recorder(handler)
    client = build_client(recorder)

    with pytest.raises(ModelTimeoutError):
        await client.complete(request_with_tools())

    assert recorder.calls == 1


async def test_connect_error_retries_once_then_raises_upstream_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    recorder = Recorder(handler)
    client = build_client(recorder)

    with pytest.raises(ModelUpstreamError):
        await client.complete(request_with_tools())

    assert recorder.calls == 2


async def test_tool_unsupported_response_raises_protocol_error() -> None:
    recorder = Recorder(
        lambda request: httpx.Response(
            400,
            json={"error": {"message": "this model does not support tool_choice"}},
        )
    )
    client = build_client(recorder)

    with pytest.raises(ModelProtocolError) as excinfo:
        await client.complete(request_with_tools())

    assert recorder.calls == 1
    assert "tool_choice" not in str(excinfo.value)


async def test_unknown_payload_structure_raises_protocol_error() -> None:
    client = build_client(lambda request: httpx.Response(200, json={"id": "chatcmpl-1"}))

    with pytest.raises(ModelProtocolError):
        await client.complete(request_with_tools())


async def test_invalid_json_body_raises_protocol_error() -> None:
    client = build_client(lambda request: httpx.Response(200, text="<html>gateway</html>"))

    with pytest.raises(ModelProtocolError):
        await client.complete(request_with_tools())


async def test_empty_message_raises_protocol_error() -> None:
    client = build_client(
        lambda request: httpx.Response(200, json={"choices": [{"message": {"content": " "}}]})
    )

    with pytest.raises(ModelProtocolError):
        await client.complete(request_with_tools())


async def test_errors_never_expose_secrets_or_raw_body() -> None:
    bodies = {
        "429": httpx.Response(429, json={"error": {"message": SECRET_BODY}}),
        "500": httpx.Response(500, text=SECRET_BODY),
        "401": httpx.Response(401, text=SECRET_BODY),
        "garbage": httpx.Response(200, text=SECRET_BODY),
    }

    for name, response in bodies.items():
        client = build_client(lambda request, response=response: response)
        with pytest.raises(Exception) as excinfo:
            await client.complete(request_with_tools())

        message = str(excinfo.value)
        assert SECRET_BODY not in message, name
        assert API_KEY not in message, name
        assert "authorization" not in message.lower(), name
        assert excinfo.value.args, name

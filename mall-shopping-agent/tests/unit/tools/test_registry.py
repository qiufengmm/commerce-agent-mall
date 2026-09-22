from __future__ import annotations

from typing import Any

import pytest

from mall_shopping_agent.tools import create_tool_registry
from mall_shopping_agent.tools.registry import (
    InvalidToolArgumentsError,
    ToolContext,
    UnknownToolError,
)
from support.fake_storefront import FakeStorefrontBackend
from support.registry_fixture import call, invoke

EXPECTED_TOOLS = (
    "searchProducts",
    "getProductDetail",
    "compareProducts",
    "getMemberCouponsForProduct",
)

FORBIDDEN_PROPERTY_NAMES = {
    "url",
    "baseUrl",
    "method",
    "headers",
    "authorization",
    "token",
    "timeout",
    "endpoint",
    "path",
    "sql",
}


def test_registry_exposes_exactly_the_four_read_only_tools() -> None:
    registry = create_tool_registry()

    assert registry.names == EXPECTED_TOOLS


def test_openai_tool_schema_is_strict_and_self_contained() -> None:
    registry = create_tool_registry()

    tools = registry.openai_tools()

    assert len(tools) == 4
    for tool in tools:
        assert tool["type"] == "function"
        function = tool["function"]
        assert function["name"] in EXPECTED_TOOLS
        assert function["description"]
        schema = function["parameters"]
        assert schema["type"] == "object"
        assert schema.get("additionalProperties") is False
        for property_name in schema.get("properties", {}):
            assert property_name not in FORBIDDEN_PROPERTY_NAMES


def test_tool_schema_never_exposes_http_method_or_host_controls() -> None:
    registry = create_tool_registry()

    serialised = str(registry.openai_tools()).lower()

    for forbidden in FORBIDDEN_PROPERTY_NAMES:
        assert f"'{forbidden.lower()}'" not in serialised


async def test_unknown_tool_is_rejected_before_any_backend_call() -> None:
    registry = create_tool_registry()
    backend = FakeStorefrontBackend()

    with pytest.raises(UnknownToolError):
        await invoke(registry, backend, "deleteProduct", {"productId": 1})

    assert backend.calls == []


async def test_extra_arguments_are_rejected_before_any_backend_call() -> None:
    registry = create_tool_registry()
    backend = FakeStorefrontBackend()

    with pytest.raises(InvalidToolArgumentsError):
        await invoke(registry, backend, "searchProducts", {"keyword": "手机", "pageSize": 100})

    assert backend.calls == []


@pytest.mark.parametrize(
    "arguments",
    [
        {"productId": 0},
        {"productId": -5},
        {"productId": "27"},
    ],
)
async def test_negative_or_non_integer_product_id_is_rejected(arguments: dict[str, Any]) -> None:
    registry = create_tool_registry()
    backend = FakeStorefrontBackend()

    with pytest.raises(InvalidToolArgumentsError):
        await invoke(registry, backend, "getProductDetail", arguments)

    assert backend.calls == []


async def test_four_compare_ids_are_rejected() -> None:
    registry = create_tool_registry()
    backend = FakeStorefrontBackend()

    with pytest.raises(InvalidToolArgumentsError):
        await invoke(registry, backend, "compareProducts", {"productIds": [1, 2, 3, 4]})

    assert backend.calls == []


async def test_single_compare_id_is_rejected() -> None:
    registry = create_tool_registry()
    backend = FakeStorefrontBackend()

    with pytest.raises(InvalidToolArgumentsError):
        await invoke(registry, backend, "compareProducts", {"productIds": [1]})

    assert backend.calls == []


async def test_duplicate_compare_ids_are_rejected() -> None:
    registry = create_tool_registry()
    backend = FakeStorefrontBackend()

    with pytest.raises(InvalidToolArgumentsError):
        await invoke(registry, backend, "compareProducts", {"productIds": [1, 1]})

    assert backend.calls == []


@pytest.mark.parametrize(
    "arguments",
    [
        {"pageNum": 21},
        {"pageNum": 0},
        {"sort": 5},
        {"sort": -1},
        {"brandId": 0},
        {"keyword": "x" * 101},
    ],
)
async def test_oversized_or_invalid_search_arguments_are_rejected(
    arguments: dict[str, Any],
) -> None:
    registry = create_tool_registry()
    backend = FakeStorefrontBackend()

    with pytest.raises(InvalidToolArgumentsError):
        await invoke(registry, backend, "searchProducts", arguments)

    assert backend.calls == []


@pytest.mark.parametrize(
    "arguments",
    [
        {"url": "http://evil.invalid/"},
        {"method": "POST"},
        {"headers": {"Authorization": "Bearer x"}},
        {"baseUrl": "http://evil.invalid"},
        {"timeout": 999},
        {"sql": "select 1"},
    ],
)
async def test_model_cannot_inject_transport_controls(arguments: dict[str, Any]) -> None:
    registry = create_tool_registry()
    backend = FakeStorefrontBackend()

    with pytest.raises(InvalidToolArgumentsError):
        await invoke(registry, backend, "searchProducts", arguments)

    assert backend.calls == []


async def test_invalid_json_arguments_are_rejected() -> None:
    registry = create_tool_registry()
    backend = FakeStorefrontBackend()

    with pytest.raises(InvalidToolArgumentsError):
        await registry.invoke(
            call("searchProducts", "{not-json"),
            context=ToolContext(backend=backend),
        )

    assert backend.calls == []


async def test_non_object_arguments_are_rejected() -> None:
    registry = create_tool_registry()
    backend = FakeStorefrontBackend()

    with pytest.raises(InvalidToolArgumentsError):
        await invoke(registry, backend, "searchProducts", "[1, 2, 3]")

    assert backend.calls == []

from __future__ import annotations

import asyncio
import uuid

import httpx
import pytest
from fastapi.testclient import TestClient

from mall_shopping_agent.model.client import ModelTimeoutError
from mall_shopping_agent.safety.rate_limit import (
    ChatRateLimiter,
    InMemoryRateCounter,
    RateLimitRule,
)
from mall_shopping_agent.session.identity import Identity
from mall_shopping_agent.storefront.backend import (
    MemberUnauthorizedError,
    StorefrontUnavailableError,
)
from mall_shopping_agent.storefront.schemas import ProductSearchPage
from support.api_app import (
    MEMBER_TOKEN,
    SESSION_ID,
    AgentTestApp,
    build_settings,
    build_test_app,
)
from support.fake_model import FakeModel, text_response, tool_call, tool_response
from support.fake_storefront import FakeStorefrontBackend, build_summary

CHAT_URL = "/agent/chat"
GUEST_KEY = Identity.guest(SESSION_ID).session_key
MEMBER_KEY = Identity.member(7, SESSION_ID).session_key


def payload(message: str = "3000 元左右有哪些手机", session_id: str = SESSION_ID) -> dict:
    return {"sessionId": session_id, "message": message}


def client_for(app_test: AgentTestApp, **kwargs) -> TestClient:
    return TestClient(app_test.app, **kwargs)


class BlockingModel(FakeModel):
    """在返回前等待事件，用于构造并发重复请求。"""

    def __init__(self, gate: asyncio.Event) -> None:
        super().__init__()
        self._gate = gate
        self.started = asyncio.Event()

    async def complete(self, request):  # type: ignore[override]
        self.requests.append(request)
        self.started.set()
        await self._gate.wait()
        return text_response("好的")


def search_backend() -> FakeStorefrontBackend:
    backend = FakeStorefrontBackend()
    backend.search_page = ProductSearchPage(
        pageNum=1,
        pageSize=5,
        totalPage=1,
        total=1,
        list=[build_summary(id=27, name="示例手机 B", price="2999.00", stock=120)],
    )
    return backend


# --------------------------------------------------------------------------- #
# 成功契约
# --------------------------------------------------------------------------- #


def test_chat_returns_unified_envelope_with_server_side_cards() -> None:
    app_test = build_test_app(
        model=FakeModel(
            [
                tool_response(tool_call("searchProducts", {"keyword": "手机"})),
                text_response("为您找到示例手机 B。\n[[MALL_PRODUCTS: 27]]"),
            ]
        ),
        backend=search_backend(),
    )

    with client_for(app_test) as client:
        response = client.post(CHAT_URL, json=payload())

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == 200
    assert body["message"] == "操作成功"
    data = body["data"]
    assert data["sessionId"] == SESSION_ID
    assert uuid.UUID(data["messageId"]).version == 4
    assert data["requiresLogin"] is False
    assert 1 <= len(data["suggestedQuestions"]) <= 3
    assert data["products"][0] == {
        "id": 27,
        "name": "示例手机 B",
        "pic": "http://localhost:9000/mall/example-26.jpg",
        "price": "2999.00",
        "subtitle": "示例副标题 A",
        "stockStatus": "IN_STOCK",
        "availableStock": 120,
        "detailPath": "/pages/product/product?id=27",
    }


def test_chat_persists_session_without_token_or_tool_payloads() -> None:
    app_test = build_test_app(
        model=FakeModel(
            [
                tool_response(tool_call("searchProducts", {"keyword": "手机"})),
                text_response("为您找到商品。\n[[MALL_PRODUCTS: 27]]"),
            ]
        ),
        backend=search_backend(),
    )

    with client_for(app_test) as client:
        client.post(CHAT_URL, json=payload())

    raw = app_test.repository.raw(GUEST_KEY) or ""
    assert "Bearer" not in raw
    assert "authorization" not in raw.lower()
    assert "tool_calls" not in raw
    assert "示例手机 B" in raw


# --------------------------------------------------------------------------- #
# 参数校验
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize(
    "session_id",
    [
        "not-a-uuid",
        "2dc7b03e-7368-1d6a-a8ef-b0ea16f6c92c",
        "",
        "2dc7b03e73684d6aa8efb0ea16f6c92c",
    ],
)
def test_invalid_session_id_returns_400(session_id: str) -> None:
    app_test = build_test_app()

    with client_for(app_test) as client:
        response = client.post(CHAT_URL, json=payload(session_id=session_id))

    assert response.status_code == 400
    assert response.json()["code"] == 400


@pytest.mark.parametrize("message", ["", "   ", "字" * 1001])
def test_invalid_message_length_returns_400(message: str) -> None:
    app_test = build_test_app()

    with client_for(app_test) as client:
        response = client.post(CHAT_URL, json=payload(message=message))

    assert response.status_code == 400


def test_message_is_trimmed_before_use() -> None:
    app_test = build_test_app(model=FakeModel([text_response("好的")]))

    with client_for(app_test) as client:
        response = client.post(CHAT_URL, json=payload("  有什么手机  "))

    assert response.status_code == 200
    raw = app_test.repository.raw(GUEST_KEY) or ""
    assert "有什么手机" in raw


@pytest.mark.parametrize(
    "extra",
    [
        {"memberId": 7},
        {"token": "Bearer x"},
        {"url": "http://evil.invalid/"},
        {"model": "gpt-4"},
    ],
)
def test_unknown_or_client_controlled_fields_are_rejected(extra: dict) -> None:
    app_test = build_test_app()

    body = {**payload(), **extra}
    with client_for(app_test) as client:
        response = client.post(CHAT_URL, json=body)

    assert response.status_code == 400


# --------------------------------------------------------------------------- #
# 错误契约
# --------------------------------------------------------------------------- #


def test_model_not_configured_returns_503() -> None:
    app_test = build_test_app(settings=build_settings(openai_api_key=""))

    with client_for(app_test) as client:
        response = client.post(CHAT_URL, json=payload())

    assert response.status_code == 503
    assert response.json()["code"] == 503
    assert app_test.model.call_count == 0


def test_placeholder_api_key_is_rejected_with_503() -> None:
    app_test = build_test_app(settings=build_settings(openai_api_key="<请填写模型服务 API Key>"))

    with client_for(app_test) as client:
        response = client.post(CHAT_URL, json=payload())

    assert response.status_code == 503


def test_model_timeout_returns_502() -> None:
    app_test = build_test_app(model=FakeModel([ModelTimeoutError("模型服务响应超时")]))

    with client_for(app_test) as client:
        response = client.post(CHAT_URL, json=payload())

    assert response.status_code == 502
    assert "超时" in response.json()["message"] or response.json()["code"] == 502


def test_portal_failure_produces_no_cards_and_no_upstream_body() -> None:
    backend = search_backend()
    backend.errors["search_products"] = StorefrontUnavailableError("portal-upstream-secret")
    app_test = build_test_app(
        model=FakeModel(
            [
                tool_response(tool_call("searchProducts", {"keyword": "手机"})),
                text_response("商品数据暂时无法获取。"),
            ]
        ),
        backend=backend,
    )

    with client_for(app_test) as client:
        response = client.post(CHAT_URL, json=payload())

    assert response.status_code == 200
    assert response.json()["data"]["products"] == []


def test_rate_limit_returns_429_without_calling_model() -> None:
    limiter = ChatRateLimiter(
        counter=InMemoryRateCounter(),
        session_rule=RateLimitRule(limit=1, window_seconds=300),
        ip_rule=RateLimitRule(limit=100, window_seconds=300),
    )
    app_test = build_test_app(model=FakeModel([text_response("好的")]), limiter=limiter)

    with client_for(app_test) as client:
        first = client.post(CHAT_URL, json=payload("第一条"))
        second = client.post(CHAT_URL, json=payload("第二条"))

    assert first.status_code == 200
    assert second.status_code == 429
    assert second.json()["code"] == 429
    assert app_test.model.call_count == 1


def test_tool_round_limit_returns_422() -> None:
    responses = [
        tool_response(tool_call("searchProducts", {}, call_id=f"call_{index}"))
        for index in range(5)
    ]
    app_test = build_test_app(model=FakeModel(responses), backend=search_backend())

    with client_for(app_test) as client:
        response = client.post(CHAT_URL, json=payload())

    assert response.status_code == 422
    assert response.json()["code"] == 422


def test_unexpected_error_returns_generic_500() -> None:
    class BrokenRepository:
        async def load(self, key):
            raise RuntimeError("internal-database-detail")

        async def save(self, key, snapshot):
            raise RuntimeError("internal-database-detail")

        async def delete(self, key):
            raise RuntimeError("internal-database-detail")

        async def copy(self, source_key, target_key):
            raise RuntimeError("internal-database-detail")

    app_test = build_test_app(repository=BrokenRepository())

    with client_for(app_test, raise_server_exceptions=False) as client:
        response = client.post(CHAT_URL, json=payload())

    assert response.status_code == 500
    body = response.json()
    assert body["code"] == 500
    assert "RuntimeError" not in response.text
    assert "internal-database-detail" not in response.text
    assert "Traceback" not in response.text


# --------------------------------------------------------------------------- #
# 身份与登录
# --------------------------------------------------------------------------- #


def test_invalid_token_returns_200_with_requires_login_and_no_model_call() -> None:
    backend = search_backend()
    backend.errors["resolve_member"] = MemberUnauthorizedError("登录失效")
    app_test = build_test_app(
        model=FakeModel([text_response("不应该被调用")]),
        backend=backend,
    )

    with client_for(app_test) as client:
        response = client.post(
            CHAT_URL, json=payload("我的优惠券"), headers={"Authorization": MEMBER_TOKEN}
        )

    assert response.status_code == 200
    body = response.json()
    assert body["data"]["requiresLogin"] is True
    assert body["data"]["products"] == []
    assert app_test.model.call_count == 0


def test_invalid_token_does_not_query_member_endpoints() -> None:
    backend = search_backend()
    backend.errors["resolve_member"] = MemberUnauthorizedError("登录失效")
    app_test = build_test_app(backend=backend)

    with client_for(app_test) as client:
        client.post(CHAT_URL, json=payload("我的优惠券"), headers={"Authorization": MEMBER_TOKEN})

    assert backend.call_names == ["resolve_member"]


def test_invalid_token_keeps_the_question_in_the_guest_session() -> None:
    backend = search_backend()
    backend.errors["resolve_member"] = MemberUnauthorizedError("登录失效")
    app_test = build_test_app(backend=backend)

    with client_for(app_test) as client:
        client.post(CHAT_URL, json=payload("我的优惠券"), headers={"Authorization": MEMBER_TOKEN})

    raw = app_test.repository.raw(GUEST_KEY) or ""
    assert "我的优惠券" in raw
    assert MEMBER_TOKEN not in raw


def test_migrated_member_session_restores_the_pre_login_question() -> None:
    backend = search_backend()
    backend.errors["resolve_member"] = MemberUnauthorizedError("登录失效")
    app_test = build_test_app(backend=backend)

    with client_for(app_test) as client:
        client.post(CHAT_URL, json=payload("我的优惠券"), headers={"Authorization": MEMBER_TOKEN})

    # 登录成功后同一个 sessionId 的游客会话会被迁移到会员命名空间
    backend.errors.pop("resolve_member", None)
    with client_for(app_test) as client:
        response = client.post(
            CHAT_URL, json=payload("继续"), headers={"Authorization": MEMBER_TOKEN}
        )

    assert response.status_code == 200
    assert app_test.repository.raw(GUEST_KEY) is None
    member_raw = app_test.repository.raw(MEMBER_KEY) or ""
    assert "我的优惠券" in member_raw
    assert "继续" in member_raw
    assert MEMBER_TOKEN not in member_raw


def test_valid_token_migrates_guest_session_into_member_namespace() -> None:
    backend = search_backend()
    app_test = build_test_app(
        model=FakeModel([text_response("您好，已登录。")]),
        backend=backend,
    )
    guest_identity = Identity.guest(SESSION_ID)

    async def seed() -> None:
        from mall_shopping_agent.session.repository import SessionMessage, SessionSnapshot

        await app_test.repository.save(
            guest_identity.session_key,
            SessionSnapshot(messages=[SessionMessage(role="user", content="登录前的问题")]),
        )

    asyncio.run(seed())

    with client_for(app_test) as client:
        response = client.post(
            CHAT_URL, json=payload("登录后继续"), headers={"Authorization": MEMBER_TOKEN}
        )

    assert response.status_code == 200
    assert response.json()["data"]["requiresLogin"] is False
    assert app_test.repository.raw(guest_identity.session_key) is None

    member_raw = app_test.repository.raw(MEMBER_KEY) or ""
    assert "登录前的问题" in member_raw
    assert "登录后继续" in member_raw
    assert MEMBER_TOKEN not in member_raw


def test_guest_chat_never_touches_member_namespace() -> None:
    app_test = build_test_app(model=FakeModel([text_response("您好")]))

    with client_for(app_test) as client:
        client.post(CHAT_URL, json=payload())

    assert app_test.repository.keys == [GUEST_KEY]


# --------------------------------------------------------------------------- #
# 并发重复提交
# --------------------------------------------------------------------------- #


async def test_duplicate_in_flight_request_is_rejected() -> None:
    gate = asyncio.Event()
    blocking = BlockingModel(gate)
    app_test = build_test_app(model=blocking)
    transport = httpx.ASGITransport(app=app_test.app)

    async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
        first = asyncio.create_task(client.post(CHAT_URL, json=payload()))
        await blocking.started.wait()
        second = await client.post(CHAT_URL, json=payload())
        gate.set()
        first_response = await first

    assert second.status_code == 409
    assert second.json()["code"] == 409
    assert first_response.status_code == 200
    assert blocking.call_count == 1

from __future__ import annotations

import asyncio

import pytest
from fastapi.testclient import TestClient

from mall_shopping_agent.session.identity import Identity
from mall_shopping_agent.session.repository import SessionMessage, SessionSnapshot
from mall_shopping_agent.storefront.backend import MemberUnauthorizedError
from support.api_app import MEMBER_TOKEN, SESSION_ID, AgentTestApp, build_test_app
from support.fake_model import FakeModel, text_response
from support.fake_storefront import FakeStorefrontBackend

GUEST_KEY = Identity.guest(SESSION_ID).session_key
MEMBER_KEY = Identity.member(7, SESSION_ID).session_key
UNKNOWN_SESSION_ID = "7d8869ab-7819-4c1e-a245-0933e4f1662f"

CARD = {
    "id": 27,
    "name": "示例手机 B",
    "pic": "http://localhost:9000/mall/example-27.jpg",
    "price": "2999.00",
    "subtitle": "示例副标题 B",
    "stockStatus": "IN_STOCK",
    "availableStock": 112,
    "detailPath": "/pages/product/product?id=27",
}


def seed(app_test: AgentTestApp, key: str, snapshot: SessionSnapshot) -> None:
    asyncio.run(app_test.repository.save(key, snapshot))


def client_for(app_test: AgentTestApp, **kwargs) -> TestClient:
    return TestClient(app_test.app, **kwargs)


def test_get_session_returns_messages_and_cards() -> None:
    app_test = build_test_app()
    seed(
        app_test,
        GUEST_KEY,
        SessionSnapshot(
            messages=[
                SessionMessage(role="user", content="有什么手机"),
                SessionMessage(role="assistant", content="为您找到示例手机 B"),
            ],
            products=[CARD],
        ),
    )

    with client_for(app_test) as client:
        response = client.get(f"/agent/session/{SESSION_ID}")

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["sessionId"] == SESSION_ID
    assert [item["role"] for item in data["messages"]] == ["user", "assistant"]
    assert data["messages"][0]["content"] == "有什么手机"
    assert data["products"] == [CARD]
    assert data["requiresLogin"] is False
    assert "Bearer" not in response.text


def test_get_session_unknown_returns_empty_snapshot() -> None:
    app_test = build_test_app()

    with client_for(app_test) as client:
        response = client.get(f"/agent/session/{UNKNOWN_SESSION_ID}")

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["messages"] == []
    assert data["products"] == []


@pytest.mark.parametrize(
    "session_id",
    ["not-a-uuid", "2dc7b03e-7368-1d6a-a8ef-b0ea16f6c92c", "abc"],
)
def test_get_session_invalid_uuid_returns_400(session_id: str) -> None:
    app_test = build_test_app()

    with client_for(app_test) as client:
        response = client.get(f"/agent/session/{session_id}")

    assert response.status_code == 400
    assert response.json()["code"] == 400


def test_get_session_is_capped_at_twenty_messages() -> None:
    app_test = build_test_app()
    seed(
        app_test,
        GUEST_KEY,
        SessionSnapshot(
            messages=[SessionMessage(role="user", content=f"消息 {index}") for index in range(20)]
        ),
    )

    with client_for(app_test) as client:
        response = client.get(f"/agent/session/{SESSION_ID}")

    assert len(response.json()["data"]["messages"]) == 20


def test_member_session_requires_a_valid_token() -> None:
    backend = FakeStorefrontBackend()
    backend.errors["resolve_member"] = MemberUnauthorizedError("登录失效")
    app_test = build_test_app(backend=backend)
    seed(
        app_test,
        MEMBER_KEY,
        SessionSnapshot(messages=[SessionMessage(role="user", content="会员私密会话")]),
    )

    with client_for(app_test) as client:
        response = client.get(
            f"/agent/session/{SESSION_ID}", headers={"Authorization": MEMBER_TOKEN}
        )

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["requiresLogin"] is True
    assert data["messages"] == []
    assert "会员私密会话" not in response.text


def test_member_session_is_read_from_the_member_namespace() -> None:
    app_test = build_test_app()
    seed(
        app_test,
        MEMBER_KEY,
        SessionSnapshot(messages=[SessionMessage(role="user", content="会员会话")]),
    )
    seed(
        app_test,
        GUEST_KEY,
        SessionSnapshot(messages=[SessionMessage(role="user", content="游客会话")]),
    )

    with client_for(app_test) as client:
        member_response = client.get(
            f"/agent/session/{SESSION_ID}", headers={"Authorization": MEMBER_TOKEN}
        )
        guest_response = client.get(f"/agent/session/{SESSION_ID}")

    assert member_response.json()["data"]["messages"][0]["content"] == "会员会话"
    assert guest_response.json()["data"]["messages"][0]["content"] == "游客会话"


def test_get_session_never_returns_cards_for_failed_tools() -> None:
    app_test = build_test_app()
    seed(
        app_test,
        GUEST_KEY,
        SessionSnapshot(messages=[SessionMessage(role="user", content="你好")], products=[]),
    )

    with client_for(app_test) as client:
        response = client.get(f"/agent/session/{SESSION_ID}")

    assert response.json()["data"]["products"] == []


def test_delete_session_removes_only_current_namespace_and_is_idempotent() -> None:
    app_test = build_test_app()
    seed(
        app_test,
        GUEST_KEY,
        SessionSnapshot(messages=[SessionMessage(role="user", content="游客会话")]),
    )
    seed(
        app_test,
        MEMBER_KEY,
        SessionSnapshot(messages=[SessionMessage(role="user", content="会员会话")]),
    )

    with client_for(app_test) as client:
        first = client.delete(f"/agent/session/{SESSION_ID}")
        second = client.delete(f"/agent/session/{SESSION_ID}")

    assert first.status_code == 200
    assert first.json()["code"] == 200
    assert second.status_code == 200
    assert app_test.repository.raw(GUEST_KEY) is None
    assert app_test.repository.raw(MEMBER_KEY) is not None


@pytest.mark.parametrize("session_id", ["not-a-uuid", "abc"])
def test_delete_session_invalid_uuid_returns_400(session_id: str) -> None:
    app_test = build_test_app()

    with client_for(app_test) as client:
        response = client.delete(f"/agent/session/{session_id}")

    assert response.status_code == 400


def test_delete_member_session_requires_a_valid_token() -> None:
    backend = FakeStorefrontBackend()
    backend.errors["resolve_member"] = MemberUnauthorizedError("登录失效")
    app_test = build_test_app(backend=backend)
    seed(
        app_test,
        MEMBER_KEY,
        SessionSnapshot(messages=[SessionMessage(role="user", content="会员会话")]),
    )

    with client_for(app_test) as client:
        response = client.delete(
            f"/agent/session/{SESSION_ID}", headers={"Authorization": MEMBER_TOKEN}
        )

    assert response.status_code == 200
    assert response.json()["data"]["requiresLogin"] is True
    assert app_test.repository.raw(MEMBER_KEY) is not None


def test_session_endpoints_do_not_call_the_model() -> None:
    app_test = build_test_app(model=FakeModel([text_response("不应该被调用")]))

    with client_for(app_test) as client:
        client.get(f"/agent/session/{SESSION_ID}")
        client.delete(f"/agent/session/{SESSION_ID}")

    assert app_test.model.call_count == 0

from __future__ import annotations

import json

import pytest

from mall_shopping_agent.session.identity import Identity
from mall_shopping_agent.session.memory_repository import MemorySessionRepository
from mall_shopping_agent.session.repository import SessionMessage, SessionSnapshot

SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c"
MEMBER_TOKEN = "Bearer placeholder-member-token"


class Clock:
    def __init__(self, now: float = 1000.0) -> None:
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def build_repo(clock: Clock | None = None, **kwargs: int) -> MemorySessionRepository:
    return MemorySessionRepository(
        ttl_seconds=86400, max_messages=20, clock=clock or Clock(), **kwargs
    )


def snapshot(*messages: tuple[str, str]) -> SessionSnapshot:
    return SessionSnapshot(
        messages=[SessionMessage(role=role, content=content) for role, content in messages]  # type: ignore[arg-type]
    )


async def test_save_and_load_round_trip() -> None:
    repo = build_repo()
    key = Identity.guest(SESSION_ID).session_key

    await repo.save(key, snapshot(("user", "有什么手机"), ("assistant", "为您找到三款")))
    loaded = await repo.load(key)

    assert [(item.role, item.content) for item in loaded.messages] == [
        ("user", "有什么手机"),
        ("assistant", "为您找到三款"),
    ]


async def test_unknown_session_returns_empty_snapshot() -> None:
    repo = build_repo()

    loaded = await repo.load(Identity.guest(SESSION_ID).session_key)

    assert loaded.messages == []
    assert loaded.products == []


async def test_ttl_is_twenty_four_hours_and_renewed_on_access() -> None:
    clock = Clock()
    repo = build_repo(clock)
    key = Identity.guest(SESSION_ID).session_key

    await repo.save(key, snapshot(("user", "你好")))
    assert repo.ttl_seconds(key) == pytest.approx(86400)

    clock.advance(3600)
    assert repo.ttl_seconds(key) == pytest.approx(82800)

    await repo.load(key)
    assert repo.ttl_seconds(key) == pytest.approx(86400)


async def test_session_expires_after_ttl() -> None:
    clock = Clock()
    repo = build_repo(clock)
    key = Identity.guest(SESSION_ID).session_key

    await repo.save(key, snapshot(("user", "你好")))
    clock.advance(86401)

    assert (await repo.load(key)).messages == []
    assert repo.ttl_seconds(key) is None


async def test_only_last_twenty_messages_are_kept() -> None:
    repo = build_repo()
    key = Identity.guest(SESSION_ID).session_key

    messages = [("user", f"消息 {index}") for index in range(30)]
    await repo.save(key, snapshot(*messages))
    loaded = await repo.load(key)

    assert len(loaded.messages) == 20
    assert loaded.messages[0].content == "消息 10"
    assert loaded.messages[-1].content == "消息 29"


async def test_delete_is_idempotent() -> None:
    repo = build_repo()
    key = Identity.guest(SESSION_ID).session_key

    assert await repo.delete(key) is False

    await repo.save(key, snapshot(("user", "你好")))
    assert await repo.delete(key) is True
    assert await repo.delete(key) is False


async def test_guest_member_migration_copies_then_removes_guest_key() -> None:
    repo = build_repo()
    guest_key = Identity.guest(SESSION_ID).session_key
    member_key = Identity.member(7, SESSION_ID).session_key

    await repo.save(guest_key, snapshot(("user", "有什么手机")))
    await repo.copy(guest_key, member_key)

    assert (await repo.load(guest_key)).messages == []
    assert [item.content for item in (await repo.load(member_key)).messages] == ["有什么手机"]


async def test_migration_without_guest_session_keeps_member_namespace_untouched() -> None:
    repo = build_repo()
    guest_key = Identity.guest(SESSION_ID).session_key
    member_key = Identity.member(7, SESSION_ID).session_key

    await repo.save(member_key, snapshot(("user", "会员消息")))
    await repo.copy(guest_key, member_key)

    assert [item.content for item in (await repo.load(member_key)).messages] == ["会员消息"]


async def test_guest_and_member_namespaces_do_not_collide() -> None:
    repo = build_repo()
    guest_key = Identity.guest(SESSION_ID).session_key
    member_key = Identity.member(7, SESSION_ID).session_key

    await repo.save(guest_key, snapshot(("user", "游客问题")))
    await repo.save(member_key, snapshot(("user", "会员问题")))

    assert repo.keys == [guest_key, member_key]
    assert (await repo.load(guest_key)).messages[0].content == "游客问题"
    assert (await repo.load(member_key)).messages[0].content == "会员问题"


async def test_product_cards_are_stored_without_tool_payloads() -> None:
    repo = build_repo()
    key = Identity.guest(SESSION_ID).session_key
    cards = [
        {
            "id": 27,
            "name": "示例手机 B",
            "pic": "http://localhost:9000/mall/example-27.jpg",
            "price": "2999.00",
            "subtitle": "示例副标题 B",
            "stockStatus": "IN_STOCK",
            "availableStock": 112,
            "detailPath": "/pages/product/product?id=27",
        }
    ]

    await repo.save(key, SessionSnapshot(messages=[], products=cards))

    assert json.loads(repo.raw(key) or "{}")["products"] == cards


async def test_session_document_never_contains_authorization_or_token() -> None:
    repo = build_repo()
    key = Identity.member(7, SESSION_ID).session_key

    await repo.save(key, snapshot(("user", "我的优惠券"), ("assistant", "您有一张满减券")))

    raw = repo.raw(key) or ""
    assert "authorization" not in raw.lower()
    assert "bearer" not in raw.lower()
    assert "token" not in raw.lower()
    assert MEMBER_TOKEN not in raw


async def test_session_keys_never_contain_authorization() -> None:
    repo = build_repo()
    key = Identity.member(7, SESSION_ID).session_key

    await repo.save(key, snapshot(("user", "你好")))

    for stored_key in repo.keys:
        assert "Bearer" not in stored_key
        assert "authorization" not in stored_key.lower()


async def test_unknown_corrupted_document_returns_empty_snapshot() -> None:
    repo = build_repo()
    key = Identity.guest(SESSION_ID).session_key
    repo._docs[key] = ("{not json", 1e12)  # type: ignore[index]

    assert (await repo.load(key)).messages == []


async def test_message_rejects_unknown_fields_and_empty_content() -> None:
    with pytest.raises(ValueError):
        SessionMessage(role="user", content="")  # type: ignore[arg-type]
    with pytest.raises(ValueError):
        SessionMessage(role="system", content="x")  # type: ignore[arg-type]
    with pytest.raises(ValueError):
        SessionMessage(role="user", content="x", extra="nope")  # type: ignore[call-arg]


async def test_snapshot_rejects_unknown_fields() -> None:
    with pytest.raises(ValueError):
        SessionSnapshot.model_validate({"messages": [], "token": "must-not-exist"})

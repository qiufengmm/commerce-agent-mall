"""Redis 集成测试。

只有显式设置 ``MALL_AGENT_TEST_REDIS_URL`` 时才运行；未设置时整体跳过。
测试键使用随机前缀，并且只删除自己创建的键。
"""

from __future__ import annotations

import os
import uuid

import pytest
import redis.asyncio as redis_asyncio

from mall_shopping_agent.safety.rate_limit import (
    ChatRateLimiter,
    RateLimitExceededError,
    RateLimitRule,
    RedisRateCounter,
)
from mall_shopping_agent.session.identity import fingerprint
from mall_shopping_agent.session.redis_repository import RedisSessionRepository
from mall_shopping_agent.session.repository import SessionMessage, SessionSnapshot

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        not os.environ.get("MALL_AGENT_TEST_REDIS_URL"),
        reason="需要显式设置 MALL_AGENT_TEST_REDIS_URL 才运行 Redis 集成测试",
    ),
]


@pytest.fixture
async def redis_client():
    url = os.environ["MALL_AGENT_TEST_REDIS_URL"]
    client = redis_asyncio.from_url(url, decode_responses=True)
    prefix = f"mall:agent:it:{uuid.uuid4().hex[:10]}:"
    try:
        await client.ping()
    except Exception:  # pragma: no cover - 环境不可用时跳过而不是失败
        await client.aclose()
        pytest.skip("本地 Redis 不可用，跳过 Redis 集成测试")
    try:
        yield client, prefix
    finally:
        keys = [key async for key in client.scan_iter(match=f"{prefix}*")]
        if keys:
            await client.delete(*keys)
        await client.aclose()


async def test_session_document_round_trip_and_ttl(redis_client) -> None:
    client, prefix = redis_client
    repo = RedisSessionRepository(client, ttl_seconds=86400, max_messages=20)
    key = f"{prefix}session:guest"

    await repo.save(
        key,
        SessionSnapshot(messages=[SessionMessage(role="user", content="有什么手机")]),
    )

    ttl = await client.ttl(key)
    assert 86000 <= ttl <= 86400

    loaded = await repo.load(key)
    assert [item.content for item in loaded.messages] == ["有什么手机"]

    refreshed = await client.ttl(key)
    assert refreshed >= ttl


async def test_session_document_is_a_single_json_value(redis_client) -> None:
    client, prefix = redis_client
    repo = RedisSessionRepository(client, ttl_seconds=86400, max_messages=20)
    key = f"{prefix}session:json"

    await repo.save(key, SessionSnapshot(messages=[SessionMessage(role="user", content="你好")]))

    assert await client.type(key) == "string"
    raw = await client.get(key)
    assert raw is not None
    assert raw.startswith("{")
    assert "authorization" not in raw.lower()
    assert "bearer" not in raw.lower()


async def test_trimming_delete_and_idempotency(redis_client) -> None:
    client, prefix = redis_client
    repo = RedisSessionRepository(client, ttl_seconds=86400, max_messages=5)
    key = f"{prefix}session:trim"

    await repo.save(
        key,
        SessionSnapshot(
            messages=[SessionMessage(role="user", content=f"消息 {i}") for i in range(8)]
        ),
    )

    loaded = await repo.load(key)
    assert [item.content for item in loaded.messages] == [f"消息 {i}" for i in range(3, 8)]

    assert await repo.delete(key) is True
    assert await repo.delete(key) is False
    assert await client.exists(key) == 0


async def test_guest_to_member_migration_deletes_guest_key(redis_client) -> None:
    client, prefix = redis_client
    repo = RedisSessionRepository(client, ttl_seconds=86400, max_messages=20)
    guest_key = f"{prefix}session:guest-migrate"
    member_key = f"{prefix}session:member-migrate"

    await repo.save(
        guest_key,
        SessionSnapshot(messages=[SessionMessage(role="user", content="迁移")]),
    )
    await repo.copy(guest_key, member_key)

    assert await client.exists(guest_key) == 0
    assert [item.content for item in (await repo.load(member_key)).messages] == ["迁移"]


async def test_redis_rate_counter_counts_and_expires(redis_client) -> None:
    client, prefix = redis_client
    counter = RedisRateCounter(client)
    key = f"{prefix}rate:session:test"

    assert await counter.incr(key, 300) == 1
    assert await counter.incr(key, 300) == 2
    ttl = await client.ttl(key)
    assert 0 < ttl <= 300


async def test_redis_backed_limiter_rejects_after_limit(redis_client) -> None:
    client, prefix = redis_client
    limiter = ChatRateLimiter(
        counter=RedisRateCounter(client),
        session_rule=RateLimitRule(limit=2, window_seconds=300),
        ip_rule=RateLimitRule(limit=100, window_seconds=300),
    )
    session_key = f"{prefix}session:limit"
    client_ip = "203.0.113.10"
    session_pattern = f"mall:agent:rate:session:{fingerprint(session_key)}:*"
    ip_pattern = f"mall:agent:rate:ip:{fingerprint(client_ip)}:*"

    try:
        await limiter.check(session_key=session_key, client_ip=client_ip)
        await limiter.check(session_key=session_key, client_ip=client_ip)

        with pytest.raises(RateLimitExceededError):
            await limiter.check(session_key=session_key, client_ip=client_ip)

        session_keys = [key async for key in client.scan_iter(match=session_pattern)]
        assert len(session_keys) == 1
        assert fingerprint(session_key) in session_keys[0]
        assert client_ip not in session_keys[0]
        assert session_key not in session_keys[0]
    finally:
        leftovers = [key async for key in client.scan_iter(match=session_pattern)]
        leftovers += [key async for key in client.scan_iter(match=ip_pattern)]
        if leftovers:
            await client.delete(*leftovers)

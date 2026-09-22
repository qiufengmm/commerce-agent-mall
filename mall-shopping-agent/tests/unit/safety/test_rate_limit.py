from __future__ import annotations

import pytest

from mall_shopping_agent.safety.rate_limit import (
    ChatRateLimiter,
    InMemoryRateCounter,
    RateLimitExceededError,
    RateLimitRule,
    rate_limit_key,
)
from mall_shopping_agent.session.identity import Identity, fingerprint

SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c"
SESSION_KEY = f"mall:agent:session:guest:{SESSION_ID}"
CLIENT_IP = "203.0.113.10"
OTHER_IP = "198.51.100.7"

SESSION_RULE = RateLimitRule(limit=20, window_seconds=300)
IP_RULE = RateLimitRule(limit=60, window_seconds=300)


class Clock:
    def __init__(self, now: float = 1_700_000_000.0) -> None:
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def build_limiter(clock: Clock | None = None) -> tuple[ChatRateLimiter, InMemoryRateCounter]:
    counter = InMemoryRateCounter()
    limiter = ChatRateLimiter(
        counter=counter,
        session_rule=SESSION_RULE,
        ip_rule=IP_RULE,
        clock=clock or Clock(),
    )
    return limiter, counter


def test_rate_limit_key_layout_matches_documented_format() -> None:
    assert rate_limit_key("session", "abc123", 5666666) == "mall:agent:rate:session:abc123:5666666"
    assert rate_limit_key("ip", "def456", 5666666) == "mall:agent:rate:ip:def456:5666666"


async def test_session_allows_exactly_twenty_requests_per_window() -> None:
    limiter, _ = build_limiter()

    for _ in range(20):
        await limiter.check(session_key=SESSION_KEY, client_ip=CLIENT_IP)

    with pytest.raises(RateLimitExceededError) as excinfo:
        await limiter.check(session_key=SESSION_KEY, client_ip=CLIENT_IP)

    assert 1 <= excinfo.value.retry_after_seconds <= 300


async def test_a_rejected_session_request_does_not_consume_the_ip_budget() -> None:
    limiter, counter = build_limiter()

    for _ in range(20):
        await limiter.check(session_key=SESSION_KEY, client_ip=CLIENT_IP)

    with pytest.raises(RateLimitExceededError):
        await limiter.check(session_key=SESSION_KEY, client_ip=OTHER_IP)

    ip_keys = [key for key in counter.keys if ":ip:" in key and fingerprint(OTHER_IP) in key]
    assert ip_keys == []


async def test_ip_allows_exactly_sixty_requests_across_sessions() -> None:
    limiter, _ = build_limiter()

    for index in range(60):
        session_key = f"{SESSION_KEY[:-1]}{'0123456789abcdef'[index % 16]}{index}"
        await limiter.check(session_key=session_key, client_ip=CLIENT_IP)

    with pytest.raises(RateLimitExceededError):
        await limiter.check(session_key="mall:agent:session:guest:other", client_ip=CLIENT_IP)


async def test_raw_ip_and_raw_session_id_never_appear_in_keys() -> None:
    limiter, counter = build_limiter()

    await limiter.check(session_key=SESSION_KEY, client_ip=CLIENT_IP)

    keys = counter.keys
    assert len(keys) == 2
    for key in keys:
        assert CLIENT_IP not in key
        assert SESSION_ID not in key
        assert key.startswith("mall:agent:rate:")


async def test_window_reset_restores_the_budget() -> None:
    clock = Clock()
    limiter, _ = build_limiter(clock)

    for _ in range(20):
        await limiter.check(session_key=SESSION_KEY, client_ip=CLIENT_IP)

    clock.advance(300)

    await limiter.check(session_key=SESSION_KEY, client_ip=CLIENT_IP)


async def test_limits_are_independent_per_session_and_per_ip() -> None:
    limiter, _ = build_limiter()
    other_session = f"mall:agent:session:member:9:{SESSION_ID}"

    for _ in range(20):
        await limiter.check(session_key=SESSION_KEY, client_ip=CLIENT_IP)

    await limiter.check(session_key=other_session, client_ip=OTHER_IP)


async def test_rate_limit_identity_is_hashed_not_reversible() -> None:
    identity = Identity.guest(SESSION_ID)

    assert identity.rate_limit_identity == fingerprint(SESSION_KEY)
    assert len(identity.rate_limit_identity) == 32


async def test_missing_client_ip_falls_back_to_a_hashed_placeholder() -> None:
    limiter, counter = build_limiter()

    await limiter.check(session_key=SESSION_KEY, client_ip="")

    assert any(":ip:" in key for key in counter.keys)
    assert all(":ip:unknown" not in key for key in counter.keys)

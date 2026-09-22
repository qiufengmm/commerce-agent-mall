"""会话与身份层。"""

from __future__ import annotations

from mall_shopping_agent.session.identity import (
    GUEST_SESSION_PREFIX,
    MEMBER_SESSION_PREFIX,
    Identity,
    fingerprint,
    guest_identity,
    member_identity,
    validate_session_id,
)
from mall_shopping_agent.session.memory_repository import MemorySessionRepository
from mall_shopping_agent.session.redis_repository import RedisSessionRepository
from mall_shopping_agent.session.repository import (
    SessionMessage,
    SessionRepository,
    SessionSnapshot,
)

__all__ = [
    "GUEST_SESSION_PREFIX",
    "MEMBER_SESSION_PREFIX",
    "Identity",
    "MemorySessionRepository",
    "RedisSessionRepository",
    "SessionMessage",
    "SessionRepository",
    "SessionSnapshot",
    "fingerprint",
    "guest_identity",
    "member_identity",
    "validate_session_id",
]

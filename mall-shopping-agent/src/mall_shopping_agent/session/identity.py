"""会话身份与 Redis 键规则。

游客与会员使用不同命名空间；会员身份只能由服务端通过 ``/sso/info`` 解析得到，
客户端无法直接提交 ``memberId``。所有进入日志或限流键的值都先做不可逆摘要。
"""

from __future__ import annotations

import hashlib
import uuid
from dataclasses import dataclass
from typing import Literal

SESSION_KEY_PREFIX = "mall:agent:session"
GUEST_SESSION_PREFIX = f"{SESSION_KEY_PREFIX}:guest:"
MEMBER_SESSION_PREFIX = f"{SESSION_KEY_PREFIX}:member:"

IdentityKind = Literal["guest", "member"]


def fingerprint(value: str, *, length: int = 32) -> str:
    """返回不可逆的 SHA-256 摘要前缀，用于日志、限流键等位置。"""

    digest = hashlib.sha256(value.encode("utf-8")).hexdigest()
    return digest[:length]


def validate_session_id(value: object) -> str:
    """校验并规范化 UUID v4，非法输入直接抛 ``ValueError``。"""

    if not isinstance(value, str):
        raise ValueError("sessionId 必须是 UUID v4 字符串")

    text = value.strip().lower()
    if not text:
        raise ValueError("sessionId 不能为空")

    try:
        parsed = uuid.UUID(text)
    except (ValueError, AttributeError) as exc:
        raise ValueError("sessionId 必须是 UUID v4 字符串") from exc

    if parsed.version != 4 or str(parsed) != text:
        raise ValueError("sessionId 必须是规范的 UUID v4 字符串")

    return text


@dataclass(frozen=True, slots=True)
class Identity:
    """一次会话的身份命名空间。"""

    kind: IdentityKind
    session_id: str
    member_id: int | None = None

    @classmethod
    def guest(cls, session_id: object) -> Identity:
        return cls(kind="guest", session_id=validate_session_id(session_id))

    @classmethod
    def member(cls, member_id: object, session_id: object) -> Identity:
        if not isinstance(member_id, int) or isinstance(member_id, bool) or member_id <= 0:
            raise ValueError("member_id 必须是正整数")
        return cls(
            kind="member",
            session_id=validate_session_id(session_id),
            member_id=member_id,
        )

    @property
    def is_member(self) -> bool:
        return self.kind == "member"

    @property
    def session_key(self) -> str:
        if self.kind == "member":
            return f"{MEMBER_SESSION_PREFIX}{self.member_id}:{self.session_id}"
        return f"{GUEST_SESSION_PREFIX}{self.session_id}"

    @property
    def guest_key(self) -> str:
        return f"{GUEST_SESSION_PREFIX}{self.session_id}"

    @property
    def rate_limit_identity(self) -> str:
        return fingerprint(self.session_key)

    @property
    def log_label(self) -> str:
        """日志用标签：不含原始 sessionId，也不含会员 Token。"""

        return f"{self.kind}:{fingerprint(self.session_id, length=12)}"


def guest_identity(session_id: object) -> Identity:
    return Identity.guest(session_id)


def member_identity(member_id: object, session_id: object) -> Identity:
    return Identity.member(member_id, session_id)

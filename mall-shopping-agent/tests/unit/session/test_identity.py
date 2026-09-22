from __future__ import annotations

import uuid

import pytest

from mall_shopping_agent.session.identity import (
    GUEST_SESSION_PREFIX,
    MEMBER_SESSION_PREFIX,
    Identity,
    fingerprint,
    validate_session_id,
)

VALID_SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c"


def test_guest_session_key_uses_documented_namespace() -> None:
    identity = Identity.guest(VALID_SESSION_ID)

    assert identity.session_key == f"{GUEST_SESSION_PREFIX}{VALID_SESSION_ID}"
    assert identity.session_key == f"mall:agent:session:guest:{VALID_SESSION_ID}"
    assert identity.is_member is False
    assert identity.member_id is None


def test_member_session_key_includes_member_id_and_session_id() -> None:
    identity = Identity.member(7, VALID_SESSION_ID)

    assert identity.session_key == f"{MEMBER_SESSION_PREFIX}7:{VALID_SESSION_ID}"
    assert identity.session_key == f"mall:agent:session:member:7:{VALID_SESSION_ID}"
    assert identity.is_member is True


def test_guest_cannot_carry_member_id() -> None:
    identity = Identity.guest(VALID_SESSION_ID)

    with pytest.raises(AttributeError):
        identity.member_id = 7  # type: ignore[misc]


def test_member_identity_rejects_non_positive_member_id() -> None:
    with pytest.raises(ValueError):
        Identity.member(0, VALID_SESSION_ID)
    with pytest.raises(ValueError):
        Identity.member(-3, VALID_SESSION_ID)


def test_session_id_is_normalised_to_canonical_lowercase_form() -> None:
    upper = VALID_SESSION_ID.upper()

    assert validate_session_id(upper) == VALID_SESSION_ID
    assert Identity.guest(f"  {upper}  ").session_id == VALID_SESSION_ID


@pytest.mark.parametrize(
    "value",
    [
        "",
        "   ",
        "not-a-uuid",
        "2dc7b03e-7368-1d6a-a8ef-b0ea16f6c92c",  # UUID v1
        "2dc7b03e73684d6aa8efb0ea16f6c92c",  # 缺少连字符
        "{2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c}",  # 非规范形式
        "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92",
        "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c0",
        None,
        123,
    ],
)
def test_invalid_session_ids_are_rejected(value: object) -> None:
    with pytest.raises((ValueError, TypeError)):
        validate_session_id(value)  # type: ignore[arg-type]


def test_generated_uuid4_is_accepted() -> None:
    value = str(uuid.uuid4())

    assert validate_session_id(value) == value
    assert uuid.UUID(value).version == 4


def test_fingerprint_hides_the_original_value() -> None:
    raw = "203.0.113.10"

    digest = fingerprint(raw)

    assert raw not in digest
    assert len(digest) == 32
    assert fingerprint(raw) == digest
    assert fingerprint("203.0.113.11") != digest


def test_log_label_never_contains_raw_session_id() -> None:
    identity = Identity.member(7, VALID_SESSION_ID)

    assert VALID_SESSION_ID not in identity.log_label
    assert identity.log_label.startswith("member:")


def test_rate_limit_identity_differs_from_raw_session_key() -> None:
    identity = Identity.guest(VALID_SESSION_ID)

    assert identity.rate_limit_identity != identity.session_key
    assert VALID_SESSION_ID not in identity.rate_limit_identity

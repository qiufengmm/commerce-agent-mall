from __future__ import annotations

import os

import pytest
from pydantic import ValidationError

from mall_shopping_agent.config import Settings, is_placeholder


@pytest.fixture(autouse=True)
def _clean_agent_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """隔离宿主环境，避免真实 MALL_AGENT_* 变量影响默认值断言。"""
    for key in list(os.environ):
        if key.startswith("MALL_AGENT_"):
            monkeypatch.delenv(key, raising=False)


def test_settings_only_read_mall_agent_prefix() -> None:
    assert Settings.model_config.get("env_prefix") == "MALL_AGENT_"
    assert Settings.model_config.get("extra") == "ignore"


def test_default_settings_values() -> None:
    settings = Settings()

    assert settings.port == 8086
    assert settings.portal_base_url == "http://localhost:8085"
    assert settings.redis_url == "redis://localhost:6379/0"
    assert settings.session_ttl_seconds == 86400
    assert settings.max_tool_rounds == 4
    assert settings.session_max_messages == 20
    assert settings.rate_limit_session_limit == 20
    assert settings.rate_limit_session_window_seconds == 300
    assert settings.rate_limit_ip_limit == 60
    assert settings.rate_limit_ip_window_seconds == 300
    assert settings.model_mode == "openai"


def test_api_key_can_be_empty_but_model_is_unavailable() -> None:
    settings = Settings()

    assert settings.openai_api_key == ""
    assert settings.model_available is False


def test_settings_read_prefixed_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MALL_AGENT_PORT", "9099")
    monkeypatch.setenv("MALL_AGENT_OPENAI_BASE_URL", "https://api.deepseek.com")
    monkeypatch.setenv("MALL_AGENT_OPENAI_MODEL", "demo-model")
    monkeypatch.setenv("MALL_AGENT_SESSION_TTL_SECONDS", "3600")

    settings = Settings()

    assert settings.port == 9099
    assert settings.chat_completions_url == "https://api.deepseek.com/chat/completions"
    assert settings.openai_model == "demo-model"
    assert settings.session_ttl_seconds == 3600


def test_element_without_prefix_is_ignored(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("PORT", "1234")

    assert Settings().port == 8086


@pytest.mark.parametrize(
    ("base_url", "expected"),
    [
        ("https://api.deepseek.com", "https://api.deepseek.com"),
        ("HTTPS://api.deepseek.com", "https://api.deepseek.com"),
        ("https://api.openai.com/v1/", "https://api.openai.com/v1"),
        ("http://[::1]:8086/v1/", "http://[::1]:8086/v1"),
        (
            "https://gateway.example.com/compatible/v1/",
            "https://gateway.example.com/compatible/v1",
        ),
        (
            "https://gateway.example.com/compatible%20path/v1/",
            "https://gateway.example.com/compatible%20path/v1",
        ),
        (
            "https://gateway.example.com/compatible%2Fv1/",
            "https://gateway.example.com/compatible%2Fv1",
        ),
    ],
)
def test_base_url_preserves_provider_path_and_removes_trailing_slash(
    base_url: str, expected: str
) -> None:
    settings = Settings(openai_base_url=base_url)

    assert settings.openai_base_url == expected
    assert settings.chat_completions_url == f"{expected}/chat/completions"


def test_base_url_rejects_non_http_scheme() -> None:
    with pytest.raises(ValidationError):
        Settings(openai_base_url="ftp://model.example.com/v1")


@pytest.mark.parametrize(
    "base_url",
    [
        "https://model.example.com/v1?query=1",
        "https://model.example.com/v1?",
        "https://model.example.com/v1#fragment",
        "https://model.example.com/v1#",
        "https://user:password@model.example.com/v1",
        "https://model.example.com/path with space",
        " https://model.example.com/v1",
        "https://model.example.com/v1 ",
        "https://:443/v1",
        "https://model.example.com:0/v1",
        "https://model.example.com:70000/v1",
        "https://model.example.com:invalid/v1",
        "https://model.example.com:/v1",
        "https://[::::]/v1",
        "https://[1:2:3:4:5:6:7:8:9]/v1",
        "https://[fe80::1%25Ethernet]/v1",
        "https://gateway[bad].test/v1",
    ],
)
def test_base_url_rejects_unsupported_components(base_url: str) -> None:
    with pytest.raises(ValidationError):
        Settings(openai_base_url=base_url)


def test_placeholder_api_key_is_not_available() -> None:
    settings = Settings(
        model_mode="openai",
        openai_api_key="<请填写模型服务 API Key>",
    )

    assert settings.model_available is False


def test_real_api_key_is_available() -> None:
    settings = Settings(model_mode="openai", openai_api_key="sk-local-development-value")

    assert settings.model_available is True


def test_stub_mode_allows_missing_api_key() -> None:
    settings = Settings(model_mode="stub", openai_api_key="")

    assert settings.model_mode == "stub"
    assert settings.model_available is True


def test_invalid_model_mode_is_rejected() -> None:
    with pytest.raises(ValidationError):
        Settings(model_mode="anthropic")


def test_is_placeholder_detects_blank_and_bracketed_values() -> None:
    assert is_placeholder("") is True
    assert is_placeholder(None) is True
    assert is_placeholder("<token>") is True
    assert is_placeholder("   ") is True
    assert is_placeholder("sk-local-development-value") is False

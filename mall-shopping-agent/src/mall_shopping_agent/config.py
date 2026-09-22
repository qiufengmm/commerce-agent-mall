"""服务配置。

所有配置项统一使用 ``MALL_AGENT_`` 前缀，未配置模型 Key 时服务仍可启动，
但聊天接口会返回 503，而不是让模型凭空生成商品事实。
"""

from __future__ import annotations

import re
from functools import lru_cache
from typing import Literal
from urllib.parse import urlsplit, urlunsplit

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

_BRACKET_PLACEHOLDER = re.compile(r"^<[^>]*>$")

_PLACEHOLDER_MARKERS = (
    "your-",
    "your_",
    "changeme",
    "change-me",
    "change_me",
    "replace-me",
    "replace_me",
    "placeholder",
    "请填写",
    "请生成",
    "<请",
)


def is_placeholder(value: str | None) -> bool:
    """判断配置值是否为占位符或空值。

    占位符判定只用于「是否可用于真实模型调用」以及环境校验脚本，
    不会把配置值本身写入日志或响应。
    """

    if value is None:
        return True

    text = value.strip()
    if not text:
        return True

    if _BRACKET_PLACEHOLDER.match(text):
        return True

    lowered = text.lower()
    return any(marker in lowered for marker in _PLACEHOLDER_MARKERS)


def _require_http_url(value: str, field_name: str) -> str:
    text = (value or "").strip()
    parts = urlsplit(text)
    if parts.scheme not in {"http", "https"} or not parts.netloc:
        raise ValueError(f"{field_name} 必须是包含协议与主机的 http(s) 地址")
    return text


def _normalise_openai_base_url(value: str) -> str:
    text = _require_http_url(value, "openai_base_url")
    parts = urlsplit(text)
    path = parts.path.rstrip("/")
    if not path.endswith("/v1"):
        path = f"{path}/v1"
    return urlunsplit((parts.scheme, parts.netloc, path, "", ""))


class Settings(BaseSettings):
    """进程级配置，全部可通过环境变量覆盖。"""

    model_config = SettingsConfigDict(
        env_prefix="MALL_AGENT_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # 服务
    host: str = "0.0.0.0"
    port: int = Field(default=8086, ge=1, le=65535)
    log_level: str = "INFO"
    cors_allow_origins: str = "*"
    request_timeout_seconds: float = Field(default=35.0, gt=0)

    # 模型
    model_mode: Literal["openai", "stub"] = "openai"
    openai_base_url: str = "https://api.openai.com/v1"
    openai_api_key: str = ""
    openai_model: str = "gpt-4o-mini"
    openai_timeout_seconds: float = Field(default=30.0, gt=0, le=300)
    max_tool_rounds: int = Field(default=4, ge=1, le=8)

    # 门户
    portal_base_url: str = "http://localhost:8085"
    portal_timeout_seconds: float = Field(default=10.0, gt=0)

    # Redis 会话
    redis_url: str = "redis://localhost:6379/0"
    session_ttl_seconds: int = Field(default=86400, ge=60)
    session_max_messages: int = Field(default=20, ge=2, le=100)

    # 限流
    rate_limit_session_limit: int = Field(default=20, ge=1)
    rate_limit_session_window_seconds: int = Field(default=300, ge=1)
    rate_limit_ip_limit: int = Field(default=60, ge=1)
    rate_limit_ip_window_seconds: int = Field(default=300, ge=1)

    # 上下文裁剪
    tool_result_max_chars: int = Field(default=4000, ge=200)
    context_max_chars: int = Field(default=16000, ge=1000)

    @field_validator("openai_base_url")
    @classmethod
    def _validate_openai_base_url(cls, value: str) -> str:
        return _normalise_openai_base_url(value)

    @field_validator("portal_base_url")
    @classmethod
    def _validate_portal_base_url(cls, value: str) -> str:
        return _require_http_url(value, "portal_base_url").rstrip("/")

    @field_validator("redis_url")
    @classmethod
    def _validate_redis_url(cls, value: str) -> str:
        text = (value or "").strip()
        parts = urlsplit(text)
        if parts.scheme not in {"redis", "rediss"}:
            raise ValueError("redis_url 必须是 redis:// 或 rediss:// 地址")
        return text

    @property
    def chat_completions_url(self) -> str:
        return f"{self.openai_base_url}/chat/completions"

    @property
    def cors_origin_list(self) -> list[str]:
        raw = (self.cors_allow_origins or "").strip()
        if not raw or raw == "*":
            return ["*"]
        return [item.strip() for item in raw.split(",") if item.strip()]

    @property
    def model_available(self) -> bool:
        """当前配置是否可用于真实模型调用。

        Stub 模式不需要 Key；OpenAI 兼容模式必须提供非占位 Key 与模型名。
        """

        if self.model_mode == "stub":
            return True
        return not is_placeholder(self.openai_api_key) and not is_placeholder(self.openai_model)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()

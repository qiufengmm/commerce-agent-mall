"""HTTP 请求与响应模型。

请求模型禁止未知字段，客户端无法提交 ``memberId``、``token``、``url`` 等控制字段。
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel

from mall_shopping_agent.session.identity import validate_session_id

MAX_MESSAGE_CHARS = 1000

_CAMEL_CONFIG = ConfigDict(
    alias_generator=to_camel,
    populate_by_name=True,
    extra="forbid",
    strict=True,
)

_RESPONSE_CONFIG = ConfigDict(
    alias_generator=to_camel,
    populate_by_name=True,
    extra="ignore",
)


class ChatRequest(BaseModel):
    model_config = _CAMEL_CONFIG

    session_id: str
    message: str = Field(min_length=1, max_length=MAX_MESSAGE_CHARS)

    @field_validator("session_id")
    @classmethod
    def _validate_session_id(cls, value: str) -> str:
        return validate_session_id(value)

    @field_validator("message", mode="before")
    @classmethod
    def _trim_message(cls, value: object) -> object:
        return value.strip() if isinstance(value, str) else value


class ProductCard(BaseModel):
    """服务端构造的商品卡片，模型无法提供其中任何字段。"""

    model_config = _RESPONSE_CONFIG

    id: int
    name: str = ""
    pic: str | None = None
    price: str | None = None
    subtitle: str | None = None
    stock_status: str
    available_stock: int
    detail_path: str


class SessionMessageOut(BaseModel):
    model_config = _RESPONSE_CONFIG

    role: Literal["user", "assistant"]
    content: str


class ChatData(BaseModel):
    model_config = _RESPONSE_CONFIG

    session_id: str
    message_id: str
    answer: str
    products: list[ProductCard] = Field(default_factory=list)
    requires_login: bool = False
    suggested_questions: list[str] = Field(default_factory=list)


class SessionData(BaseModel):
    model_config = _RESPONSE_CONFIG

    session_id: str
    messages: list[SessionMessageOut] = Field(default_factory=list)
    products: list[ProductCard] = Field(default_factory=list)
    requires_login: bool = False


class DeleteSessionData(BaseModel):
    model_config = _RESPONSE_CONFIG

    session_id: str
    deleted: bool = True
    requires_login: bool = False

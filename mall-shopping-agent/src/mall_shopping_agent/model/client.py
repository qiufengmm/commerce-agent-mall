"""模型客户端协议与错误类型。

错误类型自带 ``http_status``，供 HTTP 层直接映射为错误契约；
异常正文永远不包含 API Key、Authorization 或上游原始响应。
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from mall_shopping_agent.model.schemas import (
    ModelMessage,
    ModelRequest,
    ModelResponse,
    ModelUsage,
    ToolCall,
)

__all__ = [
    "ModelClient",
    "ModelError",
    "ModelMessage",
    "ModelProtocolError",
    "ModelRequest",
    "ModelResponse",
    "ModelTimeoutError",
    "ModelUnavailableError",
    "ModelUpstreamError",
    "ModelUsage",
    "ToolCall",
]


class ModelError(Exception):
    """模型层错误基类。"""

    http_status: int = 502
    code: str = "MODEL_UPSTREAM_ERROR"

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class ModelUnavailableError(ModelError):
    """模型未配置或鉴权失败，聊天接口映射为 503。"""

    http_status = 503
    code = "MODEL_UNAVAILABLE"


class ModelProtocolError(ModelUnavailableError):
    """响应结构非法或缺少工具调用能力，聊天接口映射为 503。"""

    code = "MODEL_PROTOCOL_ERROR"


class ModelTimeoutError(ModelError):
    """读取/总请求超时，聊天接口映射为 502。"""

    http_status = 502
    code = "MODEL_TIMEOUT"


class ModelUpstreamError(ModelError):
    """连接失败或不可重试的上游错误，聊天接口映射为 502。"""

    http_status = 502
    code = "MODEL_UPSTREAM_ERROR"


@runtime_checkable
class ModelClient(Protocol):
    """模型客户端协议，便于用 Stub 或 Fake 替换。"""

    async def complete(self, request: ModelRequest) -> ModelResponse: ...

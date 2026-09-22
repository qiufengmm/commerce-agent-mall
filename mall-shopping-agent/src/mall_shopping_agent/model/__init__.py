"""模型适配层。"""

from __future__ import annotations

from mall_shopping_agent.model.client import (
    ModelClient,
    ModelError,
    ModelProtocolError,
    ModelTimeoutError,
    ModelUnavailableError,
    ModelUpstreamError,
)
from mall_shopping_agent.model.openai_compatible import OpenAICompatibleClient
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
    "OpenAICompatibleClient",
    "ToolCall",
]

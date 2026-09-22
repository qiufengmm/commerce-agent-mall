"""智能体编排层。"""

from __future__ import annotations

from mall_shopping_agent.agent.orchestrator import (
    FALLBACK_ANSWER,
    LOGIN_REQUIRED_ANSWER,
    PRODUCT_SELECTION_PATTERN,
    ROUND_LIMIT_ANSWER,
    ShoppingAgentOrchestrator,
    build_suggested_questions,
    parse_product_selection,
    strip_internal_markers,
)
from mall_shopping_agent.agent.prompt import build_conversation, build_system_prompt
from mall_shopping_agent.agent.types import (
    DEFAULT_SUGGESTED_QUESTIONS,
    AgentError,
    AgentLimits,
    AgentTurnRequest,
    AgentTurnResult,
    ToolCallRecord,
    ToolRoundLimitError,
)

__all__ = [
    "DEFAULT_SUGGESTED_QUESTIONS",
    "FALLBACK_ANSWER",
    "LOGIN_REQUIRED_ANSWER",
    "PRODUCT_SELECTION_PATTERN",
    "ROUND_LIMIT_ANSWER",
    "AgentError",
    "AgentLimits",
    "AgentTurnRequest",
    "AgentTurnResult",
    "ShoppingAgentOrchestrator",
    "ToolCallRecord",
    "ToolRoundLimitError",
    "build_conversation",
    "build_suggested_questions",
    "build_system_prompt",
    "parse_product_selection",
    "strip_internal_markers",
]

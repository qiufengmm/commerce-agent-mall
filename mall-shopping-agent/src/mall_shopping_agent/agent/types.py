"""编排层的输入输出类型与错误。"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from mall_shopping_agent.session.identity import Identity
from mall_shopping_agent.session.repository import SessionSnapshot

DEFAULT_SUGGESTED_QUESTIONS: tuple[str, ...] = (
    "3000 元左右有哪些手机？",
    "比较一下前两款的规格",
    "哪款库存更充足？",
    "这个商品支持哪些优惠？",
)


@dataclass(frozen=True, slots=True)
class AgentLimits:
    """单次会话的硬边界。"""

    max_tool_rounds: int = 4
    max_messages: int = 20
    tool_result_max_chars: int = 4000
    context_max_chars: int = 16000
    max_cards: int = 5
    max_suggested_questions: int = 3


@dataclass(slots=True)
class AgentTurnRequest:
    message: str
    identity: Identity
    session: SessionSnapshot
    authorization: str | None = None


@dataclass(frozen=True, slots=True)
class ToolCallRecord:
    name: str
    status: str
    ok: bool


@dataclass(slots=True)
class AgentTurnResult:
    answer: str
    products: list[dict[str, Any]] = field(default_factory=list)
    requires_login: bool = False
    suggested_questions: list[str] = field(default_factory=list)
    tool_calls: list[ToolCallRecord] = field(default_factory=list)
    tool_rounds: int = 0
    stopped_reason: str = "ANSWER"


class AgentError(Exception):
    """编排层错误基类。"""

    http_status: int = 502
    code: str = "AGENT_ERROR"

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class ToolRoundLimitError(AgentError):
    """超过工具调用轮数上限，聊天接口映射为 422。"""

    http_status = 422
    code = "TOOL_ROUND_LIMIT"

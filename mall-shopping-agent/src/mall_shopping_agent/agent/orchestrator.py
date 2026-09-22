"""智能体编排循环。

非流式、最多 4 轮工具调用；每个工具调用先经注册表校验再执行；工具失败以结构化
错误反馈给模型，但不会包装成成功事实。商品卡片只由服务端工具事实构造。
"""

from __future__ import annotations

import re
from collections.abc import Sequence
from typing import Any

from mall_shopping_agent.agent.prompt import build_conversation
from mall_shopping_agent.agent.types import (
    DEFAULT_SUGGESTED_QUESTIONS,
    AgentLimits,
    AgentTurnRequest,
    AgentTurnResult,
    ToolCallRecord,
    ToolRoundLimitError,
)
from mall_shopping_agent.model.client import ModelClient
from mall_shopping_agent.model.schemas import ModelMessage, ModelRequest
from mall_shopping_agent.presentation.products import PresentationBuilder, ProductFactCollector
from mall_shopping_agent.safety.fencing import fence_json
from mall_shopping_agent.safety.policy import detect_refusal, refusal_answer
from mall_shopping_agent.session.repository import (
    MAX_MESSAGE_CHARS,
    SessionMessage,
    SessionSnapshot,
)
from mall_shopping_agent.tools.registry import ToolContext, ToolError, ToolRegistry, ToolStatus

PRODUCT_SELECTION_PATTERN = re.compile(r"\[\[MALL_PRODUCTS:\s*([^\]]*)\]\]")

FALLBACK_ANSWER = "抱歉，我暂时无法根据当前商品信息给出回答，请换个说法或缩小问题范围再试一次。"
ROUND_LIMIT_ANSWER = (
    "本次问题涉及的信息过多，请缩小范围后重试，例如只比较两件商品或指定一个关键词。"
)
LOGIN_REQUIRED_ANSWER = "查询您本人的优惠券信息需要先登录，登录后可以继续当前对话。"
MAX_QUESTION_CHARS = 30
MAX_QUESTION_SOURCE_CHARS = 60


def parse_product_selection(text: str | None) -> list[int]:
    """解析模型给出的候选商品 ID，非法值会被忽略。"""

    selected: list[int] = []
    for match in PRODUCT_SELECTION_PATTERN.finditer(text or ""):
        for chunk in match.group(1).split(","):
            chunk = chunk.strip()
            if not chunk.isdigit():
                continue
            value = int(chunk)
            if value > 0 and value not in selected:
                selected.append(value)
    return selected


def strip_internal_markers(text: str | None) -> str:
    return PRODUCT_SELECTION_PATTERN.sub("", text or "").strip()


def _total_chars(messages: Sequence[ModelMessage]) -> int:
    total = 0
    for message in messages:
        total += len(message.content or "")
        for call in message.tool_calls:
            total += len(call.arguments) + len(call.name)
    return total


def _advance_to_next_user(messages: list[ModelMessage]) -> list[ModelMessage]:
    for index in range(1, len(messages)):
        if messages[index].role == "user":
            return messages[index:]
    return messages


class ShoppingAgentOrchestrator:
    def __init__(
        self,
        *,
        model: ModelClient,
        registry: ToolRegistry,
        backend: Any,
        limits: AgentLimits | None = None,
    ) -> None:
        self._model = model
        self._registry = registry
        self._backend = backend
        self._limits = limits or AgentLimits()
        self._builder = PresentationBuilder(max_cards=self._limits.max_cards)

    # -- 上下文裁剪 -------------------------------------------------------

    def _trim_context(self, messages: list[ModelMessage]) -> list[ModelMessage]:
        system = [message for message in messages if message.role == "system"]
        rest = [message for message in messages if message.role != "system"]

        user_boundaries = [index for index, item in enumerate(rest) if item.role == "user"]
        if user_boundaries:
            start = user_boundaries[0]
            for index in user_boundaries:
                start = index
                if len(rest) - index <= self._limits.max_messages:
                    break
            rest = rest[start:]
        elif len(rest) > self._limits.max_messages:
            rest = rest[-self._limits.max_messages :]

        while _total_chars(rest) > self._limits.context_max_chars:
            advanced = _advance_to_next_user(rest)
            if advanced is rest or len(advanced) == len(rest):
                break
            rest = advanced

        return [*system, *rest]

    # -- 主流程 -----------------------------------------------------------

    async def run(self, request: AgentTurnRequest) -> AgentTurnResult:
        # 交易写操作直接拒绝：不调用模型，也不触发任何工具
        refusal = detect_refusal(request.message)
        if refusal is not None:
            return AgentTurnResult(
                answer=refusal_answer(refusal),
                products=[],
                requires_login=False,
                suggested_questions=list(DEFAULT_SUGGESTED_QUESTIONS)[
                    : self._limits.max_suggested_questions
                ],
                tool_calls=[],
                tool_rounds=0,
                stopped_reason="REFUSAL",
            )

        messages = build_conversation(
            request.session.messages,
            request.message,
            max_tool_rounds=self._limits.max_tool_rounds,
            max_messages=self._limits.max_messages,
        )
        context = ToolContext(backend=self._backend, authorization=request.authorization)
        tools = self._registry.openai_tools()

        collector = ProductFactCollector()
        records: list[ToolCallRecord] = []
        requires_login = False
        rounds = 0
        answer_text = ""
        stopped_reason = "ANSWER"

        while True:
            messages = self._trim_context(messages)
            response = await self._model.complete(
                ModelRequest(messages=messages, tools=tools, tool_choice="auto")
            )

            if not response.tool_calls:
                answer_text = response.content or ""
                break

            if rounds >= self._limits.max_tool_rounds:
                raise ToolRoundLimitError(
                    f"工具调用超过 {self._limits.max_tool_rounds} 轮，本次问题需要缩小范围"
                )

            rounds += 1
            messages.append(
                ModelMessage.assistant(response.content, tool_calls=response.tool_calls)
            )

            for call in response.tool_calls:
                try:
                    result = await self._registry.invoke(call, context=context)
                except ToolError as exc:
                    records.append(ToolCallRecord(name=call.name, status=exc.code, ok=False))
                    messages.append(
                        ModelMessage.tool(
                            call.id,
                            fence_json(
                                {
                                    "tool": call.name,
                                    "status": "REJECTED",
                                    "errorCode": exc.code,
                                    "errorMessage": exc.message,
                                },
                                label="tool_error",
                                max_chars=800,
                            ),
                        )
                    )
                    continue

                records.append(
                    ToolCallRecord(name=result.name, status=str(result.status), ok=result.ok)
                )
                if result.status is ToolStatus.LOGIN_REQUIRED:
                    requires_login = True
                collector.add(result)
                messages.append(
                    ModelMessage.tool(
                        call.id,
                        result.to_model_content(max_chars=self._limits.tool_result_max_chars),
                    )
                )

            if requires_login:
                # 会员身份失效时不再继续调用模型，避免拿无效 Token 反复请求会员接口
                answer_text = LOGIN_REQUIRED_ANSWER
                stopped_reason = "LOGIN_REQUIRED"
                break

        if stopped_reason == "ANSWER" and rounds >= self._limits.max_tool_rounds:
            stopped_reason = "ROUNDS_EXHAUSTED"

        facts = collector.facts
        clean_answer = strip_internal_markers(answer_text) or FALLBACK_ANSWER
        products = self._builder.build(parse_product_selection(answer_text), facts)
        questions = build_suggested_questions(
            cards=products,
            requires_login=requires_login,
            answer=clean_answer,
            limit=self._limits.max_suggested_questions,
        )

        return AgentTurnResult(
            answer=clean_answer[:MAX_MESSAGE_CHARS],
            products=products,
            requires_login=requires_login,
            suggested_questions=questions,
            tool_calls=records,
            tool_rounds=rounds,
            stopped_reason=stopped_reason,
        )

    # -- 会话落库 ---------------------------------------------------------

    def build_session_update(
        self,
        request: AgentTurnRequest,
        result: AgentTurnResult,
    ) -> SessionSnapshot:
        """只保存用户消息、助手摘要与最近一组卡片，不保存工具或模型原始响应。"""

        messages = [
            *request.session.messages,
            SessionMessage(role="user", content=request.message[:MAX_MESSAGE_CHARS]),
            SessionMessage(role="assistant", content=result.answer[:MAX_MESSAGE_CHARS]),
        ]
        snapshot = SessionSnapshot(messages=messages, products=result.products)
        return snapshot.trimmed(self._limits.max_messages)

    def round_limit_answer(self) -> str:
        return ROUND_LIMIT_ANSWER


def build_suggested_questions(
    *,
    cards: Sequence[dict[str, Any]],
    requires_login: bool,
    answer: str,
    limit: int = 3,
) -> list[str]:
    """受控模板 + 模型文本共同产生建议问题，最多 ``limit`` 条。"""

    if requires_login:
        templates = [
            "登录后我的优惠券怎么用？",
            "登录后能查哪些优惠？",
            "登录后我的优惠券还有效吗？",
        ]
    elif len(cards) >= 2:
        templates = ["比较这几款的规格", "哪款库存更充足？", "有没有更便宜的同类商品？"]
    elif len(cards) == 1:
        templates = ["这款有哪些规格？", "这款的库存和发货情况如何？", "有没有同价位的其他选择？"]
    else:
        templates = ["3000 元左右有哪些手机？", "换个关键词再搜一次", "这个商品支持哪些优惠？"]

    questions: list[str] = list(templates)

    for line in reversed((answer or "").splitlines()):
        text = line.strip()
        if (
            2 <= len(text) <= MAX_QUESTION_SOURCE_CHARS
            and text.endswith(("？", "?"))
            and text not in questions
        ):
            questions.append(text)
            break

    return [question[:MAX_QUESTION_CHARS] for question in questions[: max(1, limit)]]

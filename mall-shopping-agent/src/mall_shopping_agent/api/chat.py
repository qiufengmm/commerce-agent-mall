"""聊天与会话接口。"""

from __future__ import annotations

import asyncio
import logging
import uuid

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from mall_shopping_agent.agent.types import (
    DEFAULT_SUGGESTED_QUESTIONS,
    AgentTurnRequest,
    ToolRoundLimitError,
)
from mall_shopping_agent.api.deps import AppServices, get_services
from mall_shopping_agent.api.errors import ApiError, envelope
from mall_shopping_agent.api.schemas import (
    ChatData,
    ChatRequest,
    DeleteSessionData,
    ProductCard,
    SessionData,
    SessionMessageOut,
)
from mall_shopping_agent.config import Settings
from mall_shopping_agent.model.client import (
    ModelError,
    ModelProtocolError,
    ModelUnavailableError,
)
from mall_shopping_agent.session.identity import Identity, fingerprint, validate_session_id
from mall_shopping_agent.session.repository import (
    MAX_MESSAGE_CHARS,
    SessionMessage,
)
from mall_shopping_agent.storefront.backend import (
    MemberUnauthorizedError,
    StorefrontError,
)

logger = logging.getLogger("mall_shopping_agent.api.chat")

router = APIRouter(tags=["agent"])

LOGIN_REQUIRED_ANSWER = "登录状态已失效，请重新登录后继续当前对话，登录前的提问不会被清空。"
MODEL_UNAVAILABLE_MESSAGE = "智能导购暂时不可用：模型服务未配置或不可用。"
MODEL_FAILED_MESSAGE = "智能导购响应失败，请稍后再试。"
STOREFRONT_FAILED_MESSAGE = "商品数据暂时无法获取，请稍后再试。"
AGENT_TIMEOUT_MESSAGE = "智能导购响应超时，请稍后再试。"


def _settings(request: Request) -> Settings:
    return request.app.state.settings


def _authorization(request: Request) -> str | None:
    """原样透传移动端保存的完整 Bearer Token，不做解析或改写。"""

    raw = request.headers.get("authorization")
    if raw is None:
        return None
    value = raw.strip()
    return value or None


def _login_required_response(session_id: str) -> JSONResponse:
    data = ChatData(
        session_id=session_id,
        message_id=str(uuid.uuid4()),
        answer=LOGIN_REQUIRED_ANSWER,
        products=[],
        requires_login=True,
        suggested_questions=list(DEFAULT_SUGGESTED_QUESTIONS)[:2],
    )
    return JSONResponse(
        status_code=200,
        content=envelope(200, "操作成功", data.model_dump(by_alias=True)),
    )


def _model_error_message(exc: ModelError) -> str:
    if isinstance(exc, ModelUnavailableError | ModelProtocolError):
        return MODEL_UNAVAILABLE_MESSAGE
    return MODEL_FAILED_MESSAGE


async def _resolve_identity(
    services: AppServices,
    session_id: str,
    authorization: str | None,
) -> tuple[Identity | None, bool]:
    """解析身份；返回 ``(None, True)`` 表示 Token 无效需要重新登录。"""

    if not authorization:
        return Identity.guest(session_id), False

    try:
        member = await services.backend.resolve_member(authorization)
    except MemberUnauthorizedError:
        return None, True

    identity = Identity.member(member.member_id, session_id)
    await _migrate_guest_session(services, session_id, identity)
    return identity, False


async def _remember_login_required_turn(
    services: AppServices,
    settings: Settings,
    session_id: str,
    message: str,
) -> None:
    """登录失效时把提问记到游客命名空间，登录回跳后可以恢复。"""

    guest_key = Identity.guest(session_id).session_key
    try:
        snapshot = await services.session_repository.load(guest_key)
        updated = snapshot.with_appended(
            [
                SessionMessage(role="user", content=message[:MAX_MESSAGE_CHARS]),
                SessionMessage(role="assistant", content=LOGIN_REQUIRED_ANSWER),
            ],
            settings.session_max_messages,
        )
        await services.session_repository.save(guest_key, updated)
    except Exception as exc:
        # 记录失败不能影响 requiresLogin 返回，只记录异常类型
        logger.warning("记录待登录提问失败：%s", type(exc).__name__)


async def _migrate_guest_session(
    services: AppServices,
    session_id: str,
    identity: Identity,
) -> None:
    """仅在本次请求同时持有有效 Token 时迁移游客会话。

    会员命名空间已经有会话时不覆盖，也不删除游客键，避免丢失登录前的提问。
    """

    guest_key = Identity.guest(session_id).session_key
    member_snapshot = await services.session_repository.load(identity.session_key)
    if member_snapshot.messages or member_snapshot.products:
        return
    await services.session_repository.copy(guest_key, identity.session_key)


@router.post("/agent/chat")
async def chat(payload: ChatRequest, request: Request) -> JSONResponse:
    services = get_services(request)
    settings = _settings(request)
    session_id = payload.session_id
    authorization = _authorization(request)

    if not settings.model_available:
        raise ApiError(503, "MODEL_UNAVAILABLE", MODEL_UNAVAILABLE_MESSAGE)

    identity, requires_login = await _resolve_identity(services, session_id, authorization)
    if requires_login or identity is None:
        await _remember_login_required_turn(services, settings, session_id, payload.message)
        return _login_required_response(session_id)

    in_flight_key = f"{identity.session_key}:{fingerprint(payload.message)}"
    if not await services.in_flight.acquire(in_flight_key):
        raise ApiError(409, "DUPLICATE_REQUEST", "相同请求正在处理中，请勿重复提交。")

    try:
        client_ip = request.client.host if request.client else ""
        await services.rate_limiter.check(
            session_key=identity.session_key,
            client_ip=client_ip,
        )

        session = await services.session_repository.load(identity.session_key)
        turn_request = AgentTurnRequest(
            message=payload.message,
            identity=identity,
            session=session,
            authorization=authorization if identity.is_member else None,
        )

        try:
            async with asyncio.timeout(settings.request_timeout_seconds):
                result = await services.orchestrator.run(turn_request)
        except TimeoutError as exc:
            raise ApiError(502, "AGENT_TIMEOUT", AGENT_TIMEOUT_MESSAGE) from exc
        except ToolRoundLimitError as exc:
            raise ApiError(422, exc.code, exc.message) from exc
        except ModelError as exc:
            raise ApiError(exc.http_status, exc.code, _model_error_message(exc)) from exc
        except StorefrontError as exc:
            raise ApiError(502, exc.code, STOREFRONT_FAILED_MESSAGE) from exc

        snapshot = services.orchestrator.build_session_update(turn_request, result)
        await services.session_repository.save(identity.session_key, snapshot)
    finally:
        services.in_flight.release(in_flight_key)

    data = ChatData(
        session_id=session_id,
        message_id=str(uuid.uuid4()),
        answer=result.answer,
        products=[ProductCard.model_validate(card) for card in result.products],
        requires_login=result.requires_login,
        suggested_questions=result.suggested_questions,
    )
    return JSONResponse(
        status_code=200,
        content=envelope(200, "操作成功", data.model_dump(by_alias=True)),
    )


@router.get("/agent/session/{session_id}")
async def get_session(session_id: str, request: Request) -> JSONResponse:
    services = get_services(request)
    normalized = _validated_session_id(session_id)

    identity, requires_login = await _resolve_identity(
        services, normalized, _authorization(request)
    )
    if requires_login or identity is None:
        data = SessionData(session_id=normalized, requires_login=True)
        return JSONResponse(
            status_code=200,
            content=envelope(200, "操作成功", data.model_dump(by_alias=True)),
        )

    snapshot = await services.session_repository.load(identity.session_key)
    data = SessionData(
        session_id=normalized,
        messages=[
            SessionMessageOut(role=message.role, content=message.content)
            for message in snapshot.messages
        ],
        products=[ProductCard.model_validate(card) for card in snapshot.products],
        requires_login=False,
    )
    return JSONResponse(
        status_code=200,
        content=envelope(200, "操作成功", data.model_dump(by_alias=True)),
    )


@router.delete("/agent/session/{session_id}")
async def delete_session(session_id: str, request: Request) -> JSONResponse:
    services = get_services(request)
    normalized = _validated_session_id(session_id)

    identity, requires_login = await _resolve_identity(
        services, normalized, _authorization(request)
    )
    if requires_login or identity is None:
        data = DeleteSessionData(session_id=normalized, deleted=False, requires_login=True)
        return JSONResponse(
            status_code=200,
            content=envelope(200, "操作成功", data.model_dump(by_alias=True)),
        )

    await services.session_repository.delete(identity.session_key)
    data = DeleteSessionData(session_id=normalized, deleted=True, requires_login=False)
    return JSONResponse(
        status_code=200,
        content=envelope(200, "操作成功", data.model_dump(by_alias=True)),
    )


def _validated_session_id(session_id: str) -> str:
    try:
        return validate_session_id(session_id)
    except ValueError as exc:
        raise ApiError(400, "INVALID_SESSION_ID", "sessionId 必须是 UUID v4。") from exc

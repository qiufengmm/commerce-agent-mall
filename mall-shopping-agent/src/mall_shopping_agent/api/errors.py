"""统一响应封装与异常处理。

响应体固定为 ``code`` / ``message`` / ``data``；堆栈、内部地址、上游正文与凭据
都不会返回前端。
"""

from __future__ import annotations

import logging
import uuid
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from mall_shopping_agent.safety.rate_limit import RateLimitExceededError

logger = logging.getLogger("mall_shopping_agent.api")

GENERIC_ERROR_MESSAGE = "服务内部错误，请稍后再试。"

_HTTP_STATUS_MESSAGES = {
    400: "请求参数不合法。",
    404: "请求的接口不存在。",
    405: "请求方法不被允许。",
    409: "相同请求正在处理中，请勿重复提交。",
    422: "本次问题需要缩小范围后重试。",
    429: "请求过于频繁，请稍后再试。",
    502: "上游服务暂时不可用，请稍后再试。",
    503: "智能导购暂时不可用，请稍后再试。",
}


def envelope(code: int, message: str, data: Any = None) -> dict[str, Any]:
    return {"code": code, "message": message, "data": data}


class ApiError(Exception):
    """业务错误，携带最终 HTTP 状态码与稳定错误码。"""

    def __init__(self, http_status: int, code: str, message: str, data: Any = None) -> None:
        super().__init__(message)
        self.http_status = http_status
        self.code = code
        self.message = message
        self.data = data


def _validation_details(exc: RequestValidationError) -> list[dict[str, str]]:
    """只返回字段位置与错误类型，不回显用户提交的原始内容。"""

    details: list[dict[str, str]] = []
    for error in exc.errors()[:5]:
        location = [
            str(part) for part in error.get("loc", ()) if part not in ("body", "path", "query")
        ]
        details.append(
            {
                "field": ".".join(location) or "request",
                "type": str(error.get("type", "invalid")),
            }
        )
    return details


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _handle_api_error(_request: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.http_status,
            content=envelope(exc.http_status, exc.message, exc.data),
        )

    @app.exception_handler(RequestValidationError)
    async def _handle_validation_error(
        _request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=400,
            content=envelope(400, "请求参数不合法。", {"details": _validation_details(exc)}),
        )

    @app.exception_handler(RateLimitExceededError)
    async def _handle_rate_limit(_request: Request, exc: RateLimitExceededError) -> JSONResponse:
        return JSONResponse(
            status_code=429,
            content=envelope(429, exc.message),
            headers={"Retry-After": str(exc.retry_after_seconds)},
        )

    @app.exception_handler(StarletteHTTPException)
    async def _handle_http_exception(
        _request: Request, exc: StarletteHTTPException
    ) -> JSONResponse:
        status = exc.status_code
        message = _HTTP_STATUS_MESSAGES.get(status, "请求失败，请稍后再试。")
        return JSONResponse(status_code=status, content=envelope(status, message))

    @app.exception_handler(Exception)
    async def _handle_unexpected(_request: Request, exc: Exception) -> JSONResponse:
        trace_id = uuid.uuid4().hex
        # 只记录异常类型与 traceId，不记录堆栈正文以外的敏感内容
        logger.error("未处理异常 traceId=%s type=%s", trace_id, type(exc).__name__)
        return JSONResponse(
            status_code=500,
            content=envelope(500, GENERIC_ERROR_MESSAGE, {"traceId": trace_id}),
        )

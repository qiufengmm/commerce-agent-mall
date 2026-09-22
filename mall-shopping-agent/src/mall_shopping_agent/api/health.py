"""健康检查接口。

``/health/live`` 只表示进程可响应；``/health/ready`` 额外要求 Redis 与
``mall-portal`` 探针成功。模型 Key 未配置不阻止就绪，只在聊天接口返回 503。
响应体不包含内部 URL、Key 或异常正文。
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse


@dataclass(frozen=True, slots=True)
class HealthProbes:
    """可替换的就绪探针，测试中可直接注入布尔结果。"""

    redis: Callable[[], Awaitable[bool]]
    portal: Callable[[], Awaitable[bool]]


router = APIRouter(tags=["health"])


async def _safe_probe(probe: Callable[[], Awaitable[bool]]) -> bool:
    try:
        return bool(await probe())
    except Exception:
        # 探针失败只表示未就绪，不能把上游地址或异常正文暴露给调用方
        return False


@router.get("/health/live")
async def live() -> dict[str, object]:
    return {"code": 200, "message": "操作成功", "data": {"status": "UP"}}


@router.get("/health/ready")
async def ready(request: Request) -> JSONResponse:
    probes: HealthProbes = request.app.state.health_probes

    redis_ok = await _safe_probe(probes.redis)
    portal_ok = await _safe_probe(probes.portal)

    if redis_ok and portal_ok:
        return JSONResponse(
            status_code=200,
            content={"code": 200, "message": "操作成功", "data": {"status": "UP"}},
        )

    return JSONResponse(
        status_code=503,
        content={"code": 503, "message": "服务未就绪", "data": {"status": "DOWN"}},
    )

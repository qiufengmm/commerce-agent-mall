"""应用依赖容器与依赖注入访问器。"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from typing import Any

from fastapi import Request

from mall_shopping_agent.agent.orchestrator import ShoppingAgentOrchestrator
from mall_shopping_agent.api.errors import ApiError
from mall_shopping_agent.model.client import ModelClient
from mall_shopping_agent.safety.rate_limit import ChatRateLimiter
from mall_shopping_agent.session.repository import SessionRepository
from mall_shopping_agent.storefront.backend import StorefrontBackend


class InFlightGuard:
    """阻止同一会话的相同问题并发重复执行。

    该保护只在单进程内生效；多副本部署时由前端防重复提交与限流共同覆盖。
    """

    def __init__(self) -> None:
        self._keys: set[str] = set()
        self._lock = asyncio.Lock()

    async def acquire(self, key: str) -> bool:
        async with self._lock:
            if key in self._keys:
                return False
            self._keys.add(key)
            return True

    def release(self, key: str) -> None:
        self._keys.discard(key)

    @property
    def keys(self) -> set[str]:
        return set(self._keys)


@dataclass(slots=True)
class AppServices:
    """可整体替换的运行时依赖。"""

    backend: StorefrontBackend
    session_repository: SessionRepository
    model: ModelClient
    rate_limiter: ChatRateLimiter
    orchestrator: ShoppingAgentOrchestrator
    in_flight: InFlightGuard = field(default_factory=InFlightGuard)
    redis_client: Any | None = None


def get_services(request: Request) -> AppServices:
    services = getattr(request.app.state, "services", None)
    if services is None:
        raise ApiError(503, "SERVICE_NOT_READY", "服务正在启动，请稍后重试。")
    return services

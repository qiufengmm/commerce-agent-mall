"""FastAPI 测试应用装配。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from fastapi import FastAPI

from mall_shopping_agent.agent.orchestrator import ShoppingAgentOrchestrator
from mall_shopping_agent.agent.types import AgentLimits
from mall_shopping_agent.api.health import HealthProbes
from mall_shopping_agent.config import Settings
from mall_shopping_agent.main import AppServices, create_app
from mall_shopping_agent.safety.rate_limit import (
    ChatRateLimiter,
    InMemoryRateCounter,
    RateLimitRule,
)
from mall_shopping_agent.session.memory_repository import MemorySessionRepository
from mall_shopping_agent.tools import create_tool_registry
from support.fake_model import FakeModel, text_response
from support.fake_storefront import FakeStorefrontBackend

VALID_API_KEY = "sk-local-development-value"
MEMBER_TOKEN = "Bearer placeholder-member-token"
SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c"


async def _ok() -> bool:
    return True


@dataclass(slots=True)
class AgentTestApp:
    """测试用应用装配结果（命名不使用 Test 前缀，避免被 pytest 收集）。"""

    app: FastAPI
    model: Any
    backend: FakeStorefrontBackend
    repository: Any
    limiter: ChatRateLimiter
    settings: Settings


def build_settings(**overrides: Any) -> Settings:
    payload: dict[str, Any] = {
        "model_mode": "openai",
        "openai_api_key": VALID_API_KEY,
        "openai_model": "demo-model",
    }
    payload.update(overrides)
    return Settings(**payload)


def build_test_app(
    *,
    model: Any | None = None,
    backend: FakeStorefrontBackend | None = None,
    settings: Settings | None = None,
    repository: Any | None = None,
    limiter: ChatRateLimiter | None = None,
    limits: AgentLimits | None = None,
    probes: HealthProbes | None = None,
) -> AgentTestApp:
    resolved_settings = settings or build_settings()
    resolved_backend = backend or FakeStorefrontBackend()
    resolved_repository = repository or MemorySessionRepository(
        ttl_seconds=resolved_settings.session_ttl_seconds,
        max_messages=resolved_settings.session_max_messages,
    )
    resolved_model = model or FakeModel([text_response("好的")])
    resolved_limiter = limiter or ChatRateLimiter(
        counter=InMemoryRateCounter(),
        session_rule=RateLimitRule(
            limit=resolved_settings.rate_limit_session_limit,
            window_seconds=resolved_settings.rate_limit_session_window_seconds,
        ),
        ip_rule=RateLimitRule(
            limit=resolved_settings.rate_limit_ip_limit,
            window_seconds=resolved_settings.rate_limit_ip_window_seconds,
        ),
    )
    orchestrator = ShoppingAgentOrchestrator(
        model=resolved_model,
        registry=create_tool_registry(),
        backend=resolved_backend,
        limits=limits
        or AgentLimits(
            max_tool_rounds=resolved_settings.max_tool_rounds,
            max_messages=resolved_settings.session_max_messages,
            tool_result_max_chars=resolved_settings.tool_result_max_chars,
            context_max_chars=resolved_settings.context_max_chars,
        ),
    )
    services = AppServices(
        backend=resolved_backend,
        session_repository=resolved_repository,
        model=resolved_model,
        rate_limiter=resolved_limiter,
        orchestrator=orchestrator,
    )
    app = create_app(
        settings=resolved_settings,
        services=services,
        probes=probes or HealthProbes(redis=_ok, portal=_ok),
    )
    return AgentTestApp(
        app=app,
        model=resolved_model,
        backend=resolved_backend,
        repository=resolved_repository,
        limiter=resolved_limiter,
        settings=resolved_settings,
    )

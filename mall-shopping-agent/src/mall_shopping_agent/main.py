"""FastAPI 应用装配。"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import redis.asyncio as redis_asyncio
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from mall_shopping_agent import __version__
from mall_shopping_agent.agent.orchestrator import ShoppingAgentOrchestrator
from mall_shopping_agent.agent.types import AgentLimits
from mall_shopping_agent.api.chat import router as agent_router
from mall_shopping_agent.api.deps import AppServices
from mall_shopping_agent.api.errors import register_exception_handlers
from mall_shopping_agent.api.health import HealthProbes
from mall_shopping_agent.api.health import router as health_router
from mall_shopping_agent.config import Settings, get_settings
from mall_shopping_agent.model.client import ModelClient
from mall_shopping_agent.model.openai_compatible import OpenAICompatibleClient
from mall_shopping_agent.safety.rate_limit import (
    ChatRateLimiter,
    RateCounter,
    RateLimitRule,
    RedisRateCounter,
)
from mall_shopping_agent.session.redis_repository import RedisSessionRepository
from mall_shopping_agent.storefront.mall_portal import MallPortalBackend
from mall_shopping_agent.storefront.schemas import ProductSearchQuery
from mall_shopping_agent.tools import create_tool_registry

__all__ = ["AppServices", "app", "create_app"]


async def _redis_probe(settings: Settings) -> bool:
    client = redis_asyncio.from_url(settings.redis_url)
    try:
        return bool(await client.ping())
    finally:
        await client.aclose()


async def _portal_probe(settings: Settings) -> bool:
    backend = MallPortalBackend(
        base_url=settings.portal_base_url,
        timeout_seconds=settings.portal_timeout_seconds,
    )
    try:
        page = await backend.search_products(ProductSearchQuery(page_num=1, page_size=1))
    finally:
        await backend.aclose()
    return page.total >= 0


def _default_probes(settings: Settings, services: AppServices | None) -> HealthProbes:
    async def _redis() -> bool:
        if services is not None:
            if services.redis_client is not None:
                return bool(await services.redis_client.ping())
            return True
        return await _redis_probe(settings)

    async def _portal() -> bool:
        if services is not None:
            page = await services.backend.search_products(
                ProductSearchQuery(page_num=1, page_size=1)
            )
            return page.total >= 0
        return await _portal_probe(settings)

    return HealthProbes(redis=_redis, portal=_portal)


def _build_limits(settings: Settings) -> AgentLimits:
    return AgentLimits(
        max_tool_rounds=settings.max_tool_rounds,
        max_messages=settings.session_max_messages,
        tool_result_max_chars=settings.tool_result_max_chars,
        context_max_chars=settings.context_max_chars,
    )


def _build_limiter(settings: Settings, counter: RateCounter) -> ChatRateLimiter:
    return ChatRateLimiter(
        counter=counter,
        session_rule=RateLimitRule(
            limit=settings.rate_limit_session_limit,
            window_seconds=settings.rate_limit_session_window_seconds,
        ),
        ip_rule=RateLimitRule(
            limit=settings.rate_limit_ip_limit,
            window_seconds=settings.rate_limit_ip_window_seconds,
        ),
    )


def _build_model(settings: Settings) -> ModelClient:
    if settings.model_mode == "stub":
        from mall_shopping_agent.model.stub import StubModelClient

        return StubModelClient()

    return OpenAICompatibleClient(
        base_url=settings.openai_base_url,
        api_key=settings.openai_api_key,
        model=settings.openai_model,
        timeout_seconds=settings.openai_timeout_seconds,
    )


async def build_services(settings: Settings) -> AppServices:
    """创建进程级共享资源；由 lifespan 负责关闭。"""

    backend = MallPortalBackend(
        base_url=settings.portal_base_url,
        timeout_seconds=settings.portal_timeout_seconds,
    )
    redis_client = redis_asyncio.from_url(settings.redis_url, decode_responses=True)
    repository = RedisSessionRepository(
        redis_client,
        ttl_seconds=settings.session_ttl_seconds,
        max_messages=settings.session_max_messages,
    )
    model = _build_model(settings)
    orchestrator = ShoppingAgentOrchestrator(
        model=model,
        registry=create_tool_registry(),
        backend=backend,
        limits=_build_limits(settings),
    )
    return AppServices(
        backend=backend,
        session_repository=repository,
        model=model,
        rate_limiter=_build_limiter(settings, RedisRateCounter(redis_client)),
        orchestrator=orchestrator,
        redis_client=redis_client,
    )


async def close_services(services: AppServices) -> None:
    closer = getattr(services.model, "aclose", None)
    if callable(closer):
        await closer()

    closer = getattr(services.backend, "aclose", None)
    if callable(closer):
        await closer()

    if services.redis_client is not None:
        await services.redis_client.aclose()


def _make_lifespan(settings: Settings):
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        owned_services: AppServices | None = None
        if app.state.services is None:
            owned_services = await build_services(settings)
            app.state.services = owned_services
        try:
            yield
        finally:
            if owned_services is not None:
                await close_services(owned_services)
                app.state.services = None

    return lifespan


def create_app(
    *,
    settings: Settings | None = None,
    probes: HealthProbes | None = None,
    services: AppServices | None = None,
) -> FastAPI:
    resolved_settings = settings or get_settings()

    application = FastAPI(
        title="Mall 商品导购智能体",
        version=__version__,
        lifespan=_make_lifespan(resolved_settings),
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    application.state.settings = resolved_settings
    application.state.services = services
    application.state.health_probes = probes or _default_probes(resolved_settings, services)

    application.add_middleware(
        CORSMiddleware,
        allow_origins=resolved_settings.cors_origin_list,
        allow_credentials=False,
        allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
        allow_headers=["*"],
    )

    register_exception_handlers(application)
    application.include_router(health_router)
    application.include_router(agent_router)

    return application


app = create_app()

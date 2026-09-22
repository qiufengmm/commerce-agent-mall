from __future__ import annotations

from collections.abc import Awaitable, Callable

from fastapi import FastAPI
from fastapi.testclient import TestClient

from mall_shopping_agent.api.health import HealthProbes
from mall_shopping_agent.config import Settings
from mall_shopping_agent.main import create_app


def _probe(result: bool) -> Callable[[], Awaitable[bool]]:
    async def _run() -> bool:
        return result

    return _run


def _build_app(
    *,
    redis_ok: bool = True,
    portal_ok: bool = True,
    settings: Settings | None = None,
) -> FastAPI:
    probes = HealthProbes(redis=_probe(redis_ok), portal=_probe(portal_ok))
    return create_app(settings=settings or Settings(), probes=probes)


def test_live_is_always_ok_even_when_probes_fail() -> None:
    with TestClient(_build_app(redis_ok=False, portal_ok=False)) as client:
        response = client.get("/health/live")

    assert response.status_code == 200
    assert response.json()["data"]["status"] == "UP"


def test_ready_is_ok_when_redis_and_portal_are_reachable() -> None:
    with TestClient(_build_app()) as client:
        response = client.get("/health/ready")

    assert response.status_code == 200
    assert response.json()["data"]["status"] == "UP"


def test_ready_is_503_when_redis_fails() -> None:
    with TestClient(_build_app(redis_ok=False)) as client:
        response = client.get("/health/ready")

    assert response.status_code == 503
    assert response.json()["data"]["status"] == "DOWN"


def test_ready_is_503_when_portal_fails() -> None:
    with TestClient(_build_app(portal_ok=False)) as client:
        response = client.get("/health/ready")

    assert response.status_code == 503


def test_missing_model_key_does_not_block_readiness() -> None:
    settings = Settings(model_mode="openai", openai_api_key="")

    with TestClient(_build_app(settings=settings)) as client:
        response = client.get("/health/ready")

    assert response.status_code == 200


def test_health_response_does_not_leak_internal_urls_or_keys() -> None:
    settings = Settings(
        openai_api_key="sk-local-development-value",
        portal_base_url="http://internal-portal.invalid:9999",
    )

    with TestClient(_build_app(settings=settings)) as client:
        body = client.get("/health/live").text
        ready_body = client.get("/health/ready").text

    for payload in (body, ready_body):
        assert "sk-local-development-value" not in payload
        assert "internal-portal.invalid" not in payload
        assert "9999" not in payload

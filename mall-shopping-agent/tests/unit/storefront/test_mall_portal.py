from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from mall_shopping_agent.storefront.backend import (
    MemberUnauthorizedError,
    StorefrontNotFoundError,
    StorefrontProtocolError,
    StorefrontUnavailableError,
)
from mall_shopping_agent.storefront.mall_portal import MallPortalBackend
from mall_shopping_agent.storefront.schemas import (
    ProductSearchQuery,
    StockStatus,
    stock_status_for,
)

FIXTURE_DIR = Path(__file__).resolve().parents[2] / "fixtures" / "portal"

MEMBER_TOKEN = "Bearer placeholder-member-token"
PORTAL_BODY = "portal-upstream-secret-body"

ALLOWED_PATH_PREFIXES = (
    "/product/search",
    "/product/detail/",
    "/sso/info",
    "/member/coupon/listHistory",
    "/member/coupon/listByProduct/",
)


def fixture_text(name: str) -> str:
    return (FIXTURE_DIR / name).read_text(encoding="utf-8")


def fixture(name: str) -> Any:
    return json.loads(fixture_text(name))


def fixture_response(name: str) -> httpx.Response:
    """按门户真实响应体返回原始 JSON 文本，保留 ``50.00`` 这类金额精度。"""

    return httpx.Response(
        200,
        content=fixture_text(name).encode("utf-8"),
        headers={"content-type": "application/json"},
    )


class Recorder:
    def __init__(self, handler: Callable[[httpx.Request], httpx.Response]) -> None:
        self.requests: list[httpx.Request] = []
        self._handler = handler

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return self._handler(request)

    @property
    def paths(self) -> list[str]:
        return [request.url.path for request in self.requests]


def routes(**overrides: httpx.Response) -> Callable[[httpx.Request], httpx.Response]:
    default_map = {
        "/product/search": fixture_response("product_search.json"),
        "/product/detail/27": fixture_response("product_detail.json"),
        "/sso/info": fixture_response("sso_info.json"),
        "/member/coupon/listHistory": fixture_response("coupon_history.json"),
        "/member/coupon/listByProduct/27": fixture_response("coupon_by_product.json"),
    }

    def handler(request: httpx.Request) -> httpx.Response:
        response = overrides.get(request.url.path, default_map.get(request.url.path))
        if response is None:
            return httpx.Response(404, json={"code": 404, "message": "not found", "data": None})
        return response

    return handler


def build_backend(handler: Callable[[httpx.Request], httpx.Response]) -> MallPortalBackend:
    return MallPortalBackend(
        base_url="http://portal.internal:8085",
        timeout_seconds=10.0,
        transport=httpx.MockTransport(handler),
    )


# --------------------------------------------------------------------------- #
# 只允许白名单内的 GET 请求
# --------------------------------------------------------------------------- #


async def test_search_only_calls_search_endpoint_with_1_based_paging() -> None:
    recorder = Recorder(routes())
    backend = build_backend(recorder)

    page = await backend.search_products(ProductSearchQuery(keyword="手机", sort=3))
    await backend.aclose()

    assert recorder.requests[0].method == "GET"
    assert recorder.requests[0].url.path == "/product/search"
    assert dict(recorder.requests[0].url.params) == {
        "keyword": "手机",
        "sort": "3",
        "pageNum": "1",
        "pageSize": "5",
    }
    assert "authorization" not in {key.lower() for key in recorder.requests[0].headers}
    assert page.page_num == 1
    assert page.items[0].id == 26


async def test_detail_only_calls_detail_endpoint() -> None:
    recorder = Recorder(routes())
    backend = build_backend(recorder)

    await backend.get_product_detail(27)
    await backend.aclose()

    assert recorder.paths == ["/product/detail/27"]
    assert "authorization" not in {key.lower() for key in recorder.requests[0].headers}


async def test_member_endpoints_pass_authorization_through_untouched() -> None:
    recorder = Recorder(routes())
    backend = build_backend(recorder)

    await backend.resolve_member(MEMBER_TOKEN)
    await backend.list_unused_coupon_history(MEMBER_TOKEN)
    await backend.list_product_coupons(27, MEMBER_TOKEN)
    await backend.aclose()

    assert recorder.paths == [
        "/sso/info",
        "/member/coupon/listHistory",
        "/member/coupon/listByProduct/27",
    ]
    for request in recorder.requests:
        assert request.headers["authorization"] == MEMBER_TOKEN
    assert dict(recorder.requests[1].url.params) == {"useStatus": "0"}


async def test_all_requests_stay_inside_the_allowed_allowlist() -> None:
    recorder = Recorder(routes())
    backend = build_backend(recorder)

    await backend.search_products(ProductSearchQuery())
    await backend.get_product_detail(27)
    await backend.resolve_member(MEMBER_TOKEN)
    await backend.list_unused_coupon_history(MEMBER_TOKEN)
    await backend.list_product_coupons(27, MEMBER_TOKEN)
    await backend.aclose()

    for path in recorder.paths:
        assert any(path.startswith(prefix) for prefix in ALLOWED_PATH_PREFIXES), path
    for request in recorder.requests:
        assert request.method == "GET"


# --------------------------------------------------------------------------- #
# 搜索分页与参数边界
# --------------------------------------------------------------------------- #


def test_search_query_enforces_server_side_limits() -> None:
    with pytest.raises(ValidationError):
        ProductSearchQuery(page_size=6)
    with pytest.raises(ValidationError):
        ProductSearchQuery(page_num=21)
    with pytest.raises(ValidationError):
        ProductSearchQuery(page_num=0)
    with pytest.raises(ValidationError):
        ProductSearchQuery(sort=5)
    with pytest.raises(ValidationError):
        ProductSearchQuery(sort=-1)
    with pytest.raises(ValidationError):
        ProductSearchQuery(brand_id=0)
    with pytest.raises(ValidationError):
        ProductSearchQuery(unknown_field=1)  # type: ignore[call-arg]


def test_search_query_defaults() -> None:
    query = ProductSearchQuery()

    assert query.page_num == 1
    assert query.page_size == 5
    assert query.sort == 0
    assert query.keyword is None


async def test_search_omits_none_filters_from_query_string() -> None:
    recorder = Recorder(routes())
    backend = build_backend(recorder)

    await backend.search_products(ProductSearchQuery())
    await backend.aclose()

    params = dict(recorder.requests[0].url.params)
    assert "keyword" not in params
    assert "brandId" not in params
    assert "productCategoryId" not in params


# --------------------------------------------------------------------------- #
# 错误映射：不得携带门户响应正文
# --------------------------------------------------------------------------- #


async def test_business_error_code_500_maps_to_unavailable() -> None:
    recorder = Recorder(
        routes(
            **{"/product/search": httpx.Response(200, json={"code": 500, "message": PORTAL_BODY})}
        )
    )
    backend = build_backend(recorder)

    with pytest.raises(StorefrontUnavailableError) as excinfo:
        await backend.search_products(ProductSearchQuery())
    await backend.aclose()

    assert PORTAL_BODY not in str(excinfo.value)
    assert "portal.internal" not in str(excinfo.value)


async def test_http_401_maps_to_member_unauthorized() -> None:
    recorder = Recorder(
        routes(**{"/sso/info": httpx.Response(401, json={"code": 401, "message": PORTAL_BODY})})
    )
    backend = build_backend(recorder)

    with pytest.raises(MemberUnauthorizedError) as excinfo:
        await backend.resolve_member(MEMBER_TOKEN)
    await backend.aclose()

    assert PORTAL_BODY not in str(excinfo.value)
    assert MEMBER_TOKEN not in str(excinfo.value)


async def test_business_error_code_401_maps_to_member_unauthorized() -> None:
    recorder = Recorder(routes(**{"/sso/info": fixture_response("sso_unauthorized.json")}))
    backend = build_backend(recorder)

    with pytest.raises(MemberUnauthorizedError):
        await backend.resolve_member(MEMBER_TOKEN)
    await backend.aclose()


async def test_member_history_without_data_maps_to_unauthorized() -> None:
    recorder = Recorder(
        routes(
            **{"/sso/info": httpx.Response(200, json={"code": 200, "message": "ok", "data": None})}
        )
    )
    backend = build_backend(recorder)

    with pytest.raises(MemberUnauthorizedError):
        await backend.resolve_member(MEMBER_TOKEN)
    await backend.aclose()


async def test_invalid_json_body_maps_to_protocol_error() -> None:
    recorder = Recorder(routes(**{"/product/search": httpx.Response(200, text=PORTAL_BODY)}))
    backend = build_backend(recorder)

    with pytest.raises(StorefrontProtocolError) as excinfo:
        await backend.search_products(ProductSearchQuery())
    await backend.aclose()

    assert PORTAL_BODY not in str(excinfo.value)


async def test_timeout_maps_to_unavailable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timeout", request=request)

    backend = build_backend(handler)

    with pytest.raises(StorefrontUnavailableError) as excinfo:
        await backend.search_products(ProductSearchQuery())
    await backend.aclose()

    assert "timeout" not in str(excinfo.value).lower()


async def test_missing_product_detail_reports_not_found() -> None:
    # 门户在商品 id 不存在时会在详情实现中抛出未捕获异常，返回 Spring 默认 500 结构
    recorder = Recorder(
        routes(
            **{
                "/product/detail/27": httpx.Response(
                    500, json={"timestamp": "2026-09-22T00:00:00Z", "status": 500, "error": "x"}
                )
            }
        )
    )
    backend = build_backend(recorder)

    with pytest.raises(StorefrontNotFoundError):
        await backend.get_product_detail(27)
    await backend.aclose()


async def test_detail_without_product_field_reports_not_found() -> None:
    recorder = Recorder(
        routes(**{"/product/detail/27": httpx.Response(200, json={"code": 200, "data": {}})})
    )
    backend = build_backend(recorder)

    with pytest.raises(StorefrontNotFoundError):
        await backend.get_product_detail(27)
    await backend.aclose()


@pytest.mark.parametrize("product_id", [0, -1])
async def test_non_positive_product_id_is_rejected_before_any_request(product_id: int) -> None:
    recorder = Recorder(routes())
    backend = build_backend(recorder)

    with pytest.raises(ValueError):
        await backend.get_product_detail(product_id)
    await backend.aclose()

    assert recorder.paths == []


# --------------------------------------------------------------------------- #
# 夹具字段解析
# --------------------------------------------------------------------------- #


async def test_search_page_tolerates_missing_optional_fields() -> None:
    backend = build_backend(routes())
    page = await backend.search_products(ProductSearchQuery())
    await backend.aclose()

    assert page.total == 7
    assert page.total_page == 2
    assert len(page.items) == 3

    third = page.items[2]
    assert third.id == 28
    assert third.price is None
    assert third.pic is None
    assert third.brand_name is None


async def test_detail_aggregates_sku_available_stock_with_negative_clamped_to_zero() -> None:
    backend = build_backend(routes())
    detail = await backend.get_product_detail(27)
    await backend.aclose()

    assert [sku.available_stock for sku in detail.sku_stocks] == [100, 0, 12]
    assert detail.available_stock == 112
    assert detail.stock_status is StockStatus.IN_STOCK


async def test_detail_parses_attributes_and_public_coupons() -> None:
    backend = build_backend(routes())
    detail = await backend.get_product_detail(27)
    await backend.aclose()

    assert [(item.name, item.value) for item in detail.attributes] == [
        ("颜色", "金色"),
        ("内存", "8GB"),
    ]
    assert [coupon.id for coupon in detail.public_coupons] == [55]
    assert str(detail.public_coupons[0].amount) == "50.00"
    assert detail.public_coupons[0].end_time is not None


@pytest.mark.parametrize(
    ("available", "expected"),
    [
        (0, StockStatus.OUT_OF_STOCK),
        (1, StockStatus.LOW_STOCK),
        (10, StockStatus.LOW_STOCK),
        (11, StockStatus.IN_STOCK),
        (999, StockStatus.IN_STOCK),
    ],
)
def test_stock_status_thresholds(available: int, expected: StockStatus) -> None:
    assert stock_status_for(available) is expected


async def test_member_identity_never_exposes_password_or_token() -> None:
    backend = build_backend(routes())
    member = await backend.resolve_member(MEMBER_TOKEN)
    await backend.aclose()

    assert member.member_id == 7
    assert member.nickname == "示例会员"
    dumped = json.dumps(member.model_dump(mode="json"), ensure_ascii=False)
    assert "password" not in dumped
    assert "placeholder-hash" not in dumped
    assert MEMBER_TOKEN not in dumped


async def test_coupon_history_never_exposes_coupon_code_or_member_id() -> None:
    backend = build_backend(routes())
    history = await backend.list_unused_coupon_history(MEMBER_TOKEN)
    await backend.aclose()

    assert [item.coupon_id for item in history] == [55, 77, 999]
    dumped = json.dumps([item.model_dump(mode="json") for item in history], ensure_ascii=False)
    assert "couponCode" not in dumped
    assert "placeholder-code" not in dumped
    assert "memberId" not in dumped


async def test_product_coupons_are_parsed_with_decimal_amounts() -> None:
    backend = build_backend(routes())
    coupons = await backend.list_product_coupons(27, MEMBER_TOKEN)
    await backend.aclose()

    assert [coupon.id for coupon in coupons] == [55, 77, 99]
    assert str(coupons[1].amount) == "200.00"
    assert coupons[1].use_type == 2

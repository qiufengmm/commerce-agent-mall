from __future__ import annotations

from mall_shopping_agent.storefront.backend import (
    MemberUnauthorizedError,
    StorefrontUnavailableError,
)
from mall_shopping_agent.storefront.schemas import CouponHistory
from mall_shopping_agent.tools import create_tool_registry
from mall_shopping_agent.tools.registry import ToolStatus
from support.fake_storefront import FakeStorefrontBackend, build_coupons, portal_fixture
from support.registry_fixture import invoke

MEMBER_TOKEN = "Bearer placeholder-member-token"
PRODUCT_ID = 27


def load_coupon_data() -> tuple[list[CouponHistory], list[dict]]:
    history_payload = portal_fixture("coupon_history.json")["data"]
    history = [CouponHistory.model_validate(item) for item in history_payload]
    coupons = portal_fixture("coupon_by_product.json")["data"]
    return history, coupons


def member_backend() -> FakeStorefrontBackend:
    history, coupons = load_coupon_data()
    backend = FakeStorefrontBackend()
    backend.coupon_history = history
    backend.product_coupons[PRODUCT_ID] = build_coupons(coupons)
    return backend


async def test_guest_receives_login_required_without_any_member_call() -> None:
    backend = member_backend()
    registry = create_tool_registry()

    result = await invoke(
        registry, backend, "getMemberCouponsForProduct", {"productId": PRODUCT_ID}
    )

    assert result.status is ToolStatus.LOGIN_REQUIRED
    assert result.payload["requiresLogin"] is True
    assert backend.calls == []


async def test_member_receives_only_received_and_applicable_coupons() -> None:
    backend = member_backend()
    registry = create_tool_registry()

    result = await invoke(
        registry,
        backend,
        "getMemberCouponsForProduct",
        {"productId": PRODUCT_ID},
        authorization=MEMBER_TOKEN,
    )

    assert result.status is ToolStatus.OK
    coupon_ids = [item["couponId"] for item in result.payload["coupons"]]
    # 99 适用于该商品但会员没有领取，999 已领取但不适用于该商品，两者都不应出现
    assert sorted(coupon_ids) == [55, 77]
    assert backend.call_names == ["list_unused_coupon_history", "list_product_coupons"]


async def test_member_endpoints_receive_authorization_untouched() -> None:
    backend = member_backend()
    registry = create_tool_registry()

    await invoke(
        registry,
        backend,
        "getMemberCouponsForProduct",
        {"productId": PRODUCT_ID},
        authorization=MEMBER_TOKEN,
    )

    for name, kwargs in backend.calls:
        assert kwargs["authorization"] == MEMBER_TOKEN, name


async def test_coupon_explanation_covers_amount_threshold_scope_and_validity() -> None:
    backend = member_backend()
    registry = create_tool_registry()

    result = await invoke(
        registry,
        backend,
        "getMemberCouponsForProduct",
        {"productId": PRODUCT_ID},
        authorization=MEMBER_TOKEN,
    )

    by_id = {item["couponId"]: item for item in result.payload["coupons"]}
    coupon = by_id[55]
    assert coupon["name"] == "满500减50"
    assert coupon["amount"] == "50.00"
    assert coupon["minPoint"] == "500.00"
    assert coupon["scope"] == "全场通用"
    assert coupon["status"] == "AVAILABLE"
    assert coupon["usableForProduct"] is True
    assert coupon["explanation"]
    assert coupon["startTime"] and coupon["endTime"]

    scoped = by_id[77]
    assert scoped["scope"] == "指定商品"
    assert scoped["amount"] == "200.00"


async def test_coupon_explanation_sorts_by_amount_descending() -> None:
    backend = member_backend()
    registry = create_tool_registry()

    result = await invoke(
        registry,
        backend,
        "getMemberCouponsForProduct",
        {"productId": PRODUCT_ID},
        authorization=MEMBER_TOKEN,
    )

    amounts = [item["amount"] for item in result.payload["coupons"]]
    assert amounts == ["200.00", "50.00"]


async def test_expired_and_not_started_coupons_are_flagged_not_claimed_usable() -> None:
    backend = FakeStorefrontBackend()
    backend.coupon_history = [
        CouponHistory.model_validate(item) for item in portal_fixture("coupon_history.json")["data"]
    ]
    backend.product_coupons[PRODUCT_ID] = build_coupons(
        [
            {
                "id": 55,
                "name": "已过期券",
                "amount": 30.00,
                "minPoint": 0.00,
                "startTime": "2020-01-01T00:00:00.000Z",
                "endTime": "2021-01-01T00:00:00.000Z",
                "useType": 0,
            },
            {
                "id": 77,
                "name": "未开始券",
                "amount": 40.00,
                "minPoint": 0.00,
                "startTime": "2099-01-01T00:00:00.000Z",
                "endTime": "2100-01-01T00:00:00.000Z",
                "useType": 1,
            },
        ]
    )
    registry = create_tool_registry()

    result = await invoke(
        registry,
        backend,
        "getMemberCouponsForProduct",
        {"productId": PRODUCT_ID},
        authorization=MEMBER_TOKEN,
    )

    by_id = {item["couponId"]: item for item in result.payload["coupons"]}
    assert by_id[55]["status"] == "EXPIRED"
    assert by_id[55]["usableForProduct"] is False
    assert by_id[77]["status"] == "NOT_STARTED"
    assert by_id[77]["usableForProduct"] is False
    assert by_id[55]["scope"] == "全场通用"
    assert by_id[77]["scope"] == "指定分类"


async def test_no_intersection_returns_explicit_empty_result() -> None:
    backend = FakeStorefrontBackend()
    backend.coupon_history = [CouponHistory.model_validate({"id": 1, "couponId": 1234})]
    backend.product_coupons[PRODUCT_ID] = build_coupons(
        [
            {
                "id": 4321,
                "name": "未领取的券",
                "amount": 10.00,
                "minPoint": 0.00,
                "useType": 0,
            }
        ]
    )
    registry = create_tool_registry()

    result = await invoke(
        registry,
        backend,
        "getMemberCouponsForProduct",
        {"productId": PRODUCT_ID},
        authorization=MEMBER_TOKEN,
    )

    assert result.status is ToolStatus.OK
    assert result.payload["coupons"] == []
    assert result.payload["receivedCouponCount"] == 1
    assert result.payload["applicableCouponCount"] == 1


async def test_expired_token_maps_to_login_required() -> None:
    backend = member_backend()
    backend.errors["list_unused_coupon_history"] = MemberUnauthorizedError("登录失效")
    registry = create_tool_registry()

    result = await invoke(
        registry,
        backend,
        "getMemberCouponsForProduct",
        {"productId": PRODUCT_ID},
        authorization=MEMBER_TOKEN,
    )

    assert result.status is ToolStatus.LOGIN_REQUIRED
    assert result.payload["requiresLogin"] is True


async def test_member_backend_failure_maps_to_structured_error() -> None:
    backend = member_backend()
    backend.errors["list_product_coupons"] = StorefrontUnavailableError("门户不可用")
    registry = create_tool_registry()

    result = await invoke(
        registry,
        backend,
        "getMemberCouponsForProduct",
        {"productId": PRODUCT_ID},
        authorization=MEMBER_TOKEN,
    )

    assert result.status is ToolStatus.ERROR
    assert result.error_code == "STOREFRONT_UNAVAILABLE"


async def test_missing_product_maps_to_not_found() -> None:
    backend = member_backend()
    backend.product_coupons.clear()
    registry = create_tool_registry()

    result = await invoke(
        registry,
        backend,
        "getMemberCouponsForProduct",
        {"productId": PRODUCT_ID},
        authorization=MEMBER_TOKEN,
    )

    assert result.status is ToolStatus.PRODUCT_NOT_FOUND


async def test_required_facts_are_stable_strings() -> None:
    backend = member_backend()
    registry = create_tool_registry()

    result = await invoke(
        registry,
        backend,
        "getMemberCouponsForProduct",
        {"productId": PRODUCT_ID},
        authorization=MEMBER_TOKEN,
    )

    assert result.facts
    assert all(isinstance(fact, str) and fact for fact in result.facts)

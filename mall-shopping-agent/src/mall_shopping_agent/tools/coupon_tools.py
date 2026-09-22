"""会员优惠券只读工具。

只解释「本人已领取（``listHistory?useStatus=0``）」与「适用于指定商品
（``listByProduct/{productId}``）」两张结果的 ``couponId`` 交集。
游客调用时立即返回 ``LOGIN_REQUIRED``，不请求任何会员接口。
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from mall_shopping_agent.storefront.backend import (
    MemberUnauthorizedError,
    StorefrontError,
    StorefrontNotFoundError,
)
from mall_shopping_agent.storefront.schemas import Coupon, format_money
from mall_shopping_agent.tools.registry import ToolContext, ToolResult, ToolStatus

GET_MEMBER_COUPONS_FOR_PRODUCT = "getMemberCouponsForProduct"

MAX_COUPONS_IN_RESULT = 5

_STRICT_CONFIG = ConfigDict(
    alias_generator=to_camel,
    populate_by_name=True,
    extra="forbid",
    strict=True,
)

_SCOPE_LABELS = {
    0: "全场通用",
    1: "指定分类",
    2: "指定商品",
}

_MAX_PUBLIC_COUPONS = 5


class MemberCouponsParams(BaseModel):
    model_config = _STRICT_CONFIG

    product_id: int = Field(ge=1)


def _login_required(product_id: int) -> ToolResult:
    return ToolResult(
        name=GET_MEMBER_COUPONS_FOR_PRODUCT,
        status=ToolStatus.LOGIN_REQUIRED,
        payload={
            "type": "coupons",
            "productId": product_id,
            "requiresLogin": True,
            "reason": "登录后可以查询您本人已领取、且适用于该商品的优惠券。",
        },
        facts=["查询个人优惠券需要先登录"],
    )


def _coupon_status(coupon: Coupon, now: datetime) -> tuple[str, bool]:
    if coupon.start_time is not None and now < coupon.start_time:
        return "NOT_STARTED", False
    if coupon.end_time is not None and now > coupon.end_time:
        return "EXPIRED", False
    return "AVAILABLE", True


def _explain(coupon: Coupon, status: str) -> str:
    scope = _SCOPE_LABELS.get(coupon.use_type, "未知适用范围")
    amount = format_money(coupon.amount) or "0.00"
    min_point = format_money(coupon.min_point)
    threshold = (
        f"满 {min_point} 元可用"
        if min_point is not None and Decimal(min_point) > 0
        else "无使用门槛"
    )
    validity = {
        "AVAILABLE": "当前在有效期内，可用于该商品。",
        "EXPIRED": "已过有效期，不能使用。",
        "NOT_STARTED": "尚未到生效时间。",
    }[status]
    name = coupon.name or "优惠券"
    return f"{name}：{scope}，{threshold}，面额 {amount} 元，{validity}"


def _coupon_payload(coupon: Coupon, now: datetime) -> dict[str, Any]:
    status, usable = _coupon_status(coupon, now)
    return {
        "couponId": coupon.id,
        "name": coupon.name,
        "amount": format_money(coupon.amount),
        "minPoint": format_money(coupon.min_point),
        "useType": coupon.use_type,
        "scope": _SCOPE_LABELS.get(coupon.use_type, "未知适用范围"),
        "startTime": coupon.start_time.isoformat() if coupon.start_time else None,
        "endTime": coupon.end_time.isoformat() if coupon.end_time else None,
        "status": status,
        "usableForProduct": usable,
        "explanation": _explain(coupon, status),
    }


def _sort_key(item: dict[str, Any]) -> tuple[int, Decimal]:
    usable_rank = 0 if item["usableForProduct"] else 1
    amount = Decimal(item["amount"]) if item["amount"] else Decimal("0")
    return usable_rank, -amount


def build_coupon_explanations(
    coupons: list[Coupon],
    *,
    clock: Callable[[], datetime] | None = None,
) -> list[dict[str, Any]]:
    """按有效期、使用门槛和适用范围生成结构化解释，可用券排在前面。"""

    now = (clock or (lambda: datetime.now(UTC)))()
    items = [_coupon_payload(coupon, now) for coupon in coupons]
    items.sort(key=_sort_key)
    return items[:MAX_COUPONS_IN_RESULT]


async def get_member_coupons_for_product(
    params: MemberCouponsParams,
    context: ToolContext,
    *,
    clock: Callable[[], datetime] | None = None,
) -> ToolResult:
    if not context.authorization:
        return _login_required(params.product_id)

    try:
        history = await context.backend.list_unused_coupon_history(context.authorization)
    except MemberUnauthorizedError:
        return _login_required(params.product_id)
    except StorefrontError as exc:
        return ToolResult(
            name=GET_MEMBER_COUPONS_FOR_PRODUCT,
            status=ToolStatus.ERROR,
            payload={"errorCode": exc.code, "message": exc.message},
            facts=[f"会员优惠券查询失败：{exc.code}"],
            error_code=exc.code,
            error_message=exc.message,
        )

    try:
        applicable = await context.backend.list_product_coupons(
            params.product_id, context.authorization
        )
    except MemberUnauthorizedError:
        return _login_required(params.product_id)
    except StorefrontNotFoundError:
        return ToolResult(
            name=GET_MEMBER_COUPONS_FOR_PRODUCT,
            status=ToolStatus.PRODUCT_NOT_FOUND,
            payload={"productId": params.product_id, "reason": "未找到该商品"},
            facts=[f"商品 {params.product_id} 不可用"],
        )
    except StorefrontError as exc:
        return ToolResult(
            name=GET_MEMBER_COUPONS_FOR_PRODUCT,
            status=ToolStatus.ERROR,
            payload={"errorCode": exc.code, "message": exc.message},
            facts=[f"会员优惠券查询失败：{exc.code}"],
            error_code=exc.code,
            error_message=exc.message,
        )

    received_ids = {
        item.coupon_id
        for item in history
        if item.coupon_id is not None and item.use_status in (0, None)
    }
    intersection = [coupon for coupon in applicable if coupon.id in received_ids]
    explanations = build_coupon_explanations(intersection, clock=clock)
    usable_count = sum(1 for item in explanations if item["usableForProduct"])

    return ToolResult(
        name=GET_MEMBER_COUPONS_FOR_PRODUCT,
        payload={
            "type": "coupons",
            "productId": params.product_id,
            "receivedCouponCount": len(received_ids),
            "applicableCouponCount": len(applicable),
            "matchedCouponCount": len(intersection),
            "usableCouponCount": usable_count,
            "coupons": explanations,
            "note": "只统计本人未使用且适用于该商品的优惠券交集，不代表可以代领或代下单。",
        },
        facts=[
            f"商品 {params.product_id} 共有 {len(intersection)} 张已领取且适用的优惠券",
            f"其中 {usable_count} 张当前可用",
        ],
    )

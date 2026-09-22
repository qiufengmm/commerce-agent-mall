"""StorefrontBackend 的内存替身，用于工具与编排层单元测试。"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from mall_shopping_agent.storefront.backend import StorefrontNotFoundError
from mall_shopping_agent.storefront.mall_portal import parse_coupons, parse_product_detail
from mall_shopping_agent.storefront.schemas import (
    Coupon,
    CouponHistory,
    MemberIdentity,
    ProductDetail,
    ProductSearchPage,
    ProductSearchQuery,
    ProductSummary,
)

FIXTURE_DIR = Path(__file__).resolve().parents[1] / "fixtures" / "portal"


def portal_fixture(name: str) -> Any:
    return json.loads((FIXTURE_DIR / name).read_text(encoding="utf-8"))


def build_detail(data: dict[str, Any]) -> ProductDetail:
    return parse_product_detail(data)


def build_coupons(items: list[dict[str, Any]]) -> list[Coupon]:
    return parse_coupons(items)


def build_summary(**overrides: Any) -> ProductSummary:
    payload: dict[str, Any] = {
        "id": 26,
        "name": "示例手机 A",
        "pic": "http://localhost:9000/mall/example-26.jpg",
        "price": "1899.00",
        "subTitle": "示例副标题 A",
        "brandName": "示例品牌",
        "productCategoryName": "手机通讯",
        "sale": 100,
        "stock": 500,
    }
    payload.update(overrides)
    return ProductSummary.model_validate(payload)


class FakeStorefrontBackend:
    """记录调用参数，并允许按方法注入异常。"""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, Any]]] = []
        self.search_page = ProductSearchPage(
            pageNum=1, pageSize=5, totalPage=1, total=1, list=[build_summary()]
        )
        self.details: dict[int, ProductDetail] = {}
        self.member: MemberIdentity | None = MemberIdentity(
            id=7, username="demo-member", nickname="示例会员"
        )
        self.coupon_history: list[CouponHistory] = []
        self.product_coupons: dict[int, list[Coupon]] = {}
        self.errors: dict[str, Exception] = {}

    # -- 测试辅助 ---------------------------------------------------------

    @property
    def call_names(self) -> list[str]:
        return [name for name, _ in self.calls]

    @property
    def last(self) -> tuple[str, dict[str, Any]]:
        return self.calls[-1]

    def _record(self, name: str, **kwargs: Any) -> None:
        self.calls.append((name, kwargs))
        error = self.errors.get(name)
        if error is not None:
            raise error

    # -- 协议实现 ---------------------------------------------------------

    async def search_products(self, query: ProductSearchQuery) -> ProductSearchPage:
        self._record("search_products", query=query.model_dump(by_alias=True))
        return self.search_page

    async def get_product_detail(self, product_id: int) -> ProductDetail:
        self._record("get_product_detail", product_id=product_id)
        detail = self.details.get(product_id)
        if detail is None:
            raise self.errors.get(
                "detail_missing",
                StorefrontNotFoundError(f"missing detail {product_id}"),
            )
        return detail

    async def resolve_member(self, authorization: str) -> MemberIdentity:
        self._record("resolve_member", authorization=authorization)
        if self.member is None:
            raise self.errors.get("resolve_member", LookupError("no member"))
        return self.member

    async def list_unused_coupon_history(self, authorization: str) -> list[CouponHistory]:
        self._record("list_unused_coupon_history", authorization=authorization)
        return self.coupon_history

    async def list_product_coupons(self, product_id: int, authorization: str) -> list[Coupon]:
        self._record(
            "list_product_coupons",
            product_id=product_id,
            authorization=authorization,
        )
        coupons = self.product_coupons.get(product_id)
        if coupons is None:
            raise self.errors.get(
                "product_coupons_missing",
                StorefrontNotFoundError(f"missing coupons {product_id}"),
            )
        return coupons

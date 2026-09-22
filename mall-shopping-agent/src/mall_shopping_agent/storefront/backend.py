"""Storefront Backend 协议与错误类型。

上层只依赖该协议，真实实现是 ``MallPortalBackend``，测试可用任意替身。
错误类型自带 ``http_status``，并且永远不携带门户响应正文或 Token。
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from mall_shopping_agent.storefront.schemas import (
    Coupon,
    CouponHistory,
    MemberIdentity,
    ProductDetail,
    ProductSearchPage,
    ProductSearchQuery,
)


class StorefrontError(Exception):
    """门户访问错误基类。"""

    http_status: int = 502
    code: str = "STOREFRONT_UNAVAILABLE"

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class StorefrontUnavailableError(StorefrontError):
    """门户超时、连接失败或返回可重试错误，映射为 502。"""

    http_status = 502
    code = "STOREFRONT_UNAVAILABLE"


class StorefrontProtocolError(StorefrontUnavailableError):
    """门户返回结构非法，映射为 502。"""

    code = "STOREFRONT_PROTOCOL_ERROR"


class StorefrontNotFoundError(StorefrontError):
    """商品不存在、已下架或详情不可用。"""

    http_status = 404
    code = "PRODUCT_NOT_FOUND"


class MemberUnauthorizedError(StorefrontError):
    """会员 Token 无效或已过期，聊天接口返回 requiresLogin。"""

    http_status = 401
    code = "MEMBER_UNAUTHORIZED"


@runtime_checkable
class StorefrontBackend(Protocol):
    """mall-portal 只读访问协议。"""

    async def search_products(self, query: ProductSearchQuery) -> ProductSearchPage: ...

    async def get_product_detail(self, product_id: int) -> ProductDetail: ...

    async def resolve_member(self, authorization: str) -> MemberIdentity: ...

    async def list_unused_coupon_history(self, authorization: str) -> list[CouponHistory]: ...

    async def list_product_coupons(self, product_id: int, authorization: str) -> list[Coupon]: ...

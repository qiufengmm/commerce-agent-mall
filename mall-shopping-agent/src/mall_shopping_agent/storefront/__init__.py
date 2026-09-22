"""mall-portal 只读访问层。"""

from __future__ import annotations

from mall_shopping_agent.storefront.backend import (
    MemberUnauthorizedError,
    StorefrontBackend,
    StorefrontError,
    StorefrontNotFoundError,
    StorefrontProtocolError,
    StorefrontUnavailableError,
)
from mall_shopping_agent.storefront.mall_portal import MallPortalBackend
from mall_shopping_agent.storefront.schemas import (
    Coupon,
    CouponHistory,
    MemberIdentity,
    ProductAttribute,
    ProductDetail,
    ProductSearchPage,
    ProductSearchQuery,
    ProductSummary,
    SkuStock,
    StockStatus,
    stock_status_for,
    to_datetime,
)

__all__ = [
    "Coupon",
    "CouponHistory",
    "MallPortalBackend",
    "MemberIdentity",
    "MemberUnauthorizedError",
    "ProductAttribute",
    "ProductDetail",
    "ProductSearchPage",
    "ProductSearchQuery",
    "ProductSummary",
    "SkuStock",
    "StockStatus",
    "StorefrontBackend",
    "StorefrontError",
    "StorefrontNotFoundError",
    "StorefrontProtocolError",
    "StorefrontUnavailableError",
    "stock_status_for",
    "to_datetime",
]

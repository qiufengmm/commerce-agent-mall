"""门户只读接口的领域模型。

金额统一使用 ``Decimal``，禁止用浮点数计算价格、门槛或优惠额；
日期字段容忍 ISO-8601 字符串、epoch 时间戳等格式，无法解析时返回 ``None``。
"""

from __future__ import annotations

from datetime import UTC, datetime
from decimal import ROUND_HALF_UP, Decimal
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

LOW_STOCK_UPPER_BOUND = 10

MAX_SEARCH_PAGE_SIZE = 5
MAX_SEARCH_PAGE_NUM = 20
MAX_SEARCH_SORT = 4


class StockStatus(StrEnum):
    """对前端统一展示的库存状态。"""

    IN_STOCK = "IN_STOCK"
    LOW_STOCK = "LOW_STOCK"
    OUT_OF_STOCK = "OUT_OF_STOCK"


def stock_status_for(available_stock: int) -> StockStatus:
    if available_stock <= 0:
        return StockStatus.OUT_OF_STOCK
    if available_stock <= LOW_STOCK_UPPER_BOUND:
        return StockStatus.LOW_STOCK
    return StockStatus.IN_STOCK


def format_money(value: Decimal | None) -> str | None:
    """把 ``Decimal`` 金额格式化为两位小数字符串，禁止使用浮点数。"""

    if value is None:
        return None
    return str(value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def to_datetime(value: Any) -> datetime | None:
    """把门户返回的时间字段规范化为 ``datetime``，失败时返回 ``None``。"""

    if value is None:
        return None
    if isinstance(value, datetime):
        return value
    if isinstance(value, bool):
        return None
    if isinstance(value, int | float):
        seconds = value / 1000 if value > 100_000_000_000 else value
        try:
            return datetime.fromtimestamp(seconds, tz=UTC)
        except (OverflowError, OSError, ValueError):
            return None
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        try:
            return datetime.fromisoformat(text.replace("Z", "+00:00"))
        except ValueError:
            return None
    return None


class PortalModel(BaseModel):
    """门户模型基类：字段用 snake_case，契约用 camelCase，忽略未知字段。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
        str_strip_whitespace=True,
    )


class ProductSearchQuery(PortalModel):
    """``GET /product/search`` 的受控参数，边界由服务端强制。

    ``extra="forbid"`` 保证模型无法通过额外字段影响门户请求。
    """

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        str_strip_whitespace=True,
    )

    keyword: str | None = Field(default=None, max_length=100)
    brand_id: int | None = Field(default=None, ge=1)
    product_category_id: int | None = Field(default=None, ge=1)
    sort: int = Field(default=0, ge=0, le=MAX_SEARCH_SORT)
    page_num: int = Field(default=1, ge=1, le=MAX_SEARCH_PAGE_NUM)
    page_size: int = Field(default=MAX_SEARCH_PAGE_SIZE, ge=1, le=MAX_SEARCH_PAGE_SIZE)


class ProductSummary(PortalModel):
    """搜索结果中的商品摘要，字段可能因 ES / MySQL 链路不同而缺失。"""

    id: int
    name: str = ""
    pic: str | None = None
    price: Decimal | None = None
    sub_title: str | None = None
    brand_id: int | None = None
    brand_name: str | None = None
    product_category_id: int | None = None
    product_category_name: str | None = None
    product_sn: str | None = None
    keywords: str | None = None
    sale: int | None = None
    stock: int | None = None
    publish_status: int | None = None


class ProductSearchPage(PortalModel):
    page_num: int = 1
    page_size: int = MAX_SEARCH_PAGE_SIZE
    total_page: int = 0
    total: int = 0
    items: list[ProductSummary] = Field(default_factory=list, alias="list")


class SkuStock(PortalModel):
    id: int
    sku_code: str | None = None
    price: Decimal | None = None
    stock: int | None = None
    lock_stock: int | None = None
    sale: int | None = None
    promotion_price: Decimal | None = None
    pic: str | None = None
    sp_data: str | None = None

    @property
    def available_stock(self) -> int:
        return max((self.stock or 0) - (self.lock_stock or 0), 0)


class ProductAttribute(PortalModel):
    name: str = ""
    value: str = ""


class Coupon(PortalModel):
    id: int
    name: str = ""
    type: int | None = None
    platform: int | None = None
    amount: Decimal | None = None
    min_point: Decimal | None = None
    per_limit: int | None = None
    start_time: datetime | None = None
    end_time: datetime | None = None
    use_type: int | None = None
    note: str | None = None
    member_level: int | None = None

    def is_active_at(self, moment: datetime) -> bool:
        if self.start_time is not None and moment < self.start_time:
            return False
        return not (self.end_time is not None and moment > self.end_time)


class CouponHistory(PortalModel):
    """只保留交集计算需要的字段，不携带优惠码、会员 id 或订单号。"""

    id: int
    coupon_id: int | None = None
    use_status: int | None = None
    get_type: int | None = None
    create_time: datetime | None = None


class ProductDetail(PortalModel):
    id: int
    name: str = ""
    pic: str | None = None
    price: Decimal | None = None
    sub_title: str | None = None
    description: str | None = None
    brand_id: int | None = None
    brand_name: str | None = None
    product_category_id: int | None = None
    product_category_name: str | None = None
    product_sn: str | None = None
    sale: int | None = None
    stock: int | None = None
    publish_status: int | None = None
    delete_status: int | None = None
    sku_stocks: list[SkuStock] = Field(default_factory=list)
    attributes: list[ProductAttribute] = Field(default_factory=list)
    public_coupons: list[Coupon] = Field(default_factory=list)

    @property
    def available_stock(self) -> int:
        if self.sku_stocks:
            return sum(sku.available_stock for sku in self.sku_stocks)
        return max(self.stock or 0, 0)

    @property
    def stock_status(self) -> StockStatus:
        return stock_status_for(self.available_stock)


class MemberIdentity(PortalModel):
    member_id: int = Field(alias="id")
    username: str | None = None
    nickname: str | None = None
    icon: str | None = None

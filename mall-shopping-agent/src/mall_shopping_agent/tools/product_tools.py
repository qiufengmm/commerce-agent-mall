"""商品只读工具：搜索、详情与比较。

所有商品事实（名称、价格、图片、库存、详情路径）都由本模块根据门户结果构造，
模型无法提供或覆盖这些字段。
"""

from __future__ import annotations

from decimal import Decimal
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel

from mall_shopping_agent.storefront.backend import (
    StorefrontError,
    StorefrontNotFoundError,
)
from mall_shopping_agent.storefront.schemas import (
    MAX_SEARCH_PAGE_SIZE,
    ProductDetail,
    ProductSearchQuery,
    format_money,
)
from mall_shopping_agent.tools.registry import ToolContext, ToolResult, ToolStatus

SEARCH_PRODUCTS = "searchProducts"
GET_PRODUCT_DETAIL = "getProductDetail"
COMPARE_PRODUCTS = "compareProducts"

MAX_SEARCH_PRODUCTS = MAX_SEARCH_PAGE_SIZE
MAX_DETAIL_SKUS = 10
MAX_DETAIL_PUBLIC_COUPONS = 5
MAX_COMPARE_ATTRIBUTES = 6

_STRICT_CONFIG = ConfigDict(
    alias_generator=to_camel,
    populate_by_name=True,
    extra="forbid",
    strict=True,
)


class SearchProductsParams(BaseModel):
    model_config = _STRICT_CONFIG

    keyword: str | None = Field(default=None, max_length=100)
    brand_id: int | None = Field(default=None, ge=1)
    product_category_id: int | None = Field(default=None, ge=1)
    sort: int = Field(default=0, ge=0, le=4)
    page_num: int = Field(default=1, ge=1, le=20)


class ProductIdParams(BaseModel):
    model_config = _STRICT_CONFIG

    product_id: int = Field(ge=1)


class CompareProductsParams(BaseModel):
    model_config = _STRICT_CONFIG

    product_ids: list[int] = Field(min_length=2, max_length=3)

    @field_validator("product_ids")
    @classmethod
    def _validate_ids(cls, value: list[int]) -> list[int]:
        if len(set(value)) != len(value):
            raise ValueError("商品 ID 不能重复")
        if any(item < 1 for item in value):
            raise ValueError("商品 ID 必须是正整数")
        return value


def _compact(payload: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in payload.items() if value is not None}


def _error_result(name: str, exc: StorefrontError) -> ToolResult:
    return ToolResult(
        name=name,
        status=ToolStatus.ERROR,
        payload={"errorCode": exc.code, "message": exc.message},
        facts=[f"{name} 执行失败：{exc.code}"],
        error_code=exc.code,
        error_message=exc.message,
    )


def _not_found_result(name: str, product_id: int, reason: str) -> ToolResult:
    return ToolResult(
        name=name,
        status=ToolStatus.PRODUCT_NOT_FOUND,
        payload={"productId": product_id, "reason": reason},
        facts=[f"商品 {product_id} 不可用：{reason}"],
    )


def _is_unavailable(detail: ProductDetail) -> bool:
    if (detail.delete_status or 0) == 1:
        return True
    return detail.publish_status is not None and detail.publish_status != 1


def summary_payload(detail: ProductDetail) -> dict[str, Any]:
    """统一的商品摘要字段，供卡片构造与会话恢复复用。"""

    return _compact(
        {
            "id": detail.id,
            "name": detail.name,
            "pic": detail.pic,
            "price": format_money(detail.price),
            "subtitle": detail.sub_title,
            "brandName": detail.brand_name,
            "categoryName": detail.product_category_name,
            "sale": detail.sale,
            "availableStock": detail.available_stock,
            "stockStatus": str(detail.stock_status),
        }
    )


async def search_products(params: SearchProductsParams, context: ToolContext) -> ToolResult:
    query = ProductSearchQuery(
        keyword=params.keyword,
        brand_id=params.brand_id,
        product_category_id=params.product_category_id,
        sort=params.sort,
        page_num=params.page_num,
        page_size=MAX_SEARCH_PAGE_SIZE,
    )

    try:
        page = await context.backend.search_products(query)
    except StorefrontError as exc:
        return _error_result(SEARCH_PRODUCTS, exc)

    products = [
        _compact(
            {
                "id": item.id,
                "name": item.name,
                "pic": item.pic,
                "price": format_money(item.price),
                "subtitle": item.sub_title,
                "brandName": item.brand_name,
                "categoryName": item.product_category_name,
                "sale": item.sale,
                "stock": item.stock,
            }
        )
        for item in page.items[:MAX_SEARCH_PRODUCTS]
    ]

    conditions = _compact(
        {
            "keyword": params.keyword,
            "brandId": params.brand_id,
            "productCategoryId": params.product_category_id,
            "sort": params.sort,
        }
    )

    return ToolResult(
        name=SEARCH_PRODUCTS,
        payload={
            "type": "search",
            "conditions": conditions,
            "pageNum": page.page_num,
            "total": page.total,
            "returnedCount": len(products),
            "maxProductsPerSearch": MAX_SEARCH_PRODUCTS,
            "products": products,
            "note": (
                "候选商品来自门户当前搜索结果；预算倾向只用于排序和解释，"
                "不代表数据库级价格区间检索。"
            ),
        },
        facts=[f"搜索返回 {len(products)} 件候选商品（共 {page.total} 件）"],
    )


async def get_product_detail(params: ProductIdParams, context: ToolContext) -> ToolResult:
    try:
        detail = await context.backend.get_product_detail(params.product_id)
    except StorefrontNotFoundError:
        return _not_found_result(GET_PRODUCT_DETAIL, params.product_id, "未找到该商品或商品已下架")
    except StorefrontError as exc:
        return _error_result(GET_PRODUCT_DETAIL, exc)

    if _is_unavailable(detail):
        return _not_found_result(
            GET_PRODUCT_DETAIL, params.product_id, "该商品已下架，无法提供详情"
        )

    payload: dict[str, Any] = {
        "type": "detail",
        "product": summary_payload(detail),
        "description": detail.description,
        "productSn": detail.product_sn,
        "availableStock": detail.available_stock,
        "stockStatus": str(detail.stock_status),
        "skuCount": len(detail.sku_stocks),
        "skuStocks": [
            _compact(
                {
                    "id": sku.id,
                    "skuCode": sku.sku_code,
                    "price": format_money(sku.price),
                    "availableStock": sku.available_stock,
                    "spData": sku.sp_data,
                }
            )
            for sku in detail.sku_stocks[:MAX_DETAIL_SKUS]
        ],
        "attributes": [
            {"name": attribute.name, "value": attribute.value} for attribute in detail.attributes
        ],
        "publicCoupons": [
            _compact(
                {
                    "id": coupon.id,
                    "name": coupon.name,
                    "amount": format_money(coupon.amount),
                    "minPoint": format_money(coupon.min_point),
                    "useType": coupon.use_type,
                    "endTime": coupon.end_time.isoformat() if coupon.end_time else None,
                }
            )
            for coupon in detail.public_coupons[:MAX_DETAIL_PUBLIC_COUPONS]
        ],
    }
    return ToolResult(
        name=GET_PRODUCT_DETAIL,
        payload=payload,
        facts=[
            f"商品 {detail.id} 可售库存 {detail.available_stock}，库存状态 {detail.stock_status}",
            f"商品 {detail.id} 共 {len(detail.sku_stocks)} 个 SKU",
        ],
    )


def _cheapest_sku_price(detail: ProductDetail) -> str | None:
    prices = [sku.price for sku in detail.sku_stocks if isinstance(sku.price, Decimal)]
    if not prices:
        return format_money(detail.price)
    return format_money(min(prices))


def _comparison_item(detail: ProductDetail) -> dict[str, Any]:
    return _compact(
        {
            "productId": detail.id,
            "status": ToolStatus.OK.value,
            "name": detail.name,
            "pic": detail.pic,
            "price": format_money(detail.price),
            "subtitle": detail.sub_title,
            "brandName": detail.brand_name,
            "categoryName": detail.product_category_name,
            "availableStock": detail.available_stock,
            "stockStatus": str(detail.stock_status),
            "skuCount": len(detail.sku_stocks),
            "cheapestSkuPrice": _cheapest_sku_price(detail),
            "attributes": [
                {"name": attribute.name, "value": attribute.value}
                for attribute in detail.attributes[:MAX_COMPARE_ATTRIBUTES]
            ],
        }
    )


async def compare_products(params: CompareProductsParams, context: ToolContext) -> ToolResult:
    items: list[dict[str, Any]] = []
    any_ok = False
    any_error = False

    for product_id in params.product_ids:
        try:
            detail = await context.backend.get_product_detail(product_id)
        except StorefrontNotFoundError:
            items.append({"productId": product_id, "status": ToolStatus.PRODUCT_NOT_FOUND.value})
            continue
        except StorefrontError:
            items.append({"productId": product_id, "status": ToolStatus.ERROR.value})
            any_error = True
            continue

        if _is_unavailable(detail):
            items.append(
                {
                    "productId": product_id,
                    "status": ToolStatus.PRODUCT_NOT_FOUND.value,
                    "reason": "该商品已下架",
                }
            )
            continue

        any_ok = True
        items.append(_comparison_item(detail))

    if any_ok:
        status = ToolStatus.OK
    elif any_error:
        status = ToolStatus.ERROR
    else:
        status = ToolStatus.PRODUCT_NOT_FOUND

    missing = [item["productId"] for item in items if item.get("status") != ToolStatus.OK.value]

    return ToolResult(
        name=COMPARE_PRODUCTS,
        status=status,
        payload={
            "type": "comparison",
            "items": items,
            "missingProductIds": missing,
            "note": "缺失、已下架或详情异常的商品只在状态中标记，不会由模型补全字段。",
        },
        facts=[f"比较 {len(params.product_ids)} 件商品，其中 {len(missing)} 件不可用"],
        error_code=None if any_ok else "STOREFRONT_UNAVAILABLE",
        error_message=None if any_ok else "商品数据暂时无法获取",
    )

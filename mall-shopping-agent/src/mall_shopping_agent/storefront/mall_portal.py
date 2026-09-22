"""mall-portal 只读实现。

只允许下列五个 GET 请求，Authorization 仅用于三条会员接口并且原样透传：

``GET /product/search``、``GET /product/detail/{id}``、``GET /sso/info``、
``GET /member/coupon/listHistory?useStatus=0``、``GET /member/coupon/listByProduct/{productId}``。
"""

from __future__ import annotations

import json
from decimal import Decimal
from typing import Any

import httpx

from mall_shopping_agent.storefront.backend import (
    MemberUnauthorizedError,
    StorefrontNotFoundError,
    StorefrontProtocolError,
    StorefrontUnavailableError,
)
from mall_shopping_agent.storefront.schemas import (
    Coupon,
    CouponHistory,
    MemberIdentity,
    ProductAttribute,
    ProductDetail,
    ProductSearchPage,
    ProductSearchQuery,
    SkuStock,
)

MAX_ATTRIBUTES = 10


def _as_int(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError:
            return None
    return None


def _parse_sku_stocks(raw: Any) -> list[SkuStock]:
    """解析全部 SKU：可售库存总量必须基于完整列表，展示上限由工具层控制。"""

    if not isinstance(raw, list):
        return []
    result: list[SkuStock] = []
    for item in raw:
        if not isinstance(item, dict) or _as_int(item.get("id")) is None:
            continue
        try:
            result.append(SkuStock.model_validate(item))
        except ValueError:
            continue
    return result


def _parse_attributes(raw_attributes: Any, raw_values: Any) -> list[ProductAttribute]:
    value_by_attribute: dict[int, str] = {}
    if isinstance(raw_values, list):
        for item in raw_values:
            if not isinstance(item, dict):
                continue
            attribute_id = _as_int(item.get("productAttributeId"))
            value = item.get("value")
            if attribute_id is None or value is None:
                continue
            value_by_attribute[attribute_id] = str(value)

    if not isinstance(raw_attributes, list):
        return []

    result: list[ProductAttribute] = []
    for item in raw_attributes:
        if not isinstance(item, dict):
            continue
        attribute_id = _as_int(item.get("id"))
        name = item.get("name")
        if attribute_id is None or not name:
            continue
        value = value_by_attribute.get(attribute_id)
        if value is None:
            # 只保留有取值的属性，避免把空属性当成商品事实
            continue
        result.append(ProductAttribute(name=str(name), value=value))
        if len(result) >= MAX_ATTRIBUTES:
            break
    return result


def parse_coupons(raw: Any) -> list[Coupon]:
    if not isinstance(raw, list):
        return []
    result: list[Coupon] = []
    for item in raw:
        if not isinstance(item, dict) or _as_int(item.get("id")) is None:
            continue
        try:
            result.append(Coupon.model_validate(item))
        except ValueError:
            continue
    return result


def parse_product_detail(data: Any) -> ProductDetail:
    """把 ``/product/detail/{id}`` 的 ``data`` 解析为领域模型。"""

    if not isinstance(data, dict):
        raise StorefrontNotFoundError("未找到对应商品")

    product = data.get("product")
    if not isinstance(product, dict) or _as_int(product.get("id")) is None:
        raise StorefrontNotFoundError("未找到对应商品")

    payload: dict[str, Any] = dict(product)
    payload["skuStocks"] = [
        sku.model_dump(by_alias=True) for sku in _parse_sku_stocks(data.get("skuStockList"))
    ]
    payload["attributes"] = [
        attribute.model_dump(by_alias=True)
        for attribute in _parse_attributes(
            data.get("productAttributeList"), data.get("productAttributeValueList")
        )
    ]
    payload["publicCoupons"] = [
        coupon.model_dump(by_alias=True) for coupon in parse_coupons(data.get("couponList"))
    ]

    try:
        return ProductDetail.model_validate(payload)
    except ValueError as exc:
        raise StorefrontProtocolError("门户商品详情结构无法解析") from exc


class MallPortalBackend:
    """基于 HTTPX 的 mall-portal 只读客户端。"""

    def __init__(
        self,
        *,
        base_url: str,
        timeout_seconds: float = 10.0,
        transport: httpx.AsyncBaseTransport | None = None,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            timeout=httpx.Timeout(
                timeout_seconds,
                connect=timeout_seconds,
                read=timeout_seconds,
                write=timeout_seconds,
                pool=timeout_seconds,
            ),
            transport=transport,
        )

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    # -- 内部请求 ---------------------------------------------------------

    def _url(self, path: str) -> str:
        return f"{self._base_url}{path}"

    async def _get(
        self,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        authorization: str | None = None,
        server_error_is_not_found: bool = False,
    ) -> Any:
        headers = {"Accept": "application/json"}
        if authorization:
            headers["Authorization"] = authorization

        try:
            response = await self._client.get(self._url(path), params=params, headers=headers)
        except httpx.TimeoutException:
            raise StorefrontUnavailableError("门户服务响应超时") from None
        except httpx.HTTPError:
            raise StorefrontUnavailableError("门户服务暂时不可用") from None

        return self._parse(
            response,
            server_error_is_not_found=server_error_is_not_found,
        )

    @staticmethod
    def _parse(response: httpx.Response, *, server_error_is_not_found: bool) -> Any:
        status = response.status_code

        if status in {401, 403}:
            raise MemberUnauthorizedError("会员登录状态已失效")

        payload: Any = None
        try:
            # parse_float=Decimal 保留门户返回的金额精度（如 50.00 不会被降为 50.0）
            payload = json.loads(response.content, parse_float=Decimal)
        except ValueError:
            payload = None

        if isinstance(payload, dict) and "code" in payload:
            code = payload.get("code")
            if code == 200:
                return payload.get("data")
            if code == 401:
                raise MemberUnauthorizedError("会员登录状态已失效")
            if code == 404:
                raise StorefrontNotFoundError("门户未找到对应资源")
            raise StorefrontUnavailableError("门户返回业务错误")

        if status == 404:
            raise StorefrontNotFoundError("未找到对应商品")
        if status == 500 and server_error_is_not_found:
            # 门户详情实现对不存在的商品会抛出未捕获异常，返回 Spring 默认 500 结构
            raise StorefrontNotFoundError("未找到对应商品")
        if status >= 400:
            raise StorefrontUnavailableError("门户服务暂时不可用")

        raise StorefrontProtocolError("门户返回结构无法解析")

    @staticmethod
    def _require_positive_id(product_id: int) -> int:
        if not isinstance(product_id, int) or isinstance(product_id, bool) or product_id <= 0:
            raise ValueError("product_id 必须是正整数")
        return product_id

    # -- 只读接口 ---------------------------------------------------------

    async def search_products(self, query: ProductSearchQuery) -> ProductSearchPage:
        params: dict[str, Any] = {
            "pageNum": query.page_num,
            "pageSize": query.page_size,
            "sort": query.sort,
        }
        if query.keyword:
            params["keyword"] = query.keyword
        if query.brand_id is not None:
            params["brandId"] = query.brand_id
        if query.product_category_id is not None:
            params["productCategoryId"] = query.product_category_id

        data = await self._get("/product/search", params=params)

        if data is None:
            return ProductSearchPage(pageNum=query.page_num, pageSize=query.page_size, list=[])

        if not isinstance(data, dict):
            raise StorefrontProtocolError("门户搜索结构无法解析")

        try:
            return ProductSearchPage.model_validate(data)
        except ValueError as exc:
            raise StorefrontProtocolError("门户搜索结构无法解析") from exc

    async def get_product_detail(self, product_id: int) -> ProductDetail:
        self._require_positive_id(product_id)
        data = await self._get(
            f"/product/detail/{product_id}",
            server_error_is_not_found=True,
        )
        return parse_product_detail(data)

    async def resolve_member(self, authorization: str) -> MemberIdentity:
        data = await self._get("/sso/info", authorization=authorization)
        if not isinstance(data, dict) or _as_int(data.get("id")) is None:
            raise MemberUnauthorizedError("会员登录状态已失效")
        try:
            return MemberIdentity.model_validate(data)
        except ValueError as exc:
            raise MemberUnauthorizedError("会员登录状态已失效") from exc

    async def list_unused_coupon_history(self, authorization: str) -> list[CouponHistory]:
        data = await self._get(
            "/member/coupon/listHistory",
            params={"useStatus": 0},
            authorization=authorization,
        )
        if data is None:
            return []
        if not isinstance(data, list):
            raise StorefrontProtocolError("门户优惠券历史结构无法解析")

        result: list[CouponHistory] = []
        for item in data:
            if not isinstance(item, dict) or _as_int(item.get("id")) is None:
                continue
            try:
                result.append(CouponHistory.model_validate(item))
            except ValueError:
                continue
        return result

    async def list_product_coupons(self, product_id: int, authorization: str) -> list[Coupon]:
        self._require_positive_id(product_id)
        data = await self._get(
            f"/member/coupon/listByProduct/{product_id}",
            authorization=authorization,
            server_error_is_not_found=True,
        )
        if data is None:
            return []
        if not isinstance(data, list):
            raise StorefrontProtocolError("门户商品优惠券结构无法解析")
        return parse_coupons(data)

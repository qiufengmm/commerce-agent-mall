"""商品卡片构造。

卡片字段只能来自本轮服务端工具事实；模型可以决定候选商品 ID，但无法提供
价格、库存、图片或跳转路径。
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from mall_shopping_agent.storefront.schemas import StockStatus, stock_status_for
from mall_shopping_agent.tools.registry import ToolResult, ToolStatus

DETAIL_PATH_TEMPLATE = "/pages/product/product?id={product_id}"
MAX_CARDS = 5

# 事实来源优先级：详情 / 比较 > 搜索结果
SOURCE_RANK_SEARCH = 1
SOURCE_RANK_COMPARE = 2
SOURCE_RANK_DETAIL = 3


def build_detail_path(product_id: int) -> str:
    if not isinstance(product_id, int) or isinstance(product_id, bool) or product_id <= 0:
        raise ValueError("product_id 必须是正整数")
    return DETAIL_PATH_TEMPLATE.format(product_id=product_id)


@dataclass(slots=True)
class ProductFact:
    """单个商品的卡片事实，全部由服务端构造。"""

    product_id: int
    name: str = ""
    pic: str | None = None
    price: str | None = None
    subtitle: str | None = None
    available_stock: int = 0
    stock_status: str = StockStatus.OUT_OF_STOCK.value
    source_rank: int = SOURCE_RANK_SEARCH

    def to_card(self) -> dict[str, Any]:
        return {
            "id": self.product_id,
            "name": self.name,
            "pic": self.pic,
            "price": self.price,
            "subtitle": self.subtitle,
            "stockStatus": self.stock_status,
            "availableStock": self.available_stock,
            "detailPath": build_detail_path(self.product_id),
        }


def _as_int(value: Any) -> int | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.strip().isdigit():
        return int(value.strip())
    return None


class ProductFactCollector:
    """按工具结果累积商品事实，并保留来源优先级。"""

    def __init__(self) -> None:
        self._facts: dict[int, ProductFact] = {}

    @property
    def facts(self) -> Mapping[int, ProductFact]:
        return dict(self._facts)

    def add(self, result: ToolResult) -> None:
        if result.status is not ToolStatus.OK:
            return

        if result.name == "searchProducts":
            for item in result.payload.get("products", []):
                product_id = _as_int(item.get("id"))
                if product_id is None:
                    continue
                stock = _as_int(item.get("stock")) or 0
                self._upsert(
                    ProductFact(
                        product_id=product_id,
                        name=str(item.get("name") or ""),
                        pic=item.get("pic"),
                        price=item.get("price"),
                        subtitle=item.get("subtitle"),
                        available_stock=max(stock, 0),
                        stock_status=str(stock_status_for(max(stock, 0))),
                        source_rank=SOURCE_RANK_SEARCH,
                    )
                )

        elif result.name == "getProductDetail":
            product = result.payload.get("product") or {}
            product_id = _as_int(product.get("id"))
            if product_id is None:
                return
            self._upsert(
                ProductFact(
                    product_id=product_id,
                    name=str(product.get("name") or ""),
                    pic=product.get("pic"),
                    price=product.get("price"),
                    subtitle=product.get("subtitle"),
                    available_stock=_as_int(result.payload.get("availableStock")) or 0,
                    stock_status=str(
                        result.payload.get("stockStatus") or StockStatus.OUT_OF_STOCK.value
                    ),
                    source_rank=SOURCE_RANK_DETAIL,
                )
            )

        elif result.name == "compareProducts":
            for item in result.payload.get("items", []):
                if item.get("status") != ToolStatus.OK.value:
                    continue
                product_id = _as_int(item.get("productId"))
                if product_id is None:
                    continue
                self._upsert(
                    ProductFact(
                        product_id=product_id,
                        name=str(item.get("name") or ""),
                        pic=item.get("pic"),
                        price=item.get("price"),
                        subtitle=item.get("subtitle"),
                        available_stock=_as_int(item.get("availableStock")) or 0,
                        stock_status=str(item.get("stockStatus") or StockStatus.OUT_OF_STOCK.value),
                        source_rank=SOURCE_RANK_COMPARE,
                    )
                )

    def _upsert(self, fact: ProductFact) -> None:
        existing = self._facts.get(fact.product_id)
        if existing is not None and existing.source_rank > fact.source_rank:
            return
        if existing is not None:
            # 保留更高优先级来源已有的图片字段
            fact.pic = fact.pic or existing.pic
            fact.subtitle = fact.subtitle or existing.subtitle
        self._facts[fact.product_id] = fact


class PresentationBuilder:
    """去重候选商品 ID，构造最多 ``max_cards`` 张服务端卡片。"""

    def __init__(self, *, max_cards: int = MAX_CARDS) -> None:
        self._max_cards = max(1, max_cards)

    def build(
        self,
        selected_ids: Sequence[int],
        facts: Mapping[int, ProductFact],
    ) -> list[dict[str, Any]]:
        ordered: list[int] = []

        for product_id in selected_ids:
            if product_id in facts and product_id not in ordered:
                ordered.append(product_id)
            if len(ordered) >= self._max_cards:
                break

        if not ordered:
            # 模型没有给出可用选择时，退回本轮工具结果顺序
            for product_id in facts:
                ordered.append(product_id)
                if len(ordered) >= self._max_cards:
                    break

        return [facts[product_id].to_card() for product_id in ordered]

from __future__ import annotations

import pytest

from mall_shopping_agent.presentation.products import (
    DETAIL_PATH_TEMPLATE,
    PresentationBuilder,
    ProductFact,
    ProductFactCollector,
    build_detail_path,
)
from mall_shopping_agent.tools.registry import ToolResult, ToolStatus


def fact(product_id: int, **overrides: object) -> ProductFact:
    payload: dict[str, object] = {
        "product_id": product_id,
        "name": f"示例商品 {product_id}",
        "pic": f"http://localhost:9000/mall/{product_id}.jpg",
        "price": "1999.00",
        "subtitle": "副标题",
        "available_stock": 50,
        "stock_status": "IN_STOCK",
    }
    payload.update(overrides)
    return ProductFact(**payload)  # type: ignore[arg-type]


def test_detail_path_is_fixed_and_server_controlled() -> None:
    assert build_detail_path(27) == "/pages/product/product?id=27"
    assert DETAIL_PATH_TEMPLATE.format(product_id=27) == "/pages/product/product?id=27"


@pytest.mark.parametrize("value", [0, -1, True, "27", None, 1.5])
def test_invalid_product_id_is_rejected(value: object) -> None:
    with pytest.raises(ValueError):
        build_detail_path(value)  # type: ignore[arg-type]


def test_cards_are_deduplicated_and_keep_selection_order() -> None:
    builder = PresentationBuilder(max_cards=5)
    facts = {27: fact(27), 26: fact(26), 28: fact(28)}

    cards = builder.build([28, 27, 28, 26], facts)

    assert [card["id"] for card in cards] == [28, 27, 26]


def test_cards_are_capped_at_five() -> None:
    builder = PresentationBuilder(max_cards=5)
    facts = {product_id: fact(product_id) for product_id in range(1, 12)}

    cards = builder.build(list(facts), facts)

    assert len(cards) == 5


def test_unknown_selected_ids_are_dropped() -> None:
    builder = PresentationBuilder(max_cards=5)
    facts = {27: fact(27)}

    cards = builder.build([999999, 27, -1], facts)

    assert [card["id"] for card in cards] == [27]


def test_empty_selection_falls_back_to_tool_fact_order() -> None:
    builder = PresentationBuilder(max_cards=5)
    facts = {26: fact(26), 27: fact(27), 28: fact(28)}

    cards = builder.build([], facts)

    assert [card["id"] for card in cards] == [26, 27, 28]


def test_card_contains_only_server_side_fields() -> None:
    builder = PresentationBuilder(max_cards=5)
    facts = {27: fact(27, price="2999.00", available_stock=112)}

    card = builder.build([27], facts)[0]

    assert card == {
        "id": 27,
        "name": "示例商品 27",
        "pic": "http://localhost:9000/mall/27.jpg",
        "price": "2999.00",
        "subtitle": "副标题",
        "stockStatus": "IN_STOCK",
        "availableStock": 112,
        "detailPath": "/pages/product/product?id=27",
    }


def test_collector_prefers_detail_facts_over_search_facts() -> None:
    collector = ProductFactCollector()

    collector.add(
        ToolResult(
            name="searchProducts",
            payload={
                "products": [
                    {"id": 27, "name": "搜索名称", "price": "2999.00", "stock": 999},
                ]
            },
        )
    )
    collector.add(
        ToolResult(
            name="getProductDetail",
            payload={
                "product": {"id": 27, "name": "详情名称", "price": "2999.00", "pic": "p.jpg"},
                "availableStock": 12,
                "stockStatus": "LOW_STOCK",
            },
        )
    )

    collected = collector.facts[27]
    assert collected.name == "详情名称"
    assert collected.available_stock == 12
    assert collected.stock_status == "LOW_STOCK"
    assert collected.pic == "p.jpg"


def test_collector_ignores_failed_and_not_found_results() -> None:
    collector = ProductFactCollector()

    collector.add(
        ToolResult(
            name="searchProducts",
            status=ToolStatus.ERROR,
            payload={"products": [{"id": 27, "name": "不应出现", "price": "1.00"}]},
        )
    )
    collector.add(
        ToolResult(
            name="getProductDetail",
            status=ToolStatus.PRODUCT_NOT_FOUND,
            payload={"productId": 27},
        )
    )

    assert collector.facts == {}


def test_collector_derives_stock_status_from_search_stock() -> None:
    collector = ProductFactCollector()

    collector.add(
        ToolResult(
            name="searchProducts",
            payload={
                "products": [
                    {"id": 1, "name": "无货", "stock": 0},
                    {"id": 2, "name": "少量", "stock": 3},
                    {"id": 3, "name": "充足", "stock": 300},
                ]
            },
        )
    )

    assert collector.facts[1].stock_status == "OUT_OF_STOCK"
    assert collector.facts[2].stock_status == "LOW_STOCK"
    assert collector.facts[3].stock_status == "IN_STOCK"


def test_collector_reads_compare_items() -> None:
    collector = ProductFactCollector()

    collector.add(
        ToolResult(
            name="compareProducts",
            payload={
                "items": [
                    {
                        "productId": 27,
                        "status": "OK",
                        "name": "示例手机 B",
                        "price": "2999.00",
                        "availableStock": 112,
                        "stockStatus": "IN_STOCK",
                    },
                    {"productId": 999999, "status": "PRODUCT_NOT_FOUND"},
                ]
            },
        )
    )

    assert list(collector.facts) == [27]
    assert collector.facts[27].available_stock == 112

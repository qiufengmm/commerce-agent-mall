from __future__ import annotations

from mall_shopping_agent.storefront.backend import StorefrontUnavailableError
from mall_shopping_agent.storefront.schemas import ProductDetail, ProductSearchPage
from mall_shopping_agent.tools import create_tool_registry
from mall_shopping_agent.tools.registry import ToolStatus
from support.fake_storefront import (
    FakeStorefrontBackend,
    build_detail,
    build_summary,
    portal_fixture,
)
from support.registry_fixture import invoke

DETAIL_ID = 27
MEMBER_TOKEN = "Bearer placeholder-member-token"
MAX_COMPARE_ATTRIBUTES = 6


def detail_for(product_id: int = DETAIL_ID) -> ProductDetail:
    data = portal_fixture("product_detail.json")["data"]
    payload = {**data, "product": {**data["product"], "id": product_id}}
    return build_detail(payload)


# --------------------------------------------------------------------------- #
# searchProducts
# --------------------------------------------------------------------------- #


async def test_search_returns_server_constructed_facts_with_two_decimal_prices() -> None:
    backend = FakeStorefrontBackend()
    backend.search_page = ProductSearchPage(
        pageNum=1,
        pageSize=5,
        totalPage=1,
        total=2,
        list=[
            build_summary(id=26, name="示例手机 A", price="1899.00"),
            build_summary(id=27, name="示例手机 B", price="2999.50"),
        ],
    )
    registry = create_tool_registry()

    result = await invoke(registry, backend, "searchProducts", {"keyword": "手机", "sort": 3})

    assert result.status is ToolStatus.OK
    assert result.payload["pageNum"] == 1
    assert result.payload["total"] == 2
    assert result.payload["maxProductsPerSearch"] == 5
    assert [item["id"] for item in result.payload["products"]] == [26, 27]
    assert [item["price"] for item in result.payload["products"]] == ["1899.00", "2999.50"]
    assert backend.call_names == ["search_products"]
    assert backend.last[1]["query"]["pageSize"] == 5
    assert backend.last[1]["query"]["keyword"] == "手机"


async def test_search_never_sends_authorization_even_when_present() -> None:
    backend = FakeStorefrontBackend()
    registry = create_tool_registry()

    await invoke(registry, backend, "searchProducts", {}, authorization=MEMBER_TOKEN)

    assert "authorization" not in backend.last[1]


async def test_search_limits_returned_products_to_five() -> None:
    backend = FakeStorefrontBackend()
    backend.search_page = ProductSearchPage(
        pageNum=1,
        pageSize=5,
        totalPage=3,
        total=15,
        list=[build_summary(id=index) for index in range(26, 36)],
    )
    registry = create_tool_registry()

    result = await invoke(registry, backend, "searchProducts", {})

    assert len(result.payload["products"]) == 5


async def test_search_maps_portal_failure_to_structured_error() -> None:
    backend = FakeStorefrontBackend()
    backend.errors["search_products"] = StorefrontUnavailableError("门户超时")
    registry = create_tool_registry()

    result = await invoke(registry, backend, "searchProducts", {"keyword": "手机"})

    assert result.status is ToolStatus.ERROR
    assert result.error_code == "STOREFRONT_UNAVAILABLE"
    assert "products" not in result.payload


# --------------------------------------------------------------------------- #
# getProductDetail
# --------------------------------------------------------------------------- #


async def test_detail_aggregates_sku_stock_and_maps_status() -> None:
    backend = FakeStorefrontBackend()
    backend.details[DETAIL_ID] = detail_for()
    registry = create_tool_registry()

    result = await invoke(registry, backend, "getProductDetail", {"productId": DETAIL_ID})

    assert result.status is ToolStatus.OK
    assert result.payload["availableStock"] == 112
    assert result.payload["stockStatus"] == "IN_STOCK"
    assert [sku["availableStock"] for sku in result.payload["skuStocks"]] == [100, 0, 12]
    assert result.payload["product"]["price"] == "2999.00"
    assert backend.call_names == ["get_product_detail"]


async def test_detail_stock_status_thresholds() -> None:
    registry = create_tool_registry()

    cases = (
        ([{"id": 1, "stock": 0, "lockStock": 0}], "OUT_OF_STOCK", 0),
        ([{"id": 1, "stock": 5, "lockStock": 4}], "LOW_STOCK", 1),
        ([{"id": 1, "stock": 10, "lockStock": 0}], "LOW_STOCK", 10),
        ([{"id": 1, "stock": 11, "lockStock": 0}], "IN_STOCK", 11),
        ([{"id": 1, "stock": 3, "lockStock": 99}], "OUT_OF_STOCK", 0),
    )

    for skus, expected_status, expected_available in cases:
        backend = FakeStorefrontBackend()
        data = portal_fixture("product_detail.json")["data"]
        backend.details[DETAIL_ID] = build_detail({**data, "skuStockList": skus})

        result = await invoke(registry, backend, "getProductDetail", {"productId": DETAIL_ID})

        assert result.payload["stockStatus"] == expected_status
        assert result.payload["availableStock"] == expected_available


async def test_detail_exposes_attributes_and_public_coupons() -> None:
    backend = FakeStorefrontBackend()
    backend.details[DETAIL_ID] = detail_for()
    registry = create_tool_registry()

    result = await invoke(registry, backend, "getProductDetail", {"productId": DETAIL_ID})

    assert result.payload["attributes"] == [
        {"name": "颜色", "value": "金色"},
        {"name": "内存", "value": "8GB"},
    ]
    assert result.payload["publicCoupons"][0]["id"] == 55
    assert result.payload["publicCoupons"][0]["amount"] == "50.00"


async def test_detail_reports_not_found_without_inventing_facts() -> None:
    backend = FakeStorefrontBackend()
    registry = create_tool_registry()

    result = await invoke(registry, backend, "getProductDetail", {"productId": 999999})

    assert result.status is ToolStatus.PRODUCT_NOT_FOUND
    assert result.payload["productId"] == 999999
    assert "product" not in result.payload


async def test_detail_marks_unpublished_product_as_not_available() -> None:
    backend = FakeStorefrontBackend()
    data = portal_fixture("product_detail.json")["data"]
    backend.details[DETAIL_ID] = build_detail(
        {**data, "product": {**data["product"], "publishStatus": 0}}
    )
    registry = create_tool_registry()

    result = await invoke(registry, backend, "getProductDetail", {"productId": DETAIL_ID})

    assert result.status is ToolStatus.PRODUCT_NOT_FOUND
    assert "已下架" in result.payload["reason"]


async def test_detail_truncates_attributes_and_sku_list() -> None:
    backend = FakeStorefrontBackend()
    data = portal_fixture("product_detail.json")["data"]
    many_skus = [{"id": 1000 + index, "stock": 1, "lockStock": 0} for index in range(40)]
    backend.details[DETAIL_ID] = build_detail({**data, "skuStockList": many_skus})
    registry = create_tool_registry()

    result = await invoke(registry, backend, "getProductDetail", {"productId": DETAIL_ID})

    assert len(result.payload["skuStocks"]) <= 10
    assert result.payload["availableStock"] == 40


async def test_detail_maps_portal_failure_to_structured_error() -> None:
    backend = FakeStorefrontBackend()
    backend.errors["get_product_detail"] = StorefrontUnavailableError("门户不可用")
    registry = create_tool_registry()

    result = await invoke(registry, backend, "getProductDetail", {"productId": DETAIL_ID})

    assert result.status is ToolStatus.ERROR
    assert result.error_code == "STOREFRONT_UNAVAILABLE"


# --------------------------------------------------------------------------- #
# compareProducts
# --------------------------------------------------------------------------- #


async def test_compare_uses_detail_facts_for_each_product() -> None:
    backend = FakeStorefrontBackend()
    backend.details[27] = detail_for(27)
    backend.details[26] = detail_for(26)
    registry = create_tool_registry()

    result = await invoke(registry, backend, "compareProducts", {"productIds": [27, 26]})

    assert result.status is ToolStatus.OK
    items = result.payload["items"]
    assert [item["productId"] for item in items] == [27, 26]
    assert items[0]["stockStatus"] == "IN_STOCK"
    assert items[0]["availableStock"] == 112
    assert items[0]["price"] == "2999.00"
    assert backend.call_names == ["get_product_detail", "get_product_detail"]


async def test_compare_marks_missing_product_without_model_filling() -> None:
    backend = FakeStorefrontBackend()
    backend.details[27] = detail_for(27)
    registry = create_tool_registry()

    result = await invoke(registry, backend, "compareProducts", {"productIds": [27, 999999]})

    items = {item["productId"]: item for item in result.payload["items"]}
    assert items[27]["status"] == "OK"
    assert items[999999]["status"] == "PRODUCT_NOT_FOUND"
    assert "price" not in items[999999]
    assert "name" not in items[999999]


async def test_compare_reports_error_status_per_product() -> None:
    backend = FakeStorefrontBackend()
    backend.errors["get_product_detail"] = StorefrontUnavailableError("门户不可用")
    registry = create_tool_registry()

    result = await invoke(registry, backend, "compareProducts", {"productIds": [27, 26]})

    assert result.status is ToolStatus.ERROR
    assert {item["status"] for item in result.payload["items"]} == {"ERROR"}


async def test_compare_includes_core_comparison_fields() -> None:
    backend = FakeStorefrontBackend()
    data = portal_fixture("product_detail.json")["data"]
    backend.details[27] = build_detail(data)
    backend.details[26] = build_detail(
        {
            **data,
            "product": {**data["product"], "id": 26, "name": "示例手机 A"},
            "productAttributeList": [],
            "productAttributeValueList": [],
        }
    )
    registry = create_tool_registry()

    result = await invoke(registry, backend, "compareProducts", {"productIds": [27, 26]})

    item = result.payload["items"][0]
    assert set(item) >= {
        "productId",
        "status",
        "name",
        "price",
        "brandName",
        "categoryName",
        "availableStock",
        "stockStatus",
        "skuCount",
        "attributes",
    }
    assert item["attributes"] == [
        {"name": "颜色", "value": "金色"},
        {"name": "内存", "value": "8GB"},
    ]


async def test_compare_attribute_limit_is_respected() -> None:
    backend = FakeStorefrontBackend()
    data = portal_fixture("product_detail.json")["data"]
    attributes = [{"id": 200 + index, "name": f"属性{index}"} for index in range(20)]
    values = [{"productAttributeId": 200 + index, "value": f"值{index}"} for index in range(20)]
    backend.details[27] = build_detail(
        {**data, "productAttributeList": attributes, "productAttributeValueList": values}
    )
    backend.details[26] = detail_for(26)
    registry = create_tool_registry()

    result = await invoke(registry, backend, "compareProducts", {"productIds": [27, 26]})

    assert result.status is ToolStatus.OK
    assert len(result.payload["items"][0]["attributes"]) <= MAX_COMPARE_ATTRIBUTES

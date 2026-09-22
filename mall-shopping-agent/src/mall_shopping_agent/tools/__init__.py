"""只读工具注册表装配。

注册表只包含四个工具：``searchProducts``、``getProductDetail``、
``compareProducts``、``getMemberCouponsForProduct``。
"""

from __future__ import annotations

from mall_shopping_agent.tools.coupon_tools import (
    GET_MEMBER_COUPONS_FOR_PRODUCT,
    MemberCouponsParams,
    build_coupon_explanations,
    get_member_coupons_for_product,
)
from mall_shopping_agent.tools.product_tools import (
    COMPARE_PRODUCTS,
    GET_PRODUCT_DETAIL,
    SEARCH_PRODUCTS,
    CompareProductsParams,
    ProductIdParams,
    SearchProductsParams,
    compare_products,
    get_product_detail,
    search_products,
)
from mall_shopping_agent.tools.registry import (
    InvalidToolArgumentsError,
    ToolContext,
    ToolDefinition,
    ToolError,
    ToolRegistry,
    ToolResult,
    ToolStatus,
    UnknownToolError,
)

__all__ = [
    "COMPARE_PRODUCTS",
    "GET_MEMBER_COUPONS_FOR_PRODUCT",
    "GET_PRODUCT_DETAIL",
    "SEARCH_PRODUCTS",
    "CompareProductsParams",
    "InvalidToolArgumentsError",
    "MemberCouponsParams",
    "ProductIdParams",
    "SearchProductsParams",
    "ToolContext",
    "ToolDefinition",
    "ToolError",
    "ToolRegistry",
    "ToolResult",
    "ToolStatus",
    "UnknownToolError",
    "build_coupon_explanations",
    "compare_products",
    "create_tool_registry",
    "get_member_coupons_for_product",
    "get_product_detail",
    "search_products",
]


def create_tool_registry() -> ToolRegistry:
    """构造唯一的只读工具注册表。"""

    return ToolRegistry(
        (
            ToolDefinition(
                name=SEARCH_PRODUCTS,
                description=(
                    "按关键词、品牌或分类搜索在售商品，返回最多 5 件候选商品摘要。"
                    "预算倾向只用于排序和解释，不代表数据库级价格区间检索。"
                ),
                params_model=SearchProductsParams,
                handler=search_products,
            ),
            ToolDefinition(
                name=GET_PRODUCT_DETAIL,
                description=(
                    "按商品 ID 获取商品详情、SKU 可售库存、属性和公开优惠券。ID 必须是正整数。"
                ),
                params_model=ProductIdParams,
                handler=get_product_detail,
            ),
            ToolDefinition(
                name=COMPARE_PRODUCTS,
                description=(
                    "比较 2 到 3 件商品的价格、可售库存、品牌分类和关键属性；"
                    "不存在或已下架的商品只在状态中标记。"
                ),
                params_model=CompareProductsParams,
                handler=compare_products,
            ),
            ToolDefinition(
                name=GET_MEMBER_COUPONS_FOR_PRODUCT,
                description=(
                    "查询当前登录会员已领取且适用于指定商品的优惠券，并解释门槛、"
                    "适用范围和有效期；游客调用时返回需要登录。"
                ),
                params_model=MemberCouponsParams,
                handler=get_member_coupons_for_product,
                requires_member=True,
            ),
        )
    )

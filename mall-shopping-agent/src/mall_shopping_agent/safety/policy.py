"""交易写操作拒绝策略。

本策略只产生解释文本，不触发任何工具，也不改变任何数据。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

REFUSAL_NOTICE = "本智能导购只提供商品信息查询，不能代替您完成任何交易或数据写入操作。"


class RefusalCode(StrEnum):
    COUPON_CLAIM = "COUPON_CLAIM"
    CART_WRITE = "CART_WRITE"
    ORDER_CREATE = "ORDER_CREATE"
    ORDER_PAY = "ORDER_PAY"
    ORDER_CANCEL = "ORDER_CANCEL"
    ORDER_CONFIRM = "ORDER_CONFIRM"
    STOCK_WRITE = "STOCK_WRITE"
    ES_WRITE = "ES_WRITE"


@dataclass(frozen=True, slots=True)
class RefusalRule:
    code: RefusalCode
    keywords: tuple[str, ...]
    message: str


REFUSAL_RULES: tuple[RefusalRule, ...] = (
    RefusalRule(
        code=RefusalCode.COUPON_CLAIM,
        keywords=(
            "领取优惠券",
            "领优惠券",
            "帮我领券",
            "帮我领取",
            "领取这张",
            "领一张券",
            "领张券",
            "领个券",
            "claim coupon",
            "claim the coupon",
        ),
        message=(
            "我不能代您领取优惠券。请点击商品卡片进入详情页，"
            "在现有页面按流程领取；领取后我可以帮您解释可用的优惠券。"
        ),
    ),
    RefusalRule(
        code=RefusalCode.CART_WRITE,
        keywords=(
            "加入购物车",
            "加到购物车",
            "加购物车",
            "帮我加购",
            "添加购物车",
            "add to cart",
        ),
        message=("我不能把商品加入购物车。请点击商品卡片进入详情页，使用现有购物车按钮完成加购。"),
    ),
    RefusalRule(
        code=RefusalCode.ORDER_CREATE,
        keywords=(
            "帮我下单",
            "我要下单",
            "帮我下个单",
            "帮我下单吧",
            "提交订单",
            "创建订单",
            "直接下单",
            "帮我购买",
            "帮我买下",
            "帮我买这台",
            "place order",
            "place an order",
        ),
        message=("我不能代您下单。请点击商品卡片进入详情页，在现有页面确认规格和地址后提交订单。"),
    ),
    RefusalRule(
        code=RefusalCode.ORDER_PAY,
        keywords=(
            "帮我支付",
            "帮我付款",
            "去支付",
            "立即支付",
            "完成支付",
            "帮我付一下",
            "pay for me",
        ),
        message="我不能代您支付或付款。支付只能在现有订单页面由您本人完成。",
    ),
    RefusalRule(
        code=RefusalCode.ORDER_CANCEL,
        keywords=("取消订单", "帮我取消订单", "cancel my order", "cancel order"),
        message="我不能代您取消订单。请在“我的订单”页面按现有流程操作。",
    ),
    RefusalRule(
        code=RefusalCode.ORDER_CONFIRM,
        keywords=("确认收货", "帮我收货", "confirm receipt"),
        message="我不能代您确认收货。请在“我的订单”页面按现有流程操作。",
    ),
    RefusalRule(
        code=RefusalCode.STOCK_WRITE,
        keywords=(
            "修改库存",
            "改库存",
            "调整库存",
            "库存改成",
            "增加库存",
            "减少库存",
            "设置库存",
            "update stock",
            "change stock",
        ),
        message="我不能修改商品库存。库存由商城后台管理，我这边只做只读查询。",
    ),
    RefusalRule(
        code=RefusalCode.ES_WRITE,
        keywords=(
            "同步到 es",
            "同步 es",
            "同步es",
            "导入索引",
            "重建索引",
            "创建索引",
            "删除索引",
            "同步索引",
            "导入所有商品",
            "importall",
            "import all",
        ),
        message="我不能执行索引导入、创建、删除或同步操作。搜索索引只能由商城后台维护。",
    ),
)


def detect_refusal(message: str) -> RefusalRule | None:
    """检测消息是否要求执行交易写操作，命中则返回对应拒绝规则。"""

    text = (message or "").strip().lower()
    if not text:
        return None

    for rule in REFUSAL_RULES:
        for keyword in rule.keywords:
            if keyword.lower() in text:
                return rule
    return None


def refusal_answer(rule: RefusalRule) -> str:
    return f"{REFUSAL_NOTICE}{rule.message}"


def refusal_codes(message: str) -> list[RefusalCode]:
    text = (message or "").strip().lower()
    return [
        rule.code
        for rule in REFUSAL_RULES
        if any(keyword.lower() in text for keyword in rule.keywords)
    ]

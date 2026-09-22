from __future__ import annotations

import pytest

from mall_shopping_agent.safety.policy import (
    REFUSAL_NOTICE,
    RefusalCode,
    detect_refusal,
    refusal_answer,
    refusal_codes,
)


@pytest.mark.parametrize(
    ("message", "expected"),
    [
        ("帮我领取这张优惠券", RefusalCode.COUPON_CLAIM),
        ("帮我把这个加入购物车", RefusalCode.CART_WRITE),
        ("帮我下单这台手机", RefusalCode.ORDER_CREATE),
        ("帮我支付这个订单", RefusalCode.ORDER_PAY),
        ("帮我取消订单", RefusalCode.ORDER_CANCEL),
        ("帮我确认收货", RefusalCode.ORDER_CONFIRM),
        ("把库存改成 999", RefusalCode.STOCK_WRITE),
        ("帮我把商品同步到 ES 索引", RefusalCode.ES_WRITE),
    ],
)
def test_transaction_write_requests_are_refused(message: str, expected: RefusalCode) -> None:
    rule = detect_refusal(message)

    assert rule is not None
    assert rule.code is expected
    assert expected in refusal_codes(message)


@pytest.mark.parametrize(
    "message",
    [
        "3000 元左右有哪些手机",
        "比较一下这两款的库存",
        "这款手机有哪些规格",
        "我的优惠券能用在哪个商品上",
        "这个商品支持哪些优惠",
        "销量最高的商品是哪个",
        "帮我看看购物车里的商品信息",
        "这个订单里包含哪些商品",
    ],
)
def test_normal_shopping_questions_are_not_refused(message: str) -> None:
    assert detect_refusal(message) is None
    assert refusal_codes(message) == []


def test_refusal_answer_explains_boundary_and_points_to_existing_pages() -> None:
    rule = detect_refusal("帮我下单")
    assert rule is not None

    answer = refusal_answer(rule)

    assert REFUSAL_NOTICE in answer
    assert "详情页" in answer
    assert "下单" in answer


def test_coupon_claim_refusal_mentions_existing_flow() -> None:
    rule = detect_refusal("帮我领券")

    assert rule is not None
    assert "优惠券" in refusal_answer(rule)
    assert "详情页" in refusal_answer(rule)


def test_empty_message_is_not_refused() -> None:
    assert detect_refusal("") is None
    assert detect_refusal(None) is None  # type: ignore[arg-type]

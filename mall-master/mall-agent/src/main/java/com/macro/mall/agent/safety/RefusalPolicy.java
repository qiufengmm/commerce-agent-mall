package com.macro.mall.agent.safety;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.macro.mall.agent.api.PythonText;

/**
 * 交易写操作拒绝策略。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.safety.policy}：8 类写操作按固定顺序匹配固定关键词，
 * 命中即返回该类的稳定 {@link RefusalCode} 与固定文案；策略只产生解释文本，不触发任何工具，
 * 也不改变任何数据。规则优先级由调用方保证：写操作拒绝 &gt; 个人券身份门槛 &gt; 普通模型对话。
 *
 * <p>本类为纯函数工具：无可变状态，不依赖模型、门户、Redis 或任何外部客户端。
 */
public final class RefusalPolicy {

    /** 固定公告，逐字符对齐 Python {@code REFUSAL_NOTICE}。 */
    public static final String REFUSAL_NOTICE = "本智能导购只提供商品信息查询，不能代替您完成任何交易或数据写入操作。";

    /** 写操作拒绝类别，顺序与 Python {@code RefusalCode} 一致。 */
    public enum RefusalCode {
        COUPON_CLAIM,
        CART_WRITE,
        ORDER_CREATE,
        ORDER_PAY,
        ORDER_CANCEL,
        ORDER_CONFIRM,
        STOCK_WRITE,
        ES_WRITE
    }

    /** 一次写操作拒绝：稳定类别 + 固定引导文案。 */
    public record Refusal(RefusalCode code, String message) {

        /** 返回给用户的完整回答：固定公告 + 该类别固定文案。 */
        public String answer() {
            return REFUSAL_NOTICE + message;
        }
    }

    private record RefusalRule(RefusalCode code, List<String> keywords, String message) {

        boolean matches(String text) {
            for (String keyword : keywords) {
                if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final List<RefusalRule> REFUSAL_RULES = List.of(
            new RefusalRule(
                    RefusalCode.COUPON_CLAIM,
                    List.of(
                            "领取优惠券",
                            "领优惠券",
                            "帮我领券",
                            "帮我领取",
                            "领取这张",
                            "领一张券",
                            "领张券",
                            "领个券",
                            "claim coupon",
                            "claim the coupon"),
                    "我不能代您领取优惠券。请点击商品卡片进入详情页，"
                            + "在现有页面按流程领取；领取后我可以帮您解释可用的优惠券。"),
            new RefusalRule(
                    RefusalCode.CART_WRITE,
                    List.of(
                            "加入购物车",
                            "加到购物车",
                            "加购物车",
                            "帮我加购",
                            "添加购物车",
                            "add to cart"),
                    "我不能把商品加入购物车。请点击商品卡片进入详情页，使用现有购物车按钮完成加购。"),
            new RefusalRule(
                    RefusalCode.ORDER_CREATE,
                    List.of(
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
                            "place an order"),
                    "我不能代您下单。请点击商品卡片进入详情页，在现有页面确认规格和地址后提交订单。"),
            new RefusalRule(
                    RefusalCode.ORDER_PAY,
                    List.of(
                            "帮我支付",
                            "帮我付款",
                            "去支付",
                            "立即支付",
                            "完成支付",
                            "帮我付一下",
                            "pay for me"),
                    "我不能代您支付或付款。支付只能在现有订单页面由您本人完成。"),
            new RefusalRule(
                    RefusalCode.ORDER_CANCEL,
                    List.of("取消订单", "帮我取消订单", "cancel my order", "cancel order"),
                    "我不能代您取消订单。请在“我的订单”页面按现有流程操作。"),
            new RefusalRule(
                    RefusalCode.ORDER_CONFIRM,
                    List.of("确认收货", "帮我收货", "confirm receipt"),
                    "我不能代您确认收货。请在“我的订单”页面按现有流程操作。"),
            new RefusalRule(
                    RefusalCode.STOCK_WRITE,
                    List.of(
                            "修改库存",
                            "改库存",
                            "调整库存",
                            "库存改成",
                            "增加库存",
                            "减少库存",
                            "设置库存",
                            "update stock",
                            "change stock"),
                    "我不能修改商品库存。库存由商城后台管理，我这边只做只读查询。"),
            new RefusalRule(
                    RefusalCode.ES_WRITE,
                    List.of(
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
                            "import all"),
                    "我不能执行索引导入、创建、删除或同步操作。搜索索引只能由商城后台维护。"));

    private RefusalPolicy() {
    }

    /**
     * 检测消息是否要求执行交易写操作，命中则返回对应拒绝。
     *
     * @param message 原始用户消息，允许为 {@code null}
     * @return 命中第一条例则的拒绝；无命中或输入为空时返回 {@link Optional#empty()}
     */
    public static Optional<Refusal> detectWriteRefusal(String message) {
        String text = PythonText.strip(message);
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        text = text.toLowerCase(Locale.ROOT);

        for (RefusalRule rule : REFUSAL_RULES) {
            if (rule.matches(text)) {
                return Optional.of(new Refusal(rule.code(), rule.message()));
            }
        }
        return Optional.empty();
    }
}

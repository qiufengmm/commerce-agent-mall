package com.macro.mall.agent.safety;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 交易写操作拒绝策略的 TDD 契约测试（计划 Task 7 Step 2）。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.safety.policy}：
 * {@code detect_refusal} 的 8 类规则、固定文案与关键词边界逐条对齐；普通商品查询与公开优惠券
 * 问法不得误拒；未知文本不得命中。领券动作同时满足“非个人券分类 + 写拒绝优先”。
 *
 * <p>本任务尚无编排链路，无法诚实断言运行时“模型/门户调用次数为 0”；这里只证明策略是
 * 无外部客户端依赖的纯函数（端到端调用计数断言留给 Task 9/10）。
 */
class RefusalPolicyTest {

    /** Python {@code REFUSAL_NOTICE} 逐字符对齐。 */
    private static final String PYTHON_REFUSAL_NOTICE =
            "本智能导购只提供商品信息查询，不能代替您完成任何交易或数据写入操作。";

    /** Python {@code REFUSAL_RULES} 的固定文案逐字符对齐。 */
    private static final Map<RefusalPolicy.RefusalCode, String> PYTHON_RULE_MESSAGES = Map.of(
            RefusalPolicy.RefusalCode.COUPON_CLAIM,
            "我不能代您领取优惠券。请点击商品卡片进入详情页，在现有页面按流程领取；领取后我可以帮您解释可用的优惠券。",
            RefusalPolicy.RefusalCode.CART_WRITE,
            "我不能把商品加入购物车。请点击商品卡片进入详情页，使用现有购物车按钮完成加购。",
            RefusalPolicy.RefusalCode.ORDER_CREATE,
            "我不能代您下单。请点击商品卡片进入详情页，在现有页面确认规格和地址后提交订单。",
            RefusalPolicy.RefusalCode.ORDER_PAY,
            "我不能代您支付或付款。支付只能在现有订单页面由您本人完成。",
            RefusalPolicy.RefusalCode.ORDER_CANCEL,
            "我不能代您取消订单。请在“我的订单”页面按现有流程操作。",
            RefusalPolicy.RefusalCode.ORDER_CONFIRM,
            "我不能代您确认收货。请在“我的订单”页面按现有流程操作。",
            RefusalPolicy.RefusalCode.STOCK_WRITE,
            "我不能修改商品库存。库存由商城后台管理，我这边只做只读查询。",
            RefusalPolicy.RefusalCode.ES_WRITE,
            "我不能执行索引导入、创建、删除或同步操作。搜索索引只能由商城后台维护。");

    // ------------------------------------------------------------------ //
    // 8 类写操作：多组代表关键词
    // ------------------------------------------------------------------ //

    static Stream<Arguments> writeRefusalMessages() {
        return Stream.of(
                // COUPON_CLAIM
                Arguments.of("帮我领取这张优惠券", RefusalPolicy.RefusalCode.COUPON_CLAIM),
                Arguments.of("帮我领优惠券", RefusalPolicy.RefusalCode.COUPON_CLAIM),
                Arguments.of("帮我领券", RefusalPolicy.RefusalCode.COUPON_CLAIM),
                Arguments.of("帮我领取", RefusalPolicy.RefusalCode.COUPON_CLAIM),
                Arguments.of("领取这张", RefusalPolicy.RefusalCode.COUPON_CLAIM),
                Arguments.of("领一张券", RefusalPolicy.RefusalCode.COUPON_CLAIM),
                Arguments.of("claim coupon", RefusalPolicy.RefusalCode.COUPON_CLAIM),
                Arguments.of("Claim The Coupon", RefusalPolicy.RefusalCode.COUPON_CLAIM),
                // CART_WRITE
                Arguments.of("帮我把这个加入购物车", RefusalPolicy.RefusalCode.CART_WRITE),
                Arguments.of("加到购物车", RefusalPolicy.RefusalCode.CART_WRITE),
                Arguments.of("加购物车", RefusalPolicy.RefusalCode.CART_WRITE),
                Arguments.of("帮我加购这个", RefusalPolicy.RefusalCode.CART_WRITE),
                Arguments.of("添加购物车", RefusalPolicy.RefusalCode.CART_WRITE),
                Arguments.of("Add To Cart", RefusalPolicy.RefusalCode.CART_WRITE),
                // ORDER_CREATE
                Arguments.of("帮我下单这台手机", RefusalPolicy.RefusalCode.ORDER_CREATE),
                Arguments.of("我要下单", RefusalPolicy.RefusalCode.ORDER_CREATE),
                Arguments.of("提交订单", RefusalPolicy.RefusalCode.ORDER_CREATE),
                Arguments.of("创建订单", RefusalPolicy.RefusalCode.ORDER_CREATE),
                Arguments.of("直接下单", RefusalPolicy.RefusalCode.ORDER_CREATE),
                Arguments.of("帮我购买", RefusalPolicy.RefusalCode.ORDER_CREATE),
                Arguments.of("Place An Order", RefusalPolicy.RefusalCode.ORDER_CREATE),
                // ORDER_PAY
                Arguments.of("帮我支付这个订单", RefusalPolicy.RefusalCode.ORDER_PAY),
                Arguments.of("立即支付", RefusalPolicy.RefusalCode.ORDER_PAY),
                Arguments.of("帮我付款", RefusalPolicy.RefusalCode.ORDER_PAY),
                Arguments.of("完成支付", RefusalPolicy.RefusalCode.ORDER_PAY),
                Arguments.of("Pay For Me", RefusalPolicy.RefusalCode.ORDER_PAY),
                // ORDER_CANCEL
                Arguments.of("帮我取消订单", RefusalPolicy.RefusalCode.ORDER_CANCEL),
                Arguments.of("取消订单", RefusalPolicy.RefusalCode.ORDER_CANCEL),
                Arguments.of("Cancel My Order", RefusalPolicy.RefusalCode.ORDER_CANCEL),
                // ORDER_CONFIRM
                Arguments.of("帮我确认收货", RefusalPolicy.RefusalCode.ORDER_CONFIRM),
                Arguments.of("帮我收货", RefusalPolicy.RefusalCode.ORDER_CONFIRM),
                Arguments.of("Confirm Receipt", RefusalPolicy.RefusalCode.ORDER_CONFIRM),
                // STOCK_WRITE
                Arguments.of("把库存改成 999", RefusalPolicy.RefusalCode.STOCK_WRITE),
                Arguments.of("改库存", RefusalPolicy.RefusalCode.STOCK_WRITE),
                Arguments.of("调整库存到 10", RefusalPolicy.RefusalCode.STOCK_WRITE),
                Arguments.of("增加库存", RefusalPolicy.RefusalCode.STOCK_WRITE),
                Arguments.of("设置库存", RefusalPolicy.RefusalCode.STOCK_WRITE),
                Arguments.of("Update Stock", RefusalPolicy.RefusalCode.STOCK_WRITE),
                // ES_WRITE
                Arguments.of("帮我把商品同步到 ES 索引", RefusalPolicy.RefusalCode.ES_WRITE),
                Arguments.of("重建索引", RefusalPolicy.RefusalCode.ES_WRITE),
                Arguments.of("删除索引", RefusalPolicy.RefusalCode.ES_WRITE),
                Arguments.of("导入所有商品", RefusalPolicy.RefusalCode.ES_WRITE),
                Arguments.of("ImportAll", RefusalPolicy.RefusalCode.ES_WRITE));
    }

    @ParameterizedTest(name = "写操作被拒绝：{0} -> {1}")
    @MethodSource("writeRefusalMessages")
    @DisplayName("8 类交易写操作命中稳定 RefusalCode")
    void writeOperationsAreRefusedWithStableCode(String message, RefusalPolicy.RefusalCode expected) {
        Optional<RefusalPolicy.Refusal> refusal = RefusalPolicy.detectWriteRefusal(message);

        assertThat(refusal).isPresent();
        assertThat(refusal.orElseThrow().code()).isEqualTo(expected);
    }

    @Test
    @DisplayName("拒绝枚举恰好 8 类，与 Python RefusalCode 对齐")
    void refusalCodesMatchPythonEnum() {
        assertThat(RefusalPolicy.RefusalCode.values()).containsExactly(
                RefusalPolicy.RefusalCode.COUPON_CLAIM,
                RefusalPolicy.RefusalCode.CART_WRITE,
                RefusalPolicy.RefusalCode.ORDER_CREATE,
                RefusalPolicy.RefusalCode.ORDER_PAY,
                RefusalPolicy.RefusalCode.ORDER_CANCEL,
                RefusalPolicy.RefusalCode.ORDER_CONFIRM,
                RefusalPolicy.RefusalCode.STOCK_WRITE,
                RefusalPolicy.RefusalCode.ES_WRITE);
    }

    @Test
    @DisplayName("每类拒绝的固定文案与 Python 逐字符一致")
    void refusalMessagesMatchPythonExactly() {
        assertThat(RefusalPolicy.REFUSAL_NOTICE).isEqualTo(PYTHON_REFUSAL_NOTICE);

        for (Map.Entry<RefusalPolicy.RefusalCode, String> entry : PYTHON_RULE_MESSAGES.entrySet()) {
            RefusalPolicy.Refusal refusal = RefusalPolicy.detectWriteRefusal(representativeInput(entry.getKey()))
                    .orElseThrow();

            assertThat(refusal.code()).isEqualTo(entry.getKey());
            assertThat(refusal.message()).isEqualTo(entry.getValue());
            assertThat(refusal.answer()).isEqualTo(PYTHON_REFUSAL_NOTICE + entry.getValue());
        }
    }

    @Test
    @DisplayName("拒绝文案有固定公告与自助引导，且不泄漏上游信息")
    void refusalAnswerExplainsBoundaryAndPointsToExistingPages() {
        RefusalPolicy.Refusal orderCreate =
                RefusalPolicy.detectWriteRefusal("帮我下单").orElseThrow();
        RefusalPolicy.Refusal couponClaim =
                RefusalPolicy.detectWriteRefusal("帮我领券").orElseThrow();

        assertThat(orderCreate.answer()).startsWith(PYTHON_REFUSAL_NOTICE);
        assertThat(orderCreate.answer()).contains("详情页", "下单");

        assertThat(couponClaim.answer()).contains("优惠券", "详情页");
        assertThat(couponClaim.answer()).doesNotContain("http://", "https://", "Bearer", "sk-");
    }

    // ------------------------------------------------------------------ //
    // 不误拒
    // ------------------------------------------------------------------ //

    static Stream<Arguments> nonWriteMessages() {
        return Stream.of(
                Arguments.of("3000 元左右有哪些手机"),
                Arguments.of("比较一下这两款的库存"),
                Arguments.of("这款手机有哪些规格"),
                Arguments.of("销量最高的商品是哪个"),
                Arguments.of("帮我看看购物车里的商品信息"),
                Arguments.of("这个订单里包含哪些商品"),
                // 公开优惠券与个人券问法都不是写操作
                Arguments.of("我的优惠券能用在哪个商品上"),
                Arguments.of("这个商品支持哪些优惠"),
                Arguments.of("商城有哪些公开优惠券"),
                Arguments.of("这个商品支持什么优惠活动"),
                Arguments.of("我不问个人券，只查商品折扣"));
    }

    @ParameterizedTest(name = "普通/只读问法不拒绝：{0}")
    @MethodSource("nonWriteMessages")
    @DisplayName("普通商品查询与优惠券只读问法返回 empty")
    void normalShoppingQuestionsAreNotRefused(String message) {
        assertThat(RefusalPolicy.detectWriteRefusal(message)).isEmpty();
    }

    @ParameterizedTest(name = "未知文本不误拒：{0}")
    @ValueSource(strings = {"今天天气怎么样", "你好", "讲个笑话", "帮我写一首诗"})
    @DisplayName("未知文本不误判成写操作")
    void unknownTextIsNotRefused(String message) {
        assertThat(RefusalPolicy.detectWriteRefusal(message)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t", "\n  \n", "\u3000"})
    @DisplayName("null 与空白输入不拒绝")
    void nullAndBlankMessagesAreNotRefused(String message) {
        assertThat(RefusalPolicy.detectWriteRefusal(message)).isEmpty();
    }

    // ------------------------------------------------------------------ //
    // 与个人券分类的优先级素材
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("领券动作：个人券分类为 false，写拒绝为 COUPON_CLAIM（拒绝优先于登录门槛）")
    void couponClaimIsRefusedRatherThanTreatedAsPersonalCouponQuery() {
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon("帮我领优惠券")).isFalse();
        assertThat(RefusalPolicy.detectWriteRefusal("帮我领优惠券").orElseThrow().code())
                .isEqualTo(RefusalPolicy.RefusalCode.COUPON_CLAIM);
    }

    // ------------------------------------------------------------------ //
    // 纯逻辑：无实例状态、无外部客户端
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("拒绝策略是无可变状态、无外部客户端依赖的纯函数")
    void policyHasNoMutableStateAndNoExternalClients() {
        assertThat(Modifier.isFinal(RefusalPolicy.class.getModifiers())).isTrue();

        for (Field field : RefusalPolicy.class.getDeclaredFields()) {
            assertThat(Modifier.isStatic(field.getModifiers()))
                    .as("字段 %s 必须是静态常量", field.getName())
                    .isTrue();
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("字段 %s 必须是 final", field.getName())
                    .isTrue();
        }

        for (Constructor<?> constructor : RefusalPolicy.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterCount())
                    .as("拒绝策略不得通过构造函数注入任何协作者")
                    .isZero();
        }

        Method detect = java.util.Arrays.stream(RefusalPolicy.class.getDeclaredMethods())
                .filter(method -> "detectWriteRefusal".equals(method.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(Modifier.isStatic(detect.getModifiers())).isTrue();
        assertThat(detect.getParameterTypes()).containsExactly(String.class);
        assertThat(detect.getReturnType()).isEqualTo(Optional.class);
        ShoppingIntentClassifierTest.assertThatExternalClientTypesAreAbsent(RefusalPolicy.class);
    }

    private static String representativeInput(RefusalPolicy.RefusalCode code) {
        return switch (code) {
            case COUPON_CLAIM -> "帮我领券";
            case CART_WRITE -> "加入购物车";
            case ORDER_CREATE -> "帮我下单";
            case ORDER_PAY -> "帮我支付";
            case ORDER_CANCEL -> "帮我取消订单";
            case ORDER_CONFIRM -> "帮我确认收货";
            case STOCK_WRITE -> "把库存改成 999";
            case ES_WRITE -> "重建索引";
        };
    }
}

package com.macro.mall.agent.safety;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 个人优惠券意图分类的 TDD 契约测试（计划 Task 7 Step 1）。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.safety.policy.is_personal_coupon_query}
 * 与已确认的未提交行为要求，但本文件只断言“分类结果”，不复制参考实现的实现细节：
 * <ul>
 *   <li>正例：明确指向“本人的券”的问法；</li>
 *   <li>否定按子句抑制：某一子句否定个人券查询，不牵连其它子句；</li>
 *   <li>公开优惠券反例：只谈公开券/商品优惠活动的问法不得命中；</li>
 *   <li>多子句中后一个正例仍命中；</li>
 *   <li>领券动作不是“个人券查询”（由 {@link RefusalPolicy} 的写拒绝优先接管）。</li>
 * </ul>
 *
 * <p>测试为纯逻辑：分类器无实例状态、无外部客户端依赖，本文件不连接模型、门户或 Redis。
 */
class ShoppingIntentClassifierTest {

    // ------------------------------------------------------------------ //
    // 正例
    // ------------------------------------------------------------------ //

    static Stream<Arguments> positivePersonalCouponMessages() {
        return Stream.of(
                Arguments.of("我的优惠券能用在哪个商品上"),
                Arguments.of("我这张券能用在这款商品上吗"),
                Arguments.of("我刚领的券在这款商品能用吗"),
                Arguments.of("我领过的券"),
                Arguments.of("我有哪些优惠券"),
                // 状态表达（不要求完成体标记）：与 Python 参考行为一致
                Arguments.of("我有优惠券吗"),
                Arguments.of("我拿到一张优惠券"),
                Arguments.of("我收到了优惠券"),
                Arguments.of("我账户里的优惠券还有哪些能用"),
                Arguments.of("这款商品我可以用哪些券"),
                // 多子句中后一个正例仍命中
                Arguments.of("不要查我的优惠券，只介绍公开券。另外我的券能用吗"),
                // 前置子句否定不得抑制后置子句
                Arguments.of("先不聊公开券，我的优惠券能用在哪"),
                // Python _PERSONAL_COUPON_PATTERNS 第 1 组：人称前缀 + 修饰表达
                Arguments.of("本人账户的优惠券"),
                Arguments.of("本人的券能用吗"),
                Arguments.of("个人的优惠券"),
                Arguments.of("我的已领取的优惠券"),
                Arguments.of("我的可用的券"),
                Arguments.of("我的未使用的优惠券"),
                Arguments.of("我的未使用券"),
                Arguments.of("我的这张券"),
                Arguments.of("我的这几张券"),
                Arguments.of("我的账户里的优惠券"),
                Arguments.of("我的账户中的优惠券"),
                Arguments.of("我的刚领的券"),
                Arguments.of("我的领到的券"),
                Arguments.of("我账户中的优惠券"));
    }

    @ParameterizedTest(name = "个人券正例命中：{0}")
    @MethodSource("positivePersonalCouponMessages")
    @DisplayName("明确询问本人优惠券的问法命中个人券意图")
    void personalCouponQuestionsAreClassifiedAsPersonal(String message) {
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon(message)).isTrue();
    }

    // ------------------------------------------------------------------ //
    // 反例：公开优惠券 / 普通商品问法
    // ------------------------------------------------------------------ //

    static Stream<Arguments> publicOrNeutralMessages() {
        return Stream.of(
                Arguments.of("商城有哪些公开优惠券"),
                Arguments.of("这个商品支持什么优惠活动"),
                Arguments.of("这个商品的公开优惠券怎么领"),
                Arguments.of("我想问这款商品有什么优惠券"),
                Arguments.of("我的商品有什么公开优惠券"),
                Arguments.of("有哪些可领取的公开券"),
                Arguments.of("3000 元左右有哪些手机"),
                // Python _PERSONAL_COUPON_PATTERNS 状态动词组不含“拥有”，故与 Python 保持一致返回 false
                Arguments.of("我拥有优惠券"));
    }

    @ParameterizedTest(name = "公开/中性/未支持状态动词问法不命中：{0}")
    @MethodSource("publicOrNeutralMessages")
    @DisplayName("公开券、普通商品与 Python 未支持的状态动词问法不得误判为个人券")
    void publicCouponQuestionsAreNotClassifiedAsPersonal(String message) {
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon(message)).isFalse();
    }

    // ------------------------------------------------------------------ //
    // 否定按子句抑制
    // ------------------------------------------------------------------ //

    static Stream<Arguments> negatedPersonalCouponMessages() {
        return Stream.of(
                Arguments.of("不要查我的优惠券，只介绍公开券"),
                Arguments.of("不要查我的优惠券"),
                Arguments.of("我不想查我的优惠券，只介绍公开优惠券"),
                Arguments.of("不用看我的券，直接介绍公开活动"),
                Arguments.of("我不问个人券，只查商品折扣"),
                Arguments.of("不需要查询我账户里的优惠券"));
    }

    @ParameterizedTest(name = "否定子句被抑制：{0}")
    @MethodSource("negatedPersonalCouponMessages")
    @DisplayName("被否定的个人券查询不命中，且否定只作用于所在子句")
    void negatedPersonalCouponClausesAreSuppressed(String message) {
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon(message)).isFalse();
    }

    @Test
    @DisplayName("否定只抑制所在子句，不牵连其它子句")
    void negationIsScopedToItsOwnClause() {
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon("不要查我的优惠券，只介绍公开券")).isFalse();
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon("不要查我的优惠券，只介绍公开券。另外我的券能用吗")).isTrue();
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon("我的券能用吗，不要查公开券")).isTrue();
    }

    // ------------------------------------------------------------------ //
    // 领券动作不是个人券查询
    // ------------------------------------------------------------------ //

    static Stream<Arguments> couponClaimMessages() {
        return Stream.of(
                Arguments.of("帮我领优惠券"),
                Arguments.of("帮我领取这张优惠券"),
                Arguments.of("帮我领一张券"),
                Arguments.of("claim coupon"));
    }

    @ParameterizedTest(name = "领券动作不进入个人券分类：{0}")
    @MethodSource("couponClaimMessages")
    @DisplayName("领券动作由写拒绝优先接管，不落入个人券登录分类")
    void couponClaimActionsAreNotPersonalCouponQueries(String message) {
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon(message)).isFalse();
    }

    // ------------------------------------------------------------------ //
    // 空值 / 空白 / 无 Python 依据的英文输入
    // ------------------------------------------------------------------ //

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t", "\n  \n", "\u3000"})
    @DisplayName("null 与空白输入安全返回 false")
    void nullAndBlankInputsReturnFalse(String message) {
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon(message)).isFalse();
    }

    @Test
    @DisplayName("首尾空白不影响分类结果")
    void surroundingWhitespaceDoesNotAffectClassification() {
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon("  我有哪些优惠券  ")).isTrue();
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon("\n我这张券能用在这款商品上吗\t")).isTrue();
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon("\u3000我的优惠券\u3000")).isTrue();
    }

    @Test
    @DisplayName("英文个人券问法不分类：无 Python 依据的英文规则已移除")
    void englishPhrasesAreNotClassifiedAsPersonal() {
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon("My Coupons")).isFalse();
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon("MY COUPONS")).isFalse();
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon("my coupons")).isFalse();
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon("which product can my coupon be used on"))
                .isFalse();
        assertThat(ShoppingIntentClassifier.classifyPersonalCoupon("Don't check my coupons, only show public ones"))
                .isFalse();
    }

    // ------------------------------------------------------------------ //
    // 纯逻辑：无实例状态、无外部客户端
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("分类器是无可变状态、无外部客户端依赖的纯函数")
    void classifierHasNoMutableStateAndNoExternalClients() {
        assertThat(Modifier.isFinal(ShoppingIntentClassifier.class.getModifiers())).isTrue();

        for (Field field : ShoppingIntentClassifier.class.getDeclaredFields()) {
            assertThat(Modifier.isStatic(field.getModifiers()))
                    .as("字段 %s 必须是静态常量，不能持有可变状态", field.getName())
                    .isTrue();
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("字段 %s 必须是 final", field.getName())
                    .isTrue();
        }

        for (Constructor<?> constructor : ShoppingIntentClassifier.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterCount())
                    .as("分类器不得通过构造函数注入任何协作者")
                    .isZero();
        }

        Method classify = Arrays.stream(ShoppingIntentClassifier.class.getDeclaredMethods())
                .filter(method -> "classifyPersonalCoupon".equals(method.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(Modifier.isStatic(classify.getModifiers())).isTrue();
        assertThat(classify.getParameterTypes()).containsExactly(String.class);
        assertThat(classify.getReturnType()).isEqualTo(boolean.class);
        assertThatExternalClientTypesAreAbsent(ShoppingIntentClassifier.class);
    }

    static void assertThatExternalClientTypesAreAbsent(Class<?> type) {
        List<String> forbidden = List.of(
                "ModelClient", "MallPortalClient", "RedisTemplate", "RedisConnectionFactory",
                "SessionRepository", "RateLimiter", "DataSource", "EntityManager",
                "MongoClient", "RabbitTemplate", "ElasticsearchClient", "RestClient");

        Stream<Class<?>> referenced = Stream.concat(
                Stream.concat(
                        Stream.of(type.getSuperclass()),
                        Arrays.stream(type.getInterfaces())),
                Stream.concat(
                        Arrays.stream(type.getDeclaredFields()).map(Field::getType),
                        Arrays.stream(type.getDeclaredMethods())
                                .flatMap(method -> Stream.concat(
                                        Stream.of(method.getReturnType()),
                                        Arrays.stream(method.getParameterTypes())))));

        assertThat(referenced
                        .filter(referencedType -> referencedType != null)
                        .map(Class::getName)
                        .filter(name -> forbidden.stream().anyMatch(name::contains))
                        .toList())
                .as("%s 不得依赖任何外部客户端类型", type.getSimpleName())
                .isEmpty();
    }
}

package com.macro.mall.agent.safety;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.macro.mall.agent.api.PythonText;

/**
 * 确定性判定消息是否在询问“当前用户本人的优惠券”。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.safety.policy.is_personal_coupon_query}：
 * 先按子句切分，再逐子句判断；某子句被否定表达命中时只跳过该子句，其余子句仍可命中。
 * 规则优先顺序由调用方保证：写操作拒绝 &gt; 个人券身份门槛 &gt; 普通模型对话。
 *
 * <p>仅沿用 Python 的中文行为，不含英文意图分类。相对 Python 的两处用户确认纠错：
 * <ol>
 *   <li>{@code 我这张券能用在这款商品上吗} 属于个人券问法（Python 参考实现漏判）；</li>
 *   <li>{@code 帮我领优惠券} 等领券动作不是个人券问法，交由 {@link RefusalPolicy} 拒绝
 *       （Python 参考实现会误判为个人券）。</li>
 * </ol>
 * 另保留用户已确认的正例 {@code 我账户里的优惠券}。
 *
 * <p>本类为纯函数工具：无可变状态，不依赖模型、门户、Redis 或任何外部客户端。
 */
public final class ShoppingIntentClassifier {

    /** 子句分隔：中英文句读 + 常见转折/补充连词。 */
    private static final Pattern CLAUSE_SEPARATOR = Pattern.compile("[，,。；;！？!?]|但是|不过|另外|此外|同时");

    /** 否定关键词，逐项对齐 Python {@code _NEGATED_PERSONAL_COUPON_PATTERNS}。 */
    private static final String NEGATION = "不要|别|不必|无需|不用|不需要|不想|不查|不看";

    /**
     * 被否定的“个人券”表达：否定词在前；以及券在前、否定词在后。
     * 逐项对齐 Python {@code _NEGATED_PERSONAL_COUPON_PATTERNS}，不扩展语义范围。
     */
    private static final List<Pattern> NEGATED_PERSONAL_COUPON = List.of(
            Pattern.compile(
                    "(?:" + NEGATION + ")"
                            + ".{0,12}(?:我的|我.{0,4})(?:优惠券|券)"),
            Pattern.compile(
                    "(?:我的|本人(?:账户)?的|个人的)"
                            + "(?:这张|这几张|账户(?:里|中)的?|已领取的|刚领的|领到的|可用的|未使用的|未使用)?"
                            + "(?:优惠券|券).{0,8}(?:" + NEGATION + ")"));

    /** 命中即视为“询问本人优惠券”的表达，对齐 Python {@code _PERSONAL_COUPON_PATTERNS}。 */
    private static final List<Pattern> PERSONAL_COUPON = List.of(
            // Python 第 1 组：人称前缀 + 可选修饰表达 + 优惠券|券；
            // 另保留用户已确认的“我这张”“我这几张”“我账户里的”前缀。
            Pattern.compile(
                    "(?:我的|我这张|我这几张|我账户(?:里|中)的|本人(?:账户)?的|个人的)"
                            + "(?:这张|这几张|账户(?:里|中)的?|已领取的|刚领的|领到的|可用的|未使用的|未使用)?"
                            + "(?:优惠券|券)"),
            // Python 第 2 组的领券动词部分：要求完成体标记，固化“领券动作不是个人券查询”的用户纠错
            Pattern.compile(
                    "我(?:刚|最近|之前|新)?(?:领|领取|领到|领过)"
                            + "(?:了|过|的|到)(?:.{0,6})(?:优惠券|券)"),
            // Python 第 2 组的状态动词部分：可选标记（动词集与 Python 一致，不含“拥有”）
            Pattern.compile(
                    "我(?:持有|收到|拿到|获得|有)(?:了|的)?(?:.{0,6})(?:优惠券|券)"),
            // Python 第 3 组：我有哪些 / 我可以用哪些 券
            Pattern.compile(
                    "我(?:自己|目前|现在|账户里|持有的)?"
                            + "(?:有哪些|有什么|能用哪些|可以用哪些|可用哪些).{0,6}(?:优惠券|券)"),
            // Python 第 4 组：券 对我可用 / 我能用 / 我这张
            Pattern.compile("(?:优惠券|券).{0,8}(?:对我可用|我能用|我可以用|我这张|我领的|我刚领的)"));

    private ShoppingIntentClassifier() {
    }

    /**
     * 判定消息是否明确询问当前用户本人的优惠券。
     *
     * @param message 原始用户消息，允许为 {@code null}
     * @return 命中任一子句的个人券表达且未被同子句否定表达抑制时为 {@code true}
     */
    public static boolean classifyPersonalCoupon(String message) {
        String text = PythonText.strip(message);
        if (text == null || text.isEmpty()) {
            return false;
        }
        text = text.toLowerCase(Locale.ROOT);

        for (String clause : CLAUSE_SEPARATOR.split(text)) {
            if (clause.isBlank()) {
                continue;
            }
            if (matchesAny(NEGATED_PERSONAL_COUPON, clause)) {
                continue;
            }
            if (matchesAny(PERSONAL_COUPON, clause)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(List<Pattern> patterns, String clause) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(clause).find()) {
                return true;
            }
        }
        return false;
    }
}

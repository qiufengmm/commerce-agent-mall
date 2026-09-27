package com.macro.mall.agent.evals;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一条离线评测用例，行为对照 Python {@code tests.support.eval_harness.EvalCase}。
 *
 * <p>JSON 字段（{@code cases.json}）为 camelCase，且拒绝未知工具名、允许/禁止工具冲突，
 * 以及「会员身份却要求登录」这类自相矛盾的定义；校验失败直接抛 {@link IllegalArgumentException}，
 * 避免用无效用例跑出虚假绿灯。
 *
 * @param id                 用例 ID
 * @param category           用例类别
 * @param message            用户消息
 * @param identity           {@code guest} 或 {@code member}
 * @param allowedTools       允许调用的工具
 * @param forbiddenTools     禁止调用的工具
 * @param requiresLogin      期望是否需要登录
 * @param maxToolCalls       工具调用次数上限
 * @param requiredFacts      证据中必须出现的事实
 * @param forbiddenFacts     证据中不得出现的内容
 * @param productCardCount   期望卡片数
 * @param stubConversationId Stub 对话脚本 ID；拒绝类用例为 {@code null}
 */
public record EvaluationCase(
        String id,
        String category,
        String message,
        String identity,
        List<String> allowedTools,
        List<String> forbiddenTools,
        boolean requiresLogin,
        int maxToolCalls,
        List<String> requiredFacts,
        List<String> forbiddenFacts,
        int productCardCount,
        String stubConversationId) {

    /** 允许在评测用例中引用的工具名。 */
    public static final Set<String> REGISTERED_TOOLS = Set.of(
            "searchProducts", "getProductDetail", "compareProducts", "getMemberCouponsForProduct");

    public static final String IDENTITY_GUEST = "guest";
    public static final String IDENTITY_MEMBER = "member";

    /** 与 Python {@code ChatRequest.message} 一致的消息长度上限。 */
    private static final int MAX_MESSAGE_CHARS = 1000;

    public EvaluationCase {
        id = requireText(id, "id");
        category = requireText(category, "category");
        message = requireText(message, "message");
        if (message.codePointCount(0, message.length()) > MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException("评测用例字段非法：message 超过 " + MAX_MESSAGE_CHARS + " 个码点");
        }
        identity = identity == null ? IDENTITY_GUEST : identity;
        if (!IDENTITY_GUEST.equals(identity) && !IDENTITY_MEMBER.equals(identity)) {
            throw new IllegalArgumentException("评测用例字段非法：identity 必须是 guest 或 member");
        }
        allowedTools = copy(allowedTools);
        forbiddenTools = copy(forbiddenTools);
        requiredFacts = copy(requiredFacts);
        forbiddenFacts = copy(forbiddenFacts);

        for (String name : allowedTools) {
            requireRegisteredTool(name);
        }
        for (String name : forbiddenTools) {
            requireRegisteredTool(name);
        }
        for (String name : allowedTools) {
            if (forbiddenTools.contains(name)) {
                throw new IllegalArgumentException("评测用例字段非法：allowedTools 与 forbiddenTools 冲突");
            }
        }
        if (IDENTITY_MEMBER.equals(identity) && requiresLogin) {
            throw new IllegalArgumentException("评测用例字段非法：会员身份不应要求登录");
        }
        if (maxToolCalls < 0) {
            throw new IllegalArgumentException("评测用例字段非法：maxToolCalls 不能为负");
        }
        if (productCardCount < 0) {
            throw new IllegalArgumentException("评测用例字段非法：productCardCount 不能为负");
        }
    }

    public boolean isMember() {
        return IDENTITY_MEMBER.equals(identity);
    }

    private static void requireRegisteredTool(String name) {
        if (!REGISTERED_TOOLS.contains(name)) {
            throw new IllegalArgumentException("评测用例字段非法：allowedTools 或 forbiddenTools 引用了未注册的工具");
        }
    }

    private static List<String> copy(List<String> values) {
        if (values == null) {
            return List.of();
        }
        for (String value : values) {
            Objects.requireNonNull(value, "工具名不能为空");
        }
        return List.copyOf(values);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("评测用例缺少必填字段：" + field);
        }
        return value;
    }
}

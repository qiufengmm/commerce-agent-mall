package com.macro.mall.agent.api;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * sessionId 必须是规范小写 UUID v4 文本。
 *
 * <p>规范化与 Python 侧 {@code validate_session_id} 对齐：先按 Python {@code str.strip()} 语义
 * 去首尾空白（含 U+00A0、U+0085 等 Java {@code String.strip()} 不处理的码点），再转小写，
 * 然后要求文本为规范形式（带连字符的 8-4-4-4-12 小写十六进制）。因此大写形式和带首尾空白的形式
 * 会被接受并规范化；无连字符形式、带花括号形式以及 UUID v1 仍被拒绝。
 *
 * <p>正则同时要求版本位为 {@code 4}、变体位为 {@code 8/9/a/b}（RFC 4122 / RFC 9562 的 {@code 10xx}）。
 * 这与 Python 一致而不是额外强化：{@code uuid.UUID(text).version} 只在变体位属于 RFC 4122 时返回版本号，
 * 其余变体位返回 {@code None}，因此 {@code parsed.version == 4} 本身就会拒绝非 RFC 变体位。
 */
public final class SessionIdValidator {

    private static final Pattern CANONICAL_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private SessionIdValidator() {
    }

    public static boolean isCanonicalV4(String value) {
        return value != null && CANONICAL_V4.matcher(normalise(value)).matches();
    }

    /**
     * @return 规范化后的小写 sessionId
     * @throws AgentApiException 400，当取值不是规范 UUID v4（含变体位校验）时
     */
    public static String requireCanonical(String value) {
        if (value == null) {
            throw AgentApiException.invalidSessionId();
        }
        String text = normalise(value);
        if (!CANONICAL_V4.matcher(text).matches()) {
            throw AgentApiException.invalidSessionId();
        }
        return text;
    }

    /** 按 Python {@code str.strip()} 语义去首尾空白并转小写；用 {@link Locale#ROOT} 避免区域设置影响大小写转换。 */
    private static String normalise(String value) {
        return PythonText.strip(value).toLowerCase(Locale.ROOT);
    }
}

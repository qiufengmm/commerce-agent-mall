package com.macro.mall.agent.api;

/**
 * 复现 Python {@code str} 文本语义的最小工具：{@code str.strip()} 与 Python 空白判定。
 *
 * <p>Java {@link String#strip()} 依据 {@link Character#isWhitespace(int)}，其空白集合与 Python
 * {@code str.isspace()} 不同：U+00A0、U+0085、U+2007、U+202F 等码点 Python 视为空白，Java 不视为空白。
 * 因此这里显式枚举 CPython {@code _PyUnicode_IsWhitespace} 的空白表，不能用 {@code String.strip()} 代替。
 */
public final class PythonText {

    private PythonText() {
    }

    /**
     * 等价于 Python {@code str.strip()}：剥除首尾所有 Python 空白码点。
     *
     * @return 剥除后的文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String strip(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isPythonWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isPythonWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    /**
     * 等价于 Python {@code str.isspace()}，与 CPython {@code _PyUnicode_IsWhitespace} 表一致：
     * U+0009..U+000D、U+001C..U+001F、U+0020、U+0085、U+00A0、U+1680、U+2000..U+200A、
     * U+2028、U+2029、U+202F、U+205F、U+3000。
     */
    public static boolean isPythonWhitespace(int codePoint) {
        switch (codePoint) {
            case 0x0009, 0x000A, 0x000B, 0x000C, 0x000D,
                 0x001C, 0x001D, 0x001E, 0x001F,
                 0x0020, 0x0085, 0x00A0, 0x1680,
                 0x2028, 0x2029, 0x202F, 0x205F, 0x3000:
                return true;
            default:
                return codePoint >= 0x2000 && codePoint <= 0x200A;
        }
    }
}

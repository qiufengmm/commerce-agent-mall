package com.macro.mall.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link PythonText} 的 Python 文本语义回归测试。
 *
 * <p>这是本迁移的语义关键件：CPython 的 {@code str.strip()} 与 {@code str.isspace()} 使用
 * {@code _PyUnicode_IsWhitespace} 空白表，与 Java {@link String#strip()}
 * （依据 {@link Character#isWhitespace}）不同。U+00A0、U+0085、U+2007、U+202F 等码点
 * Python 视为空白而 Java 不视为空白，因此必须逐码点验证真值表，避免依赖 Java 内置空白判定。
 *
 * <p>断言只覆盖公开契约（{@code isPythonWhitespace} / {@code strip} 的输入输出），不触及内部实现。
 */
class PythonTextTest {

    @Test
    @DisplayName("strip(null) 返回 null，不抛异常")
    void stripNullReturnsNull() {
        assertThat(PythonText.strip(null)).isNull();
    }

    @ParameterizedTest(name = "{1} 是 Python 空白")
    @MethodSource("pythonWhitespaceCodePoints")
    void recognisesEveryPythonWhitespaceCodePoint(int codePoint, String label) {
        String blank = new String(Character.toChars(codePoint));

        assertThat(PythonText.isPythonWhitespace(codePoint))
                .as("%s 应被判为 Python 空白", label)
                .isTrue();
        assertThat(PythonText.strip(blank))
                .as("%s 单独构成文本时应被整体剥除为空", label)
                .isEmpty();
        assertThat(PythonText.strip(blank + "商品" + blank))
                .as("%s 位于首尾时应被剥除", label)
                .isEqualTo("商品");
        assertThat(PythonText.strip("商" + blank + "品"))
                .as("%s 位于中间时不得被剥除", label)
                .isEqualTo("商" + blank + "品");
    }

    @ParameterizedTest(name = "{1} 不是 Python 空白")
    @MethodSource("nonWhitespaceCodePoints")
    void keepsCodePointsOutsidePythonWhitespaceTable(int codePoint, String label) {
        String character = new String(Character.toChars(codePoint));
        String text = character + "商品" + character;

        assertThat(PythonText.isPythonWhitespace(codePoint))
                .as("%s 不应被判为 Python 空白", label)
                .isFalse();
        assertThat(PythonText.strip(text))
                .as("%s 位于首尾时不得被剥除", label)
                .isEqualTo(text);
    }

    @Test
    @DisplayName("strip 剥除首尾混合 Python 空白，保留中间所有字符")
    void stripsOnlyLeadingAndTrailingPythonWhitespace() {
        assertThat(PythonText.strip("\u00A0\u0085\u3000")).isEmpty();
        assertThat(PythonText.strip("\u00A0\u0085商\u2007品\u202F\u3000"))
                .isEqualTo("商\u2007品");
    }

    @Test
    @DisplayName("strip 不越界、不误删补充平面字符")
    void stripsAroundSupplementaryPlaneCharacters() {
        String emoji = new String(Character.toChars(0x1F600));
        String cjkExtension = new String(Character.toChars(0x20000));
        String core = cjkExtension + emoji;

        assertThat(PythonText.strip("\u00A0" + core + "\u3000")).isEqualTo(core);
        assertThat(PythonText.strip(core)).isEqualTo(core);
        assertThat(PythonText.strip(core + "\u00A0")).isEqualTo(core);
        assertThat(PythonText.isPythonWhitespace(0x1F600)).isFalse();
    }

    @Test
    @DisplayName("strip 处理孤立代理时不越界、不误删")
    void handlesLoneSurrogatesWithoutError() {
        String loneHigh = "\uD83D";
        String loneLow = "\uDE00";

        assertThat(PythonText.strip("\u00A0" + loneHigh + "\u00A0")).isEqualTo(loneHigh);
        assertThat(PythonText.strip(loneHigh + "商品" + loneLow)).isEqualTo(loneHigh + "商品" + loneLow);
        assertThat(PythonText.strip(loneHigh)).isEqualTo(loneHigh);
        assertThat(PythonText.isPythonWhitespace(0xD83D)).isFalse();
        assertThat(PythonText.isPythonWhitespace(0xDE00)).isFalse();
    }

    /**
     * CPython {@code _PyUnicode_IsWhitespace} 全表：U+0009..U+000D、U+001C..U+001F、U+0020、
     * U+0085、U+00A0、U+1680、U+2000..U+200A、U+2028、U+2029、U+202F、U+205F、U+3000。
     */
    private static Stream<Arguments> pythonWhitespaceCodePoints() {
        List<Arguments> arguments = new ArrayList<>();
        addRange(arguments, 0x0009, 0x000D);
        addRange(arguments, 0x001C, 0x001F);
        arguments.add(codePoint(0x0020));
        arguments.add(codePoint(0x0085));
        arguments.add(codePoint(0x00A0));
        arguments.add(codePoint(0x1680));
        addRange(arguments, 0x2000, 0x200A);
        arguments.add(codePoint(0x2028));
        arguments.add(codePoint(0x2029));
        arguments.add(codePoint(0x202F));
        arguments.add(codePoint(0x205F));
        arguments.add(codePoint(0x3000));
        return arguments.stream();
    }

    /**
     * 非空白反例：明确列出的 U+001B（ESC）、U+007F（DEL）、U+180E（MONGOLIAN VOWEL SEPARATOR）、
     * U+200B（ZERO WIDTH SPACE），以及空白表每个区段边界外的相邻码点。
     */
    private static Stream<Arguments> nonWhitespaceCodePoints() {
        return Stream.of(
                codePoint(0x001B),
                codePoint(0x007F),
                codePoint(0x180E),
                codePoint(0x200B),
                codePoint(0x0008),
                codePoint(0x000E),
                codePoint(0x0021),
                codePoint(0x00A1),
                codePoint(0x167F),
                codePoint(0x1681),
                codePoint(0x1FFF),
                codePoint(0x200C),
                codePoint(0x2027),
                codePoint(0x202A),
                codePoint(0x202E),
                codePoint(0x2030),
                codePoint(0x205E),
                codePoint(0x2060),
                codePoint(0x2FFF),
                codePoint(0x3001));
    }

    private static void addRange(List<Arguments> target, int from, int to) {
        for (int codePoint = from; codePoint <= to; codePoint++) {
            target.add(codePoint(codePoint));
        }
    }

    private static Arguments codePoint(int codePoint) {
        return Arguments.of(codePoint, String.format("U+%04X", codePoint));
    }
}

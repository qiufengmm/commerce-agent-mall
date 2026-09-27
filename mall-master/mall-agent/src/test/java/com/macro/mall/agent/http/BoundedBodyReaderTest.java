package com.macro.mall.agent.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BoundedBodyReader} 的硬上限与有界读取契约测试，使用极小上限，不构造大体积数据。
 */
class BoundedBodyReaderTest {

    @Test
    @DisplayName("读取上限内的正文并按 UTF-8 解码")
    void readsBodyWithinLimit() throws IOException {
        String text = "{\"k\":\"手机\"}";

        String result = BoundedBodyReader.readUtf8(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), 1024);

        assertThat(result).isEqualTo(text);
    }

    @Test
    @DisplayName("恰好等于上限的正文可正常读取")
    void readsBodyExactlyAtLimit() throws IOException {
        byte[] body = repeated('a', 64);

        String result = BoundedBodyReader.readUtf8(new ByteArrayInputStream(body), 64);

        assertThat(result).isEqualTo("a".repeat(64));
    }

    @Test
    @DisplayName("超过上限 1 字节即失败，异常只含固定文案且无 cause")
    void failsWhenBodyExceedsLimitByOneByte() {
        byte[] body = repeated('a', 65);

        BoundedBodyReader.BodyTooLargeException failure = catchThrowableOfType(
                () -> BoundedBodyReader.readUtf8(new ByteArrayInputStream(body), 64),
                BoundedBodyReader.BodyTooLargeException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).isEqualTo("HTTP 响应体超出大小上限");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    @DisplayName("读到上限后立即停止，不 drain 完整正文")
    void doesNotDrainBeyondLimit() {
        int cap = 100;
        GeneratedBodyStream stream = new GeneratedBodyStream("X", "Y", 10_000);

        BoundedBodyReader.BodyTooLargeException failure = catchThrowableOfType(
                () -> BoundedBodyReader.readUtf8(stream, cap),
                BoundedBodyReader.BodyTooLargeException.class);

        assertThat(failure).isNotNull();
        assertThat(stream.consumed())
                .as("只应多读 1 字节用于判界，绝不 drain 完整正文")
                .isLessThanOrEqualTo(cap + 1L);
        assertThat(stream.totalBytes()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("空正文在任意上限下返回空字符串；上限为 0 时非空正文失败")
    void emptyBodyIsAllowedEvenWithZeroLimit() throws IOException {
        assertThat(BoundedBodyReader.readUtf8(new ByteArrayInputStream(new byte[0]), 0)).isEmpty();

        BoundedBodyReader.BodyTooLargeException failure = catchThrowableOfType(
                () -> BoundedBodyReader.readUtf8(new ByteArrayInputStream(new byte[] {1}), 0),
                BoundedBodyReader.BodyTooLargeException.class);

        assertThat(failure).isNotNull();
    }

    @Test
    @DisplayName("生产默认上限为 1 MiB")
    void defaultLimitIsOneMiB() {
        assertThat(BoundedBodyReader.DEFAULT_MAX_BYTES).isEqualTo(1024 * 1024);
    }

    private static byte[] repeated(char value, int length) {
        byte[] body = new byte[length];
        Arrays.fill(body, (byte) value);
        return body;
    }
}

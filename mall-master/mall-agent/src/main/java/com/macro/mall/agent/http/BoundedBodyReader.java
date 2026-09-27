package com.macro.mall.agent.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 单一职责的有界响应正文读取工具：把上游正文读成 UTF-8 字符串，但<strong>绝不</strong>超过调用方给定的
 * 硬字节上限。
 *
 * <p>动机：模型与门户客户端此前使用 {@link InputStream#readAllBytes()} 无界读取，恶意或故障上游可返回
 * 超大正文造成内存放大。这里改为有界读取：最多从底层流消费 {@code maxBytes + 1} 个字节，一旦超过上限
 * 立即抛出 {@link BodyTooLargeException}，<strong>不再继续 drain 剩余正文</strong>。
 *
 * <p>调用方负责在 try-with-resources 中关闭流；本工具只负责读取与判界，不负责错误分类。
 * 超限异常只携带固定文案，<strong>不携带</strong>任何上游正文、截断内容或原始异常。
 */
public final class BoundedBodyReader {

    /** 生产默认硬上限：1 MiB。模型为非流式 JSON、门户单页固定 5 条，正常响应远小于该值。 */
    public static final int DEFAULT_MAX_BYTES = 1024 * 1024;

    /** 底层读取块大小；不影响「最多消费 {@code maxBytes + 1} 字节」的上界。 */
    private static final int CHUNK_BYTES = 8192;

    private BoundedBodyReader() {
    }

    /**
     * 读取 UTF-8 正文，最多读取 {@code maxBytes} 字节。
     *
     * @param body     响应正文输入流；调用方负责关闭
     * @param maxBytes 允许的最大字节数，必须大于等于 0
     * @return 解码后的正文
     * @throws BodyTooLargeException 正文超过 {@code maxBytes}
     * @throws IOException           底层读取失败
     */
    public static String readUtf8(InputStream body, int maxBytes) throws IOException {
        if (body == null) {
            throw new IllegalArgumentException("body 不能为空");
        }
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes 不能为负");
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, CHUNK_BYTES));
        byte[] chunk = new byte[CHUNK_BYTES];
        int total = 0;
        while (true) {
            // 只多申请 1 字节用于判界：剩余为 0 时也会读 1 字节，读到即代表超限
            int request = (int) Math.min(CHUNK_BYTES, (long) (maxBytes - total) + 1);
            int read = body.read(chunk, 0, request);
            if (read == -1) {
                break;
            }
            total += read;
            if (total > maxBytes) {
                throw new BodyTooLargeException();
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * 正文超过硬上限的信号。
     *
     * <p>刻意使用<strong>非受检</strong>异常，使其穿过 Spring {@code RestClient.exchange} 的响应回调时
     * 不被包装成 {@code ResourceAccessException}（Spring 只把回调抛出的 {@link IOException} 包装为传输异常），
     * 从而让调用方在请求边界处精确映射为既有固定安全错误。
     *
     * <p>只携带固定文案：不包含上游正文、截断内容或原始异常。
     */
    public static final class BodyTooLargeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private BodyTooLargeException() {
            super("HTTP 响应体超出大小上限");
        }
    }
}

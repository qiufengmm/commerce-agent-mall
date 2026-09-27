package com.macro.mall.agent.http;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 测试专用：惰性生成「前缀 + 任意长度填充 + 后缀」的响应正文流，并统计下游<strong>实际读取</strong>的字节数。
 *
 * <p>用于证明有界读取：一旦读取方在封顶处停止，本流只会被消费到封顶附近，绝不会把整段正文读完。
 * 正文内容不预先分配整块字节数组，因此可以用远超上限的 {@code totalBytes} 而不浪费内存。
 */
public final class GeneratedBodyStream extends InputStream {

    private final byte[] prefix;
    private final byte[] suffix;
    private final long totalBytes;

    private long produced;
    private long consumed;

    /**
     * @param prefix     正文开头（UTF-8），例如包含唯一合成标记的 JSON 片段
     * @param suffix     正文结尾（UTF-8），补全 JSON 结构
     * @param totalBytes 该流对外提供的总字节数，必须不小于 {@code prefix + suffix} 的长度
     */
    public GeneratedBodyStream(String prefix, String suffix, long totalBytes) {
        this.prefix = prefix.getBytes(StandardCharsets.UTF_8);
        this.suffix = suffix.getBytes(StandardCharsets.UTF_8);
        if (totalBytes < (long) this.prefix.length + this.suffix.length) {
            throw new IllegalArgumentException("totalBytes 必须不小于前后缀长度之和");
        }
        this.totalBytes = totalBytes;
    }

    /** 下游已从本流读取的字节数。 */
    public long consumed() {
        return consumed;
    }

    /** 该流可供读取的总字节数。 */
    public long totalBytes() {
        return totalBytes;
    }

    @Override
    public int read() {
        if (produced >= totalBytes) {
            return -1;
        }
        int value = byteAt(produced) & 0xFF;
        produced++;
        consumed++;
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
        if (length == 0) {
            return 0;
        }
        if (produced >= totalBytes) {
            return -1;
        }
        int count = (int) Math.min(length, totalBytes - produced);
        for (int index = 0; index < count; index++) {
            buffer[offset + index] = (byte) byteAt(produced + index);
        }
        produced += count;
        consumed += count;
        return count;
    }

    private byte byteAt(long index) {
        if (index < prefix.length) {
            return prefix[(int) index];
        }
        long suffixStart = totalBytes - suffix.length;
        if (index >= suffixStart) {
            return suffix[(int) (index - suffixStart)];
        }
        return 'A';
    }
}

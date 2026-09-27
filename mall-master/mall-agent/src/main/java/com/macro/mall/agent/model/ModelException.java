package com.macro.mall.agent.model;

/**
 * 模型层错误，与 Python {@code model.client} 的错误层次对齐。
 *
 * <p>只携带固定的安全错误分类、HTTP 状态码与固定文案；<strong>永不链式持有</strong>上游响应正文、
 * Jackson 解析异常（其消息可能包含原始响应片段）、API Key、{@code Authorization} 或请求消息，
 * 从而保证异常、日志与 HTTP 响应都无法泄漏这些内容。
 *
 * <p>分类与状态码映射：
 * <ul>
 *   <li>{@link Kind#UNAVAILABLE} → 503 {@code MODEL_UNAVAILABLE}（未配置、鉴权失败或地址/模型不可用）</li>
 *   <li>{@link Kind#PROTOCOL} → 503 {@code MODEL_PROTOCOL_ERROR}（响应结构非法或模型不支持工具调用）</li>
 *   <li>{@link Kind#TIMEOUT} → 502 {@code MODEL_TIMEOUT}</li>
 *   <li>{@link Kind#UPSTREAM} → 502 {@code MODEL_UPSTREAM_ERROR}</li>
 * </ul>
 */
public final class ModelException extends RuntimeException {

    public enum Kind {
        UNAVAILABLE,
        PROTOCOL,
        TIMEOUT,
        UPSTREAM
    }

    private static final long serialVersionUID = 1L;

    private final Kind kind;
    private final int httpStatus;
    private final String code;

    private ModelException(Kind kind, int httpStatus, String code, String message) {
        // 刻意不传入 cause：上游正文、解析异常与凭据都不得被异常持有
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public static ModelException unavailable(String message) {
        return new ModelException(Kind.UNAVAILABLE, 503, "MODEL_UNAVAILABLE", message);
    }

    public static ModelException protocol(String message) {
        return new ModelException(Kind.PROTOCOL, 503, "MODEL_PROTOCOL_ERROR", message);
    }

    public static ModelException timeout(String message) {
        return new ModelException(Kind.TIMEOUT, 502, "MODEL_TIMEOUT", message);
    }

    public static ModelException upstream(String message) {
        return new ModelException(Kind.UPSTREAM, 502, "MODEL_UPSTREAM_ERROR", message);
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }
}

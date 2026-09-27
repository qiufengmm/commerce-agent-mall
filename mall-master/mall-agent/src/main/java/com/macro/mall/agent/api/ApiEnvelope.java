package com.macro.mall.agent.api;

/**
 * 统一响应外层结构：{@code code} / {@code message} / {@code data}。
 *
 * <p>成功与失败都使用同一结构；失败时不得携带异常堆栈、上游响应正文、URL 或凭据。
 * 字段名保持 camelCase，避免序列化出 snake_case。
 */
public record ApiEnvelope<T>(int code, String message, T data) {

    public static final int SUCCESS_CODE = 200;
    public static final String SUCCESS_MESSAGE = "操作成功";

    public static <T> ApiEnvelope<T> success(T data) {
        return new ApiEnvelope<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static <T> ApiEnvelope<T> failure(int code, String message) {
        return new ApiEnvelope<>(code, message, null);
    }

    public static <T> ApiEnvelope<T> failure(int code, String message, T data) {
        return new ApiEnvelope<>(code, message, data);
    }
}

package com.macro.mall.agent.api;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import jakarta.validation.constraints.NotBlank;

/**
 * 聊天请求：只接受 {@code sessionId} 与 {@code message}。
 *
 * <p>请求体中的身份、Token、URL 或工具控制字段一律拒绝，而不是静默忽略；
 * 未知字段的拒绝由 Jackson 的
 * {@code spring.jackson.deserialization.fail-on-unknown-properties=true} 保证。
 *
 * <p>{@code message} 必须是 JSON 字符串（与 Python 侧 {@code strict=True} 对齐），
 * 然后按 Python {@code str.strip()} 语义去首尾空白，再按 Unicode 码点校验长度 1..1000
 * （见 {@link PythonText}、{@link CodePointLength}）。JSON {@code null} 不会进入
 * {@link StrictStringDeserializer}，而是由 Bean Validation 拒绝。
 * {@code sessionId} 去首尾空白并转小写后，必须是规范 UUID v4（详见 {@link SessionIdValidator}）。
 */
public record ChatRequest(
        @NotBlank String sessionId,
        @CodePointLength(min = MIN_MESSAGE_CODE_POINTS, max = MAX_MESSAGE_CHARS)
        @JsonDeserialize(using = ChatRequest.StrictStringDeserializer.class) String message) {

    public static final int MIN_MESSAGE_CODE_POINTS = 1;
    public static final int MAX_MESSAGE_CHARS = 1000;

    private static final String MESSAGE_MUST_BE_JSON_STRING = "message 必须是 JSON 字符串";

    public ChatRequest {
        // 规范化失败的 sessionId 在这里抛出，最终映射为固定的 400 文案
        sessionId = SessionIdValidator.requireCanonical(sessionId);
        // 按 Python str.strip() 语义剥除首尾空白；null 保持 null，交给 Bean Validation 拒绝
        message = PythonText.strip(message);
    }

    /**
     * 只约束入站 {@code message}：拒绝 JSON 数字、布尔、数组与对象。
     *
     * <p>刻意不修改全局 Jackson 的标量宽松转换设置，避免影响后续门户/模型响应的
     * 反序列化行为。
     */
    public static final class StrictStringDeserializer extends JsonDeserializer<String> {

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                throw MismatchedInputException.from(parser, String.class, MESSAGE_MUST_BE_JSON_STRING);
            }
            return parser.getText();
        }
    }
}

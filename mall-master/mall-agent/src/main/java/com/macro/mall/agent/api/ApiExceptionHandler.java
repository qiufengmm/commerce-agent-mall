package com.macro.mall.agent.api;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.macro.mall.agent.session.RateLimitExceededException;

/**
 * 统一异常映射：所有错误都使用 {@code code/message/data} 外层结构。
 *
 * <p>面向客户端的文案固定，不回传异常堆栈、上游响应正文、请求 URL、Token 或凭据；
 * 未预期异常只记录异常类型与一次性 traceId。
 *
 * <p>模型层、门户层与工具轮数的固定映射由业务实现（{@code DefaultAgentChatService}）在编排边界
 * 转换为携带固定状态码与固定文案的 {@link AgentApiException}，与 Python {@code api/chat.py} 的
 * try/except 结构一致；本类只负责把 {@link AgentApiException}（含业务限流）与框架异常收敛为
 * 统一信封。
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String GENERIC_MESSAGE = "请求失败，请稍后再试。";
    private static final String INTERNAL_ERROR_MESSAGE = "服务内部错误，请稍后再试。";

    private static final Map<Integer, String> STATUS_MESSAGES = Map.of(
            400, AgentApiException.INVALID_REQUEST_MESSAGE,
            404, "请求的接口不存在。",
            405, "请求方法不被允许。",
            409, AgentApiException.DUPLICATE_REQUEST_MESSAGE,
            422, "本次问题需要缩小范围后重试。",
            // 与 Python api/errors.py 的 _HTTP_STATUS_MESSAGES[429] 一致（带句号）：这是框架级
            // HTTP 429 的通用兜底文案。业务限流走 RateLimitExceededException，其文案来自
            // RateLimitExceededError.message（无句号），两条路径刻意与 Python 保持一致。
            429, "请求过于频繁，请稍后再试。",
            502, "上游服务暂时不可用，请稍后再试。",
            503, AgentApiException.SERVICE_UNAVAILABLE_MESSAGE);

    @ExceptionHandler(AgentApiException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleAgentApiException(AgentApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiEnvelope.failure(ex.getStatus(), ex.getMessage()));
    }

    /**
     * 限流错误比通用 {@link AgentApiException} 更具体，因此优先命中：除固定 429 信封外，
     * 还要通过标准 {@code Retry-After} 头把整数秒暴露给客户端。
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleRateLimit(RateLimitExceededException ex) {
        return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(ex.getRetryAfterSeconds()))
                .body(ApiEnvelope.failure(ex.getStatus(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleUnexpectedException(Exception ex) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        // 只记录异常类型与 traceId，不把异常正文或堆栈写入响应
        log.error("未处理异常 traceId={} type={}", traceId, ex.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiEnvelope.failure(500, INTERNAL_ERROR_MESSAGE, Map.of("traceId", traceId)));
    }

    /**
     * 框架异常（参数校验、消息不可读、方法不支持、资源不存在等）统一收敛到固定信封。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        int code = statusCode.value();
        ApiEnvelope<Void> envelope = ApiEnvelope.failure(code, STATUS_MESSAGES.getOrDefault(code, GENERIC_MESSAGE));
        return new ResponseEntity<>(envelope, headers, statusCode);
    }
}

package com.macro.mall.agent.model;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.macro.mall.agent.api.PythonText;
import com.macro.mall.agent.config.AgentProperties;
import com.macro.mall.agent.http.BoundedBodyReader;

/**
 * OpenAI 兼容模型客户端，行为对齐 Python {@code model.openai_compatible.OpenAICompatibleClient}。
 *
 * <p>只做四件事：发送受控的非流式 JSON 请求、把响应解析为稳定的 {@link ModelResponse}、
 * 对可重试错误重试一次、把其余失败转换为不泄漏凭据与上游正文的 {@link ModelException}。
 *
 * <p>请求地址为 {@code baseUrl} 去除末尾全部斜杠后拼接 {@code /chat/completions}，
 * 供应商路径原样保留（DeepSeek 根地址得到 {@code /chat/completions}，{@code /v1} 地址得到
 * {@code /v1/chat/completions}），不会自动插入或重复 {@code /v1}。
 *
 * <p>响应正文使用 {@link BoundedBodyReader} 有界读取，硬上限 {@code 1 MiB}；超过上限立即按固定协议错误
 * 失败，不会无界读取或在读到上限后继续 drain，异常也不携带上游正文。
 *
 * <p>本类不建立数据库、商城 HTTP 或 Redis 连接；{@code Authorization: Bearer} 只出现在发往模型的
 * 请求上，且空或占位 API Key 一律不发送请求。凭据占位判定复用
 * {@link AgentProperties#isPlaceholder(String)}，保证与配置层的 {@code modelAvailable} 语义一致。
 *
 * <p>失败分类与重试语义：
 * <ul>
 *   <li>可重试 HTTP 状态（429/502/503/504）与连接级传输失败（连接超时、普通连接失败）在重试额度
 *       用尽前各重试一次；额度用尽后连接超时归 {@link ModelException.Kind#TIMEOUT}，
 *       其余非超时传输失败（普通连接失败及其它 I/O 失败）统一归 {@link ModelException.Kind#UPSTREAM}
 *       并使用固定文案「模型服务连接失败」，对齐 Python {@code except httpx.TransportError}。</li>
 *   <li>读超时（{@link SocketTimeoutException}/{@link HttpTimeoutException}）不重试，直接归超时。</li>
 *   <li>重试等待返回后若当前线程已中断，按 Java 取消语义抛出 {@link CancellationException}，
 *       保留中断标志且不再发送下一次请求；该异常只携带固定文案，不泄漏原因或凭据。</li>
 * </ul>
 */
public final class OpenAiCompatibleClient implements ModelClient {

    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);
    private static final List<String> TOOL_SUPPORT_MARKERS =
            List.of("tool_choice", "tool call", "tool_calls", "function calling", "tools");
    private static final long RETRY_DELAY_MILLIS = 500L;
    private static final ObjectMapper JSON = new ObjectMapper();

    // 固定安全文案：不携带 Key、Authorization、上游正文或请求内容
    private static final String MESSAGE_CREDENTIALS_UNAVAILABLE = "模型服务未配置或凭据不可用";
    private static final String MESSAGE_AUTH_FAILED = "模型服务鉴权失败，请检查服务端配置";
    private static final String MESSAGE_ENDPOINT_UNAVAILABLE = "模型服务地址或模型名不可用";
    private static final String MESSAGE_TEMPORARILY_UNAVAILABLE = "模型服务暂时不可用";
    private static final String MESSAGE_TIMEOUT = "模型服务响应超时";
    private static final String MESSAGE_CONNECT_FAILED = "模型服务连接失败";
    private static final String MESSAGE_REQUEST_REJECTED = "模型服务拒绝了本次请求";
    private static final String MESSAGE_ERROR_RESULT = "模型服务返回了错误结果";
    private static final String MESSAGE_TOOL_UNSUPPORTED = "模型服务不支持工具调用";
    private static final String MESSAGE_UNPARSEABLE = "模型服务返回了无法解析的响应";
    private static final String MESSAGE_MISSING_CHOICES = "模型服务返回结构缺少 choices";
    private static final String MESSAGE_MISSING_MESSAGE = "模型服务返回结构缺少 message";
    private static final String MESSAGE_CONTENT_TYPE = "模型服务返回的 content 类型非法";
    private static final String MESSAGE_EMPTY = "模型服务返回了空消息";
    private static final String MESSAGE_TOOL_CALLS_STRUCTURE = "模型服务返回的 tool_calls 结构非法";
    private static final String MESSAGE_TOOL_CALLS_FUNCTION = "模型服务返回的 tool_calls 缺少 function";
    private static final String MESSAGE_TOOL_CALLS_NAME = "模型服务返回的 tool_calls 缺少名称";
    private static final String MESSAGE_CANCELLED = "模型调用已被取消";
    private static final String MESSAGE_RESPONSE_TOO_LARGE = "模型服务响应体超出大小上限";

    private final RestClient restClient;
    private final String chatUrl;
    private final String apiKey;
    private final String model;
    private final int maxRetries;
    private final RetryDelay retryDelay;

    /**
     * @param restClientBuilder 由调用方提供并已完成超时/连接配置的构建器；测试可对其绑定 HTTP 模拟
     * @param baseUrl           服务商基础地址，末尾斜杠会被去除
     * @param apiKey            API Key；空或占位时不会发送任何请求
     * @param model             模型名，放入请求体
     * @param maxRetries        可重试错误的最大重试次数
     * @param retryDelay        重试等待；测试注入空实现以避免真实 sleep
     */
    public OpenAiCompatibleClient(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String apiKey,
            String model,
            int maxRetries,
            RetryDelay retryDelay) {
        if (restClientBuilder == null) {
            throw new IllegalArgumentException("restClientBuilder 不能为空");
        }
        this.restClient = restClientBuilder.build();
        this.chatUrl = stripTrailingSlashes(baseUrl) + "/chat/completions";
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.maxRetries = Math.max(0, maxRetries);
        this.retryDelay = retryDelay == null ? OpenAiCompatibleClient::sleepQuietly : retryDelay;
    }

    /** 使用默认的“最多重试一次”和真实等待。 */
    public OpenAiCompatibleClient(
            RestClient.Builder restClientBuilder, String baseUrl, String apiKey, String model) {
        this(restClientBuilder, baseUrl, apiKey, model, 1, OpenAiCompatibleClient::sleepQuietly);
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        if (AgentProperties.isPlaceholder(apiKey)) {
            throw ModelException.unavailable(MESSAGE_CREDENTIALS_UNAVAILABLE);
        }

        Map<String, Object> payload = buildPayload(request);
        int attempt = 0;
        while (true) {
            RawResponse raw;
            try {
                raw = send(payload);
            } catch (BoundedBodyReader.BodyTooLargeException ex) {
                // 有界读取在超过硬上限时立即失败，不重试；固定协议错误，绝不携带上游正文
                throw ModelException.protocol(MESSAGE_RESPONSE_TOO_LARGE);
            } catch (ResourceAccessException ex) {
                TransportFailure failure = classifyTransportFailure(ex);
                // 读超时（Python httpx.ReadTimeout）不重试，直接归超时
                if (failure == TransportFailure.TIMEOUT) {
                    throw ModelException.timeout(MESSAGE_TIMEOUT);
                }
                if (attempt < maxRetries) {
                    attempt++;
                    awaitRetryDelay();
                    continue;
                }
                // 连接超时（Python httpx.ConnectTimeout）与其余非超时传输失败不同：重试额度耗尽后仍归超时
                if (failure == TransportFailure.CONNECT_TIMEOUT) {
                    throw ModelException.timeout(MESSAGE_TIMEOUT);
                }
                // 其余非超时传输失败（CONNECT 与 OTHER，对应 Python `except httpx.TransportError`）额度耗尽后
                // 统一归 UPSTREAM，并复用 Python 的固定文案“模型服务连接失败”，不再退化为“模型服务暂时不可用”
                throw ModelException.upstream(MESSAGE_CONNECT_FAILED);
            }

            if (RETRYABLE_STATUSES.contains(raw.status())) {
                if (attempt < maxRetries) {
                    attempt++;
                    awaitRetryDelay();
                    continue;
                }
                throw ModelException.upstream(MESSAGE_TEMPORARILY_UNAVAILABLE);
            }

            return parseResponse(raw, request);
        }
    }

    /**
     * 重试等待，并在等待返回后检查线程中断状态。
     *
     * <p>Python 侧 {@code await asyncio.sleep(...)} 在任务被取消时抛 {@code CancelledError} 并直接
     * 向上传播，不会吞掉取消继续重试。Java 侧对应语义是：重试等待期间线程被中断时必须立即以
     * {@link CancellationException} 中止，<strong>保留中断标志</strong>且不得再发送下一次请求。
     *
     * @throws CancellationException 等待返回后当前线程仍处于中断状态
     */
    private void awaitRetryDelay() {
        retryDelay.pause(RETRY_DELAY_MILLIS);
        if (Thread.currentThread().isInterrupted()) {
            // 不清理中断标志：调用方（如线程池或上层协程适配层）需要看到取消信号
            throw new CancellationException(MESSAGE_CANCELLED);
        }
    }

    // -- 请求构造 ---------------------------------------------------------

    private RawResponse send(Map<String, Object> payload) {
        return restClient.post()
                .uri(chatUrl)
                .headers(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    headers.setBearerAuth(apiKey);
                })
                .body(payload)
                .exchange((request, response) ->
                        new RawResponse(response.getStatusCode().value(), readBody(response)));
    }

    private Map<String, Object> buildPayload(ModelRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", request.messages().stream()
                .map(OpenAiCompatibleClient::serialiseMessage)
                .toList());
        payload.put("stream", false);
        payload.put("temperature", request.temperature());
        if (!request.tools().isEmpty()) {
            payload.put("tools", request.tools());
            payload.put("tool_choice", request.toolChoice());
        }
        if (request.maxTokens() != null) {
            payload.put("max_tokens", request.maxTokens());
        }
        return payload;
    }

    private static Map<String, Object> serialiseMessage(ModelMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", message.role());
        payload.put("content", message.content());
        if (!message.toolCalls().isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ModelToolCall call : message.toolCalls()) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.name());
                function.put("arguments", call.arguments());
                Map<String, Object> serialised = new LinkedHashMap<>();
                serialised.put("id", call.id());
                serialised.put("type", "function");
                serialised.put("function", function);
                calls.add(serialised);
            }
            payload.put("tool_calls", calls);
        }
        if (message.toolCallId() != null) {
            payload.put("tool_call_id", message.toolCallId());
        }
        return payload;
    }

    // -- 响应解析 ---------------------------------------------------------

    private ModelResponse parseResponse(RawResponse raw, ModelRequest request) {
        int status = raw.status();

        if (status == 401 || status == 403) {
            throw ModelException.unavailable(MESSAGE_AUTH_FAILED);
        }
        if (status == 404) {
            throw ModelException.unavailable(MESSAGE_ENDPOINT_UNAVAILABLE);
        }
        if (status >= 500) {
            throw ModelException.upstream(MESSAGE_TEMPORARILY_UNAVAILABLE);
        }
        if (status >= 400) {
            if (!request.tools().isEmpty() && mentionsToolSupport(raw.body())) {
                throw ModelException.protocol(MESSAGE_TOOL_UNSUPPORTED);
            }
            throw ModelException.upstream(MESSAGE_REQUEST_REJECTED);
        }

        JsonNode body = parseJsonObject(raw.body());

        if (body.hasNonNull("error")) {
            if (!request.tools().isEmpty() && mentionsToolSupport(raw.body())) {
                throw ModelException.protocol(MESSAGE_TOOL_UNSUPPORTED);
            }
            throw ModelException.upstream(MESSAGE_ERROR_RESULT);
        }

        JsonNode choices = body.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw ModelException.protocol(MESSAGE_MISSING_CHOICES);
        }
        JsonNode first = choices.get(0);
        if (!first.isObject()) {
            throw ModelException.protocol(MESSAGE_MISSING_CHOICES);
        }
        JsonNode message = first.get("message");
        if (message == null || !message.isObject()) {
            throw ModelException.protocol(MESSAGE_MISSING_MESSAGE);
        }
        JsonNode contentNode = message.get("content");
        if (contentNode != null && !contentNode.isNull() && !contentNode.isTextual()) {
            throw ModelException.protocol(MESSAGE_CONTENT_TYPE);
        }
        String content = contentNode == null || contentNode.isNull() ? null : contentNode.asText();
        List<ModelToolCall> toolCalls = parseToolCalls(message.get("tool_calls"));
        // Python `not (content or "").strip()`：U+00A0/U+0085 等 Python 空白也算空，不能用 Java String.strip()
        if (toolCalls.isEmpty() && (content == null || PythonText.strip(content).isEmpty())) {
            throw ModelException.protocol(MESSAGE_EMPTY);
        }
        JsonNode finishReason = first.get("finish_reason");
        return new ModelResponse(
                content,
                toolCalls,
                parseUsage(body.get("usage")),
                finishReason != null && finishReason.isTextual() ? finishReason.asText() : null);
    }

    private static JsonNode parseJsonObject(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) {
                throw ModelException.protocol(MESSAGE_UNPARSEABLE);
            }
            return node;
        } catch (JsonProcessingException ex) {
            // 不链式持有解析异常：Jackson 的异常消息可能包含原始响应片段
            throw ModelException.protocol(MESSAGE_UNPARSEABLE);
        }
    }

    private static List<ModelToolCall> parseToolCalls(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return List.of();
        }
        if (!raw.isArray()) {
            throw ModelException.protocol(MESSAGE_TOOL_CALLS_STRUCTURE);
        }

        List<ModelToolCall> calls = new ArrayList<>();
        for (int index = 0; index < raw.size(); index++) {
            JsonNode item = raw.get(index);
            if (!item.isObject()) {
                throw ModelException.protocol(MESSAGE_TOOL_CALLS_STRUCTURE);
            }
            JsonNode function = item.get("function");
            if (function == null || !function.isObject()) {
                throw ModelException.protocol(MESSAGE_TOOL_CALLS_FUNCTION);
            }
            JsonNode name = function.get("name");
            // Python `not name.strip()`：Python 空白集合（含 U+00A0/U+0085）判空，不能用 Java String.strip()
            if (name == null || !name.isTextual() || PythonText.strip(name.asText()).isEmpty()) {
                throw ModelException.protocol(MESSAGE_TOOL_CALLS_NAME);
            }

            JsonNode argumentsNode = function.get("arguments");
            String arguments;
            if (argumentsNode == null || argumentsNode.isNull()) {
                arguments = "{}";
            } else if (argumentsNode.isTextual()) {
                arguments = argumentsNode.asText();
            } else {
                arguments = writeJson(argumentsNode);
            }

            JsonNode idNode = item.get("id");
            String id = idNode != null && idNode.isTextual() && !idNode.asText().isEmpty()
                    ? idNode.asText()
                    : "call_" + index;

            // Python `name.strip()`：工具名必须在返回（及后续白名单/dispatch）前按 Python 语义规范化
            calls.add(new ModelToolCall(id, PythonText.strip(name.asText()), arguments));
        }
        return calls;
    }

    private static ModelResponse.Usage parseUsage(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return ModelResponse.Usage.empty();
        }
        return new ModelResponse.Usage(
                integralOrNull(raw.get("prompt_tokens")),
                integralOrNull(raw.get("completion_tokens")),
                integralOrNull(raw.get("total_tokens")));
    }

    /**
     * 与 Python 一致：布尔、字符串与小数都不是整数计数；超出 {@link Integer} 范围的整数同样返回
     * {@code null}。
     *
     * <p>Python {@code _as_int} 返回任意精度 {@code int}，Java 侧 {@code Integer} 无法表示这类计数。
     * 此时必须返回 {@code null}（视为不可用），绝不能调用 {@code intValue()} 静默回绕成错误的小数值
     * 或负数。
     */
    private static Integer integralOrNull(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            return null;
        }
        return node.intValue();
    }

    /** 只用于判定错误类型，命中文本不会写入异常或日志。 */
    private static boolean mentionsToolSupport(String body) {
        if (body == null) {
            return false;
        }
        String lowered = body.toLowerCase(Locale.ROOT);
        for (String marker : TOOL_SUPPORT_MARKERS) {
            if (lowered.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 区分传输层失败类型，只用于决定重试与错误分类，不保留任何异常对象。
     *
     * <p>连接超时（{@link HttpConnectTimeoutException}，对应 Python {@code httpx.ConnectTimeout}）
     * 与读超时（{@link SocketTimeoutException}、{@link HttpTimeoutException}，
     * 对应 {@code httpx.ReadTimeout}）都归 {@link TransportFailure#TIMEOUT} 家族，但两者重试语义不同：
     * 连接超时有重试额度时可重试，读超时一律不重试。
     */
    private static TransportFailure classifyTransportFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException) {
                return TransportFailure.CONNECT_TIMEOUT;
            }
            if (cause instanceof SocketTimeoutException) {
                return TransportFailure.TIMEOUT;
            }
            if (cause instanceof HttpTimeoutException) {
                return TransportFailure.TIMEOUT;
            }
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                return TransportFailure.CONNECT;
            }
        }
        return TransportFailure.OTHER;
    }

    private static String writeJson(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw ModelException.protocol(MESSAGE_UNPARSEABLE);
        }
    }

    private static String readBody(ClientHttpResponse response) throws IOException {
        try (InputStream body = response.getBody()) {
            // 有界读取：超过 DEFAULT_MAX_BYTES 立即抛 BodyTooLargeException，不 drain 完整正文
            return BoundedBodyReader.readUtf8(body, BoundedBodyReader.DEFAULT_MAX_BYTES);
        }
    }

    private static String stripTrailingSlashes(String value) {
        String text = value == null ? "" : value;
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '/') {
            end--;
        }
        return text.substring(0, end);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 可重试失败之间的等待策略；测试注入空实现以避免真实 sleep。 */
    @FunctionalInterface
    public interface RetryDelay {

        void pause(long millis);
    }

    private enum TransportFailure {
        /** 读超时：不重试，直接归超时。 */
        TIMEOUT,
        /** 连接超时：有额度时重试，额度耗尽后归超时。 */
        CONNECT_TIMEOUT,
        /** 普通连接失败：有额度时重试，额度耗尽后归上游错误。 */
        CONNECT,
        OTHER
    }

    private record RawResponse(int status, String body) {
    }
}

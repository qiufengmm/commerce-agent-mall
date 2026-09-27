package com.macro.mall.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.macro.mall.agent.api.AgentChatService;
import com.macro.mall.agent.api.AgentController;
import com.macro.mall.agent.api.ApiExceptionHandler;
import com.macro.mall.agent.api.ChatData;
import com.macro.mall.agent.api.ChatRequest;
import com.macro.mall.agent.api.DeleteSessionData;
import com.macro.mall.agent.api.SessionData;
import com.macro.mall.agent.config.AgentProperties;
import com.macro.mall.agent.http.GeneratedBodyStream;

/**
 * {@link OpenAiCompatibleClient} 的协议、错误分类、重试与脱敏测试。
 *
 * <p>全程使用 {@link MockRestServiceServer} 离线模拟，不访问真实网络、不使用真实 API Key；
 * 测试中的 Key 与上游文本均为合成占位值，只用于断言它们不会出现在异常、捕获日志或 HTTP 响应里。
 *
 * <p>协议行为对齐 Python {@code mall_shopping_agent.model.openai_compatible} 与
 * {@code ...model.schemas}。
 */
class OpenAiCompatibleClientTest {

    /** 合成占位 Key；不是真实凭据。 */
    private static final String API_KEY = "sk-local-development-value";
    /** 合成上游响应正文标记；用于断言脱敏，不是真实内容。 */
    private static final String UPSTREAM_SECRET_MARKER = "upstream-secret-body-must-not-leak";
    /** 合成异常正文标记；用于断言 ApiExceptionHandler 不回显异常正文。 */
    private static final String EXCEPTION_SECRET_MARKER = "exception-secret-message-must-not-leak";

    /** 响应正文硬字节上限，与生产有界读取保持一致（1 MiB）。 */
    private static final long RESPONSE_BODY_CAP_BYTES = 1024L * 1024L;

    private static final String MODEL = "demo-model";
    private static final String CHAT_BASE_V1 = "https://model.example.com/v1";
    private static final String CHAT_V1_URL = CHAT_BASE_V1 + "/chat/completions";
    private static final String CHAT_ROOT_BASE = "https://api.deepseek.com";
    private static final String CHAT_ROOT_URL = CHAT_ROOT_BASE + "/chat/completions";
    private static final String VALID_CHAT_BODY =
            "{\"sessionId\":\"2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c\",\"message\":\"有哪些手机\"}";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, Object> SEARCH_TOOL = Map.of(
            "type", "function",
            "function", Map.of(
                    "name", "searchProducts",
                    "description", "搜索商品",
                    "parameters", Map.of("type", "object", "properties", Map.of())));

    private static final String TEXT_RESPONSE = """
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "created": 1710000000,
              "model": "demo-model",
              "choices": [
                {
                  "index": 0,
                  "message": {"role": "assistant", "content": "为您找到三款商品"},
                  "finish_reason": "stop"
                }
              ],
              "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}
            }
            """;

    private static final String SINGLE_TOOL_CALL_RESPONSE = """
            {
              "id": "chatcmpl-2",
              "object": "chat.completion",
              "created": 1710000001,
              "model": "demo-model",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call_1",
                        "type": "function",
                        "function": {"name": "searchProducts", "arguments": "{\\"keyword\\": \\"手机\\"}"}
                      }
                    ]
                  },
                  "finish_reason": "tool_calls"
                }
              ],
              "usage": {"prompt_tokens": 9, "completion_tokens": 5, "total_tokens": 14}
            }
            """;

    private static final String MULTIPLE_TOOL_CALLS_RESPONSE = """
            {
              "id": "chatcmpl-3",
              "object": "chat.completion",
              "created": 1710000002,
              "model": "demo-model",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call_a",
                        "type": "function",
                        "function": {"name": "getProductDetail", "arguments": "{\\"productId\\": 1}"}
                      },
                      {
                        "type": "function",
                        "function": {"name": "getProductDetail", "arguments": "{\\"productId\\": 2}"}
                      }
                    ]
                  },
                  "finish_reason": "tool_calls"
                }
              ]
            }
            """;

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    // -- 请求构造 ---------------------------------------------------------

    @Test
    @DisplayName("非流式 JSON POST 到 /chat/completions，带 Bearer 与完整工具协议")
    void postsNonStreamingJsonWithBearerHeaderAndToolProtocol() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        server.expect(requestTo(CHAT_V1_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(request -> captured.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(TEXT_RESPONSE, MediaType.APPLICATION_JSON));

        ModelResponse response = client(CHAT_BASE_V1).complete(requestWithTools());

        server.verify();
        JsonNode payload = JSON.readTree(captured.get());
        assertThat(payload.path("model").asText()).isEqualTo(MODEL);
        assertThat(payload.path("stream").asBoolean()).isFalse();
        assertThat(payload.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(payload.path("tool_choice").asText()).isEqualTo("auto");
        assertThat(payload.path("tools")).hasSize(1);
        assertThat(payload.path("tools").get(0).path("type").asText()).isEqualTo("function");
        assertThat(payload.path("tools").get(0).path("function").path("name").asText())
                .isEqualTo("searchProducts");
        assertThat(payload.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(payload.path("messages").get(0).path("content").asText()).isEqualTo("系统规则");
        assertThat(payload.has("max_tokens")).isFalse();

        assertThat(response.content()).isEqualTo("为您找到三款商品");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.finishReason()).isEqualTo("stop");
    }

    @Test
    @DisplayName("DeepSeek 根地址只追加 /chat/completions，不自动插入 /v1")
    void deepseekRootBaseUrlDoesNotGainV1Path() throws Exception {
        server.expect(requestTo(CHAT_ROOT_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TEXT_RESPONSE, MediaType.APPLICATION_JSON));

        client(CHAT_ROOT_BASE).complete(requestWithTools());

        server.verify();
    }

    @Test
    @DisplayName("带 /v1 前缀的地址得到 /v1/chat/completions，不重复路径")
    void v1BaseUrlKeepsSingleV1Segment() throws Exception {
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withSuccess(TEXT_RESPONSE, MediaType.APPLICATION_JSON));

        client(CHAT_BASE_V1).complete(requestWithTools());

        server.verify();
    }

    @Test
    @DisplayName("去除 baseUrl 末尾全部斜杠后再拼接 /chat/completions")
    void trailingSlashesAreStrippedBeforeAppending() throws Exception {
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withSuccess(TEXT_RESPONSE, MediaType.APPLICATION_JSON));

        client(CHAT_BASE_V1 + "///").complete(requestWithTools());

        server.verify();
    }

    @Test
    @DisplayName("无 tools 时请求体不含 tools 与 tool_choice")
    void payloadWithoutToolsOmitsToolFields() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        server.expect(requestTo(CHAT_V1_URL))
                .andExpect(request -> captured.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(TEXT_RESPONSE, MediaType.APPLICATION_JSON));

        ModelRequest request = new ModelRequest(
                List.of(ModelMessage.user("有哪些手机")), List.of(), ModelRequest.TOOL_CHOICE_AUTO, 0.2, null);
        client(CHAT_BASE_V1).complete(request);

        server.verify();
        JsonNode payload = JSON.readTree(captured.get());
        assertThat(payload.has("tools")).isFalse();
        assertThat(payload.has("tool_choice")).isFalse();
    }

    @Test
    @DisplayName("max_tokens 存在时透传，缺省时不出现")
    void maxTokensIsOptional() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        server.expect(requestTo(CHAT_V1_URL))
                .andExpect(request -> captured.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(TEXT_RESPONSE, MediaType.APPLICATION_JSON));

        ModelRequest request = new ModelRequest(
                List.of(ModelMessage.user("有哪些手机")), List.of(), ModelRequest.TOOL_CHOICE_AUTO, 0.5, 256);
        client(CHAT_BASE_V1).complete(request);

        server.verify();
        JsonNode payload = JSON.readTree(captured.get());
        assertThat(payload.path("max_tokens").asInt()).isEqualTo(256);
        assertThat(payload.path("temperature").asDouble()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("assistant.tool_calls 与 tool 消息按 Python schemas 序列化")
    void serialisesAssistantToolCallsAndToolMessages() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        server.expect(requestTo(CHAT_V1_URL))
                .andExpect(request -> captured.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(TEXT_RESPONSE, MediaType.APPLICATION_JSON));

        List<ModelMessage> messages = List.of(
                ModelMessage.system("系统规则"),
                ModelMessage.user("3000 元左右的手机"),
                ModelMessage.assistant(null, List.of(
                        new ModelToolCall("call_1", "searchProducts", "{\"keyword\": \"手机\"}"))),
                ModelMessage.tool("call_1", "{\"products\": []}"));
        client(CHAT_BASE_V1).complete(
                new ModelRequest(messages, List.of(SEARCH_TOOL), ModelRequest.TOOL_CHOICE_AUTO, 0.2, null));

        server.verify();
        JsonNode outgoing = JSON.readTree(captured.get()).path("messages");
        assertThat(outgoing).hasSize(4);

        JsonNode assistant = outgoing.get(2);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        assertThat(assistant.get("content").isNull()).isTrue();
        JsonNode call = assistant.path("tool_calls").get(0);
        assertThat(call.path("id").asText()).isEqualTo("call_1");
        assertThat(call.path("type").asText()).isEqualTo("function");
        assertThat(call.path("function").path("name").asText()).isEqualTo("searchProducts");
        assertThat(JSON.readTree(call.path("function").path("arguments").asText()).path("keyword").asText())
                .isEqualTo("手机");

        JsonNode tool = outgoing.get(3);
        assertThat(tool.path("role").asText()).isEqualTo("tool");
        assertThat(tool.path("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(tool.path("content").asText()).isEqualTo("{\"products\": []}");
        assertThat(tool.has("tool_calls")).isFalse();

        JsonNode system = outgoing.get(0);
        assertThat(system.has("tool_calls")).isFalse();
        assertThat(system.has("tool_call_id")).isFalse();
    }

    @Test
    @DisplayName("空或占位 API Key 不发送任何请求，映射为不可用")
    void unusableApiKeyDoesNotSendRequest() {
        for (String key : new String[] {"", "   ", "your-api-key"}) {
            OpenAiCompatibleClient unusable = new OpenAiCompatibleClient(
                    restClientBuilder, CHAT_BASE_V1, key, MODEL, 1, millis -> { });

            ModelException failure = catchThrowableOfType(
                    () -> unusable.complete(requestWithTools()), ModelException.class);

            assertThat(failure.kind()).isEqualTo(ModelException.Kind.UNAVAILABLE);
            assertThat(failure.httpStatus()).isEqualTo(503);
            assertThat(failure.getMessage()).doesNotContain(key.isBlank() ? API_KEY : key);
        }
        // 未登记任何期望：一旦真的发出请求，MockRestServiceServer 会报未预期的请求
        server.verify();
    }

    // -- 响应解析 ---------------------------------------------------------

    @Test
    @DisplayName("解析单个工具调用并保留原 id")
    void parsesSingleToolCall() throws Exception {
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withSuccess(SINGLE_TOOL_CALL_RESPONSE, MediaType.APPLICATION_JSON));

        ModelResponse response = client(CHAT_BASE_V1).complete(requestWithTools());

        server.verify();
        assertThat(response.content()).isNull();
        assertThat(response.toolCalls()).hasSize(1);
        ModelToolCall call = response.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_1");
        assertThat(call.name()).isEqualTo("searchProducts");
        assertThat(JSON.readTree(call.arguments()).path("keyword").asText()).isEqualTo("手机");
        assertThat(response.usage().promptTokens()).isEqualTo(9);
        assertThat(response.usage().completionTokens()).isEqualTo(5);
        assertThat(response.usage().totalTokens()).isEqualTo(14);
        assertThat(response.finishReason()).isEqualTo("tool_calls");
    }

    @Test
    @DisplayName("多个工具调用按原序返回，缺失 id 回退为 call_{index}")
    void parsesMultipleToolCallsInOrderWithFallbackIds() throws Exception {
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withSuccess(MULTIPLE_TOOL_CALLS_RESPONSE, MediaType.APPLICATION_JSON));

        ModelResponse response = client(CHAT_BASE_V1).complete(requestWithTools());

        server.verify();
        assertThat(response.toolCalls()).extracting(ModelToolCall::id)
                .containsExactly("call_a", "call_1");
        assertThat(response.toolCalls()).extracting(ModelToolCall::name)
                .containsExactly("getProductDetail", "getProductDetail");
        // 该 fixture 未提供 usage，缺失计数一律为 null
        assertThat(response.usage().totalTokens()).isNull();
        assertThat(response.usage().promptTokens()).isNull();
    }

    @Test
    @DisplayName("usage 仅接受整数计数，布尔/字符串/小数一律为 null")
    void usageAcceptsOnlyIntegralCounts() throws Exception {
        String body = """
                {
                  "choices": [{"message": {"role": "assistant", "content": "回答"}}],
                  "usage": {"prompt_tokens": true, "completion_tokens": "7", "total_tokens": 18}
                }
                """;
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        ModelResponse response = client(CHAT_BASE_V1).complete(requestWithTools());

        server.verify();
        assertThat(response.usage().promptTokens()).isNull();
        assertThat(response.usage().completionTokens()).isNull();
        assertThat(response.usage().totalTokens()).isEqualTo(18);
    }

    /**
     * Python {@code _as_int} 返回任意精度 int；Java 侧 {@link Integer} 无法表示超出 int 范围的计数。
     * 这种情况下必须返回 {@code null}（视为不可用），绝不能静默回绕成错误的小数值或负数。
     */
    @Test
    @DisplayName("usage 计数超出 Integer 范围时返回 null，不静默回绕")
    void usageTokensBeyondIntegerRangeBecomeNull() throws Exception {
        String body = """
                {
                  "choices": [{"message": {"role": "assistant", "content": "回答"}}],
                  "usage": {
                    "prompt_tokens": 4294967296,
                    "completion_tokens": 2147483647,
                    "total_tokens": 2147483648
                  }
                }
                """;
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        ModelResponse response = client(CHAT_BASE_V1).complete(requestWithTools());

        server.verify();
        assertThat(response.usage().promptTokens())
                .as("4294967296 超出 int 范围应返回 null")
                .isNull();
        assertThat(response.usage().totalTokens())
                .as("2147483648 超出 int 范围应返回 null")
                .isNull();
        assertThat(response.usage().completionTokens())
                .as("2147483647 在 int 范围内应原样保留")
                .isEqualTo(2147483647);
    }

    @Test
    @DisplayName("arguments 缺失或 null 转 {}，字符串原样保留")
    void argumentsFallbacksAndRawStringPreserved() throws Exception {
        String body = """
                {
                  "choices": [{
                    "message": {
                      "tool_calls": [
                        {"id": "a", "function": {"name": "searchProducts"}},
                        {"id": "b", "function": {"name": "getProductDetail", "arguments": "{not-json"}}
                      ]
                    }
                  }]
                }
                """;
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        ModelResponse response = client(CHAT_BASE_V1).complete(requestWithTools());

        server.verify();
        assertThat(response.toolCalls().get(0).arguments()).isEqualTo("{}");
        assertThat(response.toolCalls().get(1).arguments()).isEqualTo("{not-json");
    }

    @Test
    @DisplayName("非字符串 arguments 序列化为 JSON 且保留 Unicode")
    void nonStringArgumentsAreSerialisedKeepingUnicode() throws Exception {
        String body = """
                {
                  "choices": [{
                    "message": {
                      "tool_calls": [
                        {"id": "a", "function": {"name": "searchProducts",
                         "arguments": {"keyword": "手机", "pageNum": 1}}}
                      ]
                    }
                  }]
                }
                """;
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        ModelResponse response = client(CHAT_BASE_V1).complete(requestWithTools());

        server.verify();
        String arguments = response.toolCalls().get(0).arguments();
        assertThat(arguments).contains("手机");
        JsonNode parsed = JSON.readTree(arguments);
        assertThat(parsed.path("keyword").asText()).isEqualTo("手机");
        assertThat(parsed.path("pageNum").asInt()).isEqualTo(1);
    }

    // -- 协议错误 ---------------------------------------------------------

    @Test
    @DisplayName("缺少 choices 归协议错误且映射 503")
    void missingChoicesIsProtocolError() throws Exception {
        expectSingleResponse("{\"id\":\"chatcmpl-1\"}");

        ModelException failure = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

        server.verify();
        assertThat(failure.kind()).isEqualTo(ModelException.Kind.PROTOCOL);
        assertThat(failure.httpStatus()).isEqualTo(503);
        assertThat(failure.code()).isEqualTo("MODEL_PROTOCOL_ERROR");
    }

    @Test
    @DisplayName("choices[0] 缺少 message 归协议错误")
    void missingMessageIsProtocolError() throws Exception {
        expectSingleResponse("{\"choices\":[{\"index\":0}]}");

        ModelException failure = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

        server.verify();
        assertThat(failure.kind()).isEqualTo(ModelException.Kind.PROTOCOL);
    }

    @Test
    @DisplayName("content 类型非法归协议错误")
    void nonTextualContentIsProtocolError() throws Exception {
        expectSingleResponse("{\"choices\":[{\"message\":{\"content\":42}}]}");

        ModelException failure = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

        server.verify();
        assertThat(failure.kind()).isEqualTo(ModelException.Kind.PROTOCOL);
    }

    @Test
    @DisplayName("无工具调用且内容空白归协议错误")
    void blankContentWithoutToolCallsIsProtocolError() throws Exception {
        expectSingleResponse("{\"choices\":[{\"message\":{\"content\":\"   \"}}]}");

        ModelException failure = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

        server.verify();
        assertThat(failure.kind()).isEqualTo(ModelException.Kind.PROTOCOL);
    }

    @Test
    @DisplayName("content 仅含 Python str.strip() 会移除的空白（U+00A0/U+0085 等）而无工具调用，按空消息归协议错误")
    void pythonWhitespaceOnlyContentIsProtocolError() throws Exception {
        // Java String.strip() 不把 U+00A0/U+0085/U+2007/U+202F 视为空白，Python str.strip() 会；
        // Python `if not tool_calls and not (content or "").strip()` 因此判定为空消息。
        for (String escapedBlank : new String[] {
                "\\u00A0", "\\u0085", "\\u00A0\\u0085\\u00A0", "\\u2007", "\\u202F"}) {
            setUp();
            expectSingleResponse(contentOnlyBody(escapedBlank));

            ModelException failure = catchThrowableOfType(
                    () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

            server.verify();
            assertThat(failure).as("content=%s 应被 Python 语义判定为空消息", escapedBlank).isNotNull();
            assertThat(failure.kind()).as("content=%s", escapedBlank).isEqualTo(ModelException.Kind.PROTOCOL);
            assertThat(failure.code()).as("content=%s", escapedBlank).isEqualTo("MODEL_PROTOCOL_ERROR");
        }
    }

    @Test
    @DisplayName("function.name 仅含 Python str.strip() 空白（U+00A0/U+0085）按 Python 语义归协议错误")
    void pythonWhitespaceOnlyToolNameIsProtocolError() throws Exception {
        // Python `if not isinstance(name, str) or not name.strip()` 会将这类名称判为缺少名称。
        for (String escapedBlank : new String[] {"\\u00A0", "\\u0085", "\\u00A0\\u0085"}) {
            setUp();
            expectSingleResponse(toolCallBody(escapedBlank, "{}"));

            ModelException failure = catchThrowableOfType(
                    () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

            server.verify();
            assertThat(failure).as("name=%s 应被 Python 语义判为缺少名称", escapedBlank).isNotNull();
            assertThat(failure.kind()).as("name=%s", escapedBlank).isEqualTo(ModelException.Kind.PROTOCOL);
        }
    }

    @Test
    @DisplayName("function.name 首尾 Python 空白按 Python str.strip() 规范化后作为工具名返回")
    void toolNamePaddedWithPythonOnlyWhitespaceIsNormalised() throws Exception {
        // Python `name=name.strip()` 会移除首尾 U+00A0/U+0085，而 Java String.strip() 不会。
        expectSingleResponse(toolCallBody("\\u00A0searchProducts\\u0085", "{}"));

        ModelResponse response = client(CHAT_BASE_V1).complete(requestWithTools());

        server.verify();
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).name()).isEqualTo("searchProducts");
    }

    @Test
    @DisplayName("非法 JSON 与非对象 JSON 归协议错误")
    void unparseableOrNonObjectBodyIsProtocolError() throws Exception {
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withSuccess("<html>gateway</html>", MediaType.TEXT_HTML));
        ModelException notJson = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);
        assertThat(notJson.kind()).isEqualTo(ModelException.Kind.PROTOCOL);

        setUp();
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withSuccess("[1, 2, 3]", MediaType.APPLICATION_JSON));
        ModelException notObject = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);
        assertThat(notObject.kind()).isEqualTo(ModelException.Kind.PROTOCOL);

        server.verify();
    }

    @Test
    @DisplayName("tool_calls 结构非法归协议错误")
    void invalidToolCallsStructureIsProtocolError() throws Exception {
        for (String body : new String[] {
                "{\"choices\":[{\"message\":{\"tool_calls\":\"nope\"}}]}",
                "{\"choices\":[{\"message\":{\"tool_calls\":[123]}}]}",
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"a\"}]}}]}",
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"\"}}]}}]}"}) {
            setUp();
            expectSingleResponse(body);
            ModelException failure = catchThrowableOfType(
                    () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);
            server.verify();
            assertThat(failure.kind()).as(body).isEqualTo(ModelException.Kind.PROTOCOL);
        }
    }

    @Test
    @DisplayName("2xx 响应体含 error 字段归上游错误")
    void errorFieldInSuccessfulBodyIsUpstreamError() throws Exception {
        expectSingleResponse("{\"error\":{\"message\":\"" + UPSTREAM_SECRET_MARKER + "\"}}");

        ModelException failure = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

        server.verify();
        assertThat(failure.kind()).isEqualTo(ModelException.Kind.UPSTREAM);
        assertThat(failure.httpStatus()).isEqualTo(502);
        assertThat(failure.getMessage()).doesNotContain(UPSTREAM_SECRET_MARKER);
    }

    // -- 状态码分类与重试 -------------------------------------------------

    @Test
    @DisplayName("401/403/404 映射不可用且不重试")
    void authAndNotFoundMapToUnavailableWithoutRetry() throws Exception {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND}) {
            setUp();
            server.expect(requestTo(CHAT_V1_URL)).andRespond(withStatus(status));
            ModelException failure = catchThrowableOfType(
                    () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);
            server.verify();
            assertThat(failure.kind()).as(status.name()).isEqualTo(ModelException.Kind.UNAVAILABLE);
            assertThat(failure.httpStatus()).isEqualTo(503);
        }
    }

    @Test
    @DisplayName("一般 4xx 与 500 归上游错误且不重试")
    void genericClientAndServerErrorsMapToUpstreamWithoutRetry() throws Exception {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.BAD_REQUEST, HttpStatus.INTERNAL_SERVER_ERROR}) {
            setUp();
            server.expect(requestTo(CHAT_V1_URL)).andRespond(withStatus(status));
            ModelException failure = catchThrowableOfType(
                    () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);
            server.verify();
            assertThat(failure.kind()).as(status.name()).isEqualTo(ModelException.Kind.UPSTREAM);
            assertThat(failure.httpStatus()).isEqualTo(502);
        }
    }

    @Test
    @DisplayName("带 tools 的 4xx 响应文本命中工具标记时归协议错误且不重试")
    void toolUnsupportedMarkerMapsToProtocolError() throws Exception {
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"error\":{\"message\":\"this model does not support tool_choice\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        ModelException failure = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

        server.verify();
        assertThat(failure.kind()).isEqualTo(ModelException.Kind.PROTOCOL);
        assertThat(failure.getMessage()).doesNotContain("tool_choice");
    }

    @Test
    @DisplayName("429 重试一次后成功，共两次请求")
    void retriesOnceOn429ThenSucceeds() throws Exception {
        server.expect(requestTo(CHAT_V1_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withSuccess(TEXT_RESPONSE, MediaType.APPLICATION_JSON));

        ModelResponse response = client(CHAT_BASE_V1).complete(requestWithTools());

        server.verify();
        assertThat(response.content()).isEqualTo("为您找到三款商品");
    }

    @Test
    @DisplayName("503 重试一次后仍失败，共两次请求并归上游错误")
    void retriesOnceOn503ThenRaisesUpstream() throws Exception {
        server.expect(requestTo(CHAT_V1_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(CHAT_V1_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        ModelException failure = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

        server.verify();
        assertThat(failure.kind()).isEqualTo(ModelException.Kind.UPSTREAM);
        assertThat(failure.httpStatus()).isEqualTo(502);
    }

    @Test
    @DisplayName("502/504 各重试一次")
    void retriesOnceOn502And504() throws Exception {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.BAD_GATEWAY, HttpStatus.GATEWAY_TIMEOUT}) {
            setUp();
            server.expect(requestTo(CHAT_V1_URL)).andRespond(withStatus(status));
            server.expect(requestTo(CHAT_V1_URL)).andRespond(withStatus(status));
            ModelException failure = catchThrowableOfType(
                    () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);
            server.verify();
            assertThat(failure.kind()).as(status.name()).isEqualTo(ModelException.Kind.UPSTREAM);
        }
    }

    @Test
    @DisplayName("读超时映射超时且不重试")
    void readTimeoutMapsToTimeoutWithoutRetry() {
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        ModelException failure = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

        server.verify();
        assertThat(failure.kind()).isEqualTo(ModelException.Kind.TIMEOUT);
        assertThat(failure.httpStatus()).isEqualTo(502);
    }

    @Test
    @DisplayName("通用 HttpTimeoutException 映射超时且不重试，共一次请求")
    void genericHttpTimeoutMapsToTimeoutWithoutRetry() {
        // Python `except httpx.TimeoutException` 中，非 ConnectTimeout 的超时一律直接归
        // ModelTimeoutError。java.net.http.HttpTimeoutException 是 HttpConnectTimeoutException 的父类，
        // 必须按读超时语义（TIMEOUT、不重试）处理，不能被误判为可重试的连接超时。
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withException(new HttpTimeoutException("request timed out")));

        ModelException failure = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

        server.verify();
        assertThat(failure.kind()).isEqualTo(ModelException.Kind.TIMEOUT);
        assertThat(failure.code()).isEqualTo("MODEL_TIMEOUT");
        assertThat(failure.httpStatus()).isEqualTo(502);
    }

    @Test
    @DisplayName("连接失败重试一次后归上游错误，共两次请求")
    void connectFailureRetriesOnceThenRaisesUpstream() {
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withException(new ConnectException("connection refused")));
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withException(new ConnectException("connection refused")));

        ModelException failure = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

        server.verify();
        assertThat(failure.kind()).isEqualTo(ModelException.Kind.UPSTREAM);
    }

    /**
     * Python 侧除 {@code httpx.ConnectTimeout} 外的传输失败（{@code httpx.TransportError}）在重试额度用尽后
     * 统一 {@code raise ModelUpstreamError("模型服务连接失败")}，不区分更细的连接类型。Java 侧对既不是超时、
     * 也不是明确连接类原因的传输失败（如普通 {@link IOException}）必须给出同一文案，不得退化为
     * “模型服务暂时不可用”。
     */
    @Test
    @DisplayName("非超时、非明确连接类的传输失败重试耗尽后归上游并报连接失败文案，共两次请求")
    void exhaustedGenericTransportFailureRaisesUpstreamWithConnectFailedMessage() {
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withException(new IOException("transport failure")));
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withException(new IOException("transport failure")));

        ModelException failure = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

        server.verify();
        assertThat(failure.kind()).isEqualTo(ModelException.Kind.UPSTREAM);
        assertThat(failure.httpStatus()).isEqualTo(502);
        assertThat(failure.getMessage()).isEqualTo("模型服务连接失败");
    }

    @Test
    @DisplayName("连接超时重试一次后成功，共两次请求")
    void connectTimeoutRetriesOnceThenSucceeds() throws Exception {
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withException(new HttpConnectTimeoutException("connect timed out")));
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withSuccess(TEXT_RESPONSE, MediaType.APPLICATION_JSON));

        ModelResponse response = client(CHAT_BASE_V1).complete(requestWithTools());

        server.verify();
        assertThat(response.content()).isEqualTo("为您找到三款商品");
    }

    /**
     * Python 的 {@code httpx.ConnectTimeout} 属于可重试超时：有重试额度时先重试，额度耗尽后抛
     * {@code ModelTimeoutError}（TIMEOUT），而不是上游连接失败。Java 侧必须与 Python 一致，
     * 不能在重试耗尽后把连接超时降级为 CONNECT/UPSTREAM 错误。
     */
    @Test
    @DisplayName("连续两次连接超时最终归超时（TIMEOUT），共两次请求")
    void repeatedConnectTimeoutFinalisesAsTimeout() {
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withException(new HttpConnectTimeoutException("connect timed out")));
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withException(new HttpConnectTimeoutException("connect timed out")));

        ModelException failure = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

        server.verify();
        assertThat(failure.kind()).isEqualTo(ModelException.Kind.TIMEOUT);
        assertThat(failure.code()).isEqualTo("MODEL_TIMEOUT");
        assertThat(failure.httpStatus()).isEqualTo(502);
    }

    /**
     * 重试等待期间线程被中断时，必须按 Java 取消语义立即中止，不得再发起第二次 HTTP 请求。
     * 对应 Python {@code await asyncio.sleep} 在任务取消时抛 {@code CancelledError} 并直接向上传播，
     * 不会吞掉取消继续重试。用例使用真实重试等待（默认构造器），由线程中断触发，
     * 并在 finally 清除当前线程中断标志，避免污染其它用例。
     */
    @Test
    @DisplayName("重试等待期间线程中断时立即取消且不发第二次请求")
    void interruptedRetryDelayCancelsWithoutSecondRequest() {
        server.expect(requestTo(CHAT_V1_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // 默认构造器使用真实重试等待；线程已中断时等待立即被打断，无需真实等待满 500ms。
        OpenAiCompatibleClient cancellable =
                new OpenAiCompatibleClient(restClientBuilder, CHAT_BASE_V1, API_KEY, MODEL);
        Thread.currentThread().interrupt();
        Throwable failure;
        try {
            failure = catchThrowable(() -> cancellable.complete(requestWithTools()));
            // finally 会清除中断标志；清除前必须确认取消信号仍在，供上层线程池/适配层观察。
            assertThat(Thread.currentThread().isInterrupted())
                    .as("重试等待取消后、finally 清除中断标志前，当前线程中断标志仍应为 true")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(failure)
                .as("中断后的重试必须按 Java 取消语义中止，而不是继续发起第二次请求")
                .isInstanceOf(CancellationException.class);
        // 只登记了一次期望：若仍发起第二次请求，MockRestServiceServer 会因未预期请求失败。
        server.verify();
    }

    // -- 响应体硬上限 -----------------------------------------------------

    /**
     * 恶意/故障上游可返回超大正文；客户端必须以固定字节上限有界读取，超过上限立即按固定安全错误失败，
     * 且<strong>不在读到上限后继续 drain 完整正文</strong>（否则仍会造成内存/带宽放大）。
     * 异常不得携带合成正文标记或 Key。
     */
    @Test
    @DisplayName("响应体超过硬上限时按固定协议错误失败，且不读取封顶之后的正文")
    void oversizedResponseBodyFailsWithFixedProtocolErrorWithoutDraining() {
        long cap = RESPONSE_BODY_CAP_BYTES;
        GeneratedBodyStream stream = new GeneratedBodyStream(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + UPSTREAM_SECRET_MARKER,
                "\"}}]}",
                cap + 4096);
        server.expect(requestTo(CHAT_V1_URL)).andRespond(request -> {
            MockClientHttpResponse response = new MockClientHttpResponse(stream, HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        });

        ModelException failure = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);

        server.verify();
        assertThat(failure).as("超过硬上限必须按固定错误失败，而不是当成正常响应").isNotNull();
        assertThat(failure.kind()).isEqualTo(ModelException.Kind.PROTOCOL);
        assertThat(failure.getCause()).as("不得链式持有上游正文或原始异常").isNull();
        assertThat(failure.getMessage())
                .doesNotContain(UPSTREAM_SECRET_MARKER)
                .doesNotContain(API_KEY);
        assertThat(stream.consumed())
                .as("读到上限后必须停止，不得继续 drain 完整正文")
                .isLessThanOrEqualTo(cap + 1)
                .isLessThan(stream.totalBytes());
    }

    // -- 脱敏 -------------------------------------------------------------

    @Test
    @DisplayName("异常不携带、不输出上游正文、Key 或 Authorization，且不链式持有原因")
    void errorsNeverExposeSecretsOrRawBody() {
        setUp();
        ModelException rateLimited = completeExpectingFailure(service -> {
            service.expect(requestTo(CHAT_V1_URL)).andRespond(singleFailureResponse());
            service.expect(requestTo(CHAT_V1_URL)).andRespond(singleFailureResponse());
        });
        assertNoSecrets(rateLimited, "429");

        setUp();
        ModelException serverError = completeExpectingFailure(service ->
                service.expect(requestTo(CHAT_V1_URL))
                        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(UPSTREAM_SECRET_MARKER).contentType(MediaType.TEXT_PLAIN)));
        assertNoSecrets(serverError, "500");

        setUp();
        ModelException unauthorized = completeExpectingFailure(service ->
                service.expect(requestTo(CHAT_V1_URL))
                        .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                                .body(UPSTREAM_SECRET_MARKER).contentType(MediaType.TEXT_PLAIN)));
        assertNoSecrets(unauthorized, "401");

        setUp();
        ModelException unparseable = completeExpectingFailure(service ->
                service.expect(requestTo(CHAT_V1_URL))
                        .andRespond(withSuccess(UPSTREAM_SECRET_MARKER, MediaType.TEXT_PLAIN)));
        assertNoSecrets(unparseable, "garbage");
    }

    @Test
    @DisplayName("ApiExceptionHandler 兜底不回显模型异常正文、Key 或 Authorization，日志同样不含")
    void apiExceptionHandlerDoesNotLeakModelSecrets() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AgentController(
                        new LeakyAgentChatService(), AgentProperties.from(new MockEnvironment())))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        Logger handlerLogger = (Logger) org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
        MvcResult result;
        try {
            result = mockMvc.perform(MockMvcRequestBuilders.post("/agent/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_CHAT_BODY))
                    .andExpect(MockMvcResultMatchers.status().isInternalServerError())
                    .andReturn();
        } finally {
            handlerLogger.detachAppender(appender);
        }

        String responseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(responseBody)
                .doesNotContain(EXCEPTION_SECRET_MARKER)
                .doesNotContain(API_KEY)
                .doesNotContain("Authorization")
                .doesNotContain("Exception");

        String logged = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(logged)
                .doesNotContain(EXCEPTION_SECRET_MARKER)
                .doesNotContain(API_KEY)
                .doesNotContain("Authorization");
    }

    // -- helpers ----------------------------------------------------------

    private OpenAiCompatibleClient client(String baseUrl) {
        return new OpenAiCompatibleClient(restClientBuilder, baseUrl, API_KEY, MODEL, 1, millis -> { });
    }

    private static ModelRequest requestWithTools() {
        return new ModelRequest(
                List.of(ModelMessage.system("系统规则"), ModelMessage.user("3000 元左右的手机")),
                List.of(SEARCH_TOOL),
                ModelRequest.TOOL_CHOICE_AUTO,
                0.2,
                null);
    }

    private void expectSingleResponse(String body) {
        server.expect(requestTo(CHAT_V1_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    /** 生成仅含 assistant content 的响应体；{@code escapedContent} 为 JSON 字符串转义片段（如 {@code \\u00A0}）。 */
    private static String contentOnlyBody(String escapedContent) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + escapedContent + "\"}}]}";
    }

    /** 生成含单个 tool call 的响应体；名称与参数均以 JSON 字符串转义片段给出。 */
    private static String toolCallBody(String escapedName, String escapedArguments) {
        return "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"call_1\",\"function\":{\"name\":\""
                + escapedName + "\",\"arguments\":\"" + escapedArguments + "\"}}]}}]}";
    }

    /** 429 的可重试失败响应，正文携带合成上游标记。 */
    private static org.springframework.test.web.client.ResponseCreator singleFailureResponse() {
        return withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body("{\"error\":{\"message\":\"" + UPSTREAM_SECRET_MARKER + "\"}}")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private ModelException completeExpectingFailure(
            java.util.function.Consumer<MockRestServiceServer> expectations) {
        expectations.accept(server);
        ModelException failure = catchThrowableOfType(
                () -> client(CHAT_BASE_V1).complete(requestWithTools()), ModelException.class);
        assertThat(failure).isNotNull();
        server.verify();
        return failure;
    }

    private static void assertNoSecrets(ModelException failure, String label) {
        assertThat(failure.getMessage()).as(label).doesNotContain(UPSTREAM_SECRET_MARKER);
        assertThat(failure.getMessage()).as(label).doesNotContain(API_KEY);
        assertThat(failure.getMessage().toLowerCase(Locale.ROOT)).as(label).doesNotContain("authorization");
        assertThat(failure.getCause()).as(label).isNull();
    }

    /** 让受测控制器抛出携带合成敏感标记的模型异常，验证兜底不泄露。 */
    private static final class LeakyAgentChatService implements AgentChatService {

        @Override
        public ChatData chat(ChatRequest request, String authorization, String clientIp) {
            throw ModelException.upstream(EXCEPTION_SECRET_MARKER);
        }

        @Override
        public SessionData getSession(String sessionId, String authorization, String clientIp) {
            throw new UnsupportedOperationException("当前用例不覆盖 GET 会话");
        }

        @Override
        public DeleteSessionData deleteSession(String sessionId, String authorization, String clientIp) {
            throw new UnsupportedOperationException("当前用例不覆盖 DELETE 会话");
        }
    }
}

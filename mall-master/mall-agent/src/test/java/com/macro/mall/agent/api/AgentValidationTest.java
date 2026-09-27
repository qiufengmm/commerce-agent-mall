package com.macro.mall.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.mockito.ArgumentCaptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.macro.mall.agent.config.AgentConfiguration;
import com.macro.mall.agent.config.AgentProperties;

/**
 * 输入校验与 CORS 列表配置测试。
 *
 * <p>本类额外覆盖逗号分隔的 {@link AgentProperties#CORS_ALLOW_ORIGINS} 配置，
 * 因此使用独立的属性覆盖；通配 origin 的行为在 {@link AgentContractTest} 中覆盖。
 *
 * <p>sessionId 规则与 Python {@code validate_session_id} 对齐：按 Python {@code str.strip()}
 * 去首尾空白、转小写后必须是规范 UUID v4 文本，因此大写与带空白形式会被接受并规范化。
 * Python 的 {@code uuid.UUID(text).version == 4} 在变体位不是 RFC 4122 时返回 {@code None}，
 * 因此拒绝非 RFC 变体位同样属于 Python 行为，而不是额外的强化。
 *
 * <p>message 规则同样对齐 Python {@code ChatRequest}：先按 Python {@code str.strip()} 剥除
 * 首尾空白，再按 Unicode 码点（而非 UTF-16 单元）校验长度 1..1000。
 */
@WebMvcTest(AgentController.class)
@Import(AgentConfiguration.class)
@TestPropertySource(properties = "MALL_AGENT_CORS_ALLOW_ORIGINS=https://a.example, https://b.example")
class AgentValidationTest {

    private static final String SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c";
    private static final String MESSAGE_ID = "5f1c1a2b-3d4e-4f50-8a9b-0c1d2e3f4a5b";
    private static final String BAD_REQUEST_MESSAGE = "请求参数不合法。";
    private static final String INVALID_SESSION_ID_MESSAGE = "sessionId 必须是 UUID v4。";

    /** U+00A0 NO-BREAK SPACE：Python str.isspace() 为真，Java String.strip() 不处理。 */
    private static final String NBSP = "\u00A0";

    /** U+0085 NEXT LINE：Python str.isspace() 为真，Java String.strip() 不处理。 */
    private static final String NEL = "\u0085";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentChatService chatService;

    @BeforeEach
    void stubContractResponses() {
        given(chatService.chat(any(ChatRequest.class), any(), any()))
                .willReturn(new ChatData(SESSION_ID, MESSAGE_ID, "回答", List.of(), false, List.of()));
        given(chatService.getSession(any(), any(), any())).willReturn(new SessionData(SESSION_ID, List.of(), List.of(), false));
        given(chatService.deleteSession(any(), any(), any()))
                .willReturn(new DeleteSessionData(SESSION_ID, true, false));
    }

    @Test
    @DisplayName("缺少 message 被拒绝")
    void missingMessageIsRejected() throws Exception {
        assertBadRequest(chatBody("{\"sessionId\":\"" + SESSION_ID + "\"}"));
    }

    @Test
    @DisplayName("缺少 sessionId 被拒绝")
    void missingSessionIdIsRejected() throws Exception {
        assertBadRequest(chatBody("{\"message\":\"有哪些手机\"}"));
    }

    @Test
    @DisplayName("空白消息被拒绝")
    void blankMessageIsRejected() throws Exception {
        assertBadRequest(chatBody(chatJson("   ")));
    }

    @Test
    @DisplayName("超过 1000 字符的消息被拒绝")
    void overLongMessageIsRejected() throws Exception {
        assertBadRequest(chatBody(chatJson("a".repeat(1001))));
    }

    @Test
    @DisplayName("正好 1000 字符的消息被接受")
    void messageAtUpperBoundaryIsAccepted() throws Exception {
        mockMvc.perform(chatBody(chatJson("a".repeat(1000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("单字符消息被接受")
    void singleCharacterMessageIsAccepted() throws Exception {
        mockMvc.perform(chatBody(chatJson("好")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /** Python 侧 ChatRequest 为 strict=True，JSON 数字/布尔/数组/对象不能被自动转成字符串。 */
    @ParameterizedTest(name = "非字符串 message 被拒绝：{0}")
    @ValueSource(strings = {"123", "1.5", "0", "true", "false", "[]", "[\"a\"]", "{}", "{\"a\":1}"})
    void nonStringMessageIsRejected(String rawMessageValue) throws Exception {
        MvcResult result = mockMvc.perform(chatBody(chatJsonRawMessage(rawMessageValue)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(BAD_REQUEST_MESSAGE))
                .andReturn();

        assertThat(responseBody(result)).doesNotContain("Exception").doesNotContain("at com.");
        then(chatService).should(never()).chat(any(ChatRequest.class), any(), any());
    }

    @Test
    @DisplayName("null message 被拒绝且不调用业务层")
    void nullMessageIsRejected() throws Exception {
        mockMvc.perform(chatBody(chatJsonRawMessage("null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(BAD_REQUEST_MESSAGE));

        then(chatService).should(never()).chat(any(ChatRequest.class), any(), any());
    }

    @Test
    @DisplayName("非规范 UUID v4 被拒绝")
    void nonCanonicalSessionIdsAreRejected() throws Exception {
        assertBadRequest(chatBody(chatJsonUuid("2dc7b03e73684d6aa8efb0ea16f6c92c")));
        // JSON 字符串中的真实花括号 UUID（{...}）不是规范形式，必须被拒绝
        assertBadRequest(chatBody(chatJsonUuid("{" + SESSION_ID + "}")));
        // JSON 请求体不做 URL 解码：字面量 "%7B...%7D" 只是普通文本，同样不是规范 UUID
        assertBadRequest(chatBody(chatJsonUuid("%7B2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c%7D")));
        assertBadRequest(chatBody(chatJsonUuid("2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92")));
        assertBadRequest(chatBody(chatJsonUuid("")));
    }

    @Test
    @DisplayName("UUID v1 被拒绝")
    void uuidVersionOneIsRejected() throws Exception {
        assertBadRequest(chatBody(chatJsonUuid("2dc7b03e-7368-1a67-a8ef-b0ea16f6c92c")));
    }

    @Test
    @DisplayName("变体位不符合 RFC 4122/9562 的 UUID 被拒绝")
    void uuidWithInvalidVariantIsRejected() throws Exception {
        for (String raw : new String[] {
                "2dc7b03e-7368-4d6a-0aef-b0ea16f6c92c",
                "2dc7b03e-7368-4d6a-7aef-b0ea16f6c92c",
                "2dc7b03e-7368-4d6a-caef-b0ea16f6c92c"}) {
            assertBadRequest(chatBody(chatJsonUuid(raw)));
            mockMvc.perform(get("/agent/session/" + raw))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value(INVALID_SESSION_ID_MESSAGE));
            mockMvc.perform(delete("/agent/session/" + raw))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value(INVALID_SESSION_ID_MESSAGE));
        }
    }

    @Test
    @DisplayName("大写 UUID 被规范化为小写后传给业务层")
    void uppercaseSessionIdIsNormalisedToLowercase() throws Exception {
        mockMvc.perform(chatBody(chatJsonUuid(SESSION_ID.toUpperCase(Locale.ROOT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        then(chatService).should().chat(captor.capture(), any(), any());
        assertThat(captor.getValue().sessionId()).isEqualTo(SESSION_ID);
    }

    @Test
    @DisplayName("带首尾空格的 UUID 被规范化后传给业务层")
    void paddedSessionIdIsNormalised() throws Exception {
        mockMvc.perform(chatBody(chatJsonUuid("  " + SESSION_ID.toUpperCase(Locale.ROOT) + "  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        then(chatService).should().chat(captor.capture(), any(), any());
        assertThat(captor.getValue().sessionId()).isEqualTo(SESSION_ID);
    }

    @Test
    @DisplayName("GET/DELETE 路径中的大写 UUID 被规范化后传给业务层")
    void pathSessionIdIsNormalisedToLowercase() throws Exception {
        mockMvc.perform(get("/agent/session/" + SESSION_ID.toUpperCase(Locale.ROOT)))
                .andExpect(status().isOk());
        then(chatService).should().getSession(eq(SESSION_ID), any(), any());

        mockMvc.perform(delete("/agent/session/" + SESSION_ID.toUpperCase(Locale.ROOT)))
                .andExpect(status().isOk());
        then(chatService).should().deleteSession(eq(SESSION_ID), any(), any());
    }

    /**
     * Python {@code str.strip()} 的空白集合比 Java {@code String.strip()} 更宽：
     * U+00A0、U+0085、U+2007、U+202F 等 Java 不视为空白的码点，Python 会剥除。
     */
    @ParameterizedTest(name = "message 首尾 U+{0} 被 Python 语义剥除")
    @ValueSource(strings = {"00A0", "0085", "1680", "2007", "2000", "202F", "205F", "3000"})
    void messageWithPythonWhitespaceIsStripped(String codePointHex) throws Exception {
        String blank = Character.toString(Integer.parseInt(codePointHex, 16));

        mockMvc.perform(chatBody(chatJson(blank + "有哪些手机" + blank)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        then(chatService).should().chat(captor.capture(), any(), any());
        assertThat(captor.getValue().message())
                .as("U+%s 应被 Python str.strip() 语义剥除", codePointHex)
                .isEqualTo("有哪些手机");
    }

    @ParameterizedTest(name = "仅含 Python 空白 U+{0} 的 message 被拒绝")
    @ValueSource(strings = {"00A0", "0085", "1680", "2007", "2000", "202F", "205F", "3000"})
    void messageOfOnlyPythonWhitespaceIsRejected(String codePointHex) throws Exception {
        String blank = Character.toString(Integer.parseInt(codePointHex, 16));

        assertBadRequest(chatBody(chatJson(blank)));

        then(chatService).should(never()).chat(any(ChatRequest.class), any(), any());
    }

    /** Python {@code len()} 按 Unicode 码点计数，Java {@code String.length()} 与 {@code @Size} 按 UTF-16 单元计数。 */
    @Test
    @DisplayName("1000 个补充平面字符（emoji）按码点计数被接受")
    void supplementaryPlaneMessageAtCodePointBoundaryIsAccepted() throws Exception {
        String emoji = emoji();
        assertThat(emoji.length()).as("单个 emoji 占 2 个 UTF-16 单元").isEqualTo(2);

        mockMvc.perform(chatBody(chatJson(emoji.repeat(1000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        then(chatService).should().chat(captor.capture(), any(), any());
        String accepted = captor.getValue().message();
        assertThat(accepted.codePointCount(0, accepted.length()))
                .as("按 Unicode 码点应为 1000")
                .isEqualTo(1000);
        assertThat(accepted.length())
                .as("UTF-16 单元应为 2000，证明长度按码点而非 UTF-16 单元计算")
                .isEqualTo(2000);
    }

    @Test
    @DisplayName("1001 个补充平面字符按码点计数被拒绝")
    void supplementaryPlaneMessageAboveCodePointBoundaryIsRejected() throws Exception {
        assertBadRequest(chatBody(chatJson(emoji().repeat(1001))));

        then(chatService).should(never()).chat(any(ChatRequest.class), any(), any());
    }

    @Test
    @DisplayName("999 个 emoji 加 1 个 ASCII 恰为 1000 码点并被接受")
    void mixedCodePointMessageAtBoundaryIsAccepted() throws Exception {
        mockMvc.perform(chatBody(chatJson(emoji().repeat(999) + "a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("sessionId 两侧带 U+00A0 与 U+0085 时被接受并规范化为小写")
    void sessionIdWithPythonWhitespacePaddingIsNormalised() throws Exception {
        mockMvc.perform(chatBody(chatJsonUuid(NBSP + SESSION_ID.toUpperCase(Locale.ROOT) + NEL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        then(chatService).should().chat(captor.capture(), any(), any());
        assertThat(captor.getValue().sessionId()).isEqualTo(SESSION_ID);
    }

    @Test
    @DisplayName("请求体中仅含 Python 空白的 sessionId 被拒绝")
    void whitespaceOnlySessionIdIsRejected() throws Exception {
        assertBadRequest(chatBody(chatJsonUuid(NBSP + NEL)));
    }

    /**
     * 路径中的编码空白必须先经 Spring MVC 解码，再按 Python 语义 strip 与转小写。
     * 本用例端到端验证编码路径最终规范化为 sessionId 后传给业务层：断言
     * {@code chatService.getSession} / {@code chatService.deleteSession} 收到规范小写 UUID；
     * 本用例并不直接捕获 MVC 解码出的中间字符串。
     *
     * <p>这里用 {@link URI} 而不是 {@code get(String)}：后者把入参当作 URL 模板并把 {@code %} 再编码一次
     * （实测 requestURI 变成 {@code %2520}），无法表达真实请求行里的编码空白；{@code URI} 保留单次编码，
     * 使请求行按原样携带 {@code %20} / {@code %C2%A0} / {@code %C2%85} / {@code %09} 等序列。
     */
    @ParameterizedTest(name = "GET/DELETE 路径中编码空白 {0} 被解码并规范化")
    @ValueSource(strings = {"%20", "%C2%A0", "%C2%85", "%09"})
    void encodedWhitespaceInPathVariableIsDecodedAndNormalised(String encodedPadding) throws Exception {
        URI path = URI.create("/agent/session/"
                + encodedPadding + SESSION_ID.toUpperCase(Locale.ROOT) + encodedPadding);

        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        then(chatService).should().getSession(eq(SESSION_ID), any(), any());

        mockMvc.perform(delete(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        then(chatService).should().deleteSession(eq(SESSION_ID), any(), any());
    }

    @Test
    @DisplayName("路径中只含编码空白时被拒绝")
    void pathWithOnlyEncodedWhitespaceIsRejected() throws Exception {
        for (String rawPath : new String[] {
                "/agent/session/%20",
                "/agent/session/%C2%A0",
                "/agent/session/%C2%85"}) {
            mockMvc.perform(get(URI.create(rawPath)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value(INVALID_SESSION_ID_MESSAGE));
            mockMvc.perform(delete(URI.create(rawPath)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value(INVALID_SESSION_ID_MESSAGE));
        }
    }

    @ParameterizedTest(name = "未知请求字段 {0} 被拒绝")
    @ValueSource(strings = {"memberId", "token", "url", "authorization", "tools", "modelMode"})
    void unknownRequestFieldsAreRejected(String fieldName) throws Exception {
        String marker = "unit-test-extra-field-value";
        String body = chatJson("有哪些手机");
        String withExtraField = body.substring(0, body.length() - 1)
                + ",\"" + fieldName + "\":\"" + marker + "\"}";

        MvcResult result = mockMvc.perform(chatBody(withExtraField))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(BAD_REQUEST_MESSAGE))
                .andReturn();

        assertThat(responseBody(result))
                .as("错误响应不得回显未知字段名或取值")
                .doesNotContain(fieldName)
                .doesNotContain(marker);
    }

    @Test
    @DisplayName("路径中的非法 sessionId 被拒绝")
    void invalidPathSessionIdIsRejected() throws Exception {
        for (String raw : new String[] {
                "not-a-uuid",
                "2dc7b03e-7368-1a67-a8ef-b0ea16f6c92c",
                "2dc7b03e73684d6aa8efb0ea16f6c92c"}) {
            mockMvc.perform(get("/agent/session/" + raw))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value(INVALID_SESSION_ID_MESSAGE));
            mockMvc.perform(delete("/agent/session/" + raw))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value(INVALID_SESSION_ID_MESSAGE));
        }
    }

    @Test
    @DisplayName("错误响应保持 code/message/data 外层结构且不含异常细节")
    void errorResponseKeepsEnvelopeShape() throws Exception {
        MvcResult result = mockMvc.perform(chatBody(chatJsonUuid("not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(responseBody(result))
                .contains("\"code\":400")
                .contains("\"message\":\"" + BAD_REQUEST_MESSAGE + "\"")
                .contains("\"data\":null")
                .doesNotContain("Exception")
                .doesNotContain("java.")
                .doesNotContain("stackTrace")
                .doesNotContain("at com.");
    }

    @Test
    @DisplayName("CORS 使用逗号分隔的 origin 列表并 trim")
    void corsUsesConfiguredOriginList() throws Exception {
        assertPreflightAllowed("https://a.example", "https://a.example");
        assertPreflightAllowed("https://b.example", "https://b.example");
    }

    @Test
    @DisplayName("CORS 拒绝不在列表中的 origin")
    void corsRejectsUnknownOrigin() throws Exception {
        mockMvc.perform(options("/agent/chat")
                        .header(HttpHeaders.ORIGIN, "https://unknown.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    private void assertPreflightAllowed(String origin, String expectedOrigin) throws Exception {
        mockMvc.perform(options("/agent/chat")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, expectedOrigin))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    private void assertBadRequest(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(BAD_REQUEST_MESSAGE))
                .andReturn();
        assertThat(responseBody(result))
                .doesNotContain("Exception")
                .doesNotContain("at com.")
                .doesNotContain(".java:");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder chatBody(String json) {
        return post("/agent/chat").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static String chatJson(String message) {
        return "{\"sessionId\":\"" + SESSION_ID + "\",\"message\":\"" + message + "\"}";
    }

    private static String chatJsonUuid(String sessionId) {
        return "{\"sessionId\":\"" + sessionId + "\",\"message\":\"有哪些手机\"}";
    }

    /** message 使用原始 JSON 字面量，用于覆盖非字符串类型。 */
    private static String chatJsonRawMessage(String rawMessageValue) {
        return "{\"sessionId\":\"" + SESSION_ID + "\",\"message\":" + rawMessageValue + "}";
    }

    /** U+1F600 GRINNING FACE：1 个 Unicode 码点、2 个 UTF-16 单元。 */
    private static String emoji() {
        return new String(Character.toChars(0x1F600));
    }

    private static String responseBody(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}

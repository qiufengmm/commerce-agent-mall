package com.macro.mall.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.macro.mall.agent.config.AgentConfiguration;
import com.macro.mall.agent.config.AgentCorsConfiguration;

/**
 * HTTP 契约测试：字段命名、响应结构、端点方法与 CORS。
 *
 * <p>使用 {@code @WebMvcTest} 切片：Jackson 与 CORS 均取自真实配置
 * （{@code application.yml} 与 {@link AgentCorsConfiguration}），
 * 业务编排用测试替身，不连接 Redis、mall-portal 或模型。
 */
@WebMvcTest(AgentController.class)
@Import(AgentConfiguration.class)
class AgentContractTest {

    private static final String SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c";

    /** 只匹配 snake_case 的字段名（形如 {@code "session_id":}），跳过枚举取值里的下划线。 */
    private static final Pattern SNAKE_CASE_FIELD_NAME =
            Pattern.compile("\"[a-zA-Z][a-zA-Z0-9]*_[a-zA-Z0-9_]*\"\\s*:");

    private static final String MESSAGE_ID = "5f1c1a2b-3d4e-4f50-8a9b-0c1d2e3f4a5b";
    private static final String ANSWER = "候选商品的名称与价格均来自商城数据。";
    private static final String CHAT_BODY = """
            {"sessionId":"2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c","message":"有哪些手机"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentChatService chatService;

    @BeforeEach
    void stubContractResponses() {
        given(chatService.chat(any(ChatRequest.class), any(), any()))
                .willReturn(new ChatData(SESSION_ID, MESSAGE_ID, ANSWER, List.of(), false, List.of()));
        given(chatService.getSession(eq(SESSION_ID), any(), any())).willReturn(new SessionData(
                SESSION_ID,
                List.of(new SessionMessage("user", "你好"), new SessionMessage("assistant", "您好")),
                List.of(),
                false));
        given(chatService.deleteSession(eq(SESSION_ID), any(), any()))
                .willReturn(new DeleteSessionData(SESSION_ID, true, false));
    }

    @Test
    @DisplayName("POST /agent/chat 返回 camelCase 的 code/message/data 结构，支持空产品列表")
    void chatReturnsCamelCaseEnvelopeWithEmptyProducts() throws Exception {
        MvcResult result = mockMvc.perform(post("/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CHAT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.messageId").value(MESSAGE_ID))
                .andExpect(jsonPath("$.data.answer").value(ANSWER))
                .andExpect(jsonPath("$.data.products").isArray())
                .andExpect(jsonPath("$.data.products").isEmpty())
                .andExpect(jsonPath("$.data.requiresLogin").value(false))
                .andExpect(jsonPath("$.data.suggestedQuestions").isArray())
                .andReturn();

        String body = body(result);
        assertThat(body)
                .contains("\"sessionId\"")
                .contains("\"messageId\"")
                .contains("\"requiresLogin\"")
                .contains("\"suggestedQuestions\"");
        assertNoSnakeCaseFieldNames(body);
    }

    @Test
    @DisplayName("聊天响应支持 requiresLogin 与建议问题")
    void chatSupportsRequiresLoginAndSuggestedQuestions() throws Exception {
        given(chatService.chat(any(ChatRequest.class), any(), any())).willReturn(new ChatData(
                SESSION_ID, MESSAGE_ID, "查询您本人的优惠券信息需要先登录。", List.of(), true, List.of("登录后我的优惠券怎么用？")));

        MvcResult result = mockMvc.perform(post("/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CHAT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.requiresLogin").value(true))
                .andExpect(jsonPath("$.data.products").isEmpty())
                .andExpect(jsonPath("$.data.suggestedQuestions[0]").value("登录后我的优惠券怎么用？"))
                .andReturn();

        assertNoSnakeCaseFieldNames(body(result));
    }

    @Test
    @DisplayName("商品卡片字段使用 camelCase")
    void chatSerializesProductCardFieldsInCamelCase() throws Exception {
        given(chatService.chat(any(ChatRequest.class), any(), any())).willReturn(new ChatData(
                SESSION_ID,
                MESSAGE_ID,
                ANSWER,
                List.of(new ProductCard(
                        27L,
                        "示例手机 B",
                        "http://localhost:9000/mall/example-27.jpg",
                        "2999.00",
                        "示例副标题 B",
                        "IN_STOCK",
                        112,
                        "/pages/product/product?id=27")),
                false,
                List.of()));

        MvcResult result = mockMvc.perform(post("/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CHAT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products[0].id").value(27))
                .andExpect(jsonPath("$.data.products[0].name").value("示例手机 B"))
                .andExpect(jsonPath("$.data.products[0].price").value("2999.00"))
                .andExpect(jsonPath("$.data.products[0].stockStatus").value("IN_STOCK"))
                .andExpect(jsonPath("$.data.products[0].availableStock").value(112))
                .andExpect(jsonPath("$.data.products[0].detailPath").value("/pages/product/product?id=27"))
                .andReturn();

        String body = body(result);
        assertThat(body)
                .contains("\"availableStock\"")
                .contains("\"stockStatus\"")
                .contains("\"detailPath\"");
        assertNoSnakeCaseFieldNames(body);
    }

    @Test
    @DisplayName("消息先 trim 再交给业务层")
    void chatPassesTrimmedMessageToService() throws Exception {
        mockMvc.perform(post("/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c","message":"  有哪些手机  "}"""))
                .andExpect(status().isOk());

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        then(chatService).should().chat(captor.capture(), any(), any());
        assertThat(captor.getValue().message()).isEqualTo("有哪些手机");
        assertThat(captor.getValue().sessionId()).isEqualTo(SESSION_ID);
    }

    @Test
    @DisplayName("GET /agent/session/{sessionId} 返回消息与产品字段")
    void getSessionReturnsMessageAndProductFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/agent/session/{sessionId}", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.messages[0].role").value("user"))
                .andExpect(jsonPath("$.data.messages[0].content").value("你好"))
                .andExpect(jsonPath("$.data.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.data.products").isArray())
                .andExpect(jsonPath("$.data.requiresLogin").value(false))
                .andReturn();

        String body = body(result);
        assertThat(body).contains("\"sessionId\"").contains("\"messages\"");
        assertNoSnakeCaseFieldNames(body);
    }

    @Test
    @DisplayName("DELETE /agent/session/{sessionId} 返回 deleted 与 requiresLogin")
    void deleteSessionReturnsDeletedFlag() throws Exception {
        MvcResult result = mockMvc.perform(delete("/agent/session/{sessionId}", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.requiresLogin").value(false))
                .andReturn();

        String body = body(result);
        assertThat(body).contains("\"deleted\"");
        assertNoSnakeCaseFieldNames(body);
    }

    /**
     * 默认配置（未显式给出精确来源）下不注册任何跨域许可：即使携带 Origin 与预检头，
     * 也不得返回 {@code Access-Control-Allow-Origin}，更不得回显请求头或允许凭据。
     */
    @Test
    @DisplayName("默认不注册跨域许可：预检不返回任何 Access-Control-* 头")
    void corsIsDeniedByDefault() throws Exception {
        mockMvc.perform(options("/agent/chat")
                        .header(HttpHeaders.ORIGIN, "https://client.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Custom-Header, Content-Type"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS));
    }

    /**
     * 来源解析层与配置读取层保持同一 fail-closed 语义：缺失或空白解析为「无来源」，
     * 精确来源原样保留，而通配 {@code *}（单独或混合）直接拒绝，绝不产出 {@code *} 许可。
     */
    @Test
    @DisplayName("来源解析层：空白解析为空、精确来源保留、通配一律拒绝")
    void corsOriginParsingFailsClosed() {
        assertThat(AgentCorsConfiguration.parseAllowedOrigins(null)).isEmpty();
        assertThat(AgentCorsConfiguration.parseAllowedOrigins("   ")).isEmpty();
        assertThat(AgentCorsConfiguration.parseAllowedOrigins("https://a.example, https://b.example"))
                .containsExactly("https://a.example", "https://b.example");

        assertThatThrownBy(() -> AgentCorsConfiguration.parseAllowedOrigins("*"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentCorsConfiguration.parseAllowedOrigins("https://a.example,*"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 不注册跨域许可不得影响同源/无 Origin 的普通请求路由。 */
    @Test
    @DisplayName("默认不注册跨域许可不影响同源普通请求")
    void sameOriginRequestsAreUnaffectedByCorsDefault() throws Exception {
        mockMvc.perform(post("/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CHAT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("未支持的方法返回统一错误信封")
    void unsupportedMethodReturnsEnvelope() throws Exception {
        mockMvc.perform(put("/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CHAT_BODY))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.message").value("请求方法不被允许。"));
    }

    private static void assertNoSnakeCaseFieldNames(String body) {
        Matcher matcher = SNAKE_CASE_FIELD_NAME.matcher(body);
        boolean found = matcher.find();
        assertThat(found)
                .as("响应中不应出现 snake_case 字段名，实际命中：%s", found ? matcher.group() : "无")
                .isFalse();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}

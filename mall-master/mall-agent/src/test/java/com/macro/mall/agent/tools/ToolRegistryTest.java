package com.macro.mall.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 只读工具注册表的 TDD 契约测试（计划 Task 8 Step 1）。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.tools.registry}：
 * 注册表只按代码定义的名称、描述与 JSON Schema 暴露工具；调用前统一把参数解析为 JSON 对象，
 * 非法 JSON / 非对象 / 未注册工具都在<strong>调用门户之前</strong>返回固定结构化错误。
 * 工具执行接口不得接受任意 URI、HTTP method、headers、Token 或 SQL 之类的控制字段。
 *
 * <p>本测试使用测试内替身工具，不连接任何门户、Redis 或真实网络。
 */
class ToolRegistryTest {

    private static final String BASE_URL = "http://localhost:8085";

    // ------------------------------------------------------------------ //
    // 注册与顺序
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("注册表保留注册顺序并暴露名称")
    void registryKeepsRegistrationOrderAndNames() {
        ToolRegistry registry = new ToolRegistry(List.of(
                new FakeTool("alpha"), new FakeTool("beta"), new FakeTool("gamma")));

        assertThat(registry.names()).containsExactly("alpha", "beta", "gamma");
        assertThat(registry.find("beta")).isPresent();
        assertThat(registry.find("beta").orElseThrow().name()).isEqualTo("beta");
    }

    @Test
    @DisplayName("重复注册同名工具被拒绝")
    void duplicateToolNameIsRejected() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(new FakeTool("dup"), new FakeTool("dup"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("重复注册的工具：dup");
    }

    @Test
    @DisplayName("未注册工具查询为空")
    void findReturnsEmptyForUnknownName() {
        ToolRegistry registry = new ToolRegistry(List.of(new FakeTool("alpha")));

        assertThat(registry.find("missing")).isEmpty();
        assertThat(registry.jsonSchema("missing")).isEmpty();
    }

    // ------------------------------------------------------------------ //
    // OpenAI 工具与 JSON Schema
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("openAiTools 为每个工具发布 function schema，且参数对象禁止额外字段")
    void openAiToolsExposeFunctionSchemaPerTool() {
        ToolRegistry registry = new ToolRegistry(List.of(new FakeTool("alpha"), new FakeTool("beta")));

        List<Map<String, Object>> tools = registry.openAiTools();

        assertThat(tools).hasSize(2);
        for (Map<String, Object> tool : tools) {
            assertThat(tool.get("type")).isEqualTo("function");
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) tool.get("function");
            assertThat(function.get("name")).isIn("alpha", "beta");
            assertThat(function.get("description")).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
            assertThat(parameters.get("type")).isEqualTo("object");
            assertThat(parameters.get("additionalProperties")).isEqualTo(Boolean.FALSE);
        }
    }

    @Test
    @DisplayName("jsonSchema 只对已注册工具返回")
    void jsonSchemaIsReturnedOnlyForRegisteredTools() {
        ToolRegistry registry = new ToolRegistry(List.of(new FakeTool("alpha")));

        Optional<Map<String, Object>> schema = registry.jsonSchema("alpha");

        assertThat(schema).isPresent();
        assertThat(schema.orElseThrow().get("additionalProperties")).isEqualTo(Boolean.FALSE);
    }

    // ------------------------------------------------------------------ //
    // 调用分发
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("invoke 把解析后的 JSON 对象交给对应工具")
    void invokeDelegatesParsedObjectToTool() {
        FakeTool tool = new FakeTool("alpha");
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        ToolContext context = ToolContext.guest(portal());

        ToolResult result = registry.invoke("alpha", "{\"productId\":27}", context);

        assertThat(result.name()).isEqualTo("alpha");
        assertThat(result.status()).isEqualTo(ToolResult.Status.OK);
        assertThat(tool.received).isNotNull();
        assertThat(tool.received.get("productId").asInt()).isEqualTo(27);
        assertThat(tool.receivedContext).isSameAs(context);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\n\t"})
    @DisplayName("空或空白参数按空对象处理")
    void invokeTreatsBlankArgumentsAsEmptyObject(String arguments) {
        FakeTool tool = new FakeTool("alpha");
        ToolRegistry registry = new ToolRegistry(List.of(tool));

        registry.invoke("alpha", arguments, ToolContext.guest(portal()));

        assertThat(tool.received).isNotNull();
        assertThat(tool.received.isObject()).isTrue();
        assertThat(tool.received.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("未注册工具在触达任何工具前失败")
    void unknownToolRaisesUnknownToolErrorWithoutTouchingTool() {
        FakeTool tool = new FakeTool("alpha");
        ToolRegistry registry = new ToolRegistry(List.of(tool));

        assertThatThrownBy(() -> registry.invoke("nope", "{}", ToolContext.guest(portal())))
                .isInstanceOf(ToolRegistry.UnknownToolException.class)
                .hasMessage("未注册的工具：nope")
                .extracting(exception -> ((ToolRegistry.ToolException) exception).code())
                .isEqualTo("UNKNOWN_TOOL");
        assertThat(tool.received).isNull();
        assertThat(tool.invocations).isZero();
    }

    // ------------------------------------------------------------------ //
    // 固定结构化参数错误
    // ------------------------------------------------------------------ //

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{", "{\"a\":}", "{\"a\":1,}", "[1,2", "{\"a\" 1}"})
    @DisplayName("非法 JSON 参数被拒绝，且不触达工具")
    void malformedJsonIsRejected(String arguments) {
        FakeTool tool = new FakeTool("alpha");
        ToolRegistry registry = new ToolRegistry(List.of(tool));

        assertThatThrownBy(() -> registry.invoke("alpha", arguments, ToolContext.guest(portal())))
                .isInstanceOf(ToolRegistry.InvalidToolArgumentsException.class)
                .hasMessage("工具参数不是合法 JSON")
                .extracting(exception -> ((ToolRegistry.ToolException) exception).code())
                .isEqualTo("INVALID_TOOL_ARGUMENTS");
        assertThat(tool.invocations).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[1,2]", "null", "\"x\"", "1", "true", "3.5"})
    @DisplayName("非 JSON 对象参数被拒绝，且不触达工具")
    void nonObjectJsonIsRejected(String arguments) {
        FakeTool tool = new FakeTool("alpha");
        ToolRegistry registry = new ToolRegistry(List.of(tool));

        assertThatThrownBy(() -> registry.invoke("alpha", arguments, ToolContext.guest(portal())))
                .isInstanceOf(ToolRegistry.InvalidToolArgumentsException.class)
                .hasMessage("工具参数必须是 JSON 对象");
        assertThat(tool.invocations).isZero();
    }

    // ------------------------------------------------------------------ //
    // 执行接口无任意 URI / method / headers 入口
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("工具执行接口不接受任意 URI、HTTP method、headers 或 Token")
    void executionSurfaceExposesNoArbitraryUriMethodOrHeaders() throws Exception {
        Method invoke = AgentTool.class.getMethod("invoke", JsonNode.class, ToolContext.class);
        assertThat(invoke.getParameterTypes()).containsExactly(JsonNode.class, ToolContext.class);

        Method registryInvoke = ToolRegistry.class.getMethod(
                "invoke", String.class, String.class, ToolContext.class);
        assertThat(registryInvoke.getParameterTypes())
                .containsExactly(String.class, String.class, ToolContext.class);

        List<Class<?>> forbiddenTypes = List.of(URI.class, URL.class, HttpHeaders.class, HttpMethod.class);
        for (Class<?> type : List.of(AgentTool.class, ToolContext.class, ToolRegistry.class)) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                for (Parameter parameter : method.getParameters()) {
                    assertThat(forbiddenTypes)
                            .as("%s#%s 不得接受 %s", type.getSimpleName(), method.getName(),
                                    parameter.getType().getSimpleName())
                            .doesNotContain(parameter.getType());
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    // long ID 域（D1）与 ToolContext 脱敏（D2）
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("三个 long ID 工具的参数 schema 显式声明 1..Long.MAX_VALUE")
    void longIdSchemasMatchJavaSignedLongDomain() {
        ToolRegistry registry = ToolRegistry.defaultRegistry(java.time.Clock.systemUTC());

        Map<String, Object> detailSchema = registry.jsonSchema("getProductDetail").orElseThrow();
        assertLongIdProperty(detailSchema);

        Map<String, Object> couponSchema = registry.jsonSchema("getMemberCouponsForProduct").orElseThrow();
        assertLongIdProperty(couponSchema);

        Map<String, Object> compareSchema = registry.jsonSchema("compareProducts").orElseThrow();
        Map<String, Object> productIds = property(compareSchema, "productIds");
        assertThat(productIds.get("type")).isEqualTo("array");
        Map<String, Object> items = property(compareSchema, "productIds", "items");
        assertThat(items.get("type")).isEqualTo("integer");
        assertThat(((Number) items.get("minimum")).longValue()).isEqualTo(1L);
        assertThat(((Number) items.get("maximum")).longValue()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("ToolContext.toString 不泄漏 Authorization 与 backend 细节")
    void toolContextToStringRedactsAuthorizationAndBackend() {
        com.macro.mall.agent.storefront.MallPortalClient portal = portal();
        String marker = "synthetic-token-marker-9f3a";
        ToolContext member = ToolContext.member(portal, "Bearer " + marker);
        ToolContext guest = ToolContext.guest(portal);
        ToolContext blank = new ToolContext(portal, "");

        assertThat(member.toString()).doesNotContain(marker).doesNotContain("Bearer");
        assertThat(member.toString()).doesNotContain(portal.toString());
        assertThat(guest.toString()).doesNotContain(portal.toString());
        assertThat(blank.toString()).doesNotContain(portal.toString());
        assertThat(member.toString()).isNotEqualTo(guest.toString());
        assertThat(member.toString()).contains("<present>");
        assertThat(guest.toString()).contains("<absent>");
        assertThat(blank.toString()).contains("<absent>");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> schema, String... path) {
        Map<String, Object> current = (Map<String, Object>) schema.get("properties");
        for (int index = 0; index < path.length - 1; index++) {
            current = (Map<String, Object>) current.get(path[index]);
        }
        return (Map<String, Object>) current.get(path[path.length - 1]);
    }

    private static void assertLongIdProperty(Map<String, Object> schema) {
        Map<String, Object> productId = property(schema, "productId");
        assertThat(productId.get("type")).isEqualTo("integer");
        assertThat(((Number) productId.get("minimum")).longValue()).isEqualTo(1L);
        assertThat(((Number) productId.get("maximum")).longValue()).isEqualTo(Long.MAX_VALUE);
    }

    // ------------------------------------------------------------------ //
    // 替身
    // ------------------------------------------------------------------ //

    private static com.macro.mall.agent.storefront.MallPortalClient portal() {
        return org.mockito.Mockito.mock(com.macro.mall.agent.storefront.MallPortalClient.class);
    }

    /** 测试替身工具：记录收到的参数与调用次数，不触碰任何外部依赖。 */
    private static final class FakeTool implements AgentTool {

        private final String name;
        private JsonNode received;
        private ToolContext receivedContext;
        private int invocations;

        private FakeTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "替身工具 " + name;
        }

        @Override
        public boolean requiresMember() {
            return false;
        }

        @Override
        public Map<String, Object> parameters() {
            Map<String, Object> properties = new LinkedHashMap<>();
            Map<String, Object> keyword = new LinkedHashMap<>();
            keyword.put("type", "string");
            properties.put("keyword", keyword);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("additionalProperties", Boolean.FALSE);
            schema.put("properties", properties);
            return schema;
        }

        @Override
        public ToolResult invoke(JsonNode arguments, ToolContext context) {
            this.invocations++;
            this.received = arguments;
            this.receivedContext = context;
            List<String> facts = new ArrayList<>();
            facts.add("替身调用 " + name);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "fake");
            return ToolResult.ok(name, payload, facts);
        }
    }
}

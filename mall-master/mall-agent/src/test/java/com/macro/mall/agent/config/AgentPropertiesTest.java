package com.macro.mall.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * AgentProperties 的默认值、精确环境变量覆盖与非法值拒绝测试。
 *
 * <p>关键约束：所有取值必须从精确的 {@code MALL_AGENT_*} 变量名读取，
 * 不能依赖 Spring 的宽松下划线绑定，否则 {@code MALL_AGENT_RATE_LIMIT_SESSION_LIMIT}
 * 会被解释成嵌套属性 {@code rate.limit.session.limit} 并静默退回默认值。
 */
class AgentPropertiesTest {

    /** 与 Python 侧 Settings 完全一致的默认值。 */
    @Test
    @DisplayName("默认值与 Python 配置一致")
    void defaultsMatchPythonSettings() {
        AgentProperties properties = AgentProperties.from(new MockEnvironment());

        assertThat(properties.getHost()).isEqualTo("0.0.0.0");
        assertThat(properties.getPort()).isEqualTo(8086);
        assertThat(properties.getLogLevel()).isEqualTo("INFO");
        // CORS 来源默认收紧为「不授权任何来源」，刻意不再与 Python Settings 的 "*" 默认值一致：
        // 缺失即空字符串，由 CORS 配置层 fail-closed 地解析为不注册跨域许可。
        assertThat(properties.getCorsAllowOrigins()).isEmpty();
        // 可信代理默认为空：默认不信任任何代理，X-Real-IP 在未显式配置时一律不采信。
        assertThat(properties.getTrustedProxyIp()).isEmpty();
        assertThat(properties.getRequestTimeoutSeconds()).isEqualTo(35.0);

        assertThat(properties.getModelMode()).isEqualTo("openai");
        assertThat(properties.getOpenaiBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(properties.getOpenaiApiKey()).isEmpty();
        assertThat(properties.getOpenaiModel()).isEqualTo("gpt-4o-mini");
        assertThat(properties.getOpenaiTimeoutSeconds()).isEqualTo(30.0);
        assertThat(properties.getMaxToolRounds()).isEqualTo(4);

        assertThat(properties.getPortalBaseUrl()).isEqualTo("http://localhost:8085");
        assertThat(properties.getPortalTimeoutSeconds()).isEqualTo(10.0);

        assertThat(properties.getRedisUrl()).isEqualTo("redis://localhost:6379/0");
        assertThat(properties.getSessionTtlSeconds()).isEqualTo(86400);
        assertThat(properties.getSessionMaxMessages()).isEqualTo(20);

        assertThat(properties.getRateLimitSessionLimit()).isEqualTo(20);
        assertThat(properties.getRateLimitSessionWindowSeconds()).isEqualTo(300);
        assertThat(properties.getRateLimitIpLimit()).isEqualTo(60);
        assertThat(properties.getRateLimitIpWindowSeconds()).isEqualTo(300);

        assertThat(properties.getToolResultMaxChars()).isEqualTo(4000);
        assertThat(properties.getContextMaxChars()).isEqualTo(16000);

        assertThat(AgentProperties.ENVIRONMENT_VARIABLES).hasSize(23);
    }

    /** 逐项用同名环境变量覆盖，验证下划线分隔的名字被精确读取。 */
    @Test
    @DisplayName("每个 MALL_AGENT_* 变量都能覆盖对应配置项")
    void exactEnvironmentVariablesOverrideEveryProperty() {
        MockEnvironment environment = new MockEnvironment();
        overridesForTest().forEach(environment::setProperty);

        AgentProperties properties = AgentProperties.from(environment);

        assertThat(properties.getHost()).isEqualTo("127.0.0.1");
        assertThat(properties.getPort()).isEqualTo(9099);
        assertThat(properties.getLogLevel()).isEqualTo("DEBUG");
        assertThat(properties.getCorsAllowOrigins()).isEqualTo("https://a.example,https://b.example");
        assertThat(properties.getTrustedProxyIp()).isEqualTo("10.0.0.5");
        assertThat(properties.getRequestTimeoutSeconds()).isEqualTo(41.5);

        assertThat(properties.getModelMode()).isEqualTo("stub");
        assertThat(properties.getOpenaiBaseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(properties.getOpenaiApiKey()).isEqualTo("unit-test-model-key");
        assertThat(properties.getOpenaiModel()).isEqualTo("unit-test-model");
        assertThat(properties.getOpenaiTimeoutSeconds()).isEqualTo(12.5);
        assertThat(properties.getMaxToolRounds()).isEqualTo(6);

        assertThat(properties.getPortalBaseUrl()).isEqualTo("http://portal.internal:8085");
        assertThat(properties.getPortalTimeoutSeconds()).isEqualTo(7.5);

        assertThat(properties.getRedisUrl()).isEqualTo("redis://redis.internal:6390/3");
        assertThat(properties.getSessionTtlSeconds()).isEqualTo(7200);
        assertThat(properties.getSessionMaxMessages()).isEqualTo(30);

        assertThat(properties.getRateLimitSessionLimit()).isEqualTo(25);
        assertThat(properties.getRateLimitSessionWindowSeconds()).isEqualTo(301);
        assertThat(properties.getRateLimitIpLimit()).isEqualTo(65);
        assertThat(properties.getRateLimitIpWindowSeconds()).isEqualTo(302);

        assertThat(properties.getToolResultMaxChars()).isEqualTo(5000);
        assertThat(properties.getContextMaxChars()).isEqualTo(20000);
    }

    /** 反向保护：yml 风格的嵌套键不得被当成配置来源。 */
    @Test
    @DisplayName("嵌套属性写法不会被读取，也不会覆盖精确变量")
    void nestedPropertyNamesAreNotUsedAsConfigurationSource() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("mall.agent.rate.limit.session.limit", "999");
        environment.setProperty("mall.agent.rateLimitSessionLimit", "999");
        environment.setProperty("mall.agent.rate-limit-session-limit", "999");

        AgentProperties properties = AgentProperties.from(environment);

        assertThat(properties.getRateLimitSessionLimit()).isEqualTo(20);
    }

    @Test
    @DisplayName("modelAvailable 按模式与占位值判定")
    void modelAvailabilityFollowsModeAndPlaceholders() {
        assertThat(propertiesWith(null, null, "stub").isModelAvailable()).isTrue();
        assertThat(propertiesWith("", "", "openai").isModelAvailable()).isFalse();
        assertThat(propertiesWith("   ", "gpt-4o-mini", "openai").isModelAvailable()).isFalse();
        assertThat(propertiesWith("unit-test-model-key", "", "openai").isModelAvailable()).isFalse();
        assertThat(propertiesWith("unit-test-model-key", "   ", "openai").isModelAvailable()).isFalse();
        assertThat(propertiesWith("unit-test-model-key", "unit-test-model", "openai").isModelAvailable())
                .isTrue();

        for (String placeholder : new String[] {
                "<your-api-key>", "your-openai-key", "your_openai_key", "changeme", "change-me",
                "change_me", "replace-me", "replace_me", "PLACEHOLDER", "请填写", "请生成", "<请填入>"}) {
            assertThat(propertiesWith(placeholder, "unit-test-model", "openai").isModelAvailable())
                    .as("占位 Key 不应被视为可用：%s", placeholder)
                    .isFalse();
        }
    }

    /**
     * Python {@code is_placeholder} 用 {@code str.strip()} 判空，其空白集合包含 U+00A0（NO-BREAK SPACE）
     * 与 U+0085（NEL）；Java {@link String#strip()} 依据 {@link Character#isWhitespace} 不把它们视为空白。
     * 因此仅由这类码点构成的取值必须与 Python 一致地判为占位。
     */
    @Test
    @DisplayName("isPlaceholder 把仅由 U+00A0/U+0085 构成的取值判为占位")
    void isPlaceholderTreatsPythonOnlyWhitespaceAsPlaceholder() {
        assertThat(AgentProperties.isPlaceholder("\u00A0"))
                .as("纯 U+00A0 按 Python str.strip() 语义为空")
                .isTrue();
        assertThat(AgentProperties.isPlaceholder("\u0085"))
                .as("纯 U+0085 按 Python str.strip() 语义为空")
                .isTrue();
        assertThat(AgentProperties.isPlaceholder("\u00A0\u0085\u00A0"))
                .as("多个 Python 空白码点组合按 Python str.strip() 语义为空")
                .isTrue();
    }

    /**
     * Python 先 {@code strip()} 再匹配 {@code ^<[^>]*>$}；被 U+00A0/U+0085 包裹的括号占位符
     * 应先去空白后命中括号占位规则。这里刻意使用不含任何占位标记的括号内容，避免标记匹配掩盖空白语义。
     */
    @Test
    @DisplayName("isPlaceholder 识别被 U+00A0/U+0085 包裹的括号占位符")
    void isPlaceholderDetectsBracketPlaceholderWrappedInPythonWhitespace() {
        assertThat(AgentProperties.isPlaceholder("\u00A0<api-key>\u00A0"))
                .as("U+00A0 包裹的括号占位符应先按 Python 语义去空白再命中 ^<...>$")
                .isTrue();
        assertThat(AgentProperties.isPlaceholder("\u0085<api-key>\u0085"))
                .as("U+0085 包裹的括号占位符应先按 Python 语义去空白再命中 ^<...>$")
                .isTrue();
    }

    /** 反向保护：占位判定不得过度扩大，被 U+00A0 包裹的真实取值仍不是占位。 */
    @Test
    @DisplayName("isPlaceholder 不把被 U+00A0 包裹的真实取值误判为占位")
    void isPlaceholderKeepsRealValueWrappedInPythonWhitespace() {
        assertThat(AgentProperties.isPlaceholder("\u00A0unit-test-model-key\u00A0"))
                .as("去空白后不含占位标记的真实 Key 不是占位")
                .isFalse();
    }

    @ParameterizedTest(name = "{0}={1} 必须被拒绝")
    @MethodSource("invalidValues")
    @DisplayName("非法值被拒绝，错误信息包含变量名")
    void invalidValuesAreRejected(String variable, String value) {
        assertThatThrownBy(() -> AgentProperties.from(environmentWith(variable, value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(variable);
    }

    @Test
    @DisplayName("URL 内嵌凭据不会被回显到错误信息")
    void embeddedCredentialsAreNeverEchoed() {
        assertThatThrownBy(() -> AgentProperties.from(
                environmentWith("MALL_AGENT_OPENAI_BASE_URL", "https://credential:secret@api.example.com/v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MALL_AGENT_OPENAI_BASE_URL")
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("credential");
    }

    @Test
    @DisplayName("空白必填项被拒绝")
    void blankRequiredValuesAreRejected() {
        // CORS 来源不在必填之列：留空表示「不授权任何来源」，由 corsOriginsAreOptionalAndBlankMeansNone 覆盖。
        for (String variable : new String[] {
                "MALL_AGENT_HOST", "MALL_AGENT_LOG_LEVEL",
                "MALL_AGENT_MODEL_MODE", "MALL_AGENT_OPENAI_BASE_URL", "MALL_AGENT_PORTAL_BASE_URL",
                "MALL_AGENT_REDIS_URL"}) {
            assertThatThrownBy(() -> AgentProperties.from(environmentWith(variable, "   ")))
                    .as("%s 为空白时必须被拒绝", variable)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(variable)
                    .hasMessageNotContaining("   ");
        }
    }

    /**
     * CORS 来源是唯一允许「留空表示不授权」的配置：缺失与纯空白都归一化为空字符串，
     * 交给 CORS 配置层 fail-closed 处理，配置读取本身不得抛异常。
     */
    @Test
    @DisplayName("CORS 来源缺失或空白时归一化为空且不抛异常")
    void corsOriginsAreOptionalAndBlankMeansNone() {
        assertThat(AgentProperties.from(new MockEnvironment()).getCorsAllowOrigins())
                .as("缺失时应为空字符串，而非通配 *")
                .isEmpty();
        assertThat(AgentProperties.from(environmentWith("MALL_AGENT_CORS_ALLOW_ORIGINS", "   "))
                .getCorsAllowOrigins())
                .as("纯空白应归一化为空字符串")
                .isEmpty();
    }

    /**
     * 通配来源 {@code *} 一旦被解析为 Spring 的 allowedOrigins，就会放开任意站点跨域访问，
     * 因此必须 fail closed：单独出现或与精确来源混合出现都在配置阶段直接拒绝，阻止应用以
     * 宽松 CORS 启动。错误信息只保留变量名与规则，不回显任何来源取值，避免把配置内容写入日志。
     */
    @Test
    @DisplayName("CORS 通配来源（单独或混合）一律拒绝，错误信息不回显取值")
    void corsWildcardOriginsAreRejectedFailClosed() {
        for (String wildcard : new String[] {
                "*",
                " * ",
                "https://a.example,*",
                "*,https://a.example",
                "https://a.example, * ,https://b.example"}) {
            assertThatThrownBy(() -> AgentProperties.from(
                    environmentWith(AgentProperties.CORS_ALLOW_ORIGINS, wildcard)))
                    .as("通配来源必须 fail closed：%s", wildcard)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(AgentProperties.CORS_ALLOW_ORIGINS)
                    .hasMessageNotContaining("https://a.example")
                    .hasMessageNotContaining("https://b.example");
        }
    }

    @Test
    @DisplayName("toString 不包含 API Key")
    void toStringNeverContainsTheApiKey() {
        AgentProperties properties = propertiesWith("unit-test-model-key", "unit-test-model", "openai");

        assertThat(properties.toString())
                .doesNotContain("unit-test-model-key")
                .contains("openaiApiKey=[masked]");
    }

    /**
     * Redis URL 可能同时携带 user-info、query 与 fragment；任一部分都不得进入日志。
     * 因此 toString 采用 fail-safe 策略，整个 Redis URL 固定输出 [masked]。
     */
    @Test
    @DisplayName("toString 完整遮盖 Redis URL，配置读取本身不受影响")
    void toStringNeverLeaksRedisUrlSecrets() {
        Map<String, String> redisUrls = new LinkedHashMap<>();
        redisUrls.put("user-info", "redis://unit-test-user:unit-test-user-info@localhost:6379/0");
        redisUrls.put("query", "redis://localhost:6379/0?unit-test-query-marker=unit-test-query-value");
        redisUrls.put("fragment", "redis://localhost:6379/0#unit-test-fragment-marker");

        redisUrls.forEach((label, redisUrl) -> {
            AgentProperties properties = propertiesWithRedisUrl(redisUrl);

            assertThat(properties.getRedisUrl())
                    .as("Redis URL 配置必须原样保留（%s）", label)
                    .isEqualTo(redisUrl);
            assertThat(properties.toString())
                    .as("toString 不得泄露 Redis URL 任何部分（%s）", label)
                    .doesNotContain("unit-test-user")
                    .doesNotContain("unit-test-query")
                    .doesNotContain("unit-test-fragment")
                    .contains("redisUrl=[masked]");
        });
    }

    @Test
    @DisplayName("变量名常量与文档中的变量名逐字一致")
    void variableNameConstantsMatchTheDocumentedNames() {
        assertThat(AgentProperties.ENVIRONMENT_VARIABLES).containsExactlyInAnyOrder(
                "MALL_AGENT_HOST",
                "MALL_AGENT_PORT",
                "MALL_AGENT_LOG_LEVEL",
                "MALL_AGENT_CORS_ALLOW_ORIGINS",
                "MALL_AGENT_TRUSTED_PROXY_IP",
                "MALL_AGENT_REQUEST_TIMEOUT_SECONDS",
                "MALL_AGENT_MODEL_MODE",
                "MALL_AGENT_OPENAI_BASE_URL",
                "MALL_AGENT_OPENAI_API_KEY",
                "MALL_AGENT_OPENAI_MODEL",
                "MALL_AGENT_OPENAI_TIMEOUT_SECONDS",
                "MALL_AGENT_MAX_TOOL_ROUNDS",
                "MALL_AGENT_PORTAL_BASE_URL",
                "MALL_AGENT_PORTAL_TIMEOUT_SECONDS",
                "MALL_AGENT_REDIS_URL",
                "MALL_AGENT_SESSION_TTL_SECONDS",
                "MALL_AGENT_SESSION_MAX_MESSAGES",
                "MALL_AGENT_RATE_LIMIT_SESSION_LIMIT",
                "MALL_AGENT_RATE_LIMIT_SESSION_WINDOW_SECONDS",
                "MALL_AGENT_RATE_LIMIT_IP_LIMIT",
                "MALL_AGENT_RATE_LIMIT_IP_WINDOW_SECONDS",
                "MALL_AGENT_TOOL_RESULT_MAX_CHARS",
                "MALL_AGENT_CONTEXT_MAX_CHARS");
        // 只读集成测试专用变量不得进入运行配置。
        assertThat(AgentProperties.ENVIRONMENT_VARIABLES)
                .doesNotContain("MALL_AGENT_TEST_REDIS_URL");
    }

    private static AgentProperties propertiesWith(String apiKey, String model, String mode) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("MALL_AGENT_MODEL_MODE", mode);
        if (apiKey != null) {
            environment.setProperty("MALL_AGENT_OPENAI_API_KEY", apiKey);
        }
        if (model != null) {
            environment.setProperty("MALL_AGENT_OPENAI_MODEL", model);
        }
        return AgentProperties.from(environment);
    }

    private static AgentProperties propertiesWithRedisUrl(String redisUrl) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("MALL_AGENT_REDIS_URL", redisUrl);
        return AgentProperties.from(environment);
    }

    private static MockEnvironment environmentWith(String variable, String value) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(variable, value);
        return environment;
    }

    private static Map<String, String> overridesForTest() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("MALL_AGENT_HOST", "127.0.0.1");
        values.put("MALL_AGENT_PORT", "9099");
        values.put("MALL_AGENT_LOG_LEVEL", "DEBUG");
        values.put("MALL_AGENT_CORS_ALLOW_ORIGINS", "https://a.example,https://b.example");
        values.put("MALL_AGENT_TRUSTED_PROXY_IP", "10.0.0.5");
        values.put("MALL_AGENT_REQUEST_TIMEOUT_SECONDS", "41.5");
        values.put("MALL_AGENT_MODEL_MODE", "stub");
        values.put("MALL_AGENT_OPENAI_BASE_URL", "https://api.deepseek.com/v1/");
        values.put("MALL_AGENT_OPENAI_API_KEY", "unit-test-model-key");
        values.put("MALL_AGENT_OPENAI_MODEL", "unit-test-model");
        values.put("MALL_AGENT_OPENAI_TIMEOUT_SECONDS", "12.5");
        values.put("MALL_AGENT_MAX_TOOL_ROUNDS", "6");
        values.put("MALL_AGENT_PORTAL_BASE_URL", "http://portal.internal:8085/");
        values.put("MALL_AGENT_PORTAL_TIMEOUT_SECONDS", "7.5");
        values.put("MALL_AGENT_REDIS_URL", "redis://redis.internal:6390/3");
        values.put("MALL_AGENT_SESSION_TTL_SECONDS", "7200");
        values.put("MALL_AGENT_SESSION_MAX_MESSAGES", "30");
        values.put("MALL_AGENT_RATE_LIMIT_SESSION_LIMIT", "25");
        values.put("MALL_AGENT_RATE_LIMIT_SESSION_WINDOW_SECONDS", "301");
        values.put("MALL_AGENT_RATE_LIMIT_IP_LIMIT", "65");
        values.put("MALL_AGENT_RATE_LIMIT_IP_WINDOW_SECONDS", "302");
        values.put("MALL_AGENT_TOOL_RESULT_MAX_CHARS", "5000");
        values.put("MALL_AGENT_CONTEXT_MAX_CHARS", "20000");
        return values;
    }

    private static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("MALL_AGENT_PORT", "not-a-number"),
                Arguments.of("MALL_AGENT_PORT", "0"),
                Arguments.of("MALL_AGENT_PORT", "70000"),
                Arguments.of("MALL_AGENT_MODEL_MODE", "unsupported-mode"),
                Arguments.of("MALL_AGENT_REQUEST_TIMEOUT_SECONDS", "0"),
                Arguments.of("MALL_AGENT_REQUEST_TIMEOUT_SECONDS", "-1"),
                Arguments.of("MALL_AGENT_OPENAI_TIMEOUT_SECONDS", "0"),
                Arguments.of("MALL_AGENT_OPENAI_TIMEOUT_SECONDS", "301"),
                Arguments.of("MALL_AGENT_MAX_TOOL_ROUNDS", "0"),
                Arguments.of("MALL_AGENT_MAX_TOOL_ROUNDS", "9"),
                Arguments.of("MALL_AGENT_SESSION_TTL_SECONDS", "30"),
                Arguments.of("MALL_AGENT_SESSION_MAX_MESSAGES", "1"),
                Arguments.of("MALL_AGENT_SESSION_MAX_MESSAGES", "101"),
                Arguments.of("MALL_AGENT_RATE_LIMIT_SESSION_LIMIT", "0"),
                Arguments.of("MALL_AGENT_RATE_LIMIT_SESSION_WINDOW_SECONDS", "0"),
                Arguments.of("MALL_AGENT_RATE_LIMIT_IP_LIMIT", "0"),
                Arguments.of("MALL_AGENT_RATE_LIMIT_IP_WINDOW_SECONDS", "0"),
                Arguments.of("MALL_AGENT_TOOL_RESULT_MAX_CHARS", "199"),
                Arguments.of("MALL_AGENT_CONTEXT_MAX_CHARS", "999"),
                Arguments.of("MALL_AGENT_OPENAI_BASE_URL", "ftp://api.example.com/v1"),
                Arguments.of("MALL_AGENT_OPENAI_BASE_URL", "https://api.example.com/v1?debug=1"),
                Arguments.of("MALL_AGENT_OPENAI_BASE_URL", "not-a-url"),
                Arguments.of("MALL_AGENT_PORTAL_BASE_URL", "portal.example.com:8085"),
                Arguments.of("MALL_AGENT_REDIS_URL", "http://localhost:6379/0"),
                Arguments.of("MALL_AGENT_PORTAL_TIMEOUT_SECONDS", "0"));
    }
}

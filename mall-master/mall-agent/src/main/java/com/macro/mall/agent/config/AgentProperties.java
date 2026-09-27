package com.macro.mall.agent.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.core.env.Environment;

import com.macro.mall.agent.api.PythonText;

/**
 * 进程级配置，取值与 Python 侧 {@code Settings} 完全兼容。
 *
 * <p><strong>不使用 Spring 宽松绑定。</strong>{@code MALL_AGENT_RATE_LIMIT_SESSION_LIMIT}
 * 这类变量名在 {@code @ConfigurationProperties} 的宽松绑定下会被拆成嵌套属性
 * {@code mall.agent.rate.limit.session.limit}，无法匹配 {@code rateLimitSessionLimit}，
 * 从而静默退回默认值。因此这里对每个变量名做精确读取。
 *
 * <p>错误信息只包含变量名与期望格式，绝不回显取值，避免把 API Key、URL 内嵌凭据等
 * 写入日志或测试报告。
 */
public final class AgentProperties {

    public static final String HOST = "MALL_AGENT_HOST";
    public static final String PORT = "MALL_AGENT_PORT";
    public static final String LOG_LEVEL = "MALL_AGENT_LOG_LEVEL";
    public static final String CORS_ALLOW_ORIGINS = "MALL_AGENT_CORS_ALLOW_ORIGINS";
    public static final String TRUSTED_PROXY_IP = "MALL_AGENT_TRUSTED_PROXY_IP";
    public static final String REQUEST_TIMEOUT_SECONDS = "MALL_AGENT_REQUEST_TIMEOUT_SECONDS";
    public static final String MODEL_MODE = "MALL_AGENT_MODEL_MODE";
    public static final String OPENAI_BASE_URL = "MALL_AGENT_OPENAI_BASE_URL";
    public static final String OPENAI_API_KEY = "MALL_AGENT_OPENAI_API_KEY";
    public static final String OPENAI_MODEL = "MALL_AGENT_OPENAI_MODEL";
    public static final String OPENAI_TIMEOUT_SECONDS = "MALL_AGENT_OPENAI_TIMEOUT_SECONDS";
    public static final String MAX_TOOL_ROUNDS = "MALL_AGENT_MAX_TOOL_ROUNDS";
    public static final String PORTAL_BASE_URL = "MALL_AGENT_PORTAL_BASE_URL";
    public static final String PORTAL_TIMEOUT_SECONDS = "MALL_AGENT_PORTAL_TIMEOUT_SECONDS";
    public static final String REDIS_URL = "MALL_AGENT_REDIS_URL";
    public static final String SESSION_TTL_SECONDS = "MALL_AGENT_SESSION_TTL_SECONDS";
    public static final String SESSION_MAX_MESSAGES = "MALL_AGENT_SESSION_MAX_MESSAGES";
    public static final String RATE_LIMIT_SESSION_LIMIT = "MALL_AGENT_RATE_LIMIT_SESSION_LIMIT";
    public static final String RATE_LIMIT_SESSION_WINDOW_SECONDS =
            "MALL_AGENT_RATE_LIMIT_SESSION_WINDOW_SECONDS";
    public static final String RATE_LIMIT_IP_LIMIT = "MALL_AGENT_RATE_LIMIT_IP_LIMIT";
    public static final String RATE_LIMIT_IP_WINDOW_SECONDS = "MALL_AGENT_RATE_LIMIT_IP_WINDOW_SECONDS";
    public static final String TOOL_RESULT_MAX_CHARS = "MALL_AGENT_TOOL_RESULT_MAX_CHARS";
    public static final String CONTEXT_MAX_CHARS = "MALL_AGENT_CONTEXT_MAX_CHARS";

    /**
     * 运行配置使用的全部变量名。
     *
     * <p>{@code MALL_AGENT_TEST_REDIS_URL} 只供后续显式 Redis 集成测试读取，不在此列表中。
     */
    public static final Set<String> ENVIRONMENT_VARIABLES = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(
                    HOST,
                    PORT,
                    LOG_LEVEL,
                    CORS_ALLOW_ORIGINS,
                    TRUSTED_PROXY_IP,
                    REQUEST_TIMEOUT_SECONDS,
                    MODEL_MODE,
                    OPENAI_BASE_URL,
                    OPENAI_API_KEY,
                    OPENAI_MODEL,
                    OPENAI_TIMEOUT_SECONDS,
                    MAX_TOOL_ROUNDS,
                    PORTAL_BASE_URL,
                    PORTAL_TIMEOUT_SECONDS,
                    REDIS_URL,
                    SESSION_TTL_SECONDS,
                    SESSION_MAX_MESSAGES,
                    RATE_LIMIT_SESSION_LIMIT,
                    RATE_LIMIT_SESSION_WINDOW_SECONDS,
                    RATE_LIMIT_IP_LIMIT,
                    RATE_LIMIT_IP_WINDOW_SECONDS,
                    TOOL_RESULT_MAX_CHARS,
                    CONTEXT_MAX_CHARS)));

    public static final String MODEL_MODE_OPENAI = "openai";
    public static final String MODEL_MODE_STUB = "stub";

    private static final Pattern BRACKET_PLACEHOLDER = Pattern.compile("^<[^>]*>$");
    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "your-",
            "your_",
            "changeme",
            "change-me",
            "change_me",
            "replace-me",
            "replace_me",
            "placeholder",
            "请填写",
            "请生成",
            "<请");

    private final String host;
    private final int port;
    private final String logLevel;
    private final String corsAllowOrigins;
    private final String trustedProxyIp;
    private final double requestTimeoutSeconds;
    private final String modelMode;
    private final String openaiBaseUrl;
    private final String openaiApiKey;
    private final String openaiModel;
    private final double openaiTimeoutSeconds;
    private final int maxToolRounds;
    private final String portalBaseUrl;
    private final double portalTimeoutSeconds;
    private final String redisUrl;
    private final int sessionTtlSeconds;
    private final int sessionMaxMessages;
    private final int rateLimitSessionLimit;
    private final int rateLimitSessionWindowSeconds;
    private final int rateLimitIpLimit;
    private final int rateLimitIpWindowSeconds;
    private final int toolResultMaxChars;
    private final int contextMaxChars;

    private AgentProperties(Function<String, String> lookup) {
        this.host = requiredText(lookup, HOST, "0.0.0.0");
        this.port = integer(lookup, PORT, 8086, 1, 65535);
        this.logLevel = requiredText(lookup, LOG_LEVEL, "INFO");
        this.corsAllowOrigins = corsAllowOrigins(lookup);
        // 默认空表示「不信任任何代理」：未配置时客户端 IP 只取对端地址，绝不采信 X-Real-IP。
        // 字面量严格校验由 ClientIpResolver 负责；此处非法取值因无法匹配合法对端地址而自然失效。
        this.trustedProxyIp = optionalText(lookup, TRUSTED_PROXY_IP, "");
        this.requestTimeoutSeconds = positiveDouble(lookup, REQUEST_TIMEOUT_SECONDS, 35.0);
        this.modelMode = modelMode(lookup);
        this.openaiBaseUrl = httpUrl(lookup, OPENAI_BASE_URL, "https://api.openai.com/v1");
        this.openaiApiKey = optionalText(lookup, OPENAI_API_KEY, "");
        this.openaiModel = optionalText(lookup, OPENAI_MODEL, "gpt-4o-mini");
        this.openaiTimeoutSeconds = boundedPositiveDouble(lookup, OPENAI_TIMEOUT_SECONDS, 30.0, 300.0);
        this.maxToolRounds = integer(lookup, MAX_TOOL_ROUNDS, 4, 1, 8);
        this.portalBaseUrl = httpUrl(lookup, PORTAL_BASE_URL, "http://localhost:8085");
        this.portalTimeoutSeconds = positiveDouble(lookup, PORTAL_TIMEOUT_SECONDS, 10.0);
        this.redisUrl = redisUrl(lookup, REDIS_URL, "redis://localhost:6379/0");
        this.sessionTtlSeconds = integer(lookup, SESSION_TTL_SECONDS, 86400, 60, Integer.MAX_VALUE);
        this.sessionMaxMessages = integer(lookup, SESSION_MAX_MESSAGES, 20, 2, 100);
        this.rateLimitSessionLimit = integer(lookup, RATE_LIMIT_SESSION_LIMIT, 20, 1, Integer.MAX_VALUE);
        this.rateLimitSessionWindowSeconds =
                integer(lookup, RATE_LIMIT_SESSION_WINDOW_SECONDS, 300, 1, Integer.MAX_VALUE);
        this.rateLimitIpLimit = integer(lookup, RATE_LIMIT_IP_LIMIT, 60, 1, Integer.MAX_VALUE);
        this.rateLimitIpWindowSeconds =
                integer(lookup, RATE_LIMIT_IP_WINDOW_SECONDS, 300, 1, Integer.MAX_VALUE);
        this.toolResultMaxChars = integer(lookup, TOOL_RESULT_MAX_CHARS, 4000, 200, Integer.MAX_VALUE);
        this.contextMaxChars = integer(lookup, CONTEXT_MAX_CHARS, 16000, 1000, Integer.MAX_VALUE);
    }

    /** 从 Spring {@link Environment} 装配：环境变量按精确名称命中，不做宽松展开。 */
    public static AgentProperties from(Environment environment) {
        return new AgentProperties(environment::getProperty);
    }

    /** 供测试与显式装配使用的精确名称读取入口。 */
    static AgentProperties from(Function<String, String> lookup) {
        return new AgentProperties(lookup);
    }

    /**
     * 判断取值是否为空、括号占位或包含占位标记；不用于日志输出。
     *
     * <p>判空前先按 Python {@code str.strip()} 语义去空白，而不是 Java {@link String#strip()}：
     * Python 的空白集合包含 U+00A0（NO-BREAK SPACE）、U+0085（NEL）、U+2007、U+202F 等码点，
     * 仅由这些码点构成的取值在 Python 侧会被判为空并从环境变量中读出空字符串。若改用
     * {@code String.strip()}，这类取值会被当作真实凭据，导致本应判定为「未配置」的 Key
     * 触发真实模型请求。判空与括号占位匹配前的规范化统一走
     * {@link PythonText#strip(String)}，保证与 Python 侧 {@code is_placeholder} 一致。
     */
    public static boolean isPlaceholder(String value) {
        if (value == null) {
            return true;
        }
        String text = PythonText.strip(value);
        if (text.isEmpty()) {
            return true;
        }
        if (BRACKET_PLACEHOLDER.matcher(text).matches()) {
            return true;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String marker : PLACEHOLDER_MARKERS) {
            if (lowered.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 当前配置是否可用于真实模型调用；stub 模式无需凭据。 */
    public boolean isModelAvailable() {
        if (MODEL_MODE_STUB.equals(modelMode)) {
            return true;
        }
        return !isPlaceholder(openaiApiKey) && !isPlaceholder(openaiModel);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public String getCorsAllowOrigins() {
        return corsAllowOrigins;
    }

    public String getTrustedProxyIp() {
        return trustedProxyIp;
    }

    public double getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public String getModelMode() {
        return modelMode;
    }

    public String getOpenaiBaseUrl() {
        return openaiBaseUrl;
    }

    public String getOpenaiApiKey() {
        return openaiApiKey;
    }

    public String getOpenaiModel() {
        return openaiModel;
    }

    public double getOpenaiTimeoutSeconds() {
        return openaiTimeoutSeconds;
    }

    public int getMaxToolRounds() {
        return maxToolRounds;
    }

    public String getPortalBaseUrl() {
        return portalBaseUrl;
    }

    public double getPortalTimeoutSeconds() {
        return portalTimeoutSeconds;
    }

    public String getRedisUrl() {
        return redisUrl;
    }

    public int getSessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public int getSessionMaxMessages() {
        return sessionMaxMessages;
    }

    public int getRateLimitSessionLimit() {
        return rateLimitSessionLimit;
    }

    public int getRateLimitSessionWindowSeconds() {
        return rateLimitSessionWindowSeconds;
    }

    public int getRateLimitIpLimit() {
        return rateLimitIpLimit;
    }

    public int getRateLimitIpWindowSeconds() {
        return rateLimitIpWindowSeconds;
    }

    public int getToolResultMaxChars() {
        return toolResultMaxChars;
    }

    public int getContextMaxChars() {
        return contextMaxChars;
    }

    /**
     * 输出全部配置项，但 API Key 与 Redis URL 一律打码。
     *
     * <p>Redis URL 允许携带 user-info、query 与 fragment，逐段遮盖容易漏掉其中一部分，
     * 因此这里采用 fail-safe 策略：整个 URL 固定显示为 {@code [masked]}，
     * 只保留取值是否存在的信号。配置读取与校验行为不受影响。
     */
    @Override
    public String toString() {
        return "AgentProperties{host=" + host
                + ", port=" + port
                + ", logLevel=" + logLevel
                + ", corsAllowOrigins=" + corsAllowOrigins
                + ", trustedProxyIp=" + trustedProxyIp
                + ", requestTimeoutSeconds=" + requestTimeoutSeconds
                + ", modelMode=" + modelMode
                + ", openaiBaseUrl=" + openaiBaseUrl
                + ", openaiApiKey=[masked]"
                + ", openaiModel=" + openaiModel
                + ", openaiTimeoutSeconds=" + openaiTimeoutSeconds
                + ", maxToolRounds=" + maxToolRounds
                + ", portalBaseUrl=" + portalBaseUrl
                + ", portalTimeoutSeconds=" + portalTimeoutSeconds
                + ", redisUrl=[masked]"
                + ", sessionTtlSeconds=" + sessionTtlSeconds
                + ", sessionMaxMessages=" + sessionMaxMessages
                + ", rateLimitSessionLimit=" + rateLimitSessionLimit
                + ", rateLimitSessionWindowSeconds=" + rateLimitSessionWindowSeconds
                + ", rateLimitIpLimit=" + rateLimitIpLimit
                + ", rateLimitIpWindowSeconds=" + rateLimitIpWindowSeconds
                + ", toolResultMaxChars=" + toolResultMaxChars
                + ", contextMaxChars=" + contextMaxChars
                + '}';
    }

    /**
     * CORS 允许来源是唯一允许「留空」的配置：缺失或纯空白归一化为空字符串，表示不授权任何来源，
     * 交给 CORS 配置层 fail-closed 地不注册跨域许可。通配来源 {@code *}（单独或与精确来源混合）
     * 一律在配置阶段拒绝，避免误配置放开任意站点跨域访问。错误信息只包含变量名与规则，绝不回显取值。
     */
    private static String corsAllowOrigins(Function<String, String> lookup) {
        String value = raw(lookup, CORS_ALLOW_ORIGINS, "").strip();
        if (value.isEmpty()) {
            return value;
        }
        if (value.indexOf('*') >= 0) {
            throw invalid(CORS_ALLOW_ORIGINS, "不允许使用通配来源 *");
        }
        return value;
    }

    private static String modelMode(Function<String, String> lookup) {
        String value = requiredText(lookup, MODEL_MODE, MODEL_MODE_OPENAI);
        if (!MODEL_MODE_OPENAI.equals(value) && !MODEL_MODE_STUB.equals(value)) {
            throw invalid(MODEL_MODE, "只能是 " + MODEL_MODE_OPENAI + " 或 " + MODEL_MODE_STUB);
        }
        return value;
    }

    private static String httpUrl(Function<String, String> lookup, String name, String fallback) {
        String value = requiredText(lookup, name, fallback);
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw invalid(name, "不能包含空白字符");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            throw invalid(name, "必须是包含协议与主机的 http(s) 地址");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null
                || uri.getHost().isEmpty()) {
            throw invalid(name, "必须是包含协议与主机的 http(s) 地址");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw invalid(name, "不能包含查询参数、片段或内嵌凭据");
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw invalid(name, "端口必须在 1 到 65535 之间");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        StringBuilder normalized = new StringBuilder()
                .append(scheme.toLowerCase(Locale.ROOT))
                .append("://")
                .append(uri.getHost());
        if (port != -1) {
            normalized.append(':').append(port);
        }
        return normalized.append(path).toString();
    }

    private static String redisUrl(Function<String, String> lookup, String name, String fallback) {
        String value = requiredText(lookup, name, fallback);
        String scheme = null;
        try {
            scheme = new URI(value).getScheme();
        } catch (URISyntaxException ex) {
            scheme = null;
        }
        if (scheme == null || !(scheme.equalsIgnoreCase("redis") || scheme.equalsIgnoreCase("rediss"))) {
            throw invalid(name, "必须是 redis:// 或 rediss:// 地址");
        }
        return value;
    }

    private static String requiredText(Function<String, String> lookup, String name, String fallback) {
        String value = raw(lookup, name, fallback).strip();
        if (value.isEmpty()) {
            throw invalid(name, "不能为空");
        }
        return value;
    }

    private static String optionalText(Function<String, String> lookup, String name, String fallback) {
        return raw(lookup, name, fallback).strip();
    }

    private static String raw(Function<String, String> lookup, String name, String fallback) {
        String value = lookup.apply(name);
        return value == null ? fallback : value;
    }

    private static int integer(
            Function<String, String> lookup, String name, int fallback, int min, int max) {
        String value = raw(lookup, name, Integer.toString(fallback)).strip();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw invalid(name, expectedRange(min, max));
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw invalid(name, expectedRange(min, max));
        }
    }

    private static double positiveDouble(
            Function<String, String> lookup, String name, double fallback) {
        String value = raw(lookup, name, Double.toString(fallback)).strip();
        try {
            double parsed = Double.parseDouble(value);
            if (!(parsed > 0)) {
                throw invalid(name, "必须是大于 0 的数值");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw invalid(name, "必须是大于 0 的数值");
        }
    }

    private static double boundedPositiveDouble(
            Function<String, String> lookup, String name, double fallback, double max) {
        String value = raw(lookup, name, Double.toString(fallback)).strip();
        String expected = "必须是大于 0 且不超过 " + (long) max + " 的数值";
        try {
            double parsed = Double.parseDouble(value);
            if (!(parsed > 0) || parsed > max) {
                throw invalid(name, expected);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw invalid(name, expected);
        }
    }

    private static String expectedRange(int min, int max) {
        if (max == Integer.MAX_VALUE) {
            return "必须是不小于 " + min + " 的整数";
        }
        return "必须是 " + min + " 到 " + max + " 之间的整数";
    }

    private static IllegalArgumentException invalid(String name, String reason) {
        return new IllegalArgumentException(name + " " + reason);
    }
}

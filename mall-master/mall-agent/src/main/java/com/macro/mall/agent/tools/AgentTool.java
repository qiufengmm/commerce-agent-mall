package com.macro.mall.agent.tools;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import com.macro.mall.agent.storefront.PortalException;

/**
 * 一个只读工具的定义与执行入口。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.tools.registry.ToolDefinition}：
 * 名称、描述与 JSON Schema 全部由代码定义，模型只能提供受控参数对象。
 * {@link #invoke(JsonNode, ToolContext)} 只接受已解析的 JSON 对象与只读上下文，
 * <strong>没有</strong>任意 URI、HTTP method、headers、超时或 SQL 之类的入口。
 *
 * <p>本接口同时提供受控参数解析与商品事实格式化的共享辅助方法，对齐 Python
 * {@code registry._validate_arguments} 的 strict 语义与 {@code storefront.schemas} 的金额/库存格式化。
 */
public interface AgentTool {

    /** 低库存上限，与 Python {@code LOW_STOCK_UPPER_BOUND} 一致。 */
    int LOW_STOCK_UPPER_BOUND = 10;

    String IN_STOCK = "IN_STOCK";
    String LOW_STOCK = "LOW_STOCK";
    String OUT_OF_STOCK = "OUT_OF_STOCK";

    /** 工具名，与 OpenAI function name 一致。 */
    String name();

    /** 面向模型的功能描述。 */
    String description();

    /** 是否需要会员凭据。 */
    boolean requiresMember();

    /** JSON Schema（{@code type=object} 且 {@code additionalProperties=false}）。 */
    Map<String, Object> parameters();

    /**
     * 执行工具。
     *
     * <p>调用方（{@link ToolRegistry}）已完成 JSON 解析与「必须是 JSON 对象」检查；
     * 实现方负责受控字段的严格校验（拒绝未知字段、类型强制与越界取值）。
     *
     * @param arguments 已解析且保证为 JSON 对象的受控参数
     * @param context   只读依赖与会员凭据
     */
    ToolResult invoke(JsonNode arguments, ToolContext context);

    /** OpenAI 工具声明：{@code {"type":"function","function":{name,description,parameters}}}。 */
    default Map<String, Object> openAiTool() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description", description());
        function.put("parameters", parameters());
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    // ------------------------------------------------------------------ //
    // JSON Schema 骨架
    // ------------------------------------------------------------------ //

    /** {@code type=object} + {@code additionalProperties=false} 的 Schema 骨架。 */
    static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", List.copyOf(required));
        }
        schema.put("additionalProperties", Boolean.FALSE);
        return schema;
    }

    /** {@code integer} 且带上下界的属性 Schema（32 位 int 域）。 */
    static Map<String, Object> integerProperty(Integer min, Integer max) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        if (min != null) {
            property.put("minimum", min);
        }
        if (max != null) {
            property.put("maximum", max);
        }
        return property;
    }

    /**
     * 商品 ID 属性 Schema：{@code 1..Long.MAX_VALUE}。
     *
     * <p>Java 侧商品 ID 与门户、卡片一致使用 {@code long}，因此 schema 上界显式声明为
     * {@link Long#MAX_VALUE}；不设上界会让模型以为可以传入任意精度整数（Python 的 {@code int}
     * 是任意精度，Java 只有 signed long，二者语言域不同，需要由 schema 明确）。
     */
    static Map<String, Object> longIdProperty() {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        property.put("minimum", 1L);
        property.put("maximum", Long.MAX_VALUE);
        return property;
    }

    /** {@code string}（可选 {@code maxLength}）或 {@code null} 的属性 Schema。 */
    static Map<String, Object> nullableStringProperty(Integer maxLength) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "string");
        if (maxLength != null) {
            text.put("maxLength", maxLength);
        }
        Map<String, Object> nullType = new LinkedHashMap<>();
        nullType.put("type", "null");
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("anyOf", List.of(text, nullType));
        return property;
    }

    /** {@code integer} 或 {@code null} 的属性 Schema。 */
    static Map<String, Object> nullableIntegerProperty(int min) {
        Map<String, Object> number = new LinkedHashMap<>();
        number.put("type", "integer");
        number.put("minimum", min);
        Map<String, Object> nullType = new LinkedHashMap<>();
        nullType.put("type", "null");
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("anyOf", List.of(number, nullType));
        return property;
    }

    // ------------------------------------------------------------------ //
    // 受控参数解析（strict + extra=forbid）
    // ------------------------------------------------------------------ //

    /** camelCase 对应的 snake_case 字段名（Python {@code populate_by_name} 同时接受两者）。 */
    static String snakeCase(String camelName) {
        StringBuilder builder = new StringBuilder(camelName.length() + 4);
        for (int index = 0; index < camelName.length(); index++) {
            char current = camelName.charAt(index);
            if (Character.isUpperCase(current)) {
                builder.append('_').append(Character.toLowerCase(current));
            } else {
                builder.append(current);
            }
        }
        return builder.toString();
    }

    /** 拒绝任何不在受控字段集合内的键（camelCase 与 snake_case 均接受）。 */
    static void requireOnlyKnownFields(JsonNode arguments, List<String> camelNames) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String camel : camelNames) {
            allowed.add(camel);
            allowed.add(snakeCase(camel));
        }
        Iterator<String> names = arguments.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw invalidArguments();
            }
        }
    }

    /** 读取字段；同时给出 camelCase 与 snake_case 视为非法（对齐 Python {@code extra_forbidden}）。 */
    static JsonNode field(JsonNode arguments, String camelName) {
        String snake = snakeCase(camelName);
        if (snake.equals(camelName)) {
            // 单段字段名（如 sort）的 camelCase 与 snake_case 相同，只有一种写法
            return arguments.has(camelName) ? arguments.get(camelName) : null;
        }
        boolean hasCamel = arguments.has(camelName);
        boolean hasSnake = arguments.has(snake);
        if (hasCamel && hasSnake) {
            throw invalidArguments();
        }
        if (hasCamel) {
            return arguments.get(camelName);
        }
        return hasSnake ? arguments.get(snake) : null;
    }

    /** 带默认值的严格整型字段：缺失取默认值，显式 {@code null}、字符串、浮点与布尔都拒绝。 */
    static int intOrDefault(JsonNode arguments, String camelName, int defaultValue, int min, int max) {
        JsonNode node = field(arguments, camelName);
        if (node == null) {
            return defaultValue;
        }
        int value = strictInt(node);
        if (value < min || value > max) {
            throw invalidArguments();
        }
        return value;
    }

    /** {@code int | None} 字段：缺失或显式 {@code null} 返回 {@code null}；其它必须为严格整型。 */
    static Integer nullableInt(JsonNode arguments, String camelName, int min, int max) {
        JsonNode node = field(arguments, camelName);
        if (node == null || node.isNull()) {
            return null;
        }
        int value = strictInt(node);
        if (value < min || value > max) {
            throw invalidArguments();
        }
        return value;
    }

    /** 必填正整数 ID（signed long 域）：缺失、{@code null}、非整型或超出 long 都拒绝。 */
    static long requiredPositiveLong(JsonNode arguments, String camelName) {
        JsonNode node = field(arguments, camelName);
        if (node == null || node.isNull()) {
            throw invalidArguments();
        }
        long value = strictLong(node);
        if (value < 1) {
            throw invalidArguments();
        }
        return value;
    }

    /** {@code str | None} 字段：缺失或 {@code null} 返回 {@code null}；按 Unicode 码点限制长度，不做去空白。 */
    static String optionalText(JsonNode arguments, String camelName, int maxChars) {
        JsonNode node = field(arguments, camelName);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalidArguments();
        }
        String value = node.asText();
        if (value.codePointCount(0, value.length()) > maxChars) {
            throw invalidArguments();
        }
        return value;
    }

    /** 必填的正整数 ID 列表：长度、元素类型、正数与唯一性都在调用门户前校验。 */
    static List<Long> requiredPositiveIdList(
            JsonNode arguments, String camelName, int minSize, int maxSize) {
        JsonNode node = field(arguments, camelName);
        if (node == null || node.isNull() || !node.isArray()) {
            throw invalidArguments();
        }
        if (node.size() < minSize || node.size() > maxSize) {
            throw invalidArguments();
        }
        List<Long> ids = new ArrayList<>(node.size());
        Set<Long> seen = new HashSet<>();
        for (JsonNode item : node) {
            long value = strictLong(item);
            if (value < 1 || !seen.add(value)) {
                throw invalidArguments();
            }
            ids.add(value);
        }
        return ids;
    }

    /** 严格 32 位整型：只接受 JSON 整数（拒绝字符串、浮点、布尔与越界大整数）。 */
    static int strictInt(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalidArguments();
        }
        return node.intValue();
    }

    /** 严格 signed long 整型：只接受 JSON 整数（拒绝字符串、浮点、布尔与超出 long 的整数）。 */
    static long strictLong(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalidArguments();
        }
        return node.longValue();
    }

    static ToolRegistry.InvalidToolArgumentsException invalidArguments() {
        return new ToolRegistry.InvalidToolArgumentsException("工具参数不符合受控约束");
    }

    // ------------------------------------------------------------------ //
    // 商品事实格式化（对齐 Python storefront.schemas）
    // ------------------------------------------------------------------ //

    /** 两位小数金额；{@code null} 保持 {@code null}。 */
    static String formatMoney(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 库存状态：{@code <=0} 无货、{@code <=10} 低库存、其余有货。 */
    static String stockStatusFor(int availableStock) {
        if (availableStock <= 0) {
            return OUT_OF_STOCK;
        }
        if (availableStock <= LOW_STOCK_UPPER_BOUND) {
            return LOW_STOCK;
        }
        return IN_STOCK;
    }

    /**
     * 与 Python {@code datetime.isoformat()} 一致的 UTC 文本。
     *
     * <p>Python 的 tz-aware UTC 时间输出 {@code +00:00} 后缀，且微秒为 0 时省略小数部分；
     * {@link Instant#toString()} 会输出 {@code Z}，因此这里显式格式化以避免契约漂移。
     */
    static String isoPython(Instant instant) {
        if (instant == null) {
            return null;
        }
        OffsetDateTime utc = instant.atOffset(ZoneOffset.UTC);
        long micros = utc.getNano() / 1000L;
        StringBuilder text = new StringBuilder(32);
        text.append(String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d",
                utc.getYear(), utc.getMonthValue(), utc.getDayOfMonth(),
                utc.getHour(), utc.getMinute(), utc.getSecond()));
        if (micros != 0) {
            text.append(String.format(Locale.ROOT, ".%06d", micros));
        }
        return text.append("+00:00").toString();
    }

    static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    // ------------------------------------------------------------------ //
    // 固定失败结果
    // ------------------------------------------------------------------ //

    /** 门户失败：只保留稳定错误码与固定文案，绝不携带上游正文或凭据。 */
    static ToolResult portalFailureResult(String toolName, PortalException exception) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errorCode", exception.code());
        payload.put("message", exception.getMessage());
        return ToolResult.failure(toolName, ToolResult.Status.ERROR, payload,
                List.of(toolName + " 执行失败：" + exception.code()),
                exception.code(), exception.getMessage());
    }

    /** 商品不可用：状态为未找到，携带可解释原因。 */
    static ToolResult productNotFoundResult(String toolName, long productId, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId);
        payload.put("reason", reason);
        return ToolResult.failure(toolName, ToolResult.Status.PRODUCT_NOT_FOUND, payload,
                List.of("商品 " + productId + " 不可用：" + reason), null, null);
    }

    /** 商品是否已下架或已删除（对齐 Python {@code _is_unavailable}）。 */
    static boolean isUnavailable(Integer publishStatus, Integer deleteStatus) {
        if (deleteStatus != null && deleteStatus == 1) {
            return true;
        }
        return publishStatus != null && publishStatus != 1;
    }
}

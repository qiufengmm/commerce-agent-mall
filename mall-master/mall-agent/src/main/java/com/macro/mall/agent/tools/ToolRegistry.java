package com.macro.mall.agent.tools;

import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.macro.mall.agent.api.PythonText;

/**
 * 只读工具注册表。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.tools.registry.ToolRegistry}：
 * 注册顺序即暴露顺序；调用前统一把模型提供的参数字符串解析为 JSON 对象，
 * 非法 JSON、非对象与未注册工具都在<strong>触达任何后端之前</strong>抛出固定结构化错误。
 *
 * <p>本类不持有外部门户、Redis 或数据库句柄：只读依赖通过 {@link ToolContext} 逐次传入。
 */
public final class ToolRegistry {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, AgentTool> tools;

    /**
     * @param tools 工具列表；名称重复时拒绝注册
     * @throws IllegalArgumentException 存在重复工具名
     */
    public ToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> registered = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            if (registered.containsKey(tool.name())) {
                throw new IllegalArgumentException("重复注册的工具：" + tool.name());
            }
            registered.put(tool.name(), tool);
        }
        this.tools = Collections.unmodifiableMap(registered);
    }

    /** 已注册工具名，保持注册顺序。 */
    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    /**
     * 构造唯一的只读工具注册表。
     *
     * <p>对齐 Python {@code tools.create_tool_registry()}：注册且只注册四个工具，
     * 顺序为 {@code searchProducts}、{@code getProductDetail}、{@code compareProducts}、
     * {@code getMemberCouponsForProduct}。
     */
    public static ToolRegistry defaultRegistry() {
        return defaultRegistry(Clock.systemUTC());
    }

    /** 使用可注入时钟构造默认注册表（会员券解释需要可测时间）。 */
    public static ToolRegistry defaultRegistry(Clock clock) {
        return new ToolRegistry(List.of(
                new SearchProductsTool(),
                new GetProductDetailTool(),
                new CompareProductsTool(),
                new GetMemberCouponsForProductTool(clock)));
    }

    /** 按名称查找工具。 */
    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 按名称返回 JSON Schema；未注册时为空。 */
    public Optional<Map<String, Object>> jsonSchema(String name) {
        return find(name).map(AgentTool::parameters);
    }

    /** OpenAI 工具声明列表，保持注册顺序。 */
    public List<Map<String, Object>> openAiTools() {
        return tools.values().stream().map(AgentTool::openAiTool).toList();
    }

    /**
     * 解析参数并执行工具。
     *
     * @param name          工具名
     * @param argumentsJson 模型提供的原始参数字符串；空白等价于空对象
     * @param context       只读依赖与会员凭据
     * @throws ToolException 未注册工具（{@code UNKNOWN_TOOL}）或参数非法（{@code INVALID_TOOL_ARGUMENTS}）
     */
    public ToolResult invoke(String name, String argumentsJson, ToolContext context) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new UnknownToolException(name);
        }
        return tool.invoke(parseArguments(argumentsJson), context);
    }

    private static JsonNode parseArguments(String argumentsJson) {
        String text = PythonText.strip(argumentsJson);
        if (text == null || text.isEmpty()) {
            return JSON.createObjectNode();
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(text);
        } catch (JsonProcessingException exception) {
            // 不链式持有解析异常：Jackson 消息可能包含原始参数片段
            throw new InvalidToolArgumentsException("工具参数不是合法 JSON");
        }
        if (payload == null || !payload.isObject()) {
            throw new InvalidToolArgumentsException("工具参数必须是 JSON 对象");
        }
        return payload;
    }

    /** 工具调用被拒绝（未注册或参数非法）；携带稳定错误码与固定文案。 */
    public static class ToolException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        ToolException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 未注册的工具。 */
    public static final class UnknownToolException extends ToolException {

        private static final long serialVersionUID = 1L;

        public UnknownToolException(String name) {
            super("UNKNOWN_TOOL", "未注册的工具：" + name);
        }
    }

    /** 参数不是合法 JSON、不是 JSON 对象或不满足受控约束。 */
    public static final class InvalidToolArgumentsException extends ToolException {

        private static final long serialVersionUID = 1L;

        public InvalidToolArgumentsException(String message) {
            super("INVALID_TOOL_ARGUMENTS", message);
        }
    }
}

package com.macro.mall.agent.model;

import java.util.List;
import java.util.Map;

/**
 * 一次模型调用的输入，与 Python {@code model.schemas.ModelRequest} 对齐。
 *
 * @param messages    对话消息，按顺序发送
 * @param tools       已注册工具的 JSON schema 定义，为空时不发送 {@code tools}/{@code tool_choice}
 * @param toolChoice  有工具时的 {@code auto} 或 {@code none}
 * @param temperature 采样温度，默认 0.2
 * @param maxTokens   可选的最大生成 token 数，为 {@code null} 时不发送
 */
public record ModelRequest(
        List<ModelMessage> messages,
        List<Map<String, Object>> tools,
        String toolChoice,
        double temperature,
        Integer maxTokens) {

    public static final String TOOL_CHOICE_AUTO = "auto";
    public static final String TOOL_CHOICE_NONE = "none";
    public static final double DEFAULT_TEMPERATURE = 0.2;

    public ModelRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolChoice = toolChoice == null ? TOOL_CHOICE_AUTO : toolChoice;
        if (!TOOL_CHOICE_AUTO.equals(toolChoice) && !TOOL_CHOICE_NONE.equals(toolChoice)) {
            throw new IllegalArgumentException("toolChoice 只能是 auto 或 none");
        }
    }

    /** 只有消息、无工具、默认温度的常用组合。 */
    public static ModelRequest of(List<ModelMessage> messages) {
        return new ModelRequest(messages, List.of(), TOOL_CHOICE_AUTO, DEFAULT_TEMPERATURE, null);
    }
}

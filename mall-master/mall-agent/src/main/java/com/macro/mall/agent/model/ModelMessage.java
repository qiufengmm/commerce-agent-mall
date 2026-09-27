package com.macro.mall.agent.model;

import java.util.List;
import java.util.Set;

/**
 * 模型对话中的一条消息，与 Python {@code model.schemas.ModelMessage} 对齐。
 *
 * <p>只有 {@code assistant} 消息可以携带 {@code toolCalls}，只有 {@code tool} 消息携带
 * {@code toolCallId}；{@code content} 允许为 {@code null}（例如纯工具调用的 assistant 消息）。
 *
 * @param role       {@code system} / {@code user} / {@code assistant} / {@code tool}
 * @param content    文本内容，可为 {@code null}
 * @param toolCallId 工具结果消息对应的调用 ID，可为 {@code null}
 * @param toolCalls  assistant 消息发起的工具调用，缺省为空列表
 */
public record ModelMessage(String role, String content, String toolCallId, List<ModelToolCall> toolCalls) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    private static final Set<String> ROLES = Set.of(ROLE_SYSTEM, ROLE_USER, ROLE_ASSISTANT, ROLE_TOOL);

    public ModelMessage {
        if (role == null || !ROLES.contains(role)) {
            throw new IllegalArgumentException("role 必须是 system/user/assistant/tool 之一");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ModelMessage system(String content) {
        return new ModelMessage(ROLE_SYSTEM, content, null, List.of());
    }

    public static ModelMessage user(String content) {
        return new ModelMessage(ROLE_USER, content, null, List.of());
    }

    public static ModelMessage assistant(String content, List<ModelToolCall> toolCalls) {
        return new ModelMessage(ROLE_ASSISTANT, content, null, toolCalls);
    }

    public static ModelMessage tool(String toolCallId, String content) {
        return new ModelMessage(ROLE_TOOL, content, toolCallId, List.of());
    }
}

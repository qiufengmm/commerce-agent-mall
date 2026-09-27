package com.macro.mall.agent.agent;

/**
 * 一次工具调用的审计记录，行为对齐 Python {@code mall_shopping_agent.agent.types.ToolCallRecord}。
 *
 * <p>{@code status} 为 {@code ToolResult.Status} 的名称，或被拒绝时的稳定错误码
 * （{@code UNKNOWN_TOOL} / {@code INVALID_TOOL_ARGUMENTS}）。
 * 记录<strong>不含</strong>工具参数或结果载荷，避免把凭据或商品原文带进日志与会话。
 *
 * @param name   工具名（可能来自模型，因此仍属不可信输入，仅用于审计）
 * @param status 稳定状态或错误码
 * @param ok     是否成功（仅 {@code OK} 为真）
 */
public record ToolCallRecord(String name, String status, boolean ok) {
}

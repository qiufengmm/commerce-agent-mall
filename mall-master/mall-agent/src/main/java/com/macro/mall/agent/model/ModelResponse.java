package com.macro.mall.agent.model;

import java.util.List;

/**
 * 规范化后的模型响应，与 Python {@code model.schemas.ModelResponse} 对齐。
 *
 * <p>上层只接触这里的稳定字段，不感知任何供应商原始响应结构。
 *
 * @param content      文本内容，可为 {@code null}（纯工具调用时）
 * @param toolCalls    工具调用，保持供应商返回顺序
 * @param usage        token 用量，缺失时为全 {@code null}
 * @param finishReason 结束原因，可为 {@code null}
 */
public record ModelResponse(String content, List<ModelToolCall> toolCalls, Usage usage, String finishReason) {

    public ModelResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? Usage.empty() : usage;
    }

    /** token 用量；与 Python 一致，缺失或非整数计数一律为 {@code null}。 */
    public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {

        public static Usage empty() {
            return new Usage(null, null, null);
        }
    }
}

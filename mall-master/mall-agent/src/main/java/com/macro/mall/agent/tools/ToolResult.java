package com.macro.mall.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.macro.mall.agent.safety.UntrustedTextFence;

/**
 * 一次只读工具调用的结果。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.tools.registry.ToolResult}：
 * 交给模型的内容只包含工具名、状态与数据；失败时额外附带稳定 {@code errorCode}/{@code errorMessage}。
 * 内容统一经 {@link UntrustedTextFence#fenceJson} 围栏并受字符上限约束，绝不携带
 * {@code Authorization} 或上游响应正文。
 *
 * @param name         工具名
 * @param status       调用状态
 * @param payload      结构化数据（保持插入顺序）
 * @param facts        供卡片/会话使用的事实描述
 * @param errorCode    失败时的稳定错误码；成功时为 {@code null}
 * @param errorMessage 失败时的固定文案；成功时为 {@code null}
 */
public record ToolResult(
        String name,
        Status status,
        Map<String, Object> payload,
        List<String> facts,
        String errorCode,
        String errorMessage) {

    /** 交给模型的工具结果字符上限，与 Python {@code MAX_TOOL_RESULT_CHARS} 一致。 */
    public static final int MAX_RESULT_CHARS = 4000;

    /** 工具调用状态，取值与 Python {@code ToolStatus} 一致。 */
    public enum Status {
        OK,
        LOGIN_REQUIRED,
        PRODUCT_NOT_FOUND,
        ERROR
    }

    public ToolResult {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    public boolean ok() {
        return status == Status.OK;
    }

    /** 构造成功结果。 */
    public static ToolResult ok(String name, Map<String, Object> payload, List<String> facts) {
        return new ToolResult(name, Status.OK, payload, facts, null, null);
    }

    /** 构造失败结果：数据里保留稳定错误码与固定文案，供模型解释。 */
    public static ToolResult failure(
            String name, Status status, Map<String, Object> payload, List<String> facts,
            String errorCode, String errorMessage) {
        return new ToolResult(name, status, payload, facts, errorCode, errorMessage);
    }

    /** 交给模型的围栏内容，默认使用 {@link #MAX_RESULT_CHARS}。 */
    public String toModelContent() {
        return toModelContent(MAX_RESULT_CHARS);
    }

    /** 交给模型的围栏内容，按给定上限裁剪。 */
    public String toModelContent(int maxChars) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", name);
        body.put("status", status.name());
        body.put("data", payload);
        if (errorCode != null && !errorCode.isEmpty()) {
            body.put("errorCode", errorCode);
            body.put("errorMessage", errorMessage);
        }
        return UntrustedTextFence.fenceJson(body, "tool_result:" + name, maxChars);
    }
}

package com.macro.mall.agent.agent;

/**
 * 工具调用轮数超过配置上限的领域异常，行为对齐 Python
 * {@code mall_shopping_agent.agent.types.ToolRoundLimitError}。
 *
 * <p>只携带固定的 HTTP 状态 {@value #HTTP_STATUS}、稳定错误码 {@value #CODE} 与固定文案；
 * <strong>永不链式持有</strong>原因异常、模型原始响应、提示词或凭据，供 API 层
 * 直接映射为 422，而不需要（也不允许）读取任何原始文本。
 */
public final class ToolRoundLimitException extends RuntimeException {

    /** 对外 HTTP 状态。 */
    public static final int HTTP_STATUS = 422;

    /** 稳定错误码。 */
    public static final String CODE = "TOOL_ROUND_LIMIT";

    private static final long serialVersionUID = 1L;

    private final int maxToolRounds;

    /**
     * @param maxToolRounds 配置的工具调用轮数上限，必须是正整数
     */
    public ToolRoundLimitException(int maxToolRounds) {
        super("工具调用超过 " + maxToolRounds + " 轮，本次问题需要缩小范围");
        if (maxToolRounds < 1) {
            throw new IllegalArgumentException("maxToolRounds 必须是正整数");
        }
        this.maxToolRounds = maxToolRounds;
    }

    public int httpStatus() {
        return HTTP_STATUS;
    }

    public String code() {
        return CODE;
    }

    public int maxToolRounds() {
        return maxToolRounds;
    }
}

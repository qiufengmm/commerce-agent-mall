package com.macro.mall.agent.agent;

import java.util.List;

import com.macro.mall.agent.api.ProductCard;

/**
 * 一次智能体问答的输出，行为对齐 Python {@code mall_shopping_agent.agent.types.AgentTurnResult}。
 *
 * <p>字段由服务层直接映射为 {@code ChatData}：{@code answer} / {@code products} /
 * {@code requiresLogin} / {@code suggestedQuestions} 对应移动端契约字段，
 * {@code toolCalls} 与 {@code toolRounds} 只用于审计与测试，不对外暴露。
 *
 * @param answer             清理内部标记并裁剪后的最终回答
 * @param products           服务端事实构造的商品卡片（最多 {@link AgentLimits#maxCards()} 张）
 * @param requiresLogin      是否要求登录（个人券身份门槛命中）
 * @param suggestedQuestions 受控模板生成的建议问题
 * @param toolCalls          本轮实际执行的工具调用记录
 * @param toolRounds         本轮执行的工具调用轮数
 * @param stoppedReason      结束原因
 */
public record AgentTurnResult(
        String answer,
        List<ProductCard> products,
        boolean requiresLogin,
        List<String> suggestedQuestions,
        List<ToolCallRecord> toolCalls,
        int toolRounds,
        StoppedReason stoppedReason) {

    /** 结束原因，取值与 Python 字符串一致。 */
    public enum StoppedReason {
        ANSWER,
        REFUSAL,
        LOGIN_REQUIRED,
        ROUNDS_EXHAUSTED
    }

    public AgentTurnResult {
        answer = answer == null ? "" : answer;
        products = products == null ? List.of() : List.copyOf(products);
        suggestedQuestions = suggestedQuestions == null ? List.of() : List.copyOf(suggestedQuestions);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}

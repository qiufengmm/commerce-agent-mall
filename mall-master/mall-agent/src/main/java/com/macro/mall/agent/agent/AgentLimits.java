package com.macro.mall.agent.agent;

import com.macro.mall.agent.config.AgentProperties;

/**
 * 单次会话的硬边界，行为对齐 Python {@code mall_shopping_agent.agent.types.AgentLimits}。
 *
 * <p>取值来源是运行配置（{@link AgentProperties}），但本类型本身为纯数据：
 * 不依赖 Spring 容器，便于离线评测与单元测试直接构造。
 *
 * @param maxToolRounds           单次回答允许的最大工具调用轮数
 * @param maxMessages             会话历史保留的最近消息条数
 * @param toolResultMaxChars      单条工具结果进入模型前的字符上限
 * @param contextMaxChars         非 system 消息合计的字符上限
 * @param maxCards                单次回答最多输出的商品卡片数
 * @param maxSuggestedQuestions   最多输出的建议问题数
 */
public record AgentLimits(
        int maxToolRounds,
        int maxMessages,
        int toolResultMaxChars,
        int contextMaxChars,
        int maxCards,
        int maxSuggestedQuestions) {

    public AgentLimits {
        requirePositive("maxToolRounds", maxToolRounds);
        requirePositive("maxMessages", maxMessages);
        requirePositive("toolResultMaxChars", toolResultMaxChars);
        requirePositive("contextMaxChars", contextMaxChars);
        requirePositive("maxCards", maxCards);
        requirePositive("maxSuggestedQuestions", maxSuggestedQuestions);
    }

    /** Python {@code AgentLimits()} 的缺省值：4 轮、20 条、4000 字符、16000 字符、5 卡、3 问。 */
    public static AgentLimits defaults() {
        return new AgentLimits(4, 20, 4000, 16000, 5, 3);
    }

    /** 从进程配置读取边界；不与 Spring Bean 图耦合。 */
    public static AgentLimits from(AgentProperties properties) {
        return new AgentLimits(
                properties.getMaxToolRounds(),
                properties.getSessionMaxMessages(),
                properties.getToolResultMaxChars(),
                properties.getContextMaxChars(),
                AgentLimits.defaults().maxCards(),
                AgentLimits.defaults().maxSuggestedQuestions());
    }

    private static void requirePositive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " 必须是正整数");
        }
    }
}

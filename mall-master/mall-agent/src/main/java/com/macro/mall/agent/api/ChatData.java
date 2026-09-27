package com.macro.mall.agent.api;

import java.util.List;

/**
 * 聊天响应数据。
 *
 * <p>{@code products} 与 {@code suggestedQuestions} 必须是数组，缺省为空数组，
 * 不能序列化成 {@code null}。
 */
public record ChatData(
        String sessionId,
        String messageId,
        String answer,
        List<ProductCard> products,
        boolean requiresLogin,
        List<String> suggestedQuestions) {

    public ChatData {
        products = products == null ? List.of() : List.copyOf(products);
        suggestedQuestions = suggestedQuestions == null ? List.of() : List.copyOf(suggestedQuestions);
    }
}

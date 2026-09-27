package com.macro.mall.agent.api;

import java.util.List;

/**
 * 会话恢复数据。{@code messages} 与 {@code products} 必须是数组，缺省为空数组。
 */
public record SessionData(
        String sessionId,
        List<SessionMessage> messages,
        List<ProductCard> products,
        boolean requiresLogin) {

    public SessionData {
        messages = messages == null ? List.of() : List.copyOf(messages);
        products = products == null ? List.of() : List.copyOf(products);
    }
}

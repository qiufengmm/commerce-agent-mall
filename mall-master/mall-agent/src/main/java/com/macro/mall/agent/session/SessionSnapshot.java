package com.macro.mall.agent.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.macro.mall.agent.api.ProductCard;
import com.macro.mall.agent.api.SessionMessage;

/**
 * 一次会话的持久化内容，行为对齐 Python
 * {@code mall_shopping_agent.session.repository.SessionSnapshot}。
 *
 * <p>只保存两类内容：{@code messages}（{@code role}/{@code content}）与 {@code products}（商品卡片）。
 * 不保存 Token、Authorization、模型原始响应、门户原始响应或工具调用载荷；字段集合由
 * {@link SessionDocumentCodec} 在读写两侧严格校验（额外字段按文档损坏处理）。
 *
 * <p>裁剪语义与 Python 完全一致：{@link #trimmed(int)} 保留<strong>最近</strong>
 * {@code max(1, maxMessages)} 条消息与<strong>最前</strong> {@link #MAX_STORED_PRODUCTS} 张卡片。
 */
public record SessionSnapshot(List<SessionMessage> messages, List<ProductCard> products) {

    /** 单条消息的最大长度（Unicode 码点数），对齐 Python {@code MAX_MESSAGE_CHARS}。 */
    public static final int MAX_MESSAGE_CHARS = 4000;

    /** 最多保存的商品卡片数量，对齐 Python {@code MAX_STORED_PRODUCTS}。 */
    public static final int MAX_STORED_PRODUCTS = 5;

    public SessionSnapshot {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages 不能为空"));
        products = List.copyOf(Objects.requireNonNull(products, "products 不能为空"));
    }

    public static SessionSnapshot empty() {
        return new SessionSnapshot(List.of(), List.of());
    }

    /** 消息与卡片都为空时视为空会话（会员目标是否非空的判断依据）。 */
    public boolean isEmpty() {
        return messages.isEmpty() && products.isEmpty();
    }

    /** 只保留最近 {@code max(1, maxMessages)} 条消息与最前 {@link #MAX_STORED_PRODUCTS} 张卡片。 */
    public SessionSnapshot trimmed(int maxMessages) {
        int limit = Math.max(1, maxMessages);
        List<SessionMessage> keptMessages = messages.size() <= limit
                ? messages
                : List.copyOf(messages.subList(messages.size() - limit, messages.size()));
        List<ProductCard> keptProducts = products.size() <= MAX_STORED_PRODUCTS
                ? products
                : List.copyOf(products.subList(0, MAX_STORED_PRODUCTS));
        if (keptMessages == messages && keptProducts == products) {
            return this;
        }
        return new SessionSnapshot(keptMessages, keptProducts);
    }

    /** 追加消息后按 {@code maxMessages} 裁剪；卡片保持不变，与 Python {@code with_appended} 一致。 */
    public SessionSnapshot withAppended(List<SessionMessage> appended, int maxMessages) {
        Objects.requireNonNull(appended, "appended 不能为空");
        List<SessionMessage> combined = new ArrayList<>(messages.size() + appended.size());
        combined.addAll(messages);
        for (SessionMessage message : appended) {
            combined.add(requireValidMessage(message));
        }
        return new SessionSnapshot(combined, products).trimmed(maxMessages);
    }

    /**
     * 校验单条消息：{@code role} 只能是 {@code user}/{@code assistant}，
     * {@code content} 为 1..{@link #MAX_MESSAGE_CHARS} 个 Unicode 码点。
     *
     * @throws IllegalArgumentException 取值不符合持久化约束
     */
    public static SessionMessage requireValidMessage(SessionMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("消息不能为空");
        }
        String role = message.role();
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("消息 role 只能是 user 或 assistant");
        }
        String content = message.content();
        if (content == null) {
            throw new IllegalArgumentException("消息 content 不能为空");
        }
        int codePoints = content.codePointCount(0, content.length());
        if (codePoints < 1 || codePoints > MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException("消息 content 长度必须在 1 到 " + MAX_MESSAGE_CHARS + " 之间");
        }
        return message;
    }
}

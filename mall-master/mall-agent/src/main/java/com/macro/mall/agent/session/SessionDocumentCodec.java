package com.macro.mall.agent.session;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.macro.mall.agent.api.ProductCard;
import com.macro.mall.agent.api.SessionMessage;

/**
 * 会话文档的 JSON 编解码，严格对齐 Python Pydantic 持久化结构。
 *
 * <p>读写结构固定为 {@code {"messages":[{"role","content"}],"products":[<商品卡片>]}}：
 * <ul>
 *   <li>顶层、消息、卡片三层都只允许白名单字段；出现未知字段（如 {@code token}、
 *       {@code authorization}、{@code tool_calls}）一律判为文档损坏。</li>
 *   <li>{@code messages} 缺失时按 Python {@code default_factory} 视为空列表；
 *       显式 {@code null} 与 Python 一样判为非法。</li>
 *   <li>字段个数/类型/取值与 Python 模型一致：{@code role} 只能是 {@code user}/{@code assistant}，
 *       {@code content} 为 1..4000 个码点，卡片的 {@code name}/{@code pic}/{@code price}/{@code subtitle}
 *       可为缺省或 {@code null}，{@code stockStatus}/{@code availableStock}/{@code detailPath} 必填。</li>
 * </ul>
 *
 * <p>这里比 Python 更严格的一点：Python 把 {@code products} 声明为 {@code list[dict[str, Any]]}，
 * 允许卡片带任意附加键；Java 侧只允许卡片白名单字段，避免把敏感附加字段持久化或被当作事实读回。
 * 自身写入的文档始终满足白名单，因此不影响与 Python 的往返兼容。
 */
final class SessionDocumentCodec {

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final String MESSAGES = "messages";
    private static final String PRODUCTS = "products";
    private static final Set<String> DOCUMENT_FIELDS = Set.of(MESSAGES, PRODUCTS);
    private static final Set<String> MESSAGE_FIELDS = Set.of("role", "content");
    private static final Set<String> PRODUCT_FIELDS = Set.of(
            "id", "name", "pic", "price", "subtitle", "stockStatus", "availableStock", "detailPath");

    private SessionDocumentCodec() {
    }

    /** 文档损坏（非法 JSON、未知字段、非法取值）时抛出；调用方据此删除该键并返回空快照。 */
    static final class InvalidDocument extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private InvalidDocument() {
            // 刻意不携带原始 JSON 或解析异常：文档可能包含用户内容
            super(null, null, false, false);
        }
    }

    static String encode(SessionSnapshot snapshot) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode messages = root.putArray(MESSAGES);
        for (SessionMessage message : snapshot.messages()) {
            SessionMessage valid = SessionSnapshot.requireValidMessage(message);
            ObjectNode node = messages.addObject();
            node.put("role", valid.role());
            node.put("content", valid.content());
        }
        ArrayNode products = root.putArray(PRODUCTS);
        for (ProductCard card : snapshot.products()) {
            products.add(productNode(card));
        }
        return root.toString();
    }

    static SessionSnapshot decode(String json) {
        if (json == null) {
            throw new InvalidDocument();
        }
        JsonNode root;
        try {
            root = JSON.readValue(json, JsonNode.class);
        } catch (JsonProcessingException | RuntimeException ex) {
            // 不链式持有解析异常：Jackson 异常消息会包含原始文档片段
            throw new InvalidDocument();
        }
        if (root == null || !root.isObject()) {
            throw new InvalidDocument();
        }
        requireOnlyFields(root, DOCUMENT_FIELDS);
        return new SessionSnapshot(decodeMessages(root.get(MESSAGES)), decodeProducts(root.get(PRODUCTS)));
    }

    private static List<SessionMessage> decodeMessages(JsonNode node) {
        if (node == null) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new InvalidDocument();
        }
        List<SessionMessage> messages = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw new InvalidDocument();
            }
            requireOnlyFields(item, MESSAGE_FIELDS);
            String role = requireText(item, "role");
            String content = requireText(item, "content");
            try {
                messages.add(SessionSnapshot.requireValidMessage(new SessionMessage(role, content)));
            } catch (IllegalArgumentException ex) {
                throw new InvalidDocument();
            }
        }
        return messages;
    }

    private static List<ProductCard> decodeProducts(JsonNode node) {
        if (node == null) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new InvalidDocument();
        }
        List<ProductCard> products = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw new InvalidDocument();
            }
            requireOnlyFields(item, PRODUCT_FIELDS);
            products.add(new ProductCard(
                    requireIntegralLong(item, "id"),
                    optionalText(item, "name", ""),
                    optionalTextOrNull(item, "pic"),
                    optionalTextOrNull(item, "price"),
                    optionalTextOrNull(item, "subtitle"),
                    requireText(item, "stockStatus"),
                    requireIntegralInt(item, "availableStock"),
                    requireText(item, "detailPath")));
        }
        return products;
    }

    private static ObjectNode productNode(ProductCard card) {
        Objects.requireNonNull(card, "商品卡片不能为空");
        ObjectNode node = JSON.createObjectNode();
        node.put("id", card.id());
        node.put("name", requireNonNullValue(card.name(), "name"));
        putNullable(node, "pic", card.pic());
        putNullable(node, "price", card.price());
        putNullable(node, "subtitle", card.subtitle());
        node.put("stockStatus", requireNonNullValue(card.stockStatus(), "stockStatus"));
        node.put("availableStock", card.availableStock());
        node.put("detailPath", requireNonNullValue(card.detailPath(), "detailPath"));
        return node;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String requireNonNullValue(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("商品卡片 " + field + " 不能为空");
        }
        return value;
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            if (!allowed.contains(names.next())) {
                throw new InvalidDocument();
            }
        }
    }

    /** pydantic 宽松 int：整型 JSON 数值或整值数字字符串。 */
    private static long requireIntegralLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isBoolean()) {
            throw new InvalidDocument();
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText().strip());
            } catch (NumberFormatException ex) {
                throw new InvalidDocument();
            }
        }
        throw new InvalidDocument();
    }

    private static int requireIntegralInt(JsonNode node, String field) {
        long value = requireIntegralLong(node, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new InvalidDocument();
        }
        return (int) value;
    }

    /** pydantic 必填 str：字段缺失、显式 null 或非字符串都非法。 */
    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new InvalidDocument();
        }
        return value.asText();
    }

    /** pydantic {@code str = ""}：缺失用默认值，显式 null 或非字符串非法。 */
    private static String optionalText(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null) {
            return fallback;
        }
        if (!value.isTextual()) {
            throw new InvalidDocument();
        }
        return value.asText();
    }

    /** pydantic {@code str | None}：缺失或显式 null 为 null，非字符串非法。 */
    private static String optionalTextOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new InvalidDocument();
        }
        return value.asText();
    }
}

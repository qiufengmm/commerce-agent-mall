package com.macro.mall.agent.presentation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.macro.mall.agent.tools.AgentTool;
import com.macro.mall.agent.tools.ToolResult;

/**
 * 按工具结果累积商品事实，并保留来源优先级。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.presentation.products.ProductFactCollector}：
 * 只接受状态为 {@code OK} 的工具结果；来源优先级 {@code detail &gt; compare &gt; search}，
 * 低优先事实不覆盖高优先事实，高优先事实缺失的图片/副标题回退到已有事实。
 * 事实的插入顺序即工具结果的出现顺序，供卡片回退排序使用。
 */
public final class ProductFactCollector {

    private static final String SEARCH_PRODUCTS = "searchProducts";
    private static final String GET_PRODUCT_DETAIL = "getProductDetail";
    private static final String COMPARE_PRODUCTS = "compareProducts";
    private static final String STATUS_OK = ToolResult.Status.OK.name();

    private final Map<Long, ProductFact> facts = new LinkedHashMap<>();

    /** 已收集事实，保持首次出现顺序；返回值不可修改。 */
    public Map<Long, ProductFact> facts() {
        return Collections.unmodifiableMap(facts);
    }

    /** 累积一次工具结果；非成功结果、未知工具名或缺失商品 ID 的记录被忽略。 */
    public void add(ToolResult result) {
        if (result == null || !result.ok()) {
            return;
        }
        switch (result.name()) {
            case SEARCH_PRODUCTS -> collectSearch(result.payload());
            case GET_PRODUCT_DETAIL -> collectDetail(result.payload());
            case COMPARE_PRODUCTS -> collectCompare(result.payload());
            default -> {
                // 未知工具名不产生事实
            }
        }
    }

    private void collectSearch(Map<String, Object> payload) {
        for (Map<String, Object> item : items(payload, "products")) {
            Long productId = asLong(item.get("id"));
            if (productId == null) {
                continue;
            }
            Integer stock = asInt(item.get("stock"));
            int availableStock = Math.max(stock == null ? 0 : stock, 0);
            upsert(new ProductFact(
                    productId,
                    text(item.get("name")),
                    text(item.get("pic")),
                    text(item.get("price")),
                    text(item.get("subtitle")),
                    availableStock,
                    AgentTool.stockStatusFor(availableStock),
                    ProductFact.SOURCE_RANK_SEARCH));
        }
    }

    private void collectDetail(Map<String, Object> payload) {
        Object rawProduct = payload.get("product");
        if (!(rawProduct instanceof Map<?, ?> product)) {
            return;
        }
        Long productId = asLong(product.get("id"));
        if (productId == null) {
            return;
        }
        Integer availableStock = asInt(payload.get("availableStock"));
        String stockStatus = text(payload.get("stockStatus"));
        upsert(new ProductFact(
                productId,
                text(product.get("name")),
                text(product.get("pic")),
                text(product.get("price")),
                text(product.get("subtitle")),
                availableStock == null ? 0 : availableStock,
                stockStatus == null || stockStatus.isEmpty() ? AgentTool.OUT_OF_STOCK : stockStatus,
                ProductFact.SOURCE_RANK_DETAIL));
    }

    private void collectCompare(Map<String, Object> payload) {
        for (Map<String, Object> item : items(payload, "items")) {
            if (!STATUS_OK.equals(text(item.get("status")))) {
                continue;
            }
            Long productId = asLong(item.get("productId"));
            if (productId == null) {
                continue;
            }
            Integer availableStock = asInt(item.get("availableStock"));
            String stockStatus = text(item.get("stockStatus"));
            upsert(new ProductFact(
                    productId,
                    text(item.get("name")),
                    text(item.get("pic")),
                    text(item.get("price")),
                    text(item.get("subtitle")),
                    availableStock == null ? 0 : availableStock,
                    stockStatus == null || stockStatus.isEmpty() ? AgentTool.OUT_OF_STOCK : stockStatus,
                    ProductFact.SOURCE_RANK_COMPARE));
        }
    }

    private void upsert(ProductFact fact) {
        ProductFact existing = facts.get(fact.productId());
        if (existing != null) {
            if (existing.sourceRank() > fact.sourceRank()) {
                return;
            }
            fact = fact.withFallbacks(existing);
        }
        facts.put(fact.productId(), fact);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            String stripped = text.strip();
            if (!stripped.isEmpty() && stripped.chars().allMatch(Character::isDigit)) {
                try {
                    return Long.valueOf(stripped);
                } catch (NumberFormatException exception) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Integer asInt(Object value) {
        Long parsed = asLong(value);
        return parsed == null ? null : parsed.intValue();
    }
}

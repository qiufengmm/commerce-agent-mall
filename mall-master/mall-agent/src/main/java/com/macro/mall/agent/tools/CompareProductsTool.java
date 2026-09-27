package com.macro.mall.agent.tools;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.macro.mall.agent.storefront.PortalException;
import com.macro.mall.agent.storefront.dto.ProductAttribute;
import com.macro.mall.agent.storefront.dto.ProductDetailResponse;
import com.macro.mall.agent.storefront.dto.SkuStock;

/**
 * {@code compareProducts}：比较 2 到 3 件商品。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.tools.product_tools.compare_products}：
 * 按请求 ID 顺序逐个取详情并标记成功 / 不存在 / 异常；缺失、已下架或详情异常的商品
 * 只在状态中标记，绝不由模型补全字段；成功项最多带 6 个属性。
 */
public final class CompareProductsTool implements AgentTool {

    public static final String NAME = "compareProducts";

    static final int MIN_PRODUCTS = 2;
    static final int MAX_PRODUCTS = 3;
    static final int MAX_COMPARE_ATTRIBUTES = 6;

    private static final String STATUS_OK = ToolResult.Status.OK.name();
    private static final String STATUS_NOT_FOUND = ToolResult.Status.PRODUCT_NOT_FOUND.name();
    private static final String STATUS_ERROR = ToolResult.Status.ERROR.name();
    private static final String REASON_OFF_SHELF = "该商品已下架";
    private static final String NOTE = "缺失、已下架或详情异常的商品只在状态中标记，不会由模型补全字段。";

    private static final List<String> FIELDS = List.of("productIds");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "比较 2 到 3 件商品的价格、可售库存、品牌分类和关键属性；"
                + "不存在或已下架的商品只在状态中标记。";
    }

    @Override
    public boolean requiresMember() {
        return false;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> productIds = new LinkedHashMap<>();
        productIds.put("type", "array");
        productIds.put("items", AgentTool.longIdProperty());
        productIds.put("minItems", MIN_PRODUCTS);
        productIds.put("maxItems", MAX_PRODUCTS);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("productIds", productIds);
        return AgentTool.objectSchema(properties, List.of("productIds"));
    }

    @Override
    public ToolResult invoke(JsonNode arguments, ToolContext context) {
        AgentTool.requireOnlyKnownFields(arguments, FIELDS);
        List<Long> productIds =
                AgentTool.requiredPositiveIdList(arguments, "productIds", MIN_PRODUCTS, MAX_PRODUCTS);

        List<Map<String, Object>> items = new ArrayList<>();
        boolean anyOk = false;
        boolean anyError = false;

        for (long productId : productIds) {
            ProductDetailResponse detail;
            try {
                detail = context.backend().getProductDetail(productId);
            } catch (PortalException exception) {
                if (exception.kind() == PortalException.Kind.NOT_FOUND) {
                    items.add(statusOnly(productId, STATUS_NOT_FOUND));
                    continue;
                }
                items.add(statusOnly(productId, STATUS_ERROR));
                anyError = true;
                continue;
            }

            if (AgentTool.isUnavailable(detail.publishStatus(), detail.deleteStatus())) {
                Map<String, Object> item = statusOnly(productId, STATUS_NOT_FOUND);
                item.put("reason", REASON_OFF_SHELF);
                items.add(item);
                continue;
            }

            anyOk = true;
            items.add(comparisonItem(detail));
        }

        ToolResult.Status status;
        if (anyOk) {
            status = ToolResult.Status.OK;
        } else if (anyError) {
            status = ToolResult.Status.ERROR;
        } else {
            status = ToolResult.Status.PRODUCT_NOT_FOUND;
        }

        List<Long> missing = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (!STATUS_OK.equals(item.get("status"))) {
                missing.add(((Number) item.get("productId")).longValue());
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "comparison");
        payload.put("items", items);
        payload.put("missingProductIds", missing);
        payload.put("note", NOTE);

        return ToolResult.failure(NAME, status, payload,
                List.of("比较 " + productIds.size() + " 件商品，其中 " + missing.size() + " 件不可用"),
                anyOk ? null : "STOREFRONT_UNAVAILABLE",
                anyOk ? null : "商品数据暂时无法获取");
    }

    private static Map<String, Object> statusOnly(long productId, String status) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("status", status);
        return item;
    }

    private static Map<String, Object> comparisonItem(ProductDetailResponse detail) {
        int availableStock = detail.availableStock();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", detail.id());
        item.put("status", STATUS_OK);
        item.put("name", detail.name() == null ? "" : detail.name());
        AgentTool.putIfNotNull(item, "pic", detail.pic());
        AgentTool.putIfNotNull(item, "price", AgentTool.formatMoney(detail.price()));
        AgentTool.putIfNotNull(item, "subtitle", detail.subTitle());
        AgentTool.putIfNotNull(item, "brandName", detail.brandName());
        AgentTool.putIfNotNull(item, "categoryName", detail.productCategoryName());
        item.put("availableStock", availableStock);
        item.put("stockStatus", AgentTool.stockStatusFor(availableStock));
        item.put("skuCount", detail.skuStocks().size());
        AgentTool.putIfNotNull(item, "cheapestSkuPrice", cheapestSkuPrice(detail));

        List<Map<String, Object>> attributes = new ArrayList<>();
        for (ProductAttribute attribute : detail.attributes()) {
            if (attributes.size() >= MAX_COMPARE_ATTRIBUTES) {
                break;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", attribute.name() == null ? "" : attribute.name());
            entry.put("value", attribute.value() == null ? "" : attribute.value());
            attributes.add(entry);
        }
        item.put("attributes", attributes);
        return item;
    }

    /** 最低 SKU 价格；没有任何 SKU 价格时回退商品价（对齐 Python {@code _cheapest_sku_price}）。 */
    private static String cheapestSkuPrice(ProductDetailResponse detail) {
        BigDecimal cheapest = null;
        for (SkuStock sku : detail.skuStocks()) {
            BigDecimal price = sku.price();
            if (price != null && (cheapest == null || price.compareTo(cheapest) < 0)) {
                cheapest = price;
            }
        }
        return cheapest == null
                ? AgentTool.formatMoney(detail.price())
                : AgentTool.formatMoney(cheapest);
    }
}

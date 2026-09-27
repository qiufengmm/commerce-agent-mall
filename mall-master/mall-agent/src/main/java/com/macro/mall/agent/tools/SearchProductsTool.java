package com.macro.mall.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.storefront.PortalException;
import com.macro.mall.agent.storefront.dto.ProductSearchResponse;
import com.macro.mall.agent.storefront.dto.ProductSummary;

/**
 * {@code searchProducts}：按关键词、品牌或分类搜索在售商品。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.tools.product_tools.search_products}：
 * 分页大小由门户客户端固定为 5；候选商品只来自本轮门户搜索结果，最多返回 5 件；
 * 模型无法提供名称、价格、图片或库存。
 */
public final class SearchProductsTool implements AgentTool {

    public static final String NAME = "searchProducts";

    /** 单次搜索最多返回的候选商品数（与门户固定页大小一致）。 */
    static final int MAX_PRODUCTS = 5;
    static final int MAX_KEYWORD_CHARS = 100;

    private static final String NOTE =
            "候选商品来自门户当前搜索结果；预算倾向只用于排序和解释，不代表数据库级价格区间检索。";

    private static final List<String> FIELDS =
            List.of("keyword", "brandId", "productCategoryId", "sort", "pageNum");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按关键词、品牌或分类搜索在售商品，返回最多 5 件候选商品摘要。"
                + "预算倾向只用于排序和解释，不代表数据库级价格区间检索。";
    }

    @Override
    public boolean requiresMember() {
        return false;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("keyword", AgentTool.nullableStringProperty(MAX_KEYWORD_CHARS));
        properties.put("brandId", AgentTool.nullableIntegerProperty(1));
        properties.put("productCategoryId", AgentTool.nullableIntegerProperty(1));
        properties.put("sort", AgentTool.integerProperty(0, 4));
        properties.put("pageNum", AgentTool.integerProperty(1, 20));
        return AgentTool.objectSchema(properties, List.of());
    }

    @Override
    public ToolResult invoke(JsonNode arguments, ToolContext context) {
        AgentTool.requireOnlyKnownFields(arguments, FIELDS);
        String keyword = AgentTool.optionalText(arguments, "keyword", MAX_KEYWORD_CHARS);
        Integer brandId = AgentTool.nullableInt(arguments, "brandId", 1, Integer.MAX_VALUE);
        Integer productCategoryId =
                AgentTool.nullableInt(arguments, "productCategoryId", 1, Integer.MAX_VALUE);
        int sort = AgentTool.intOrDefault(arguments, "sort", 0, 0, 4);
        int pageNum = AgentTool.intOrDefault(arguments, "pageNum", 1, 1, 20);

        ProductSearchResponse page;
        try {
            page = context.backend().searchProducts(
                    new MallPortalClient.SearchParams(keyword, brandId, productCategoryId, sort, pageNum));
        } catch (PortalException exception) {
            return AgentTool.portalFailureResult(NAME, exception);
        }

        List<Map<String, Object>> products = new ArrayList<>();
        for (ProductSummary item : page.list()) {
            if (products.size() >= MAX_PRODUCTS) {
                break;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", item.id());
            entry.put("name", item.name() == null ? "" : item.name());
            AgentTool.putIfNotNull(entry, "pic", item.pic());
            AgentTool.putIfNotNull(entry, "price", AgentTool.formatMoney(item.price()));
            AgentTool.putIfNotNull(entry, "subtitle", item.subTitle());
            AgentTool.putIfNotNull(entry, "brandName", item.brandName());
            AgentTool.putIfNotNull(entry, "categoryName", item.productCategoryName());
            AgentTool.putIfNotNull(entry, "sale", item.sale());
            AgentTool.putIfNotNull(entry, "stock", item.stock());
            products.add(entry);
        }

        Map<String, Object> conditions = new LinkedHashMap<>();
        AgentTool.putIfNotNull(conditions, "keyword", keyword);
        AgentTool.putIfNotNull(conditions, "brandId", brandId);
        AgentTool.putIfNotNull(conditions, "productCategoryId", productCategoryId);
        conditions.put("sort", sort);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "search");
        payload.put("conditions", conditions);
        payload.put("pageNum", page.pageNum());
        payload.put("total", page.total());
        payload.put("returnedCount", products.size());
        payload.put("maxProductsPerSearch", MAX_PRODUCTS);
        payload.put("products", products);
        payload.put("note", NOTE);

        return ToolResult.ok(NAME, payload,
                List.of("搜索返回 " + products.size() + " 件候选商品（共 " + page.total() + " 件）"));
    }
}

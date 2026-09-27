package com.macro.mall.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.macro.mall.agent.storefront.PortalException;
import com.macro.mall.agent.storefront.dto.ProductAttribute;
import com.macro.mall.agent.storefront.dto.ProductCouponResponse;
import com.macro.mall.agent.storefront.dto.ProductDetailResponse;
import com.macro.mall.agent.storefront.dto.SkuStock;

/**
 * {@code getProductDetail}：按商品 ID 获取详情、SKU 可售库存、属性与公开优惠券。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.tools.product_tools.get_product_detail}：
 * 可售库存使用门户派生的全部 SKU 汇总值；已下架或已删除商品按未找到处理；
 * 最多返回 10 个 SKU 与 5 张公开券；事实全部来自门户，模型无法覆盖。
 */
public final class GetProductDetailTool implements AgentTool {

    public static final String NAME = "getProductDetail";

    static final int MAX_DETAIL_SKUS = 10;
    static final int MAX_DETAIL_PUBLIC_COUPONS = 5;

    private static final String REASON_NOT_FOUND = "未找到该商品或商品已下架";
    private static final String REASON_OFF_SHELF = "该商品已下架，无法提供详情";

    private static final List<String> FIELDS = List.of("productId");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按商品 ID 获取商品详情、SKU 可售库存、属性和公开优惠券。ID 必须是正整数。";
    }

    @Override
    public boolean requiresMember() {
        return false;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("productId", AgentTool.longIdProperty());
        return AgentTool.objectSchema(properties, List.of("productId"));
    }

    @Override
    public ToolResult invoke(JsonNode arguments, ToolContext context) {
        AgentTool.requireOnlyKnownFields(arguments, FIELDS);
        long productId = AgentTool.requiredPositiveLong(arguments, "productId");

        ProductDetailResponse detail;
        try {
            detail = context.backend().getProductDetail(productId);
        } catch (PortalException exception) {
            if (exception.kind() == PortalException.Kind.NOT_FOUND) {
                return AgentTool.productNotFoundResult(NAME, productId, REASON_NOT_FOUND);
            }
            return AgentTool.portalFailureResult(NAME, exception);
        }

        if (AgentTool.isUnavailable(detail.publishStatus(), detail.deleteStatus())) {
            return AgentTool.productNotFoundResult(NAME, productId, REASON_OFF_SHELF);
        }

        int availableStock = detail.availableStock();
        String stockStatus = AgentTool.stockStatusFor(availableStock);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", detail.id());
        summary.put("name", detail.name() == null ? "" : detail.name());
        AgentTool.putIfNotNull(summary, "pic", detail.pic());
        AgentTool.putIfNotNull(summary, "price", AgentTool.formatMoney(detail.price()));
        AgentTool.putIfNotNull(summary, "subtitle", detail.subTitle());
        AgentTool.putIfNotNull(summary, "brandName", detail.brandName());
        AgentTool.putIfNotNull(summary, "categoryName", detail.productCategoryName());
        AgentTool.putIfNotNull(summary, "sale", detail.sale());
        summary.put("availableStock", availableStock);
        summary.put("stockStatus", stockStatus);

        List<Map<String, Object>> skuStocks = new ArrayList<>();
        for (SkuStock sku : detail.skuStocks()) {
            if (skuStocks.size() >= MAX_DETAIL_SKUS) {
                break;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", sku.id());
            AgentTool.putIfNotNull(entry, "skuCode", sku.skuCode());
            AgentTool.putIfNotNull(entry, "price", AgentTool.formatMoney(sku.price()));
            entry.put("availableStock", sku.availableStock());
            // 对齐 Python _compact：spData 缺失（null）时省略该键，而不是输出 null
            AgentTool.putIfNotNull(entry, "spData", sku.spData());
            skuStocks.add(entry);
        }

        List<Map<String, Object>> attributes = new ArrayList<>();
        for (ProductAttribute attribute : detail.attributes()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", attribute.name() == null ? "" : attribute.name());
            entry.put("value", attribute.value() == null ? "" : attribute.value());
            attributes.add(entry);
        }

        List<Map<String, Object>> publicCoupons = new ArrayList<>();
        for (ProductCouponResponse coupon : detail.publicCoupons()) {
            if (publicCoupons.size() >= MAX_DETAIL_PUBLIC_COUPONS) {
                break;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", coupon.id());
            entry.put("name", coupon.name());
            AgentTool.putIfNotNull(entry, "amount", AgentTool.formatMoney(coupon.amount()));
            AgentTool.putIfNotNull(entry, "minPoint", AgentTool.formatMoney(coupon.minPoint()));
            AgentTool.putIfNotNull(entry, "useType", coupon.useType());
            AgentTool.putIfNotNull(entry, "endTime", AgentTool.isoPython(coupon.endTime()));
            publicCoupons.add(entry);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "detail");
        payload.put("product", summary);
        payload.put("description", detail.description());
        payload.put("productSn", detail.productSn());
        payload.put("availableStock", availableStock);
        payload.put("stockStatus", stockStatus);
        payload.put("skuCount", detail.skuStocks().size());
        payload.put("skuStocks", skuStocks);
        payload.put("attributes", attributes);
        payload.put("publicCoupons", publicCoupons);

        return ToolResult.ok(NAME, payload, List.of(
                "商品 " + detail.id() + " 可售库存 " + availableStock + "，库存状态 " + stockStatus,
                "商品 " + detail.id() + " 共 " + detail.skuStocks().size() + " 个 SKU"));
    }
}

package com.macro.mall.agent.storefront.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code /product/detail/{id}} 的领域结果。
 *
 * <p>{@code skuStocks}、{@code attributes}、{@code publicCoupons} 来自门户同一响应里的
 * {@code skuStockList}、{@code productAttributeList}/{@code productAttributeValueList}、
 * {@code couponList}，已在解析阶段完成过滤与派生。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductDetailResponse(
        long id,
        String name,
        String pic,
        BigDecimal price,
        String subTitle,
        String description,
        Long brandId,
        String brandName,
        Long productCategoryId,
        String productCategoryName,
        String productSn,
        Integer sale,
        Integer stock,
        Integer publishStatus,
        Integer deleteStatus,
        List<SkuStock> skuStocks,
        List<ProductAttribute> attributes,
        List<ProductCouponResponse> publicCoupons) {

    /**
     * 可售库存总量：优先对全部可解析 SKU 求和；没有可解析 SKU 时才回退 {@code max(product.stock, 0)}。
     */
    public int availableStock() {
        if (skuStocks != null && !skuStocks.isEmpty()) {
            int total = 0;
            for (SkuStock sku : skuStocks) {
                total += sku.availableStock();
            }
            return total;
        }
        return Math.max(stock == null ? 0 : stock, 0);
    }
}

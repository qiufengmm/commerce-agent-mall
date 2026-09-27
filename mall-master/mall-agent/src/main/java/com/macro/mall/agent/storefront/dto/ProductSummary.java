package com.macro.mall.agent.storefront.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code /product/search} 结果中的商品摘要。
 *
 * <p>门户字段全部为 camelCase，与门户 JSON 同名；未知字段一律忽略，与全局严格 Jackson 策略隔离。
 * 金额使用 {@link BigDecimal}，禁止用 {@code double}。字段缺失时保持 {@code null}（{@code name} 除外），
 * 以容忍 ES / MySQL 两条链路返回结构不一致。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductSummary(
        long id,
        String name,
        String pic,
        BigDecimal price,
        String subTitle,
        Long brandId,
        String brandName,
        Long productCategoryId,
        String productCategoryName,
        String productSn,
        String keywords,
        Integer sale,
        Integer stock,
        Integer publishStatus) {
}

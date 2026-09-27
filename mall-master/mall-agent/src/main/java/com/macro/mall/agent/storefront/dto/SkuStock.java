package com.macro.mall.agent.storefront.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 商品 SKU 库存。
 *
 * <p>可售库存是派生事实：{@code max(stock - lockStock, 0)}，不作为门户字段直接读取。
 *
 * <p>{@code spData} 是门户规格 JSON 的原始文本（对齐 Python {@code SkuStock.sp_data}），
 * 只做透传不解析；缺失时为 {@code null}，由详情工具按 {@code _compact} 语义省略该键。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkuStock(
        long id,
        String skuCode,
        BigDecimal price,
        Integer stock,
        Integer lockStock,
        Integer sale,
        String spData) {

    /** 可售库存；缺失值按 0 处理，且永不为负。 */
    public int availableStock() {
        int available = (stock == null ? 0 : stock) - (lockStock == null ? 0 : lockStock);
        return Math.max(available, 0);
    }
}

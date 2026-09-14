package com.macro.mall.portal.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 商品详情页直接购买参数。
 */
@Data
public class DirectBuyParam {
    @Schema(title = "商品ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long productId;
    @Schema(title = "商品SKU ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long productSkuId;
    @Schema(title = "购买数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer quantity;
}

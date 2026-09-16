package com.macro.mall.portal.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 提交商品评价时传入的参数
 */
@Data
public class PmsCommentParam {
    @Schema(title = "所属订单id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long orderId;

    @Schema(title = "订单明细id，一条订单明细只允许评价一次", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long orderItemId;

    @Schema(title = "评价星数：1->5")
    private Integer star;

    @Schema(title = "评价内容")
    private String content;

    @Schema(title = "评价图片地址，以逗号隔开")
    private String pics;
}

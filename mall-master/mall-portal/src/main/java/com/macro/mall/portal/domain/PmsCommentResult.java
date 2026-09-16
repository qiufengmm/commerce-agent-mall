package com.macro.mall.portal.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 评价列表展示结果，在评价信息基础上补充商品图片，便于移动端展示
 */
@Data
public class PmsCommentResult {
    @Schema(title = "评价id")
    private Long id;

    @Schema(title = "商品id")
    private Long productId;

    @Schema(title = "评价会员id")
    private Long memberId;

    @Schema(title = "所属订单id")
    private Long orderId;

    @Schema(title = "订单明细id")
    private Long orderItemId;

    @Schema(title = "商品名称")
    private String productName;

    @Schema(title = "商品图片")
    private String productPic;

    @Schema(title = "购买时的商品属性")
    private String productAttribute;

    @Schema(title = "评价会员昵称")
    private String memberNickName;

    @Schema(title = "评价用户头像")
    private String memberIcon;

    @Schema(title = "评价星数：0->5")
    private Integer star;

    @Schema(title = "评价内容")
    private String content;

    @Schema(title = "评价图片地址，以逗号隔开")
    private String pics;

    @Schema(title = "评价时间")
    private java.util.Date createTime;

    @Schema(title = "展示状态：0->未展示；1->展示")
    private Integer showStatus;
}

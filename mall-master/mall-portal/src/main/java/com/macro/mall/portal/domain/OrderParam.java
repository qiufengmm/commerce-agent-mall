package com.macro.mall.portal.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 生成订单时传入的参数
 */
@Data
@EqualsAndHashCode
public class OrderParam {
    @Schema(title = "收货地址ID")
    private Long memberReceiveAddressId;
    @Schema(title = "优惠券ID")
    private Long couponId;
    @Schema(title = "使用的积分数")
    private Integer useIntegration;
    @Schema(title = "支付方式")
    private Integer payType;
    @Schema(title = "被选中的购物车商品ID")
    private List<Long> cartIds;
    @Schema(title = "商品详情页直接购买参数")
    private DirectBuyParam directBuy;

    /**
     * 直购订单不依赖购物车记录。
     */
    public boolean isDirectBuy() {
        return directBuy != null;
    }
}

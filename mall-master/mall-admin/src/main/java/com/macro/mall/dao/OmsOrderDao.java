package com.macro.mall.dao;

import com.macro.mall.dto.OmsOrderDetail;
import com.macro.mall.dto.OmsOrderQueryParam;
import com.macro.mall.model.OmsOrder;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单查询自定义Dao
 * <p>
 * 订单状态与库存变更语句必须带前置条件并返回影响行数，调用方以影响行数判断操作是否真正生效。
 */
public interface OmsOrderDao {
    /**
     * 条件查询订单
     */
    List<OmsOrder> getList(@Param("queryParam") OmsOrderQueryParam queryParam);

    /**
     * 单个订单发货：只有待发货订单会被更新为已发货
     *
     * @return 影响行数，仅当返回 1 时表示本次真正完成了发货
     */
    int deliverOne(@Param("orderId") Long orderId,
                   @Param("deliveryCompany") String deliveryCompany,
                   @Param("deliverySn") String deliverySn);

    /**
     * 关闭订单：只有待付款订单会被关闭
     *
     * @return 影响行数，仅当返回 1 时表示本次真正完成了关闭
     */
    int closeOrderIfUnpaid(@Param("orderId") Long orderId);

    /**
     * 释放订单锁定库存，锁定库存不足时不会执行更新，避免 lock_stock 变成负数
     *
     * @return 影响行数，0 表示锁定库存数据已不一致
     */
    int releaseSkuStockLock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    /**
     * 返还优惠券：只返还绑定在当前订单上的已使用记录，重复返还不会重复命中
     *
     * @return 影响行数，0 表示当前订单没有可返还的优惠券记录
     */
    int returnCoupon(@Param("orderId") Long orderId, @Param("memberId") Long memberId);

    /**
     * 返还订单使用积分
     *
     * @return 影响行数，0 表示会员不存在
     */
    int refundIntegration(@Param("memberId") Long memberId, @Param("integration") Integer integration);

    /**
     * 获取订单详情
     */
    OmsOrderDetail getDetail(@Param("id") Long id);
}

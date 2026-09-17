package com.macro.mall.portal.dao;

import com.macro.mall.portal.domain.OmsOrderDetail;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 前台订单管理自定义Dao
 * <p>
 * 所有状态与库存变更语句都必须带前置条件并返回影响行数，调用方以影响行数判断本次操作是否真正生效。
 */
public interface PortalOrderDao {
    /**
     * 获取订单及下单商品详情
     */
    OmsOrderDetail getDetail(@Param("orderId") Long orderId);

    /**
     * 获取超时订单
     *
     * @param minute 超时时间（分）
     */
    List<OmsOrderDetail> getTimeOutOrders(@Param("minute") Integer minute);

    /**
     * 待付款订单在满足原状态条件时转为待发货，并写入支付方式与支付时间。
     *
     * @return 影响行数，仅当返回 1 时表示本次调用真正完成了支付状态转换
     */
    int payOrderIfUnpaid(@Param("orderId") Long orderId, @Param("payType") Integer payType);

    /**
     * 待付款订单在满足原状态条件时转为已关闭。
     *
     * @return 影响行数，仅当返回 1 时表示本次调用真正完成了关闭状态转换
     */
    int closeOrderIfUnpaid(@Param("orderId") Long orderId);

    /**
     * 已发货订单在满足原状态条件时转为已完成，并写入收货时间。
     *
     * @return 影响行数，仅当返回 1 时表示本次调用真正完成了确认收货
     */
    int confirmReceiveIfDelivered(@Param("orderId") Long orderId);

    /**
     * 锁定库存：可用库存（stock - lock_stock）充足时才允许锁定，避免锁定库存超过真实库存。
     *
     * @return 影响行数，0 表示可用库存不足
     */
    int lockSkuStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    /**
     * 支付成功后扣减真实库存并释放对应锁定库存，两个条件同时满足才允许扣减，避免库存变成负数。
     *
     * @return 影响行数，0 表示库存数据不满足扣减条件
     */
    int deductSkuStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    /**
     * 取消订单时释放锁定库存，锁定库存不足时不会执行更新，避免 lock_stock 变成负数。
     *
     * @return 影响行数，0 表示锁定库存数据已不一致
     */
    int releaseSkuStockLock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);
}

package com.macro.mall.portal.dao;

import com.macro.mall.model.SmsCoupon;
import com.macro.mall.portal.domain.SmsCouponHistoryDetail;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会员优惠券领取记录管理自定义Dao
 */
public interface SmsCouponHistoryDao {
    /**
     * 获取优惠券历史详情
     */
    List<SmsCouponHistoryDetail> getDetailList(@Param("memberId") Long memberId);

    /**
     * 获取指定会员优惠券列表
     */
    List<SmsCoupon> getCouponList(@Param("memberId") Long memberId, @Param("useStatus") Integer useStatus);

    /**
     * 占用优惠券：仅当存在未使用记录时才置为已使用，并同时把该记录绑定到当前订单。
     * <p>
     * 绑定 order_id/order_sn 后，返还时只能按当前订单定位记录，避免同一会员多个待付款订单之间互相返还。
     *
     * @return 影响行数，0 表示优惠券不可用或已被占用
     */
    int useCoupon(@Param("memberId") Long memberId,
                  @Param("couponId") Long couponId,
                  @Param("orderId") Long orderId,
                  @Param("orderSn") String orderSn);

    /**
     * 返还优惠券：只返还绑定在当前订单上的已使用记录，重复调用不会重复返还。
     *
     * @return 影响行数，0 表示当前订单没有可返还的优惠券记录
     */
    int returnCoupon(@Param("orderId") Long orderId, @Param("memberId") Long memberId);
}

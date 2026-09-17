package com.macro.mall.portal.service;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.portal.domain.ConfirmOrderResult;
import com.macro.mall.portal.domain.DirectBuyParam;
import com.macro.mall.portal.domain.OmsOrderDetail;
import com.macro.mall.portal.domain.OrderOperationResult;
import com.macro.mall.portal.domain.OrderParam;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 前台订单管理Service
 */
public interface OmsPortalOrderService {
    /**
     * 根据用户购物车信息生成确认单信息
     */
    ConfirmOrderResult generateConfirmOrder(List<Long> cartIds);

    /**
     * 根据商品详情页选中的SKU生成直购确认单。
     */
    ConfirmOrderResult generateDirectConfirmOrder(DirectBuyParam directBuyParam);

    /**
     * 根据提交信息生成订单
     */
    @Transactional
    Map<String, Object> generateOrder(OrderParam orderParam);

    /**
     * 会员支付成功后的回调，只允许把本人待付款订单转为待发货
     */
    @Transactional
    OrderOperationResult paySuccess(Long orderId, Integer payType);

    /**
     * 自动取消超时订单，返回真正被关闭的订单数量。
     * <p>
     * 不使用类级/接口级事务：方法内部按订单逐个开启独立事务，单个订单补偿失败只回滚该订单，
     * 失败订单会在方法末尾统一抛出，不会被计入成功数量。
     */
    Integer cancelTimeOutOrder();

    /**
     * 按订单号取消订单（幂等），供延迟消息与定时任务使用
     */
    @Transactional
    OrderOperationResult cancelOrder(Long orderId);

    /**
     * 会员取消本人订单，先校验订单归属再复用幂等取消逻辑
     */
    @Transactional
    OrderOperationResult cancelMemberOrder(Long orderId);

    /**
     * 发送延迟消息取消订单。
     * <p>
     * 只允许下单流程等系统内部逻辑调用，不对客户端暴露任何入口：
     * 客户端若可指定任意 orderId，就能把他人待付款订单塞进取消队列。
     */
    void sendDelayMessageCancelOrder(Long orderId);

    /**
     * 确认收货，只允许订单所属会员把已发货订单转为已完成
     */
    OrderOperationResult confirmReceiveOrder(Long orderId);

    /**
     * 分页获取用户订单
     */
    CommonPage<OmsOrderDetail> list(Integer status, Integer pageNum, Integer pageSize);

    /**
     * 根据订单ID获取订单详情，只允许订单所属会员查看
     */
    OmsOrderDetail detail(Long orderId);

    /**
     * 用户根据订单ID删除订单
     */
    void deleteOrder(Long orderId);

    /**
     * 根据orderSn来实现的支付成功逻辑（支付宝异步通知与主动查询使用）
     */
    @Transactional
    OrderOperationResult paySuccessByOrderSn(String orderSn, Integer payType);
}

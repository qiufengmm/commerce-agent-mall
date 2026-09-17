package com.macro.mall.portal.service.impl;

import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.OmsOrderSettingMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.model.OmsOrderItemExample;
import com.macro.mall.model.OmsOrderSetting;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.component.CancelOrderSender;
import com.macro.mall.portal.controller.OmsPortalOrderController;
import com.macro.mall.portal.dao.PortalOrderDao;
import com.macro.mall.portal.dao.PortalOrderItemDao;
import com.macro.mall.portal.dao.SmsCouponHistoryDao;
import com.macro.mall.portal.domain.CartPromotionItem;
import com.macro.mall.portal.domain.OmsOrderDetail;
import com.macro.mall.portal.domain.OrderOperationResult;
import com.macro.mall.portal.domain.OrderParam;
import com.macro.mall.portal.service.OmsCartItemService;
import com.macro.mall.portal.service.UmsMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单状态一致性加固的服务层单元测试。
 * <p>
 * 覆盖支付幂等、取消幂等、补偿只执行一次、状态越级、订单归属越权和库存不足回滚等场景。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OmsPortalOrderServiceImplTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long ORDER_ID = 100L;
    private static final Long SKU_ID = 11L;

    @Mock
    private UmsMemberService memberService;
    @Mock
    private OmsCartItemService cartItemService;
    @Mock
    private OmsOrderMapper orderMapper;
    @Mock
    private OmsOrderItemMapper orderItemMapper;
    @Mock
    private PortalOrderDao portalOrderDao;
    @Mock
    private PortalOrderItemDao orderItemDao;
    @Mock
    private SmsCouponHistoryDao couponHistoryDao;
    @Mock
    private OmsOrderSettingMapper orderSettingMapper;
    @Mock
    private CancelOrderSender cancelOrderSender;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private OmsPortalOrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        //批量方法按订单拆分独立事务，单测中同步执行回调，保持原有断言语义
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> {
                    TransactionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInTransaction(new SimpleTransactionStatus());
                });
    }

    @Test
    @DisplayName("首次支付：0->1 只扣减一次库存")
    void paySuccessFirstTimeDeductsStockOnce() {
        when(memberService.getCurrentMember()).thenReturn(member(MEMBER_ID));
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 0));
        when(portalOrderDao.payOrderIfUnpaid(ORDER_ID, 1)).thenReturn(1);
        stubOrderItems(orderItem(SKU_ID, 2));
        when(portalOrderDao.deductSkuStock(SKU_ID, 2)).thenReturn(1);

        OrderOperationResult result = orderService.paySuccess(ORDER_ID, 1);

        assertEquals(OrderOperationResult.SUCCESS, result);
        verify(portalOrderDao, times(1)).deductSkuStock(SKU_ID, 2);
        verify(portalOrderDao, never()).releaseSkuStockLock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("重复支付回调：状态已是待发货，安全幂等且不再扣库存")
    void paySuccessRepeatedCallbackIsIdempotent() {
        when(memberService.getCurrentMember()).thenReturn(member(MEMBER_ID));
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 1));

        OrderOperationResult result = orderService.paySuccess(ORDER_ID, 1);

        assertEquals(OrderOperationResult.IDEMPOTENT, result);
        verify(portalOrderDao, never()).payOrderIfUnpaid(anyLong(), anyInt());
        verify(portalOrderDao, never()).deductSkuStock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("已关闭订单不能再次标记支付成功")
    void paySuccessOnClosedOrderIsRejected() {
        when(memberService.getCurrentMember()).thenReturn(member(MEMBER_ID));
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 4));

        OrderOperationResult result = orderService.paySuccess(ORDER_ID, 1);

        assertEquals(OrderOperationResult.REJECTED, result);
        verify(portalOrderDao, never()).payOrderIfUnpaid(anyLong(), anyInt());
        verify(portalOrderDao, never()).deductSkuStock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("越权支付：非订单所属会员不能支付他人订单")
    void paySuccessOnOtherMemberOrderIsRejected() {
        when(memberService.getCurrentMember()).thenReturn(member(2L));
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 0));

        OrderOperationResult result = orderService.paySuccess(ORDER_ID, 1);

        assertEquals(OrderOperationResult.REJECTED, result);
        verify(portalOrderDao, never()).payOrderIfUnpaid(anyLong(), anyInt());
        verify(portalOrderDao, never()).deductSkuStock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("非法支付方式被拒绝")
    void paySuccessWithUnsupportedPayTypeIsRejected() {
        when(memberService.getCurrentMember()).thenReturn(member(MEMBER_ID));
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 0));

        assertEquals(OrderOperationResult.REJECTED, orderService.paySuccess(ORDER_ID, 99));
        assertEquals(OrderOperationResult.REJECTED, orderService.paySuccess(ORDER_ID, null));
        verify(portalOrderDao, never()).payOrderIfUnpaid(anyLong(), anyInt());
    }

    @Test
    @DisplayName("支付时库存扣减失败必须抛出业务异常，不吞掉并发冲突")
    void paySuccessWhenStockDeductFailsThrows() {
        when(memberService.getCurrentMember()).thenReturn(member(MEMBER_ID));
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 0));
        when(portalOrderDao.payOrderIfUnpaid(ORDER_ID, 1)).thenReturn(1);
        stubOrderItems(orderItem(SKU_ID, 2));
        when(portalOrderDao.deductSkuStock(SKU_ID, 2)).thenReturn(0);

        assertThrows(RuntimeException.class, () -> orderService.paySuccess(ORDER_ID, 1));
    }

    @Test
    @DisplayName("按订单号支付（支付宝通知）重复调用只扣减一次库存")
    void paySuccessByOrderSnIsIdempotent() {
        when(orderMapper.selectByExample(any())).thenReturn(Collections.singletonList(order(ORDER_ID, MEMBER_ID, 0)));
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 0));
        when(portalOrderDao.payOrderIfUnpaid(ORDER_ID, 1)).thenReturn(1);
        stubOrderItems(orderItem(SKU_ID, 1));
        when(portalOrderDao.deductSkuStock(SKU_ID, 1)).thenReturn(1);

        assertEquals(OrderOperationResult.SUCCESS, orderService.paySuccessByOrderSn("SN100", 1));

        // 第二次通知时订单已是待发货
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 1));
        assertEquals(OrderOperationResult.IDEMPOTENT, orderService.paySuccessByOrderSn("SN100", 1));
        verify(portalOrderDao, times(1)).deductSkuStock(SKU_ID, 1);
    }

    @Test
    @DisplayName("取消订单：0->4 成功后才释放库存、返还优惠券和积分")
    void cancelOrderCompensatesOnceAfterTransition() {
        OmsOrder order = order(ORDER_ID, MEMBER_ID, 0);
        order.setCouponId(55L);
        order.setUseIntegration(30);
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order);
        when(portalOrderDao.closeOrderIfUnpaid(ORDER_ID)).thenReturn(1);
        stubOrderItems(orderItem(SKU_ID, 2));
        when(portalOrderDao.releaseSkuStockLock(SKU_ID, 2)).thenReturn(1);
        //优惠券按订单返还：只返还绑定在当前订单上的记录
        when(couponHistoryDao.returnCoupon(ORDER_ID, MEMBER_ID)).thenReturn(1);
        when(memberService.refundIntegration(MEMBER_ID, 30)).thenReturn(true);

        OrderOperationResult result = orderService.cancelOrder(ORDER_ID);

        assertEquals(OrderOperationResult.SUCCESS, result);
        verify(portalOrderDao, times(1)).releaseSkuStockLock(SKU_ID, 2);
        verify(couponHistoryDao, times(1)).returnCoupon(ORDER_ID, MEMBER_ID);
        verify(memberService, times(1)).refundIntegration(MEMBER_ID, 30);
    }

    @Test
    @DisplayName("释放锁定库存失败：必须抛异常回滚订单关闭，不能只记录日志后提交已关闭状态")
    void cancelOrderWhenReleaseStockFailsThrows() {
        OmsOrder order = order(ORDER_ID, MEMBER_ID, 0);
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order);
        when(portalOrderDao.closeOrderIfUnpaid(ORDER_ID)).thenReturn(1);
        stubOrderItems(orderItem(SKU_ID, 2));
        when(portalOrderDao.releaseSkuStockLock(SKU_ID, 2)).thenReturn(0);

        assertThrows(RuntimeException.class, () -> orderService.cancelOrder(ORDER_ID));
        verify(portalOrderDao, times(1)).closeOrderIfUnpaid(ORDER_ID);
    }

    @Test
    @DisplayName("返还优惠券失败：必须抛异常回滚订单关闭")
    void cancelOrderWhenReturnCouponFailsThrows() {
        OmsOrder order = order(ORDER_ID, MEMBER_ID, 0);
        order.setCouponId(55L);
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order);
        when(portalOrderDao.closeOrderIfUnpaid(ORDER_ID)).thenReturn(1);
        stubOrderItems();
        when(couponHistoryDao.returnCoupon(ORDER_ID, MEMBER_ID)).thenReturn(0);

        assertThrows(RuntimeException.class, () -> orderService.cancelOrder(ORDER_ID));
        verify(couponHistoryDao, times(1)).returnCoupon(ORDER_ID, MEMBER_ID);
    }

    @Test
    @DisplayName("返还积分失败：必须抛异常回滚订单关闭")
    void cancelOrderWhenRefundIntegrationFailsThrows() {
        OmsOrder order = order(ORDER_ID, MEMBER_ID, 0);
        order.setUseIntegration(30);
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order);
        when(portalOrderDao.closeOrderIfUnpaid(ORDER_ID)).thenReturn(1);
        stubOrderItems();
        when(memberService.refundIntegration(MEMBER_ID, 30)).thenReturn(false);

        assertThrows(RuntimeException.class, () -> orderService.cancelOrder(ORDER_ID));
        verify(memberService, times(1)).refundIntegration(MEMBER_ID, 30);
    }

    @Test
    @DisplayName("成功取消时补偿只执行一次，重试取消不会重复补偿")
    void cancelOrderCompensatesExactlyOnceOnSuccessAndRetry() {
        OmsOrder order = order(ORDER_ID, MEMBER_ID, 0);
        order.setCouponId(55L);
        order.setUseIntegration(30);
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order);
        when(portalOrderDao.closeOrderIfUnpaid(ORDER_ID)).thenReturn(1);
        stubOrderItems(orderItem(SKU_ID, 2));
        when(portalOrderDao.releaseSkuStockLock(SKU_ID, 2)).thenReturn(1);
        when(couponHistoryDao.returnCoupon(ORDER_ID, MEMBER_ID)).thenReturn(1);
        when(memberService.refundIntegration(MEMBER_ID, 30)).thenReturn(true);

        assertEquals(OrderOperationResult.SUCCESS, orderService.cancelOrder(ORDER_ID));

        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 4));
        assertEquals(OrderOperationResult.IDEMPOTENT, orderService.cancelOrder(ORDER_ID));

        verify(portalOrderDao, times(1)).closeOrderIfUnpaid(ORDER_ID);
        verify(portalOrderDao, times(1)).releaseSkuStockLock(SKU_ID, 2);
        verify(couponHistoryDao, times(1)).returnCoupon(ORDER_ID, MEMBER_ID);
        verify(memberService, times(1)).refundIntegration(MEMBER_ID, 30);
    }

    @Test
    @DisplayName("重复取消：已关闭订单不重复补偿库存、优惠券和积分")
    void cancelOrderRepeatedDoesNotCompensateAgain() {
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 4));

        OrderOperationResult result = orderService.cancelOrder(ORDER_ID);

        assertEquals(OrderOperationResult.IDEMPOTENT, result);
        verify(portalOrderDao, never()).closeOrderIfUnpaid(anyLong());
        verify(portalOrderDao, never()).releaseSkuStockLock(anyLong(), anyInt());
        verify(couponHistoryDao, never()).returnCoupon(anyLong(), anyLong());
        verify(memberService, never()).refundIntegration(anyLong(), anyInt());
    }

    @Test
    @DisplayName("已支付订单不能被取消，也不能释放锁定库存")
    void cancelPaidOrderIsRejected() {
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 1));

        OrderOperationResult result = orderService.cancelOrder(ORDER_ID);

        assertEquals(OrderOperationResult.REJECTED, result);
        verify(portalOrderDao, never()).closeOrderIfUnpaid(anyLong());
        verify(portalOrderDao, never()).releaseSkuStockLock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("会员只能取消自己的订单")
    void cancelMemberOrderByOtherMemberIsRejected() {
        when(memberService.getCurrentMember()).thenReturn(member(2L));
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 0));

        assertEquals(OrderOperationResult.REJECTED, orderService.cancelMemberOrder(ORDER_ID));
        verify(portalOrderDao, never()).closeOrderIfUnpaid(anyLong());
    }

    @Test
    @DisplayName("越权取消：会员 A 传入会员 B 的待付款订单 ID，既不改状态也不发取消消息")
    void cancelMemberOrderByOtherMemberDoesNotSendDelayMessage() {
        when(memberService.getCurrentMember()).thenReturn(member(2L));
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 0));

        assertEquals(OrderOperationResult.REJECTED, orderService.cancelMemberOrder(ORDER_ID));

        verify(portalOrderDao, never()).closeOrderIfUnpaid(anyLong());
        verify(portalOrderDao, never()).releaseSkuStockLock(anyLong(), anyInt());
        verify(couponHistoryDao, never()).returnCoupon(anyLong(), anyLong());
        verify(memberService, never()).refundIntegration(anyLong(), anyInt());
        verify(cancelOrderSender, never()).sendMessage(anyLong(), anyLong());
    }

    @Test
    @DisplayName("旧的 /order/cancelOrder 越权入口必须被移除，延迟消息只由下单流程发送")
    void legacyCancelOrderEndpointIsRemoved() {
        boolean exists = Arrays.stream(OmsPortalOrderController.class.getDeclaredMethods())
                .anyMatch(method -> "cancelOrder".equals(method.getName()));
        assertFalse(exists, "/order/cancelOrder 允许客户端传入任意 orderId，必须从 Controller 移除");
    }

    @Test
    @DisplayName("超时取消与用户取消并发时，只有真正关闭成功的订单才补偿")
    void cancelTimeOutOrderCompensatesOnlyTransitionedOrders() {
        OmsOrderSetting setting = new OmsOrderSetting();
        setting.setNormalOrderOvertime(1);
        when(orderSettingMapper.selectByPrimaryKey(1L)).thenReturn(setting);

        OmsOrderDetail first = new OmsOrderDetail();
        first.setId(ORDER_ID);
        OmsOrderDetail second = new OmsOrderDetail();
        second.setId(200L);
        when(portalOrderDao.getTimeOutOrders(1)).thenReturn(Arrays.asList(first, second));

        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 0));
        when(portalOrderDao.closeOrderIfUnpaid(ORDER_ID)).thenReturn(1);
        stubOrderItems(orderItem(SKU_ID, 1));
        when(portalOrderDao.releaseSkuStockLock(SKU_ID, 1)).thenReturn(1);

        // 第二个订单在并发场景下已被用户取消：条件更新命中 0 行，复查状态为已关闭
        when(orderMapper.selectByPrimaryKey(200L))
                .thenReturn(order(200L, MEMBER_ID, 0))
                .thenReturn(order(200L, MEMBER_ID, 4));
        when(portalOrderDao.closeOrderIfUnpaid(200L)).thenReturn(0);

        Integer count = orderService.cancelTimeOutOrder();

        assertEquals(1, count);
        verify(portalOrderDao, times(1)).releaseSkuStockLock(SKU_ID, 1);
    }

    @Test
    @DisplayName("超时批量取消：补偿失败的订单不能被计入成功数量，也不能被静默吞掉")
    void cancelTimeOutOrderRecordsFailedOrderAndDoesNotFakeCount() {
        OmsOrderSetting setting = new OmsOrderSetting();
        setting.setNormalOrderOvertime(1);
        when(orderSettingMapper.selectByPrimaryKey(1L)).thenReturn(setting);

        OmsOrderDetail failed = new OmsOrderDetail();
        failed.setId(ORDER_ID);
        failed.setOrderSn("SN100");
        failed.setMemberId(MEMBER_ID);
        OmsOrderDetail success = new OmsOrderDetail();
        success.setId(200L);
        success.setOrderSn("SN200");
        success.setMemberId(MEMBER_ID);
        when(portalOrderDao.getTimeOutOrders(1)).thenReturn(Arrays.asList(failed, success));

        //第一个订单状态转换成功，但释放锁定库存失败
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 0));
        when(portalOrderDao.closeOrderIfUnpaid(ORDER_ID)).thenReturn(1);
        when(portalOrderDao.releaseSkuStockLock(SKU_ID, 1)).thenReturn(0);
        //第二个订单状态转换与补偿都成功
        when(orderMapper.selectByPrimaryKey(200L)).thenReturn(order(200L, MEMBER_ID, 0));
        when(portalOrderDao.closeOrderIfUnpaid(200L)).thenReturn(1);
        when(portalOrderDao.releaseSkuStockLock(SKU_ID, 2)).thenReturn(1);
        stubOrderItemsConsecutive(
                Collections.singletonList(orderItem(SKU_ID, 1)),
                Collections.singletonList(orderItem(SKU_ID, 2)));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> orderService.cancelTimeOutOrder());

        assertTrue(exception.getMessage().contains(String.valueOf(ORDER_ID)),
                "失败订单必须出现在异常信息中，实际：" + exception.getMessage());
        //失败订单不影响同一批次其它订单：第二个订单仍然完成关闭与补偿
        verify(portalOrderDao, times(1)).closeOrderIfUnpaid(200L);
        verify(portalOrderDao, times(1)).releaseSkuStockLock(SKU_ID, 2);
    }

    @Test
    @DisplayName("确认收货：只有订单所属会员可以操作，且只允许 2->3")
    void confirmReceiveOrderChecksOwnerAndStatus() {
        when(memberService.getCurrentMember()).thenReturn(member(2L));
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 2));
        assertEquals(OrderOperationResult.REJECTED, orderService.confirmReceiveOrder(ORDER_ID));

        when(memberService.getCurrentMember()).thenReturn(member(MEMBER_ID));
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 1));
        assertEquals(OrderOperationResult.REJECTED, orderService.confirmReceiveOrder(ORDER_ID));

        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 2));
        when(portalOrderDao.confirmReceiveIfDelivered(ORDER_ID)).thenReturn(1);
        assertEquals(OrderOperationResult.SUCCESS, orderService.confirmReceiveOrder(ORDER_ID));

        // 重复确认收货：已是已完成状态，幂等且不再更新
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 3));
        assertEquals(OrderOperationResult.IDEMPOTENT, orderService.confirmReceiveOrder(ORDER_ID));
        verify(portalOrderDao, times(1)).confirmReceiveIfDelivered(ORDER_ID);
    }

    @Test
    @DisplayName("订单详情只能由订单所属会员查看")
    void detailOfOtherMemberOrderIsRejected() {
        when(memberService.getCurrentMember()).thenReturn(member(2L));
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(ORDER_ID, MEMBER_ID, 0));

        assertThrows(RuntimeException.class, () -> orderService.detail(ORDER_ID));
    }

    @Test
    @DisplayName("库存不足时不能锁定库存，也不能创建订单和扣减积分")
    void generateOrderWhenLockStockFailsThrowsAndCreatesNothing() {
        when(memberService.getCurrentMember()).thenReturn(member(MEMBER_ID));
        when(cartItemService.listPromotion(eq(MEMBER_ID), anyList())).thenReturn(Collections.singletonList(cartPromotionItem()));
        when(portalOrderDao.lockSkuStock(SKU_ID, 2)).thenReturn(0);

        OrderParam orderParam = new OrderParam();
        orderParam.setMemberReceiveAddressId(1L);
        orderParam.setCartIds(Collections.singletonList(1L));

        assertThrows(RuntimeException.class, () -> orderService.generateOrder(orderParam));
        verify(orderMapper, never()).insert(any(OmsOrder.class));
        verify(orderItemDao, never()).insertList(anyList());
        verify(memberService, never()).deductIntegration(anyLong(), anyInt());
        verify(portalOrderDao, never()).deductSkuStock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("事务注解必须声明在实现类方法上，避免接口注解在代理下失效")
    void transactionalAnnotationIsDeclaredOnImplementation() throws Exception {
        Method paySuccess = OmsPortalOrderServiceImpl.class.getMethod("paySuccess", Long.class, Integer.class);
        Method cancelOrder = OmsPortalOrderServiceImpl.class.getMethod("cancelOrder", Long.class);
        Method cancelTimeOutOrder = OmsPortalOrderServiceImpl.class.getMethod("cancelTimeOutOrder");
        Method confirmReceiveOrder = OmsPortalOrderServiceImpl.class.getMethod("confirmReceiveOrder", Long.class);
        Method generateOrder = OmsPortalOrderServiceImpl.class.getMethod("generateOrder", OrderParam.class);
        Method paySuccessByOrderSn = OmsPortalOrderServiceImpl.class.getMethod("paySuccessByOrderSn", String.class, Integer.class);

        assertTransactional(paySuccess);
        assertTransactional(cancelOrder);
        assertTransactional(confirmReceiveOrder);
        assertTransactional(generateOrder);
        assertTransactional(paySuccessByOrderSn);
        //超时批量取消改为按订单拆分独立事务，不能再有方法级事务，否则独立事务会被外层事务吞并
        assertNull(cancelTimeOutOrder.getAnnotation(Transactional.class),
                "cancelTimeOutOrder 不能使用方法级事务，内部按订单逐个开启独立事务");
    }

    private void assertTransactional(Method method) {
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional, method.getName() + " 缺少实现类上的事务注解");
        assertEquals(Exception.class, transactional.rollbackFor()[0],
                method.getName() + " 未对异常回滚");
    }

    private UmsMember member(Long id) {
        UmsMember member = new UmsMember();
        member.setId(id);
        member.setUsername("member" + id);
        return member;
    }

    private OmsOrder order(Long id, Long memberId, Integer status) {
        OmsOrder order = new OmsOrder();
        order.setId(id);
        order.setOrderSn("SN" + id);
        order.setMemberId(memberId);
        order.setStatus(status);
        order.setDeleteStatus(0);
        return order;
    }

    private OmsOrderItem orderItem(Long skuId, Integer quantity) {
        OmsOrderItem orderItem = new OmsOrderItem();
        orderItem.setOrderId(ORDER_ID);
        orderItem.setProductSkuId(skuId);
        orderItem.setProductQuantity(quantity);
        return orderItem;
    }

    private void stubOrderItems(OmsOrderItem... orderItems) {
        List<OmsOrderItem> items = Arrays.asList(orderItems);
        when(orderItemMapper.selectByExample(any(OmsOrderItemExample.class))).thenReturn(items);
    }

    /**
     * 按调用顺序返回不同的订单明细，用于模拟批次内多个订单
     */
    private void stubOrderItemsConsecutive(List<OmsOrderItem> first, List<OmsOrderItem> second) {
        when(orderItemMapper.selectByExample(any(OmsOrderItemExample.class))).thenReturn(first, second);
    }

    private CartPromotionItem cartPromotionItem() {
        CartPromotionItem item = new CartPromotionItem();
        item.setProductId(1L);
        item.setProductSkuId(SKU_ID);
        item.setProductName("测试商品");
        item.setQuantity(2);
        item.setRealStock(10);
        item.setPrice(new BigDecimal("10"));
        item.setReduceAmount(BigDecimal.ZERO);
        return item;
    }
}

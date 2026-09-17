package com.macro.mall.service.impl;

import com.macro.mall.dao.OmsOrderDao;
import com.macro.mall.dao.OmsOrderOperateHistoryDao;
import com.macro.mall.dto.OmsOrderDeliveryParam;
import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.OmsOrderOperateHistoryMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.model.OmsOrderItemExample;
import com.macro.mall.model.OmsOrderOperateHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台订单发货与关闭的单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OmsOrderServiceImplTest {

    @Mock
    private OmsOrderMapper orderMapper;
    @Mock
    private OmsOrderDao orderDao;
    @Mock
    private OmsOrderOperateHistoryDao orderOperateHistoryDao;
    @Mock
    private OmsOrderOperateHistoryMapper orderOperateHistoryMapper;
    @Mock
    private OmsOrderItemMapper orderItemMapper;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private OmsOrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        //批量关闭按订单拆分独立事务，单测中同步执行回调，保持原有断言语义
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> {
                    TransactionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInTransaction(new SimpleTransactionStatus());
                });
    }

    @Test
    @DisplayName("只有待发货订单会被发货，未命中的订单不写操作历史")
    void deliveryOnlyHandlesWaitDeliverOrders() {
        when(orderDao.deliverOne(1L, "顺丰", "SF1")).thenReturn(1);
        when(orderDao.deliverOne(2L, "顺丰", "SF1")).thenReturn(0);

        int count = orderService.delivery(Arrays.asList(deliveryParam(1L), deliveryParam(2L)));

        assertEquals(1, count);
        ArgumentCaptor<List<OmsOrderOperateHistory>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(orderOperateHistoryDao, times(1)).insertList(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals(1L, captor.getValue().get(0).getOrderId());
        assertEquals(2, captor.getValue().get(0).getOrderStatus());
    }

    @Test
    @DisplayName("重复发货不重复写操作历史")
    void repeatedDeliveryDoesNotWriteHistory() {
        when(orderDao.deliverOne(1L, "顺丰", "SF1")).thenReturn(0);

        assertEquals(0, orderService.delivery(Collections.singletonList(deliveryParam(1L))));
        verify(orderOperateHistoryDao, never()).insertList(anyList());
    }

    @Test
    @DisplayName("只有待付款订单会被关闭，已支付订单跳过且不写操作历史")
    void closeOnlyHandlesUnpaidOrders() {
        when(orderMapper.selectByPrimaryKey(1L)).thenReturn(order(1L, 0));
        when(orderMapper.selectByPrimaryKey(2L)).thenReturn(order(2L, 1));
        when(orderDao.closeOrderIfUnpaid(1L)).thenReturn(1);
        when(orderItemMapper.selectByExample(any(OmsOrderItemExample.class)))
                .thenReturn(Collections.singletonList(orderItem(11L, 2)));
        when(orderDao.releaseSkuStockLock(11L, 2)).thenReturn(1);

        int count = orderService.close(Arrays.asList(1L, 2L), "超时未支付");

        assertEquals(1, count);
        verify(orderDao, never()).closeOrderIfUnpaid(2L);
        verify(orderDao, times(1)).releaseSkuStockLock(11L, 2);
        verify(orderOperateHistoryDao, times(1)).insertList(anyList());
    }

    @Test
    @DisplayName("重复关闭不重复补偿库存、优惠券和积分")
    void repeatedCloseDoesNotCompensateAgain() {
        when(orderMapper.selectByPrimaryKey(1L)).thenReturn(order(1L, 4));

        assertEquals(0, orderService.close(Collections.singletonList(1L), "重复关闭"));
        verify(orderDao, never()).closeOrderIfUnpaid(anyLong());
        verify(orderDao, never()).releaseSkuStockLock(anyLong(), anyInt());
        verify(orderDao, never()).returnCoupon(anyLong(), anyLong());
        verify(orderDao, never()).refundIntegration(anyLong(), anyInt());
        verify(orderOperateHistoryDao, never()).insertList(anyList());
    }

    @Test
    @DisplayName("关闭待付款订单时释放锁定库存，并按订单返还优惠券与积分")
    void closeCompensatesStockCouponAndIntegration() {
        OmsOrder order = order(1L, 0);
        order.setMemberId(9L);
        order.setCouponId(55L);
        order.setUseIntegration(20);
        when(orderMapper.selectByPrimaryKey(1L)).thenReturn(order);
        when(orderDao.closeOrderIfUnpaid(1L)).thenReturn(1);
        when(orderItemMapper.selectByExample(any(OmsOrderItemExample.class)))
                .thenReturn(Collections.singletonList(orderItem(11L, 2)));
        when(orderDao.releaseSkuStockLock(11L, 2)).thenReturn(1);
        //优惠券按订单返还：只返还绑定在当前订单 1L 上的记录
        when(orderDao.returnCoupon(1L, 9L)).thenReturn(1);
        when(orderDao.refundIntegration(9L, 20)).thenReturn(1);

        assertEquals(1, orderService.close(Collections.singletonList(1L), "关闭"));

        verify(orderDao, times(1)).releaseSkuStockLock(11L, 2);
        verify(orderDao, times(1)).returnCoupon(1L, 9L);
        verify(orderDao, times(1)).refundIntegration(9L, 20);
    }

    @Test
    @DisplayName("释放锁定库存失败：关闭订单必须抛异常，不能只记录日志后提交已关闭状态")
    void closeWhenReleaseStockFailsThrows() {
        when(orderMapper.selectByPrimaryKey(1L)).thenReturn(order(1L, 0));
        when(orderDao.closeOrderIfUnpaid(1L)).thenReturn(1);
        when(orderItemMapper.selectByExample(any(OmsOrderItemExample.class)))
                .thenReturn(Collections.singletonList(orderItem(11L, 2)));
        when(orderDao.releaseSkuStockLock(11L, 2)).thenReturn(0);

        assertThrows(RuntimeException.class, () -> orderService.close(Collections.singletonList(1L), "关闭"));
        verify(orderDao, times(1)).closeOrderIfUnpaid(1L);
        verify(orderOperateHistoryDao, never()).insertList(anyList());
    }

    @Test
    @DisplayName("返还优惠券失败：关闭订单必须抛异常")
    void closeWhenReturnCouponFailsThrows() {
        OmsOrder order = order(1L, 0);
        order.setMemberId(9L);
        order.setCouponId(55L);
        when(orderMapper.selectByPrimaryKey(1L)).thenReturn(order);
        when(orderDao.closeOrderIfUnpaid(1L)).thenReturn(1);
        when(orderItemMapper.selectByExample(any(OmsOrderItemExample.class))).thenReturn(Collections.emptyList());
        when(orderDao.returnCoupon(1L, 9L)).thenReturn(0);

        assertThrows(RuntimeException.class, () -> orderService.close(Collections.singletonList(1L), "关闭"));
        verify(orderDao, times(1)).returnCoupon(1L, 9L);
    }

    @Test
    @DisplayName("返还积分失败：关闭订单必须抛异常")
    void closeWhenRefundIntegrationFailsThrows() {
        OmsOrder order = order(1L, 0);
        order.setMemberId(9L);
        order.setUseIntegration(20);
        when(orderMapper.selectByPrimaryKey(1L)).thenReturn(order);
        when(orderDao.closeOrderIfUnpaid(1L)).thenReturn(1);
        when(orderItemMapper.selectByExample(any(OmsOrderItemExample.class))).thenReturn(Collections.emptyList());
        when(orderDao.refundIntegration(9L, 20)).thenReturn(0);

        assertThrows(RuntimeException.class, () -> orderService.close(Collections.singletonList(1L), "关闭"));
        verify(orderDao, times(1)).refundIntegration(9L, 20);
    }

    @Test
    @DisplayName("批量关闭：补偿失败的订单不能被计入成功数量，也不能被静默吞掉")
    void closeRecordsFailedOrdersAndDoesNotFakeCount() {
        when(orderMapper.selectByPrimaryKey(1L)).thenReturn(order(1L, 0));
        when(orderMapper.selectByPrimaryKey(2L)).thenReturn(order(2L, 0));
        when(orderDao.closeOrderIfUnpaid(1L)).thenReturn(1);
        when(orderDao.closeOrderIfUnpaid(2L)).thenReturn(1);
        //第一个订单释放锁定库存失败，第二个订单成功
        when(orderDao.releaseSkuStockLock(11L, 1)).thenReturn(0);
        when(orderDao.releaseSkuStockLock(11L, 2)).thenReturn(1);
        when(orderItemMapper.selectByExample(any(OmsOrderItemExample.class)))
                .thenReturn(Collections.singletonList(orderItem(11L, 1)),
                        Collections.singletonList(orderItem(11L, 2)));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> orderService.close(Arrays.asList(1L, 2L), "关闭"));

        assertTrue(exception.getMessage().contains("1"), "失败订单必须出现在异常信息中，实际：" + exception.getMessage());
        //失败订单不影响同一批次其它订单
        verify(orderDao, times(1)).closeOrderIfUnpaid(2L);
        verify(orderDao, times(1)).releaseSkuStockLock(11L, 2);
        //只有成功的订单写操作历史
        verify(orderOperateHistoryDao, times(1)).insertList(anyList());
    }

    @Test
    @DisplayName("事务注解必须声明在实现类方法上，避免接口注解在代理下失效")
    void transactionalAnnotationIsDeclaredOnImplementation() throws Exception {
        Method delivery = OmsOrderServiceImpl.class.getMethod("delivery", List.class);
        Method close = OmsOrderServiceImpl.class.getMethod("close", List.class, String.class);

        Transactional deliveryTransactional = delivery.getAnnotation(Transactional.class);
        assertNotNull(deliveryTransactional);
        assertEquals(Exception.class, deliveryTransactional.rollbackFor()[0]);
        //批量关闭改为按订单拆分独立事务，不能再有方法级事务，否则独立事务会被外层事务吞并
        assertNull(close.getAnnotation(Transactional.class),
                "close 不能使用方法级事务，内部按订单逐个开启独立事务");
    }

    private OmsOrderDeliveryParam deliveryParam(Long orderId) {
        OmsOrderDeliveryParam param = new OmsOrderDeliveryParam();
        param.setOrderId(orderId);
        param.setDeliveryCompany("顺丰");
        param.setDeliverySn("SF1");
        return param;
    }

    private OmsOrder order(Long id, Integer status) {
        OmsOrder order = new OmsOrder();
        order.setId(id);
        order.setMemberId(1L);
        order.setStatus(status);
        order.setDeleteStatus(0);
        return order;
    }

    private OmsOrderItem orderItem(Long skuId, Integer quantity) {
        OmsOrderItem orderItem = new OmsOrderItem();
        orderItem.setProductSkuId(skuId);
        orderItem.setProductQuantity(quantity);
        return orderItem;
    }
}

package com.macro.mall.portal.controller;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.api.ResultCode;
import com.macro.mall.portal.domain.ConfirmOrderResult;
import com.macro.mall.portal.domain.DirectBuyParam;
import com.macro.mall.portal.domain.OmsOrderDetail;
import com.macro.mall.portal.domain.OrderOperationResult;
import com.macro.mall.portal.domain.OrderParam;
import com.macro.mall.portal.service.OmsPortalOrderService;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单接口控制器测试。
 * <p>
 * 使用 standalone MockMvc，不启动 Spring 容器，不连接 MySQL、Redis 或 RabbitMQ。
 * 重点验证支付、取消、确认收货的 SUCCESS/IDEMPOTENT/NOT_FOUND/REJECTED 映射，
 * 以及订单列表分页参数的默认值绑定。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OmsPortalOrderControllerTest {

    private static final Long ORDER_ID = 100L;

    @Mock
    private OmsPortalOrderService portalOrderService;
    @InjectMocks
    private OmsPortalOrderController portalOrderController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(portalOrderController).build();
    }

    @Test
    @DisplayName("生成确认单：购物车ID数组正确绑定")
    void generateConfirmOrderBindsCartIds() throws Exception {
        when(portalOrderService.generateConfirmOrder(anyList())).thenReturn(new ConfirmOrderResult());

        mockMvc.perform(post("/order/generateConfirmOrder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1,2]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()));

        verify(portalOrderService).generateConfirmOrder(eq(Arrays.asList(1L, 2L)));
    }

    @Test
    @DisplayName("直购确认单：商品、SKU与数量正确绑定")
    void generateDirectConfirmOrderBindsParam() throws Exception {
        when(portalOrderService.generateDirectConfirmOrder(any(DirectBuyParam.class))).thenReturn(new ConfirmOrderResult());

        mockMvc.perform(post("/order/generateDirectConfirmOrder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":11,\"productSkuId\":22,\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()));

        ArgumentCaptor<DirectBuyParam> captor = ArgumentCaptor.forClass(DirectBuyParam.class);
        verify(portalOrderService).generateDirectConfirmOrder(captor.capture());
        assertEquals(11L, captor.getValue().getProductId());
        assertEquals(22L, captor.getValue().getProductSkuId());
        assertEquals(3, captor.getValue().getQuantity());
    }

    @Test
    @DisplayName("生成订单：下单成功时返回下单成功提示与订单数据")
    void generateOrderReturnsOrderResult() throws Exception {
        Map<String, Object> orderResult = new HashMap<>();
        orderResult.put("orderId", ORDER_ID);
        orderResult.put("orderSn", "SN100");
        when(portalOrderService.generateOrder(any(OrderParam.class))).thenReturn(orderResult);

        mockMvc.perform(post("/order/generateOrder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberReceiveAddressId\":1,\"payType\":1,\"cartIds\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.message").value("下单成功"))
                .andExpect(jsonPath("$.data.orderSn").value("SN100"));

        ArgumentCaptor<OrderParam> captor = ArgumentCaptor.forClass(OrderParam.class);
        verify(portalOrderService).generateOrder(captor.capture());
        assertEquals(1L, captor.getValue().getMemberReceiveAddressId());
        assertEquals(1, captor.getValue().getPayType());
        assertEquals(Collections.singletonList(1L), captor.getValue().getCartIds());
    }

    @Test
    @DisplayName("支付成功：SUCCESS 映射为支付成功")
    void paySuccessMapsSuccess() throws Exception {
        when(portalOrderService.paySuccess(ORDER_ID, 1)).thenReturn(OrderOperationResult.SUCCESS);

        mockMvc.perform(post("/order/paySuccess").param("orderId", "100").param("payType", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.message").value("支付成功"));
    }

    @Test
    @DisplayName("支付成功：IDEMPOTENT 映射为已处理，不重复扣库存")
    void paySuccessMapsIdempotent() throws Exception {
        when(portalOrderService.paySuccess(ORDER_ID, 1)).thenReturn(OrderOperationResult.IDEMPOTENT);

        mockMvc.perform(post("/order/paySuccess").param("orderId", "100").param("payType", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.message").value("订单已处理，无需重复操作"));
    }

    @Test
    @DisplayName("支付成功：NOT_FOUND 映射为订单不存在或无权支付")
    void paySuccessMapsNotFound() throws Exception {
        when(portalOrderService.paySuccess(ORDER_ID, 1)).thenReturn(OrderOperationResult.NOT_FOUND);

        mockMvc.perform(post("/order/paySuccess").param("orderId", "100").param("payType", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FAILED.getCode()))
                .andExpect(jsonPath("$.message").value("订单不存在或无权支付"));
    }

    @Test
    @DisplayName("支付成功：REJECTED 映射为状态不允许支付")
    void paySuccessMapsRejected() throws Exception {
        when(portalOrderService.paySuccess(ORDER_ID, 99)).thenReturn(OrderOperationResult.REJECTED);

        mockMvc.perform(post("/order/paySuccess").param("orderId", "100").param("payType", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FAILED.getCode()))
                .andExpect(jsonPath("$.message").value("订单当前状态不允许支付，或支付方式不合法"));
    }

    @Test
    @DisplayName("取消订单：SUCCESS/IDEMPOTENT/NOT_FOUND/REJECTED 四种映射")
    void cancelUserOrderMapsAllResults() throws Exception {
        when(portalOrderService.cancelMemberOrder(ORDER_ID)).thenReturn(OrderOperationResult.SUCCESS);
        mockMvc.perform(post("/order/cancelUserOrder").param("orderId", "100"))
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.message").value("取消成功"));

        when(portalOrderService.cancelMemberOrder(ORDER_ID)).thenReturn(OrderOperationResult.IDEMPOTENT);
        mockMvc.perform(post("/order/cancelUserOrder").param("orderId", "100"))
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.message").value("订单已处理，无需重复操作"));

        when(portalOrderService.cancelMemberOrder(ORDER_ID)).thenReturn(OrderOperationResult.NOT_FOUND);
        mockMvc.perform(post("/order/cancelUserOrder").param("orderId", "100"))
                .andExpect(jsonPath("$.code").value(ResultCode.FAILED.getCode()))
                .andExpect(jsonPath("$.message").value("订单不存在或无权取消"));

        when(portalOrderService.cancelMemberOrder(ORDER_ID)).thenReturn(OrderOperationResult.REJECTED);
        mockMvc.perform(post("/order/cancelUserOrder").param("orderId", "100"))
                .andExpect(jsonPath("$.code").value(ResultCode.FAILED.getCode()))
                .andExpect(jsonPath("$.message").value("订单当前状态不允许取消"));
    }

    @Test
    @DisplayName("确认收货：SUCCESS/NOT_FOUND/REJECTED 三种映射")
    void confirmReceiveOrderMapsResults() throws Exception {
        when(portalOrderService.confirmReceiveOrder(ORDER_ID)).thenReturn(OrderOperationResult.SUCCESS);
        mockMvc.perform(post("/order/confirmReceiveOrder").param("orderId", "100"))
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.message").value("确认收货成功"));

        when(portalOrderService.confirmReceiveOrder(ORDER_ID)).thenReturn(OrderOperationResult.NOT_FOUND);
        mockMvc.perform(post("/order/confirmReceiveOrder").param("orderId", "100"))
                .andExpect(jsonPath("$.code").value(ResultCode.FAILED.getCode()))
                .andExpect(jsonPath("$.message").value("订单不存在或无权操作"));

        when(portalOrderService.confirmReceiveOrder(ORDER_ID)).thenReturn(OrderOperationResult.REJECTED);
        mockMvc.perform(post("/order/confirmReceiveOrder").param("orderId", "100"))
                .andExpect(jsonPath("$.code").value(ResultCode.FAILED.getCode()))
                .andExpect(jsonPath("$.message").value("订单当前状态不允许确认收货"));
    }

    @Test
    @DisplayName("订单列表：分页参数使用默认值 pageNum=1、pageSize=5")
    void listUsesDefaultPageParams() throws Exception {
        when(portalOrderService.list(anyInt(), anyInt(), anyInt())).thenReturn(emptyOrderPage());

        mockMvc.perform(get("/order/list").param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()));

        verify(portalOrderService).list(0, 1, 5);
    }

    @Test
    @DisplayName("订单列表：显式传入的分页参数原样透传")
    void listPassesExplicitPageParams() throws Exception {
        when(portalOrderService.list(anyInt(), anyInt(), anyInt())).thenReturn(emptyOrderPage());

        mockMvc.perform(get("/order/list")
                        .param("status", "-1")
                        .param("pageNum", "3")
                        .param("pageSize", "20"))
                .andExpect(status().isOk());

        verify(portalOrderService).list(-1, 3, 20);
    }

    @Test
    @DisplayName("订单列表：缺少 status 参数时接口报错")
    void listWithoutStatusIsRejected() throws Exception {
        mockMvc.perform(get("/order/list"))
                .andExpect(status().isBadRequest());

        verify(portalOrderService, never()).list(anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("订单详情：路径参数正确绑定")
    void detailBindsOrderId() throws Exception {
        OmsOrderDetail detail = new OmsOrderDetail();
        detail.setId(ORDER_ID);
        when(portalOrderService.detail(ORDER_ID)).thenReturn(detail);

        mockMvc.perform(get("/order/detail/{orderId}", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.id").value(ORDER_ID));
    }

    @Test
    @DisplayName("删除订单：调用服务并返回成功")
    void deleteOrderInvokesService() throws Exception {
        mockMvc.perform(post("/order/deleteOrder").param("orderId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()));

        verify(portalOrderService).deleteOrder(ORDER_ID);
    }

    @Test
    @DisplayName("支付回调缺少 payType 参数时不调用服务")
    void paySuccessWithoutPayTypeIsRejected() throws Exception {
        mockMvc.perform(post("/order/paySuccess").param("orderId", "100"))
                .andExpect(status().isBadRequest());

        verify(portalOrderService, never()).paySuccess(anyLong(), anyInt());
    }

    private CommonPage<OmsOrderDetail> emptyOrderPage() {
        CommonPage<OmsOrderDetail> page = new CommonPage<>();
        page.setPageNum(1);
        page.setPageSize(5);
        page.setTotal(0L);
        page.setTotalPage(0);
        page.setList(Collections.<OmsOrderDetail>emptyList());
        return page;
    }
}

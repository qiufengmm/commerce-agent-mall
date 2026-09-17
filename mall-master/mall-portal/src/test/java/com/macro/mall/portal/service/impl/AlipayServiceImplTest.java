package com.macro.mall.portal.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderExample;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.config.AlipayConfig;
import com.macro.mall.portal.domain.AliPayParam;
import com.macro.mall.portal.domain.OrderOperationResult;
import com.macro.mall.portal.service.OmsPortalOrderService;
import com.macro.mall.portal.service.UmsMemberService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付宝异步通知的安全性与幂等性测试。
 * <p>
 * 覆盖签名校验、交易状态判断、回调金额与订单应付金额一致性校验，以及重复通知的幂等处理。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlipayServiceImplTest {

    private static final String OUT_TRADE_NO = "SN100";
    private static final BigDecimal PAY_AMOUNT = new BigDecimal("100.00");
    private static final Long ORDER_MEMBER_ID = 1L;

    @Mock
    private AlipayConfig alipayConfig;
    @Mock
    private AlipayClient alipayClient;
    @Mock
    private OmsOrderMapper orderMapper;
    @Mock
    private OmsPortalOrderService portalOrderService;
    @Mock
    private UmsMemberService memberService;

    @InjectMocks
    private AlipayServiceImpl alipayService;

    @Test
    @DisplayName("签名校验失败时不得处理任何订单状态")
    void notifyWithInvalidSignatureDoesNotTouchOrder() throws Exception {
        stubAlipayConfig();
        try (MockedStatic<AlipaySignature> signature = mockStatic(AlipaySignature.class)) {
            signature.when(() -> AlipaySignature.rsaCheckV1(anyMap(), anyString(), anyString(), anyString()))
                    .thenReturn(false);

            assertEquals("failure", alipayService.notify(tradeSuccessParams()));
            verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
        }
    }

    @Test
    @DisplayName("交易未成功时不处理订单状态")
    void notifyWithUnfinishedTradeDoesNotTouchOrder() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        try (MockedStatic<AlipaySignature> signature = mockStatic(AlipaySignature.class)) {
            signature.when(() -> AlipaySignature.rsaCheckV1(anyMap(), anyString(), anyString(), anyString()))
                    .thenReturn(true);

            Map<String, String> params = tradeSuccessParams();
            params.put("trade_status", "WAIT_BUYER_PAY");
            assertEquals("failure", alipayService.notify(params));
            verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
        }
    }

    @Test
    @DisplayName("签名有效、交易成功且金额一致时按订单号走幂等支付逻辑并返回 success")
    void notifyWithValidSignatureReturnsSuccess() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        when(portalOrderService.paySuccessByOrderSn(OUT_TRADE_NO, 1)).thenReturn(OrderOperationResult.SUCCESS);
        try (MockedStatic<AlipaySignature> signature = mockStatic(AlipaySignature.class)) {
            signature.when(() -> AlipaySignature.rsaCheckV1(anyMap(), anyString(), anyString(), anyString()))
                    .thenReturn(true);

            assertEquals("success", alipayService.notify(tradeSuccessParams()));
            verify(portalOrderService).paySuccessByOrderSn(OUT_TRADE_NO, 1);
        }
    }

    @Test
    @DisplayName("重复通知（订单已支付）仍然返回 success")
    void notifyRepeatedCallbackReturnsSuccess() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        when(portalOrderService.paySuccessByOrderSn(OUT_TRADE_NO, 1)).thenReturn(OrderOperationResult.IDEMPOTENT);
        try (MockedStatic<AlipaySignature> signature = mockStatic(AlipaySignature.class)) {
            signature.when(() -> AlipaySignature.rsaCheckV1(anyMap(), anyString(), anyString(), anyString()))
                    .thenReturn(true);

            assertEquals("success", alipayService.notify(tradeSuccessParams()));
        }
    }

    @Test
    @DisplayName("订单状态不允许支付时返回 failure，便于支付平台重试或人工介入")
    void notifyWhenOrderRejectedReturnsFailure() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        when(portalOrderService.paySuccessByOrderSn(OUT_TRADE_NO, 1)).thenReturn(OrderOperationResult.REJECTED);
        try (MockedStatic<AlipaySignature> signature = mockStatic(AlipaySignature.class)) {
            signature.when(() -> AlipaySignature.rsaCheckV1(anyMap(), anyString(), anyString(), anyString()))
                    .thenReturn(true);

            assertEquals("failure", alipayService.notify(tradeSuccessParams()));
        }
    }

    @Test
    @DisplayName("回调金额与订单应付金额不一致时返回 failure，且不调用支付服务")
    void notifyWithMismatchedAmountReturnsFailureAndSkipsPayment() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        try (MockedStatic<AlipaySignature> signature = mockStatic(AlipaySignature.class)) {
            signature.when(() -> AlipaySignature.rsaCheckV1(anyMap(), anyString(), anyString(), anyString()))
                    .thenReturn(true);

            Map<String, String> params = tradeSuccessParams();
            params.put("total_amount", "0.01");
            assertEquals("failure", alipayService.notify(params));
            verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
        }
    }

    @Test
    @DisplayName("回调缺少 total_amount 时返回 failure，且不调用支付服务")
    void notifyWithoutAmountReturnsFailureAndSkipsPayment() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        try (MockedStatic<AlipaySignature> signature = mockStatic(AlipaySignature.class)) {
            signature.when(() -> AlipaySignature.rsaCheckV1(anyMap(), anyString(), anyString(), anyString()))
                    .thenReturn(true);

            Map<String, String> params = tradeSuccessParams();
            params.remove("total_amount");
            assertEquals("failure", alipayService.notify(params));
            verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
        }
    }

    @Test
    @DisplayName("回调 total_amount 格式非法时返回 failure，且不调用支付服务")
    void notifyWithInvalidAmountFormatReturnsFailureAndSkipsPayment() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        try (MockedStatic<AlipaySignature> signature = mockStatic(AlipaySignature.class)) {
            signature.when(() -> AlipaySignature.rsaCheckV1(anyMap(), anyString(), anyString(), anyString()))
                    .thenReturn(true);

            Map<String, String> params = tradeSuccessParams();
            params.put("total_amount", "not-a-number");
            assertEquals("failure", alipayService.notify(params));
            verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
        }
    }

    @Test
    @DisplayName("回调订单号找不到订单时返回 failure，且不调用支付服务")
    void notifyWithUnknownOrderSnReturnsFailureAndSkipsPayment() throws Exception {
        stubAlipayConfig();
        when(orderMapper.selectByExample(any(OmsOrderExample.class))).thenReturn(Collections.emptyList());
        try (MockedStatic<AlipaySignature> signature = mockStatic(AlipaySignature.class)) {
            signature.when(() -> AlipaySignature.rsaCheckV1(anyMap(), anyString(), anyString(), anyString()))
                    .thenReturn(true);

            assertEquals("failure", alipayService.notify(tradeSuccessParams()));
            verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
        }
    }

    @Test
    @DisplayName("电脑网站支付：客户端篡改金额时，支付宝收到的仍然是数据库金额")
    void payUsesDatabaseAmountAndIgnoresClientAmount() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        stubCurrentMember(ORDER_MEMBER_ID);
        AlipayTradePagePayResponse pageResponse = new AlipayTradePagePayResponse();
        pageResponse.setBody("<form>pay</form>");
        when(alipayClient.pageExecute(any(AlipayTradePagePayRequest.class))).thenReturn(pageResponse);

        assertEquals("<form>pay</form>", alipayService.pay(payParam(new BigDecimal("0.01"), "篡改标题")));

        ArgumentCaptor<AlipayTradePagePayRequest> captor = ArgumentCaptor.forClass(AlipayTradePagePayRequest.class);
        verify(alipayClient).pageExecute(captor.capture());
        JSONObject bizContent = JSONObject.parseObject(captor.getValue().getBizContent());
        assertEquals(PAY_AMOUNT.toPlainString(), bizContent.getString("total_amount"));
        assertEquals(OUT_TRADE_NO, bizContent.getString("out_trade_no"));
        assertEquals("FAST_INSTANT_TRADE_PAY", bizContent.getString("product_code"));
        //标题由服务端生成，不采用客户端传入内容
        assertTrue(bizContent.getString("subject").contains(OUT_TRADE_NO));
    }

    @Test
    @DisplayName("手机网站支付：客户端篡改金额时，支付宝收到的仍然是数据库金额")
    void webPayUsesDatabaseAmountAndIgnoresClientAmount() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        stubCurrentMember(ORDER_MEMBER_ID);
        AlipayTradeWapPayResponse wapResponse = new AlipayTradeWapPayResponse();
        wapResponse.setBody("<form>wapPay</form>");
        when(alipayClient.pageExecute(any(AlipayTradeWapPayRequest.class))).thenReturn(wapResponse);

        assertEquals("<form>wapPay</form>", alipayService.webPay(payParam(new BigDecimal("0.01"), "篡改标题")));

        ArgumentCaptor<AlipayTradeWapPayRequest> captor = ArgumentCaptor.forClass(AlipayTradeWapPayRequest.class);
        verify(alipayClient).pageExecute(captor.capture());
        JSONObject bizContent = JSONObject.parseObject(captor.getValue().getBizContent());
        assertEquals(PAY_AMOUNT.toPlainString(), bizContent.getString("total_amount"));
        assertEquals("QUICK_WAP_WAY", bizContent.getString("product_code"));
    }

    @Test
    @DisplayName("非本人订单不能创建支付：不调用支付宝 SDK")
    void payRejectsOrderOfAnotherMember() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        stubCurrentMember(999L);

        assertThrows(RuntimeException.class, () -> alipayService.pay(payParam(PAY_AMOUNT, "订单")));
        assertThrows(RuntimeException.class, () -> alipayService.webPay(payParam(PAY_AMOUNT, "订单")));
        verify(alipayClient, never()).pageExecute(any(AlipayTradePagePayRequest.class));
        verify(alipayClient, never()).pageExecute(any(AlipayTradeWapPayRequest.class));
    }

    @Test
    @DisplayName("非待付款订单不能创建支付：不调用支付宝 SDK")
    void payRejectsOrderNotUnpaid() throws Exception {
        stubAlipayConfig();
        OmsOrder order = order(PAY_AMOUNT);
        order.setStatus(1);
        stubOrder(order);
        stubCurrentMember(ORDER_MEMBER_ID);

        assertThrows(RuntimeException.class, () -> alipayService.pay(payParam(PAY_AMOUNT, "订单")));
        verify(alipayClient, never()).pageExecute(any(AlipayTradePagePayRequest.class));
    }

    @Test
    @DisplayName("订单不存在时不能创建支付：不调用支付宝 SDK")
    void payRejectsMissingOrder() throws Exception {
        stubAlipayConfig();
        when(orderMapper.selectByExample(any(OmsOrderExample.class))).thenReturn(Collections.emptyList());

        assertThrows(RuntimeException.class, () -> alipayService.pay(payParam(PAY_AMOUNT, "订单")));
        verify(alipayClient, never()).pageExecute(any(AlipayTradePagePayRequest.class));
    }

    @Test
    @DisplayName("主动查询：金额一致且本地支付成功时返回 TRADE_SUCCESS")
    void queryWithMatchedAmountMarksPaid() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        stubCurrentMember(ORDER_MEMBER_ID);
        AlipayTradeQueryResponse response = queryResponse(true, "TRADE_SUCCESS", PAY_AMOUNT.toPlainString());
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);
        when(portalOrderService.paySuccessByOrderSn(OUT_TRADE_NO, 1)).thenReturn(OrderOperationResult.SUCCESS);

        assertEquals("TRADE_SUCCESS", alipayService.query(OUT_TRADE_NO, null));
        verify(portalOrderService).paySuccessByOrderSn(OUT_TRADE_NO, 1);
    }

    @Test
    @DisplayName("主动查询：本地支付幂等成功（订单已支付）时仍返回 TRADE_SUCCESS")
    void queryWithIdempotentLocalResultReturnsTradeSuccess() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        stubCurrentMember(ORDER_MEMBER_ID);
        AlipayTradeQueryResponse response = queryResponse(true, "TRADE_SUCCESS", PAY_AMOUNT.toPlainString());
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);
        when(portalOrderService.paySuccessByOrderSn(OUT_TRADE_NO, 1)).thenReturn(OrderOperationResult.IDEMPOTENT);

        assertEquals("TRADE_SUCCESS", alipayService.query(OUT_TRADE_NO, null));
    }

    @Test
    @DisplayName("主动查询：金额不一致时不得返回 TRADE_SUCCESS")
    void queryWithMismatchedAmountDoesNotMarkPaid() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        stubCurrentMember(ORDER_MEMBER_ID);
        AlipayTradeQueryResponse response = queryResponse(true, "TRADE_SUCCESS", "0.01");
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        assertNotEquals("TRADE_SUCCESS", alipayService.query(OUT_TRADE_NO, null));
        verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
    }

    @Test
    @DisplayName("主动查询：支付宝金额缺失时不得返回 TRADE_SUCCESS")
    void queryWithoutAmountDoesNotMarkPaid() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        stubCurrentMember(ORDER_MEMBER_ID);
        AlipayTradeQueryResponse response = queryResponse(true, "TRADE_SUCCESS", null);
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        assertNotEquals("TRADE_SUCCESS", alipayService.query(OUT_TRADE_NO, null));
        verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
    }

    @Test
    @DisplayName("主动查询：支付宝金额格式非法时不得返回 TRADE_SUCCESS")
    void queryWithInvalidAmountFormatDoesNotMarkPaid() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        stubCurrentMember(ORDER_MEMBER_ID);
        AlipayTradeQueryResponse response = queryResponse(true, "TRADE_SUCCESS", "not-a-number");
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        assertNotEquals("TRADE_SUCCESS", alipayService.query(OUT_TRADE_NO, null));
        verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
    }

    @Test
    @DisplayName("主动查询：订单应付金额缺失时不得返回 TRADE_SUCCESS")
    void queryWithoutOrderPayAmountDoesNotMarkPaid() throws Exception {
        stubAlipayConfig();
        stubOrder((BigDecimal) null);
        stubCurrentMember(ORDER_MEMBER_ID);
        AlipayTradeQueryResponse response = queryResponse(true, "TRADE_SUCCESS", PAY_AMOUNT.toPlainString());
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        assertNotEquals("TRADE_SUCCESS", alipayService.query(OUT_TRADE_NO, null));
        verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
    }

    @Test
    @DisplayName("主动查询：订单不存在时不得返回 TRADE_SUCCESS")
    void queryWithUnknownOrderSnDoesNotMarkPaid() throws Exception {
        stubAlipayConfig();
        when(orderMapper.selectByExample(any(OmsOrderExample.class))).thenReturn(Collections.emptyList());
        stubCurrentMember(ORDER_MEMBER_ID);
        AlipayTradeQueryResponse response = queryResponse(true, "TRADE_SUCCESS", PAY_AMOUNT.toPlainString());
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        assertNotEquals("TRADE_SUCCESS", alipayService.query(OUT_TRADE_NO, null));
        verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
    }

    @Test
    @DisplayName("主动查询：非本人订单不得返回 TRADE_SUCCESS")
    void queryRejectsOrderOfAnotherMember() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        stubCurrentMember(999L);
        AlipayTradeQueryResponse response = queryResponse(true, "TRADE_SUCCESS", PAY_AMOUNT.toPlainString());
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        assertNotEquals("TRADE_SUCCESS", alipayService.query(OUT_TRADE_NO, null));
        verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
    }

    @Test
    @DisplayName("主动查询：本地支付逻辑返回 REJECTED 时不得返回 TRADE_SUCCESS")
    void queryWhenLocalPaymentRejectedReturnsFailure() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        stubCurrentMember(ORDER_MEMBER_ID);
        AlipayTradeQueryResponse response = queryResponse(true, "TRADE_SUCCESS", PAY_AMOUNT.toPlainString());
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);
        when(portalOrderService.paySuccessByOrderSn(OUT_TRADE_NO, 1)).thenReturn(OrderOperationResult.REJECTED);

        assertNotEquals("TRADE_SUCCESS", alipayService.query(OUT_TRADE_NO, null));
        verify(portalOrderService).paySuccessByOrderSn(OUT_TRADE_NO, 1);
    }

    @Test
    @DisplayName("主动查询：本地支付逻辑返回 NOT_FOUND 时不得返回 TRADE_SUCCESS")
    void queryWhenLocalPaymentNotFoundReturnsFailure() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        stubCurrentMember(ORDER_MEMBER_ID);
        AlipayTradeQueryResponse response = queryResponse(true, "TRADE_SUCCESS", PAY_AMOUNT.toPlainString());
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);
        when(portalOrderService.paySuccessByOrderSn(OUT_TRADE_NO, 1)).thenReturn(OrderOperationResult.NOT_FOUND);

        assertNotEquals("TRADE_SUCCESS", alipayService.query(OUT_TRADE_NO, null));
    }

    @Test
    @DisplayName("主动查询：本地支付逻辑返回 null 时不得返回 TRADE_SUCCESS，也不会空指针")
    void queryWhenLocalPaymentReturnsNullReturnsFailure() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        stubCurrentMember(ORDER_MEMBER_ID);
        AlipayTradeQueryResponse response = queryResponse(true, "TRADE_SUCCESS", PAY_AMOUNT.toPlainString());
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);
        when(portalOrderService.paySuccessByOrderSn(OUT_TRADE_NO, 1)).thenReturn(null);

        assertNotEquals("TRADE_SUCCESS", alipayService.query(OUT_TRADE_NO, null));
    }

    @Test
    @DisplayName("主动查询：支付宝交易状态非 TRADE_SUCCESS 时原样返回，且不触发本地支付")
    void queryWithNonSuccessTradeStatusReturnsRawStatus() throws Exception {
        stubAlipayConfig();
        stubOrder(PAY_AMOUNT);
        stubCurrentMember(ORDER_MEMBER_ID);
        AlipayTradeQueryResponse waiting = queryResponse(true, "WAIT_BUYER_PAY", PAY_AMOUNT.toPlainString());
        AlipayTradeQueryResponse closed = queryResponse(true, "TRADE_CLOSED", PAY_AMOUNT.toPlainString());
        AlipayTradeQueryResponse finished = queryResponse(true, "TRADE_FINISHED", PAY_AMOUNT.toPlainString());
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class)))
                .thenReturn(waiting, closed, finished);

        assertEquals("WAIT_BUYER_PAY", alipayService.query(OUT_TRADE_NO, null));
        assertEquals("TRADE_CLOSED", alipayService.query(OUT_TRADE_NO, null));
        assertEquals("TRADE_FINISHED", alipayService.query(OUT_TRADE_NO, null));
        verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
    }

    @Test
    @DisplayName("主动查询：支付宝查询本身失败时返回 null，不得返回 TRADE_SUCCESS")
    void queryWhenAlipayResponseNotSuccessReturnsNull() throws Exception {
        stubAlipayConfig();
        AlipayTradeQueryResponse response = queryResponse(false, "TRADE_SUCCESS", PAY_AMOUNT.toPlainString());
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        assertNull(alipayService.query(OUT_TRADE_NO, null));
        verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
    }

    @Test
    @DisplayName("主动查询：SDK 异常时不会空指针，也不会标记支付成功")
    void queryWhenSdkThrowsReturnsNullWithoutNpe() throws Exception {
        stubAlipayConfig();
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class)))
                .thenThrow(new AlipayApiException("sdk down"));

        assertNull(alipayService.query(OUT_TRADE_NO, null));
        verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
    }

    @Test
    @DisplayName("主动查询：SDK 返回 null 时不会空指针")
    void queryWhenResponseNullReturnsNullWithoutNpe() throws Exception {
        stubAlipayConfig();
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(null);

        assertNull(alipayService.query(OUT_TRADE_NO, null));
        verify(portalOrderService, never()).paySuccessByOrderSn(anyString(), anyInt());
    }

    private AliPayParam payParam(BigDecimal totalAmount, String subject) {
        AliPayParam param = new AliPayParam();
        param.setOutTradeNo(OUT_TRADE_NO);
        param.setSubject(subject);
        param.setTotalAmount(totalAmount);
        return param;
    }

    /**
     * 支付宝 SDK 响应对象没有提供可用的 success 赋值入口，这里用 Mockito 构造响应
     */
    private AlipayTradeQueryResponse queryResponse(boolean success, String tradeStatus, String totalAmount) {
        AlipayTradeQueryResponse response = mock(AlipayTradeQueryResponse.class);
        when(response.isSuccess()).thenReturn(success);
        when(response.getTradeStatus()).thenReturn(tradeStatus);
        when(response.getTotalAmount()).thenReturn(totalAmount);
        when(response.getOutTradeNo()).thenReturn(OUT_TRADE_NO);
        return response;
    }

    private void stubCurrentMember(Long memberId) {
        UmsMember member = new UmsMember();
        member.setId(memberId);
        when(memberService.getCurrentMember()).thenReturn(member);
    }

    private void stubAlipayConfig() {
        when(alipayConfig.getAlipayPublicKey()).thenReturn("test-public-key");
        when(alipayConfig.getCharset()).thenReturn("UTF-8");
        when(alipayConfig.getSignType()).thenReturn("RSA2");
    }

    private OmsOrder order(BigDecimal payAmount) {
        OmsOrder order = new OmsOrder();
        order.setId(100L);
        order.setOrderSn(OUT_TRADE_NO);
        order.setPayAmount(payAmount);
        order.setDeleteStatus(0);
        order.setStatus(0);
        order.setMemberId(ORDER_MEMBER_ID);
        return order;
    }

    private void stubOrder(BigDecimal payAmount) {
        stubOrder(order(payAmount));
    }

    private void stubOrder(OmsOrder order) {
        when(orderMapper.selectByExample(any(OmsOrderExample.class))).thenReturn(Collections.singletonList(order));
    }

    private Map<String, String> tradeSuccessParams() {
        Map<String, String> params = new HashMap<>();
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("out_trade_no", OUT_TRADE_NO);
        params.put("trade_no", "2026010122001");
        params.put("total_amount", PAY_AMOUNT.toPlainString());
        return params;
    }
}

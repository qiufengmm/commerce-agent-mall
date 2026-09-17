package com.macro.mall.portal.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderExample;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.config.AlipayConfig;
import com.macro.mall.portal.domain.AliPayParam;
import com.macro.mall.portal.domain.OrderOperationResult;
import com.macro.mall.portal.service.AlipayService;
import com.macro.mall.portal.service.OmsPortalOrderService;
import com.macro.mall.portal.service.UmsMemberService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @description 支付宝支付Service实现类
 * @date 2023/9/8
 */
@Slf4j
@Service
public class AlipayServiceImpl implements AlipayService {
    /**
     * 订单状态：0->待付款，只有待付款订单允许发起支付
     */
    private static final int ORDER_STATUS_UNPAID = 0;
    /**
     * 订单标题最大长度，超出会被支付宝拒绝
     */
    private static final int MAX_SUBJECT_LENGTH = 100;
    /**
     * 支付宝交易状态：支付成功
     */
    private static final String TRADE_SUCCESS = "TRADE_SUCCESS";
    /**
     * 支付宝返回成功，但本地校验（订单归属 / 金额 / 订单存在性）未通过，
     * 此时绝不能把 TRADE_SUCCESS 透传给前端，否则前端会显示支付成功而订单仍是待付款。
     */
    private static final String PAYMENT_VERIFY_FAILED = "PAYMENT_VERIFY_FAILED";
    /**
     * 本地校验通过，但本地支付成功逻辑（paySuccessByOrderSn）返回失败，
     * 订单状态没有真正推进，同样不能透传 TRADE_SUCCESS。
     */
    private static final String PAYMENT_PROCESS_FAILED = "PAYMENT_PROCESS_FAILED";

    @Autowired
    private AlipayConfig alipayConfig;
    @Autowired
    private AlipayClient alipayClient;
    @Autowired
    private OmsOrderMapper orderMapper;
    @Autowired
    private OmsPortalOrderService portalOrderService;
    @Autowired
    private UmsMemberService memberService;

    @Override
    public String pay(AliPayParam aliPayParam) {
        //金额、标题与订单号全部以数据库为准，客户端传入的 totalAmount / subject 一律忽略
        OmsOrder order = resolvePayableOrder(aliPayParam);
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        if(StrUtil.isNotEmpty(alipayConfig.getNotifyUrl())){
            //异步接收地址，公网可访问
            request.setNotifyUrl(alipayConfig.getNotifyUrl());
        }
        if(StrUtil.isNotEmpty(alipayConfig.getReturnUrl())){
            //同步跳转地址
            request.setReturnUrl(alipayConfig.getReturnUrl());
        }
        request.setBizContent(buildBizContent(order, "FAST_INSTANT_TRADE_PAY"));
        try {
            return alipayClient.pageExecute(request).getBody();
        } catch (AlipayApiException e) {
            log.error("调用支付宝电脑网站支付接口异常，orderId:{}", order.getId(), e);
            throw new IllegalStateException("支付宝下单失败，请稍后重试", e);
        }
    }

    @Override
    public String notify(Map<String, String> params) {
        String result = "failure";
        boolean signVerified = false;
        try {
            //调用SDK验证签名
            signVerified = AlipaySignature.rsaCheckV1(params, alipayConfig.getAlipayPublicKey(), alipayConfig.getCharset(), alipayConfig.getSignType());
        } catch (AlipayApiException e) {
            log.error("支付回调签名校验异常！",e);
            e.printStackTrace();
        }
        if (signVerified) {
            String tradeStatus = params.get("trade_status");
            if(TRADE_SUCCESS.equals(tradeStatus)){
                String outTradeNo = params.get("out_trade_no");
                //签名有效仍然不能信任金额：金额缺失、格式非法或与订单应付金额不一致时，
                //不标记支付成功、不扣减库存，直接返回 failure 等待人工介入
                if (!isNotifyAmountMatched(outTradeNo, params.get("total_amount"))) {
                    return "failure";
                }
                //按订单号调用幂等支付逻辑，重复通知不会重复扣减库存
                OrderOperationResult orderResult = portalOrderService.paySuccessByOrderSn(outTradeNo,1);
                result = orderResult.isOk() ? "success" : "failure";
                if (orderResult.isOk()) {
                    log.info("notify方法被调用了，tradeStatus:{}，outTradeNo:{}，result:{}", tradeStatus, outTradeNo, orderResult);
                } else {
                    log.warn("支付通知未处理成功，outTradeNo:{}，result:{}", outTradeNo, orderResult);
                }
            }else{
                log.warn("订单未支付成功，trade_status:{}",tradeStatus);
            }
        } else {
            log.warn("支付回调签名校验失败！");
        }
        return result;
    }

    /**
     * 异步通知金额校验：支付宝匿名回调，无法校验会员归属，只校验订单存在与金额一致。
     */
    private boolean isNotifyAmountMatched(String outTradeNo, String totalAmount) {
        OmsOrder order = findOrderByOrderSn(outTradeNo);
        if (order == null) {
            log.error("支付回调未找到订单，outTradeNo:{}", outTradeNo);
            return false;
        }
        return isAmountMatched(order, totalAmount);
    }

    /**
     * 主动查询金额校验：面向会员，必须校验当前登录会员与订单归属，再校验金额一致。
     */
    private boolean isQueryAmountMatched(String outTradeNo, String totalAmount) {
        OmsOrder order = findOrderByOrderSn(outTradeNo);
        if (order == null) {
            log.error("主动查询未找到订单，outTradeNo:{}", outTradeNo);
            return false;
        }
        UmsMember currentMember = memberService.getCurrentMember();
        if (currentMember == null || !Objects.equals(currentMember.getId(), order.getMemberId())) {
            log.error("主动查询订单不属于当前会员，outTradeNo:{}, orderId:{}, orderMemberId:{}, currentMemberId:{}",
                    outTradeNo, order.getId(), order.getMemberId(), currentMember == null ? null : currentMember.getId());
            return false;
        }
        return isAmountMatched(order, totalAmount);
    }

    /**
     * 校验支付宝返回金额与订单应付金额是否一致，使用 BigDecimal.compareTo 比较，不使用字符串比较。
     * 金额缺失、格式非法或金额不一致时返回 false，调用方必须拒绝标记支付成功。
     */
    private boolean isAmountMatched(OmsOrder order, String totalAmount) {
        BigDecimal payAmount = order.getPayAmount();
        if (payAmount == null) {
            log.error("订单应付金额缺失，orderSn:{}, orderId:{}", order.getOrderSn(), order.getId());
            return false;
        }
        if (StrUtil.isEmpty(totalAmount)) {
            log.error("支付宝金额缺失，orderSn:{}, orderId:{}", order.getOrderSn(), order.getId());
            return false;
        }
        BigDecimal paidAmount;
        try {
            paidAmount = new BigDecimal(totalAmount.trim());
        } catch (NumberFormatException e) {
            log.error("支付宝金额格式非法，orderSn:{}, orderId:{}, 回调金额:{}",
                    order.getOrderSn(), order.getId(), totalAmount);
            return false;
        }
        if (paidAmount.compareTo(payAmount) != 0) {
            log.error("支付宝金额与订单应付金额不一致，orderSn:{}, orderId:{}, 支付宝金额:{}, 订单应付金额:{}",
                    order.getOrderSn(), order.getId(), paidAmount, payAmount);
            return false;
        }
        return true;
    }

    /**
     * 按订单号查询未删除的订单
     */
    private OmsOrder findOrderByOrderSn(String orderSn) {
        if (StrUtil.isEmpty(orderSn)) {
            return null;
        }
        OmsOrderExample example = new OmsOrderExample();
        example.createCriteria().andOrderSnEqualTo(orderSn).andDeleteStatusEqualTo(0);
        List<OmsOrder> orderList = orderMapper.selectByExample(example);
        if (orderList == null || orderList.isEmpty()) {
            return null;
        }
        return orderList.get(0);
    }

    @Override
    public String query(String outTradeNo, String tradeNo) {
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        //******必传参数******
        JSONObject bizContent = new JSONObject();
        //设置查询参数，out_trade_no和trade_no至少传一个
        if(StrUtil.isNotEmpty(outTradeNo)){
            bizContent.put("out_trade_no",outTradeNo);
        }
        if(StrUtil.isNotEmpty(tradeNo)){
            bizContent.put("trade_no",tradeNo);
        }
        //交易结算信息: trade_settle_info
        String[] queryOptions = {"trade_settle_info"};
        bizContent.put("query_options", queryOptions);
        request.setBizContent(bizContent.toString());
        AlipayTradeQueryResponse response = null;
        try {
            response = alipayClient.execute(request);
        } catch (AlipayApiException e) {
            log.error("查询支付宝账单异常！",e);
        }
        //SDK 调用失败时 response 为 null，必须先判空，否则会空指针
        if (response == null) {
            log.error("查询支付宝账单失败，未获取到响应，outTradeNo:{}", outTradeNo);
            return null;
        }
        if(!response.isSuccess()){
            log.error("查询支付宝账单失败！subCode:{}, subMsg:{}", response.getSubCode(), response.getSubMsg());
            //查询本身失败，没有任何可信的交易状态可以返回，返回 null 让调用方进入失败分支
            return null;
        }
        log.info("查询支付宝账单成功！");
        String tradeStatus = response.getTradeStatus();
        //交易状态：WAIT_BUYER_PAY（交易创建，等待买家付款）、TRADE_CLOSED（未付款交易超时关闭，或支付完成后全额退款）、TRADE_SUCCESS（交易支付成功）、TRADE_FINISHED（交易结束，不可退款）
        if(!TRADE_SUCCESS.equals(tradeStatus)){
            //非成功状态原样返回（WAIT_BUYER_PAY / TRADE_CLOSED / TRADE_FINISHED），
            //前端收到这些状态不会显示支付成功
            return tradeStatus;
        }
        //只有本地订单也真正处理成功，才允许把 TRADE_SUCCESS 返回给前端
        return handleTradeSuccess(outTradeNo, response);
    }

    /**
     * 支付宝已返回 TRADE_SUCCESS 后的本地处理。
     * <p>
     * 返回给前端的字符串代表「本地订单支付成功」而不是「支付宝说支付成功」：
     * <ul>
     *     <li>订单不存在 / 不属于当前会员 / 金额缺失 / 金额格式非法 / 金额不一致 -> PAYMENT_VERIFY_FAILED</li>
     *     <li>本地 paySuccessByOrderSn 返回失败 -> PAYMENT_PROCESS_FAILED</li>
     *     <li>本地支付成功（含幂等成功）-> TRADE_SUCCESS</li>
     * </ul>
     * 任何一条不通过都不会返回 TRADE_SUCCESS，避免前端显示支付成功而订单仍是待付款、库存未扣减。
     */
    private String handleTradeSuccess(String outTradeNo, AlipayTradeQueryResponse response) {
        //主动查询面向会员：订单号优先取入参，缺失时取支付宝返回，再校验归属与金额
        String orderSn = StrUtil.isNotEmpty(outTradeNo) ? outTradeNo : response.getOutTradeNo();
        //订单不存在、非本人订单、金额缺失/非法/不一致时，不标记支付成功、不扣减库存
        if (!isQueryAmountMatched(orderSn, response.getTotalAmount())) {
            return PAYMENT_VERIFY_FAILED;
        }
        //同样走幂等支付逻辑，主动查询与异步通知不会重复扣减库存
        OrderOperationResult orderResult = portalOrderService.paySuccessByOrderSn(orderSn, 1);
        log.info("查询支付结果处理完成，outTradeNo:{}，result:{}", orderSn, orderResult);
        if (orderResult == null || !orderResult.isOk()) {
            log.warn("主动查询本地支付处理未成功，outTradeNo:{}，result:{}", orderSn, orderResult);
            return PAYMENT_PROCESS_FAILED;
        }
        //SUCCESS（本次完成状态转换）与 IDEMPOTENT（已支付，安全忽略）都视为本地已支付成功
        return TRADE_SUCCESS;
    }

    @Override
    public String webPay(AliPayParam aliPayParam) {
        //金额、标题与订单号全部以数据库为准，客户端传入的 totalAmount / subject 一律忽略
        OmsOrder order = resolvePayableOrder(aliPayParam);
        AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
        if(StrUtil.isNotEmpty(alipayConfig.getNotifyUrl())){
            //异步接收地址，公网可访问
            request.setNotifyUrl(alipayConfig.getNotifyUrl());
        }
        if(StrUtil.isNotEmpty(alipayConfig.getReturnUrl())){
            //同步跳转地址
            request.setReturnUrl(alipayConfig.getReturnUrl());
        }
        //手机网站支付场景固定传值QUICK_WAP_WAY
        request.setBizContent(buildBizContent(order, "QUICK_WAP_WAY"));
        try {
            return alipayClient.pageExecute(request).getBody();
        } catch (AlipayApiException e) {
            log.error("调用支付宝手机网站支付接口异常，orderId:{}", order.getId(), e);
            throw new IllegalStateException("支付宝下单失败，请稍后重试", e);
        }
    }

    /**
     * 构建支付宝下单业务参数：订单号、金额、标题全部取自数据库订单，不使用客户端入参。
     */
    private String buildBizContent(OmsOrder order, String productCode) {
        JSONObject bizContent = new JSONObject();
        //商户订单号，取服务端订单号
        bizContent.put("out_trade_no", order.getOrderSn());
        //支付金额只使用数据库 oms_order.pay_amount，忽略客户端篡改的金额
        bizContent.put("total_amount", order.getPayAmount().toPlainString());
        //订单标题由服务端生成，不信任客户端传入内容
        bizContent.put("subject", buildSubject(order));
        bizContent.put("product_code", productCode);
        return bizContent.toString();
    }

    /**
     * 服务端生成订单标题，去掉支付宝不允许的特殊符号并限制长度
     */
    private String buildSubject(OmsOrder order) {
        String subject = "mall订单-" + order.getOrderSn();
        subject = subject.replaceAll("[^0-9a-zA-Z\\u4e00-\\u9fa5\\-]", "");
        return subject.length() > MAX_SUBJECT_LENGTH ? subject.substring(0, MAX_SUBJECT_LENGTH) : subject;
    }

    /**
     * 解析本次支付对应的订单：订单必须存在、未删除、待付款，且属于当前登录会员。
     * 校验不通过时直接抛出业务异常，不会调用支付宝 SDK。
     */
    private OmsOrder resolvePayableOrder(AliPayParam aliPayParam) {
        String outTradeNo = aliPayParam == null ? null : aliPayParam.getOutTradeNo();
        OmsOrder order = findOrderByOrderSn(outTradeNo);
        if (order == null) {
            log.error("创建支付失败：订单不存在或已删除，outTradeNo:{}", outTradeNo);
            Asserts.fail("订单不存在，无法支付");
        }
        if (order.getStatus() == null || order.getStatus() != ORDER_STATUS_UNPAID) {
            log.error("创建支付失败：订单状态不允许支付，outTradeNo:{}, orderId:{}, status:{}",
                    outTradeNo, order.getId(), order.getStatus());
            Asserts.fail("订单当前状态不允许支付");
        }
        UmsMember currentMember = memberService.getCurrentMember();
        if (currentMember == null || !Objects.equals(currentMember.getId(), order.getMemberId())) {
            log.error("创建支付失败：订单不属于当前会员，outTradeNo:{}, orderId:{}, orderMemberId:{}, currentMemberId:{}",
                    outTradeNo, order.getId(), order.getMemberId(), currentMember == null ? null : currentMember.getId());
            Asserts.fail("不能支付他人订单");
        }
        BigDecimal payAmount = order.getPayAmount();
        if (payAmount == null || payAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("创建支付失败：订单应付金额异常，outTradeNo:{}, orderId:{}, payAmount:{}",
                    outTradeNo, order.getId(), payAmount);
            Asserts.fail("订单金额异常，无法支付");
        }
        return order;
    }
}

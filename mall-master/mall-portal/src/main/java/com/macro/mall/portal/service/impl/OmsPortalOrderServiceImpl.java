package com.macro.mall.portal.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.github.pagehelper.PageHelper;
import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.common.service.RedisService;
import com.macro.mall.mapper.*;
import com.macro.mall.model.*;
import com.macro.mall.portal.component.CancelOrderSender;
import com.macro.mall.portal.dao.PortalOrderDao;
import com.macro.mall.portal.dao.PortalOrderItemDao;
import com.macro.mall.portal.dao.SmsCouponHistoryDao;
import com.macro.mall.portal.dao.PortalMemberDao;
import com.macro.mall.portal.domain.*;
import com.macro.mall.portal.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 前台订单管理Service
 */
@Slf4j
@Service
public class OmsPortalOrderServiceImpl implements OmsPortalOrderService {
    /**
     * 订单状态：0->待付款；1->待发货；2->已发货；3->已完成；4->已关闭；5->无效订单
     */
    private static final int ORDER_STATUS_UNPAID = 0;
    private static final int ORDER_STATUS_WAIT_DELIVER = 1;
    private static final int ORDER_STATUS_DELIVERED = 2;
    private static final int ORDER_STATUS_COMPLETED = 3;
    private static final int ORDER_STATUS_CLOSED = 4;
    /**
     * 支付方式：1->支付宝；2->微信
     */
    private static final int PAY_TYPE_ALIPAY = 1;
    private static final int PAY_TYPE_WECHAT = 2;

    @Autowired
    private UmsMemberService memberService;
    @Autowired
    private OmsCartItemService cartItemService;
    @Autowired
    private OmsPromotionService promotionService;
    @Autowired
    private UmsMemberReceiveAddressService memberReceiveAddressService;
    @Autowired
    private UmsMemberCouponService memberCouponService;
    @Autowired
    private UmsIntegrationConsumeSettingMapper integrationConsumeSettingMapper;
    @Autowired
    private SmsCouponHistoryDao couponHistoryDao;
    @Autowired
    private OmsOrderMapper orderMapper;
    @Autowired
    private PortalOrderItemDao orderItemDao;
    @Autowired
    private RedisService redisService;
    @Value("${redis.key.orderId}")
    private String REDIS_KEY_ORDER_ID;
    @Value("${redis.database}")
    private String REDIS_DATABASE;
    @Autowired
    private PortalOrderDao portalOrderDao;
    @Autowired
    private OmsOrderSettingMapper orderSettingMapper;
    @Autowired
    private OmsOrderItemMapper orderItemMapper;
    @Autowired
    private CancelOrderSender cancelOrderSender;
    /**
     * 用于批量超时取消时按订单拆分独立事务：单个订单补偿失败只回滚该订单，不影响同一批次其它订单
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Override
    public ConfirmOrderResult generateConfirmOrder(List<Long> cartIds) {
        UmsMember currentMember = memberService.getCurrentMember();
        List<CartPromotionItem> cartPromotionItemList = cartItemService.listPromotion(currentMember.getId(),cartIds);
        return buildConfirmOrderResult(currentMember, cartPromotionItemList);
    }

    @Override
    public ConfirmOrderResult generateDirectConfirmOrder(DirectBuyParam directBuyParam) {
        UmsMember currentMember = memberService.getCurrentMember();
        List<CartPromotionItem> cartPromotionItemList = getDirectCartPromotionItemList(directBuyParam);
        return buildConfirmOrderResult(currentMember, cartPromotionItemList);
    }

    private ConfirmOrderResult buildConfirmOrderResult(UmsMember currentMember, List<CartPromotionItem> cartPromotionItemList) {
        ConfirmOrderResult result = new ConfirmOrderResult();
        result.setCartPromotionItemList(cartPromotionItemList);
        //获取用户收货地址列表
        List<UmsMemberReceiveAddress> memberReceiveAddressList = memberReceiveAddressService.list();
        result.setMemberReceiveAddressList(memberReceiveAddressList);
        //获取用户可用优惠券列表
        List<SmsCouponHistoryDetail> couponHistoryDetailList = memberCouponService.listCart(cartPromotionItemList, 1);
        result.setCouponHistoryDetailList(couponHistoryDetailList);
        //获取用户积分
        result.setMemberIntegration(currentMember.getIntegration());
        //获取积分使用规则
        UmsIntegrationConsumeSetting integrationConsumeSetting = integrationConsumeSettingMapper.selectByPrimaryKey(1L);
        result.setIntegrationConsumeSetting(integrationConsumeSetting);
        //计算总金额、活动优惠、应付金额
        ConfirmOrderResult.CalcAmount calcAmount = calcCartAmount(cartPromotionItemList);
        result.setCalcAmount(calcAmount);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateOrder(OrderParam orderParam) {
        List<OmsOrderItem> orderItemList = new ArrayList<>();
        //校验收货地址
        if(orderParam.getMemberReceiveAddressId()==null){
            Asserts.fail("请选择收货地址！");
        }
        UmsMember currentMember = memberService.getCurrentMember();
        List<CartPromotionItem> cartPromotionItemList = getOrderPromotionItemList(currentMember, orderParam);
        for (CartPromotionItem cartPromotionItem : cartPromotionItemList) {
            //生成下单商品信息
            OmsOrderItem orderItem = new OmsOrderItem();
            orderItem.setProductId(cartPromotionItem.getProductId());
            orderItem.setProductName(cartPromotionItem.getProductName());
            orderItem.setProductPic(cartPromotionItem.getProductPic());
            orderItem.setProductAttr(cartPromotionItem.getProductAttr());
            orderItem.setProductBrand(cartPromotionItem.getProductBrand());
            orderItem.setProductSn(cartPromotionItem.getProductSn());
            orderItem.setProductPrice(cartPromotionItem.getPrice());
            orderItem.setProductQuantity(cartPromotionItem.getQuantity());
            orderItem.setProductSkuId(cartPromotionItem.getProductSkuId());
            orderItem.setProductSkuCode(cartPromotionItem.getProductSkuCode());
            orderItem.setProductCategoryId(cartPromotionItem.getProductCategoryId());
            orderItem.setPromotionAmount(cartPromotionItem.getReduceAmount());
            orderItem.setPromotionName(cartPromotionItem.getPromotionMessage());
            orderItem.setGiftIntegration(cartPromotionItem.getIntegration());
            orderItem.setGiftGrowth(cartPromotionItem.getGrowth());
            orderItemList.add(orderItem);
        }
        //判断购物车中商品是否都有库存
        if (!hasStock(cartPromotionItemList)) {
            Asserts.fail("库存不足，无法下单");
        }
        //判断使用使用了优惠券
        if (orderParam.getCouponId() == null) {
            //不用优惠券
            for (OmsOrderItem orderItem : orderItemList) {
                orderItem.setCouponAmount(new BigDecimal(0));
            }
        } else {
            //使用优惠券
            SmsCouponHistoryDetail couponHistoryDetail = getUseCoupon(cartPromotionItemList, orderParam.getCouponId());
            if (couponHistoryDetail == null) {
                Asserts.fail("该优惠券不可用");
            }
            //对下单商品的优惠券进行处理
            handleCouponAmount(orderItemList, couponHistoryDetail);
        }
        //判断是否使用积分
        if (orderParam.getUseIntegration() == null||orderParam.getUseIntegration().equals(0)) {
            //不使用积分
            for (OmsOrderItem orderItem : orderItemList) {
                orderItem.setIntegrationAmount(new BigDecimal(0));
            }
        } else {
            //使用积分
            BigDecimal totalAmount = calcTotalAmount(orderItemList);
            BigDecimal integrationAmount = getUseIntegrationAmount(orderParam.getUseIntegration(), totalAmount, currentMember, orderParam.getCouponId() != null);
            if (integrationAmount.compareTo(new BigDecimal(0)) == 0) {
                Asserts.fail("积分不可用");
            } else {
                //可用情况下分摊到可用商品中
                for (OmsOrderItem orderItem : orderItemList) {
                    BigDecimal perAmount = orderItem.getProductPrice().divide(totalAmount, 3, RoundingMode.HALF_EVEN).multiply(integrationAmount);
                    orderItem.setIntegrationAmount(perAmount);
                }
            }
        }
        //计算order_item的实付金额
        handleRealAmount(orderItemList);
        //进行库存锁定
        lockStock(cartPromotionItemList);
        //根据商品合计、运费、活动优惠、优惠券、积分计算应付金额
        OmsOrder order = new OmsOrder();
        order.setDiscountAmount(new BigDecimal(0));
        order.setTotalAmount(calcTotalAmount(orderItemList));
        order.setFreightAmount(new BigDecimal(0));
        order.setPromotionAmount(calcPromotionAmount(orderItemList));
        order.setPromotionInfo(getOrderPromotionInfo(orderItemList));
        if (orderParam.getCouponId() == null) {
            order.setCouponAmount(new BigDecimal(0));
        } else {
            order.setCouponId(orderParam.getCouponId());
            order.setCouponAmount(calcCouponAmount(orderItemList));
        }
        if (orderParam.getUseIntegration() == null) {
            order.setUseIntegration(0);
            order.setIntegrationAmount(new BigDecimal(0));
        } else {
            order.setUseIntegration(orderParam.getUseIntegration());
            order.setIntegrationAmount(calcIntegrationAmount(orderItemList));
        }
        order.setPayAmount(calcPayAmount(order));
        //转化为订单信息并插入数据库
        order.setMemberId(currentMember.getId());
        order.setCreateTime(new Date());
        order.setMemberUsername(currentMember.getUsername());
        //支付方式：0->未支付；1->支付宝；2->微信
        order.setPayType(orderParam.getPayType());
        //订单来源：0->PC订单；1->app订单
        order.setSourceType(1);
        //订单状态：0->待付款；1->待发货；2->已发货；3->已完成；4->已关闭；5->无效订单
        order.setStatus(0);
        //订单类型：0->正常订单；1->秒杀订单
        order.setOrderType(0);
        //收货人信息：姓名、电话、邮编、地址
        UmsMemberReceiveAddress address = memberReceiveAddressService.getItem(orderParam.getMemberReceiveAddressId());
        order.setReceiverName(address.getName());
        order.setReceiverPhone(address.getPhoneNumber());
        order.setReceiverPostCode(address.getPostCode());
        order.setReceiverProvince(address.getProvince());
        order.setReceiverCity(address.getCity());
        order.setReceiverRegion(address.getRegion());
        order.setReceiverDetailAddress(address.getDetailAddress());
        //0->未确认；1->已确认
        order.setConfirmStatus(0);
        order.setDeleteStatus(0);
        //计算赠送积分
        order.setIntegration(calcGifIntegration(orderItemList));
        //计算赠送成长值
        order.setGrowth(calcGiftGrowth(orderItemList));
        //生成订单号
        order.setOrderSn(generateOrderSn(order));
        //设置自动收货天数
        List<OmsOrderSetting> orderSettings = orderSettingMapper.selectByExample(new OmsOrderSettingExample());
        if(CollUtil.isNotEmpty(orderSettings)){
            order.setAutoConfirmDay(orderSettings.get(0).getConfirmOvertime());
        }
        // TODO: 2018/9/3 bill_*,delivery_*
        //插入order表和order_item表
        orderMapper.insert(order);
        for (OmsOrderItem orderItem : orderItemList) {
            orderItem.setOrderId(order.getId());
            orderItem.setOrderSn(order.getOrderSn());
        }
        orderItemDao.insertList(orderItemList);
        //如使用优惠券则原子占用优惠券并绑定到当前订单，占用失败必须回滚整单
        if (orderParam.getCouponId() != null) {
            occupyCoupon(currentMember.getId(), orderParam.getCouponId(), order.getId(), order.getOrderSn());
        }
        //如使用积分则原子扣减积分，扣减失败必须回滚整单
        if (orderParam.getUseIntegration() != null && orderParam.getUseIntegration() > 0) {
            if (!memberService.deductIntegration(currentMember.getId(), orderParam.getUseIntegration())) {
                log.warn("积分扣减失败，memberId:{}, useIntegration:{}", currentMember.getId(), orderParam.getUseIntegration());
                Asserts.fail("积分不足，无法下单");
            }
        }
        //直购商品未写入购物车，只有购物车下单才删除对应记录
        if (!orderParam.isDirectBuy()) {
            deleteCartItemList(cartPromotionItemList, currentMember);
        }
        //发送延迟消息取消订单
        sendDelayMessageCancelOrder(order.getId());
        Map<String, Object> result = new HashMap<>();
        result.put("order", order);
        result.put("orderItemList", orderItemList);
        return result;
    }

    private List<CartPromotionItem> getOrderPromotionItemList(UmsMember currentMember, OrderParam orderParam) {
        if (orderParam.isDirectBuy()) {
            return getDirectCartPromotionItemList(orderParam.getDirectBuy());
        }
        return cartItemService.listPromotion(currentMember.getId(), orderParam.getCartIds());
    }

    private List<CartPromotionItem> getDirectCartPromotionItemList(DirectBuyParam directBuyParam) {
        if (directBuyParam == null || directBuyParam.getProductId() == null || directBuyParam.getProductSkuId() == null) {
            Asserts.fail("请选择商品规格");
        }
        if (directBuyParam.getQuantity() == null || directBuyParam.getQuantity() <= 0) {
            Asserts.fail("购买数量必须大于0");
        }
        CartProduct product = cartItemService.getCartProduct(directBuyParam.getProductId());
        if (product == null || CollectionUtils.isEmpty(product.getSkuStockList())) {
            Asserts.fail("商品不存在或已下架");
        }
        PmsSkuStock skuStock = product.getSkuStockList().stream()
                .filter(item -> directBuyParam.getProductSkuId().equals(item.getId()))
                .findFirst()
                .orElse(null);
        if (skuStock == null) {
            Asserts.fail("商品规格不存在");
        }
        OmsCartItem directBuyItem = new OmsCartItem();
        directBuyItem.setProductId(product.getId());
        directBuyItem.setProductSkuId(skuStock.getId());
        directBuyItem.setProductSkuCode(skuStock.getSkuCode());
        directBuyItem.setProductName(product.getName());
        directBuyItem.setProductPic(skuStock.getPic() == null ? product.getPic() : skuStock.getPic());
        directBuyItem.setProductAttr(skuStock.getSpData());
        directBuyItem.setProductBrand(product.getBrandName());
        directBuyItem.setProductCategoryId(product.getProductCategoryId());
        directBuyItem.setProductSn(product.getProductSn());
        directBuyItem.setProductSubTitle(product.getSubTitle());
        directBuyItem.setPrice(skuStock.getPrice());
        directBuyItem.setQuantity(directBuyParam.getQuantity());
        return promotionService.calcCartPromotion(Collections.singletonList(directBuyItem));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderOperationResult paySuccess(Long orderId, Integer payType) {
        if (orderId == null) {
            return OrderOperationResult.NOT_FOUND;
        }
        //支付成功必须是当前登录会员自己的订单
        UmsMember currentMember = memberService.getCurrentMember();
        return payOrder(orderId, payType, currentMember.getId());
    }

    /**
     * 支付成功的唯一幂等实现：只允许把待付款订单转为待发货。
     * 只有带原状态条件的更新真实命中时才扣减库存，重复回调不会再次扣减库存。
     *
     * @param requiredMemberId 需要校验的订单归属会员；为 null 表示由支付平台回调触发，不校验会员归属
     */
    private OrderOperationResult payOrder(Long orderId, Integer payType, Long requiredMemberId) {
        OmsOrder order = orderMapper.selectByPrimaryKey(orderId);
        if (order == null || !Integer.valueOf(0).equals(order.getDeleteStatus())) {
            return OrderOperationResult.NOT_FOUND;
        }
        if (requiredMemberId != null && !requiredMemberId.equals(order.getMemberId())) {
            log.warn("会员{}尝试支付他人订单{}", requiredMemberId, orderId);
            return OrderOperationResult.REJECTED;
        }
        if (!isSupportedPayType(payType)) {
            log.warn("支付方式不合法，orderId:{}, payType:{}", orderId, payType);
            return OrderOperationResult.REJECTED;
        }
        if (order.getStatus() != null && order.getStatus() == ORDER_STATUS_WAIT_DELIVER) {
            //已支付，重复回调安全幂等
            return OrderOperationResult.IDEMPOTENT;
        }
        if (order.getStatus() == null || order.getStatus() != ORDER_STATUS_UNPAID) {
            //已关闭、已完成、无效等终态不允许再次标记支付成功
            log.warn("订单{}当前状态{}不允许标记支付成功", orderId, order.getStatus());
            return OrderOperationResult.REJECTED;
        }
        int updated = portalOrderDao.payOrderIfUnpaid(orderId, payType);
        if (updated != 1) {
            OmsOrder latestOrder = orderMapper.selectByPrimaryKey(orderId);
            if (latestOrder != null && latestOrder.getStatus() != null
                    && latestOrder.getStatus() == ORDER_STATUS_WAIT_DELIVER) {
                return OrderOperationResult.IDEMPOTENT;
            }
            log.warn("订单{}支付状态转换未生效", orderId);
            return OrderOperationResult.REJECTED;
        }
        //首次支付成功：扣减真实库存并释放锁定库存，失败时回滚状态转换，避免出现已支付但库存未扣减
        List<OmsOrderItem> orderItemList = listOrderItems(orderId);
        if (CollectionUtils.isEmpty(orderItemList)) {
            log.error("订单{}缺少订单明细，支付未完成", orderId);
            Asserts.fail("订单商品数据异常，支付未完成");
        }
        for (Map.Entry<Long, Integer> entry : aggregateSkuQuantity(orderItemList).entrySet()) {
            int rows = portalOrderDao.deductSkuStock(entry.getKey(), entry.getValue());
            if (rows != 1) {
                log.error("支付成功扣减库存失败，orderId:{}, skuId:{}, quantity:{}", orderId, entry.getKey(), entry.getValue());
                Asserts.fail("库存扣减失败，支付未完成");
            }
        }
        return OrderOperationResult.SUCCESS;
    }

    @Override
    public Integer cancelTimeOutOrder() {
        OmsOrderSetting orderSetting = orderSettingMapper.selectByPrimaryKey(1L);
        if (orderSetting == null || orderSetting.getNormalOrderOvertime() == null) {
            return 0;
        }
        //查询超时、未支付的订单及订单详情
        List<OmsOrderDetail> timeOutOrders = portalOrderDao.getTimeOutOrders(orderSetting.getNormalOrderOvertime());
        if (CollectionUtils.isEmpty(timeOutOrders)) {
            return 0;
        }
        int count = 0;
        List<Long> failedOrderIds = new ArrayList<>();
        for (OmsOrderDetail timeOutOrder : timeOutOrders) {
            Long orderId = timeOutOrder.getId();
            try {
                //每个订单独立开启事务：补偿失败只回滚当前订单，不会把同一批次其它已取消订单一起回滚
                OrderOperationResult result = transactionTemplate.execute(status -> cancelOrderInternal(orderId));
                //复用统一的幂等取消逻辑：已支付订单不会被关闭，重复执行不会重复补偿
                if (result != null && result.isTransitioned()) {
                    count++;
                } else if (OrderOperationResult.REJECTED.equals(result) || OrderOperationResult.NOT_FOUND.equals(result)) {
                    log.warn("超时订单取消未生效，orderId:{}, orderSn:{}, result:{}",
                            orderId, timeOutOrder.getOrderSn(), result);
                }
            } catch (Exception e) {
                //补偿失败不允许被静默吞掉：记录订单信息后收集失败订单，方法末尾统一抛出
                failedOrderIds.add(orderId);
                log.error("超时订单取消失败，订单状态转换已回滚，orderId:{}, orderSn:{}, memberId:{}",
                        orderId, timeOutOrder.getOrderSn(), timeOutOrder.getMemberId(), e);
            }
        }
        if (!failedOrderIds.isEmpty()) {
            Asserts.fail("超时订单取消部分失败，已成功取消" + count + "个，失败订单ID：" + failedOrderIds);
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderOperationResult cancelOrder(Long orderId) {
        return cancelOrderInternal(orderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderOperationResult cancelMemberOrder(Long orderId) {
        if (orderId == null) {
            return OrderOperationResult.NOT_FOUND;
        }
        UmsMember currentMember = memberService.getCurrentMember();
        OmsOrder order = orderMapper.selectByPrimaryKey(orderId);
        if (order == null || !Integer.valueOf(0).equals(order.getDeleteStatus())) {
            return OrderOperationResult.NOT_FOUND;
        }
        if (!currentMember.getId().equals(order.getMemberId())) {
            log.warn("会员{}尝试取消他人订单{}", currentMember.getId(), orderId);
            return OrderOperationResult.REJECTED;
        }
        return cancelOrderInternal(orderId);
    }

    /**
     * 取消订单的唯一幂等实现：只允许把待付款订单转为已关闭。
     * 用户取消、延迟消息取消、定时超时取消都复用本方法，只有状态转换真实命中时才执行补偿。
     */
    private OrderOperationResult cancelOrderInternal(Long orderId) {
        if (orderId == null) {
            return OrderOperationResult.NOT_FOUND;
        }
        OmsOrder order = orderMapper.selectByPrimaryKey(orderId);
        if (order == null || !Integer.valueOf(0).equals(order.getDeleteStatus())) {
            return OrderOperationResult.NOT_FOUND;
        }
        if (order.getStatus() != null && order.getStatus() == ORDER_STATUS_CLOSED) {
            //已关闭，重复取消安全幂等，不再重复返还库存、优惠券和积分
            return OrderOperationResult.IDEMPOTENT;
        }
        if (order.getStatus() == null || order.getStatus() != ORDER_STATUS_UNPAID) {
            //已支付、已发货等状态不允许关闭
            log.warn("订单{}当前状态{}不允许取消", orderId, order.getStatus());
            return OrderOperationResult.REJECTED;
        }
        int updated = portalOrderDao.closeOrderIfUnpaid(orderId);
        if (updated != 1) {
            OmsOrder latestOrder = orderMapper.selectByPrimaryKey(orderId);
            if (latestOrder != null && latestOrder.getStatus() != null
                    && latestOrder.getStatus() == ORDER_STATUS_CLOSED) {
                return OrderOperationResult.IDEMPOTENT;
            }
            log.warn("订单{}关闭状态转换未生效", orderId);
            return OrderOperationResult.REJECTED;
        }
        compensateClosedOrder(order);
        return OrderOperationResult.SUCCESS;
    }

    /**
     * 订单关闭后的统一补偿：释放锁定库存、返还优惠券与积分。
     * 仅在状态转换成功后调用，因此重复取消不会重复补偿。
     * <p>
     * 任何一项补偿失败都必须抛出异常回滚本次状态转换，不能只记录日志后提交已关闭状态，
     * 否则重试时只会返回幂等结果，导致库存、优惠券或积分永久丢失。
     */
    private void compensateClosedOrder(OmsOrder order) {
        Long orderId = order.getId();
        List<OmsOrderItem> orderItemList = listOrderItems(orderId);
        for (Map.Entry<Long, Integer> entry : aggregateSkuQuantity(orderItemList).entrySet()) {
            int rows = portalOrderDao.releaseSkuStockLock(entry.getKey(), entry.getValue());
            if (rows != 1) {
                log.error("订单关闭补偿失败：释放锁定库存未命中，orderSn:{}, orderId:{}, memberId:{}, skuId:{}, quantity:{}",
                        order.getOrderSn(), orderId, order.getMemberId(), entry.getKey(), entry.getValue());
                Asserts.fail("订单取消失败，锁定库存释放未完成");
            }
        }
        if (order.getCouponId() != null && order.getCouponId() > 0) {
            int rows = couponHistoryDao.returnCoupon(orderId, order.getMemberId());
            if (rows != 1) {
                log.error("订单关闭补偿失败：返还优惠券未命中，orderSn:{}, orderId:{}, memberId:{}, couponId:{}",
                        order.getOrderSn(), orderId, order.getMemberId(), order.getCouponId());
                Asserts.fail("订单取消失败，优惠券返还未完成");
            }
        }
        if (order.getUseIntegration() != null && order.getUseIntegration() > 0) {
            if (!memberService.refundIntegration(order.getMemberId(), order.getUseIntegration())) {
                log.error("订单关闭补偿失败：返还积分未命中，orderSn:{}, orderId:{}, memberId:{}, useIntegration:{}",
                        order.getOrderSn(), orderId, order.getMemberId(), order.getUseIntegration());
                Asserts.fail("订单取消失败，积分返还未完成");
            }
        }
    }

    @Override
    public void sendDelayMessageCancelOrder(Long orderId) {
        //获取订单超时时间
        OmsOrderSetting orderSetting = orderSettingMapper.selectByPrimaryKey(1L);
        long delayTimes = orderSetting.getNormalOrderOvertime() * 60 * 1000;
        //发送延迟消息
        cancelOrderSender.sendMessage(orderId, delayTimes);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderOperationResult confirmReceiveOrder(Long orderId) {
        if (orderId == null) {
            return OrderOperationResult.NOT_FOUND;
        }
        UmsMember member = memberService.getCurrentMember();
        OmsOrder order = orderMapper.selectByPrimaryKey(orderId);
        if (order == null || !Integer.valueOf(0).equals(order.getDeleteStatus())) {
            return OrderOperationResult.NOT_FOUND;
        }
        if (!member.getId().equals(order.getMemberId())) {
            log.warn("会员{}尝试确认他人订单{}", member.getId(), orderId);
            return OrderOperationResult.REJECTED;
        }
        if (order.getStatus() != null && order.getStatus() == ORDER_STATUS_COMPLETED) {
            //已完成，重复确认收货安全幂等
            return OrderOperationResult.IDEMPOTENT;
        }
        if (order.getStatus() == null || order.getStatus() != ORDER_STATUS_DELIVERED) {
            log.warn("订单{}当前状态{}不允许确认收货", orderId, order.getStatus());
            return OrderOperationResult.REJECTED;
        }
        //只允许 2->3 的条件更新，不再使用全字段覆盖写入
        if (portalOrderDao.confirmReceiveIfDelivered(orderId) == 1) {
            return OrderOperationResult.SUCCESS;
        }
        OmsOrder latestOrder = orderMapper.selectByPrimaryKey(orderId);
        if (latestOrder != null && latestOrder.getStatus() != null
                && latestOrder.getStatus() == ORDER_STATUS_COMPLETED) {
            return OrderOperationResult.IDEMPOTENT;
        }
        log.warn("订单{}确认收货状态转换未生效", orderId);
        return OrderOperationResult.REJECTED;
    }

    @Override
    public CommonPage<OmsOrderDetail> list(Integer status, Integer pageNum, Integer pageSize) {
        if(status==-1){
            status = null;
        }
        UmsMember member = memberService.getCurrentMember();
        PageHelper.startPage(pageNum,pageSize);
        OmsOrderExample orderExample = new OmsOrderExample();
        OmsOrderExample.Criteria criteria = orderExample.createCriteria();
        criteria.andDeleteStatusEqualTo(0)
                .andMemberIdEqualTo(member.getId());
        if(status!=null){
            criteria.andStatusEqualTo(status);
        }
        orderExample.setOrderByClause("create_time desc");
        List<OmsOrder> orderList = orderMapper.selectByExample(orderExample);
        CommonPage<OmsOrder> orderPage = CommonPage.restPage(orderList);
        //设置分页信息
        CommonPage<OmsOrderDetail> resultPage = new CommonPage<>();
        resultPage.setPageNum(orderPage.getPageNum());
        resultPage.setPageSize(orderPage.getPageSize());
        resultPage.setTotal(orderPage.getTotal());
        resultPage.setTotalPage(orderPage.getTotalPage());
        if(CollUtil.isEmpty(orderList)){
            return resultPage;
        }
        //设置数据信息
        List<Long> orderIds = orderList.stream().map(OmsOrder::getId).collect(Collectors.toList());
        OmsOrderItemExample orderItemExample = new OmsOrderItemExample();
        orderItemExample.createCriteria().andOrderIdIn(orderIds);
        List<OmsOrderItem> orderItemList = orderItemMapper.selectByExample(orderItemExample);
        List<OmsOrderDetail> orderDetailList = new ArrayList<>();
        for (OmsOrder omsOrder : orderList) {
            OmsOrderDetail orderDetail = new OmsOrderDetail();
            BeanUtil.copyProperties(omsOrder,orderDetail);
            List<OmsOrderItem> relatedItemList = orderItemList.stream().filter(item -> item.getOrderId().equals(orderDetail.getId())).collect(Collectors.toList());
            orderDetail.setOrderItemList(relatedItemList);
            orderDetailList.add(orderDetail);
        }
        resultPage.setList(orderDetailList);
        return resultPage;
    }

    @Override
    public OmsOrderDetail detail(Long orderId) {
        UmsMember member = memberService.getCurrentMember();
        OmsOrder omsOrder = orderMapper.selectByPrimaryKey(orderId);
        if (omsOrder == null || !Integer.valueOf(0).equals(omsOrder.getDeleteStatus())) {
            Asserts.fail("订单不存在");
        }
        if (!member.getId().equals(omsOrder.getMemberId())) {
            log.warn("会员{}尝试查看他人订单{}", member.getId(), orderId);
            Asserts.fail("不能查看他人订单！");
        }
        OmsOrderItemExample example = new OmsOrderItemExample();
        example.createCriteria().andOrderIdEqualTo(orderId);
        List<OmsOrderItem> orderItemList = orderItemMapper.selectByExample(example);
        OmsOrderDetail orderDetail = new OmsOrderDetail();
        BeanUtil.copyProperties(omsOrder,orderDetail);
        orderDetail.setOrderItemList(orderItemList);
        return orderDetail;
    }

    @Override
    public void deleteOrder(Long orderId) {
        UmsMember member = memberService.getCurrentMember();
        OmsOrder order = orderMapper.selectByPrimaryKey(orderId);
        if (order == null || !Integer.valueOf(0).equals(order.getDeleteStatus())) {
            Asserts.fail("订单不存在");
        }
        if(!member.getId().equals(order.getMemberId())){
            Asserts.fail("不能删除他人订单！");
        }
        if(order.getStatus()==3||order.getStatus()==4){
            order.setDeleteStatus(1);
            orderMapper.updateByPrimaryKey(order);
        }else{
            Asserts.fail("只能删除已完成或已关闭的订单！");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderOperationResult paySuccessByOrderSn(String orderSn, Integer payType) {
        if (orderSn == null || orderSn.isEmpty()) {
            return OrderOperationResult.NOT_FOUND;
        }
        OmsOrderExample example =  new OmsOrderExample();
        example.createCriteria()
                .andOrderSnEqualTo(orderSn)
                .andDeleteStatusEqualTo(0);
        List<OmsOrder> orderList = orderMapper.selectByExample(example);
        if(CollUtil.isEmpty(orderList)){
            log.warn("支付回调未找到订单，orderSn:{}", orderSn);
            return OrderOperationResult.NOT_FOUND;
        }
        //不再按 status=0 过滤，由幂等支付逻辑区分首次成功与重复回调
        return payOrder(orderList.get(0).getId(), payType, null);
    }

    /**
     * 生成18位订单编号:8位日期+2位平台号码+2位支付方式+6位以上自增id
     */
    private String generateOrderSn(OmsOrder order) {
        StringBuilder sb = new StringBuilder();
        String date = new SimpleDateFormat("yyyyMMdd").format(new Date());
        String key = REDIS_DATABASE+":"+ REDIS_KEY_ORDER_ID + date;
        Long increment = redisService.incr(key, 1);
        sb.append(date);
        sb.append(String.format("%02d", order.getSourceType()));
        sb.append(String.format("%02d", order.getPayType()));
        String incrementStr = increment.toString();
        if (incrementStr.length() <= 6) {
            sb.append(String.format("%06d", increment));
        } else {
            sb.append(incrementStr);
        }
        return sb.toString();
    }

    /**
     * 删除下单商品的购物车信息
     */
    private void deleteCartItemList(List<CartPromotionItem> cartPromotionItemList, UmsMember currentMember) {
        List<Long> ids = new ArrayList<>();
        for (CartPromotionItem cartPromotionItem : cartPromotionItemList) {
            ids.add(cartPromotionItem.getId());
        }
        cartItemService.delete(currentMember.getId(), ids);
    }

    /**
     * 计算该订单赠送的成长值
     */
    private Integer calcGiftGrowth(List<OmsOrderItem> orderItemList) {
        Integer sum = 0;
        for (OmsOrderItem orderItem : orderItemList) {
            sum = sum + orderItem.getGiftGrowth() * orderItem.getProductQuantity();
        }
        return sum;
    }

    /**
     * 计算该订单赠送的积分
     */
    private Integer calcGifIntegration(List<OmsOrderItem> orderItemList) {
        int sum = 0;
        for (OmsOrderItem orderItem : orderItemList) {
            sum += orderItem.getGiftIntegration() * orderItem.getProductQuantity();
        }
        return sum;
    }

    private void handleRealAmount(List<OmsOrderItem> orderItemList) {
        for (OmsOrderItem orderItem : orderItemList) {
            //原价-促销优惠-优惠券抵扣-积分抵扣
            BigDecimal realAmount = orderItem.getProductPrice()
                    .subtract(orderItem.getPromotionAmount())
                    .subtract(orderItem.getCouponAmount())
                    .subtract(orderItem.getIntegrationAmount());
            orderItem.setRealAmount(realAmount);
        }
    }

    /**
     * 获取订单促销信息
     */
    private String getOrderPromotionInfo(List<OmsOrderItem> orderItemList) {
        StringBuilder sb = new StringBuilder();
        for (OmsOrderItem orderItem : orderItemList) {
            sb.append(orderItem.getPromotionName());
            sb.append(";");
        }
        String result = sb.toString();
        if (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 计算订单应付金额
     */
    private BigDecimal calcPayAmount(OmsOrder order) {
        //总金额+运费-促销优惠-优惠券优惠-积分抵扣
        BigDecimal payAmount = order.getTotalAmount()
                .add(order.getFreightAmount())
                .subtract(order.getPromotionAmount())
                .subtract(order.getCouponAmount())
                .subtract(order.getIntegrationAmount());
        return payAmount;
    }

    /**
     * 计算订单优惠券金额
     */
    private BigDecimal calcIntegrationAmount(List<OmsOrderItem> orderItemList) {
        BigDecimal integrationAmount = new BigDecimal(0);
        for (OmsOrderItem orderItem : orderItemList) {
            if (orderItem.getIntegrationAmount() != null) {
                integrationAmount = integrationAmount.add(orderItem.getIntegrationAmount().multiply(new BigDecimal(orderItem.getProductQuantity())));
            }
        }
        return integrationAmount;
    }

    /**
     * 计算订单优惠券金额
     */
    private BigDecimal calcCouponAmount(List<OmsOrderItem> orderItemList) {
        BigDecimal couponAmount = new BigDecimal(0);
        for (OmsOrderItem orderItem : orderItemList) {
            if (orderItem.getCouponAmount() != null) {
                couponAmount = couponAmount.add(orderItem.getCouponAmount().multiply(new BigDecimal(orderItem.getProductQuantity())));
            }
        }
        return couponAmount;
    }

    /**
     * 计算订单活动优惠
     */
    private BigDecimal calcPromotionAmount(List<OmsOrderItem> orderItemList) {
        BigDecimal promotionAmount = new BigDecimal(0);
        for (OmsOrderItem orderItem : orderItemList) {
            if (orderItem.getPromotionAmount() != null) {
                promotionAmount = promotionAmount.add(orderItem.getPromotionAmount().multiply(new BigDecimal(orderItem.getProductQuantity())));
            }
        }
        return promotionAmount;
    }

    /**
     * 获取可用积分抵扣金额
     *
     * @param useIntegration 使用的积分数量
     * @param totalAmount    订单总金额
     * @param currentMember  使用的用户
     * @param hasCoupon      是否已经使用优惠券
     */
    private BigDecimal getUseIntegrationAmount(Integer useIntegration, BigDecimal totalAmount, UmsMember currentMember, boolean hasCoupon) {
        BigDecimal zeroAmount = new BigDecimal(0);
        //判断用户是否有这么多积分
        if (useIntegration.compareTo(currentMember.getIntegration()) > 0) {
            return zeroAmount;
        }
        //根据积分使用规则判断是否可用
        //是否可与优惠券共用
        UmsIntegrationConsumeSetting integrationConsumeSetting = integrationConsumeSettingMapper.selectByPrimaryKey(1L);
        if (hasCoupon && integrationConsumeSetting.getCouponStatus().equals(0)) {
            //不可与优惠券共用
            return zeroAmount;
        }
        //是否达到最低使用积分门槛
        if (useIntegration.compareTo(integrationConsumeSetting.getUseUnit()) < 0) {
            return zeroAmount;
        }
        //是否超过订单抵用最高百分比
        BigDecimal integrationAmount = new BigDecimal(useIntegration).divide(new BigDecimal(integrationConsumeSetting.getUseUnit()), 2, RoundingMode.HALF_EVEN);
        BigDecimal maxPercent = new BigDecimal(integrationConsumeSetting.getMaxPercentPerOrder()).divide(new BigDecimal(100), 2, RoundingMode.HALF_EVEN);
        if (integrationAmount.compareTo(totalAmount.multiply(maxPercent)) > 0) {
            return zeroAmount;
        }
        return integrationAmount;
    }

    /**
     * 对优惠券优惠进行处理
     *
     * @param orderItemList       order_item列表
     * @param couponHistoryDetail 可用优惠券详情
     */
    private void handleCouponAmount(List<OmsOrderItem> orderItemList, SmsCouponHistoryDetail couponHistoryDetail) {
        SmsCoupon coupon = couponHistoryDetail.getCoupon();
        if (coupon.getUseType().equals(0)) {
            //全场通用
            calcPerCouponAmount(orderItemList, coupon);
        } else if (coupon.getUseType().equals(1)) {
            //指定分类
            List<OmsOrderItem> couponOrderItemList = getCouponOrderItemByRelation(couponHistoryDetail, orderItemList, 0);
            calcPerCouponAmount(couponOrderItemList, coupon);
        } else if (coupon.getUseType().equals(2)) {
            //指定商品
            List<OmsOrderItem> couponOrderItemList = getCouponOrderItemByRelation(couponHistoryDetail, orderItemList, 1);
            calcPerCouponAmount(couponOrderItemList, coupon);
        }
    }

    /**
     * 对每个下单商品进行优惠券金额分摊的计算
     *
     * @param orderItemList 可用优惠券的下单商品商品
     */
    private void calcPerCouponAmount(List<OmsOrderItem> orderItemList, SmsCoupon coupon) {
        BigDecimal totalAmount = calcTotalAmount(orderItemList);
        for (OmsOrderItem orderItem : orderItemList) {
            //(商品价格/可用商品总价)*优惠券面额
            BigDecimal couponAmount = orderItem.getProductPrice().divide(totalAmount, 3, RoundingMode.HALF_EVEN).multiply(coupon.getAmount());
            orderItem.setCouponAmount(couponAmount);
        }
    }

    /**
     * 获取与优惠券有关系的下单商品
     *
     * @param couponHistoryDetail 优惠券详情
     * @param orderItemList       下单商品
     * @param type                使用关系类型：0->相关分类；1->指定商品
     */
    private List<OmsOrderItem> getCouponOrderItemByRelation(SmsCouponHistoryDetail couponHistoryDetail, List<OmsOrderItem> orderItemList, int type) {
        List<OmsOrderItem> result = new ArrayList<>();
        if (type == 0) {
            List<Long> categoryIdList = new ArrayList<>();
            for (SmsCouponProductCategoryRelation productCategoryRelation : couponHistoryDetail.getCategoryRelationList()) {
                categoryIdList.add(productCategoryRelation.getProductCategoryId());
            }
            for (OmsOrderItem orderItem : orderItemList) {
                if (categoryIdList.contains(orderItem.getProductCategoryId())) {
                    result.add(orderItem);
                } else {
                    orderItem.setCouponAmount(new BigDecimal(0));
                }
            }
        } else if (type == 1) {
            List<Long> productIdList = new ArrayList<>();
            for (SmsCouponProductRelation productRelation : couponHistoryDetail.getProductRelationList()) {
                productIdList.add(productRelation.getProductId());
            }
            for (OmsOrderItem orderItem : orderItemList) {
                if (productIdList.contains(orderItem.getProductId())) {
                    result.add(orderItem);
                } else {
                    orderItem.setCouponAmount(new BigDecimal(0));
                }
            }
        }
        return result;
    }

    /**
     * 获取该用户可以使用的优惠券
     *
     * @param cartPromotionItemList 购物车优惠列表
     * @param couponId              使用优惠券id
     */
    private SmsCouponHistoryDetail getUseCoupon(List<CartPromotionItem> cartPromotionItemList, Long couponId) {
        List<SmsCouponHistoryDetail> couponHistoryDetailList = memberCouponService.listCart(cartPromotionItemList, 1);
        for (SmsCouponHistoryDetail couponHistoryDetail : couponHistoryDetailList) {
            if (couponHistoryDetail.getCoupon().getId().equals(couponId)) {
                return couponHistoryDetail;
            }
        }
        return null;
    }

    /**
     * 计算总金额
     */
    private BigDecimal calcTotalAmount(List<OmsOrderItem> orderItemList) {
        BigDecimal totalAmount = new BigDecimal("0");
        for (OmsOrderItem item : orderItemList) {
            totalAmount = totalAmount.add(item.getProductPrice().multiply(new BigDecimal(item.getProductQuantity())));
        }
        return totalAmount;
    }

    /**
     * 锁定下单商品的所有库存。
     * 按 skuId 升序加锁避免交叉加锁，使用带可用库存条件的原子更新，锁定失败立即回滚整单。
     */
    private void lockStock(List<CartPromotionItem> cartPromotionItemList) {
        Map<Long, Integer> quantityBySku = new TreeMap<>();
        for (CartPromotionItem cartPromotionItem : cartPromotionItemList) {
            Integer quantity = cartPromotionItem.getQuantity();
            if (cartPromotionItem.getProductSkuId() == null || quantity == null || quantity <= 0) {
                Asserts.fail("购买数量必须大于0");
            }
            quantityBySku.merge(cartPromotionItem.getProductSkuId(), quantity, Integer::sum);
        }
        for (Map.Entry<Long, Integer> entry : quantityBySku.entrySet()) {
            int rows = portalOrderDao.lockSkuStock(entry.getKey(), entry.getValue());
            if (rows != 1) {
                log.warn("锁定库存失败，skuId:{}, quantity:{}", entry.getKey(), entry.getValue());
                Asserts.fail("库存不足，无法下单");
            }
        }
    }

    /**
     * 原子占用优惠券并绑定到当前订单，占用失败说明优惠券不可用或已被占用
     */
    private void occupyCoupon(Long memberId, Long couponId, Long orderId, String orderSn) {
        int rows = couponHistoryDao.useCoupon(memberId, couponId, orderId, orderSn);
        if (rows != 1) {
            log.warn("优惠券占用失败，memberId:{}, couponId:{}, orderId:{}", memberId, couponId, orderId);
            Asserts.fail("该优惠券不可用");
        }
    }

    /**
     * 读取订单明细
     */
    private List<OmsOrderItem> listOrderItems(Long orderId) {
        OmsOrderItemExample example = new OmsOrderItemExample();
        example.createCriteria().andOrderIdEqualTo(orderId);
        return orderItemMapper.selectByExample(example);
    }

    /**
     * 按 skuId 升序聚合订单明细中的商品数量，保证加锁顺序一致
     */
    private Map<Long, Integer> aggregateSkuQuantity(List<OmsOrderItem> orderItemList) {
        Map<Long, Integer> quantityBySku = new TreeMap<>();
        if (CollectionUtils.isEmpty(orderItemList)) {
            return quantityBySku;
        }
        for (OmsOrderItem orderItem : orderItemList) {
            if (orderItem.getProductSkuId() == null || orderItem.getProductQuantity() == null) {
                continue;
            }
            quantityBySku.merge(orderItem.getProductSkuId(), orderItem.getProductQuantity(), Integer::sum);
        }
        return quantityBySku;
    }

    /**
     * 校验支付方式是否为系统支持的合法取值
     */
    private boolean isSupportedPayType(Integer payType) {
        return payType != null && (payType == PAY_TYPE_ALIPAY || payType == PAY_TYPE_WECHAT);
    }

    /**
     * 判断下单商品是否都有库存
     */
    private boolean hasStock(List<CartPromotionItem> cartPromotionItemList) {
        for (CartPromotionItem cartPromotionItem : cartPromotionItemList) {
            if (cartPromotionItem.getRealStock()==null //判断真实库存是否为空
                    ||cartPromotionItem.getRealStock() <= 0 //判断真实库存是否小于0
                    || cartPromotionItem.getRealStock() < cartPromotionItem.getQuantity()) //判断真实库存是否小于下单的数量
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 计算购物车中商品的价格
     */
    private ConfirmOrderResult.CalcAmount calcCartAmount(List<CartPromotionItem> cartPromotionItemList) {
        ConfirmOrderResult.CalcAmount calcAmount = new ConfirmOrderResult.CalcAmount();
        calcAmount.setFreightAmount(new BigDecimal(0));
        BigDecimal totalAmount = new BigDecimal("0");
        BigDecimal promotionAmount = new BigDecimal("0");
        for (CartPromotionItem cartPromotionItem : cartPromotionItemList) {
            totalAmount = totalAmount.add(cartPromotionItem.getPrice().multiply(new BigDecimal(cartPromotionItem.getQuantity())));
            promotionAmount = promotionAmount.add(cartPromotionItem.getReduceAmount().multiply(new BigDecimal(cartPromotionItem.getQuantity())));
        }
        calcAmount.setTotalAmount(totalAmount);
        calcAmount.setPromotionAmount(promotionAmount);
        calcAmount.setPayAmount(totalAmount.subtract(promotionAmount));
        return calcAmount;
    }

}

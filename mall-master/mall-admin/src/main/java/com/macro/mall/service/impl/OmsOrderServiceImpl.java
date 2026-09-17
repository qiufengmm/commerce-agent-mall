package com.macro.mall.service.impl;

import com.github.pagehelper.PageHelper;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.dao.OmsOrderDao;
import com.macro.mall.dao.OmsOrderOperateHistoryDao;
import com.macro.mall.dto.*;
import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.OmsOrderOperateHistoryMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderExample;
import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.model.OmsOrderItemExample;
import com.macro.mall.model.OmsOrderOperateHistory;
import com.macro.mall.service.OmsOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 订单管理Service实现类
 */
@Service
public class OmsOrderServiceImpl implements OmsOrderService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OmsOrderServiceImpl.class);
    @Autowired
    private OmsOrderMapper orderMapper;
    @Autowired
    private OmsOrderDao orderDao;
    @Autowired
    private OmsOrderOperateHistoryDao orderOperateHistoryDao;
    @Autowired
    private OmsOrderOperateHistoryMapper orderOperateHistoryMapper;
    @Autowired
    private OmsOrderItemMapper orderItemMapper;
    /**
     * 用于批量关闭时按订单拆分独立事务：单个订单补偿失败只回滚该订单，不影响同一批次其它订单
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Override
    public List<OmsOrder> list(OmsOrderQueryParam queryParam, Integer pageSize, Integer pageNum) {
        PageHelper.startPage(pageNum, pageSize);
        return orderDao.getList(queryParam);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int delivery(List<OmsOrderDeliveryParam> deliveryParamList) {
        if (CollectionUtils.isEmpty(deliveryParamList)) {
            return 0;
        }
        int count = 0;
        List<OmsOrderOperateHistory> operateHistoryList = new ArrayList<>();
        for (OmsOrderDeliveryParam deliveryParam : deliveryParamList) {
            //只对待发货订单执行 1->2，未命中的订单不能写入虚假操作历史
            int rows = orderDao.deliverOne(deliveryParam.getOrderId(), deliveryParam.getDeliveryCompany(), deliveryParam.getDeliverySn());
            if (rows == 1) {
                count++;
                operateHistoryList.add(buildOperateHistory(deliveryParam.getOrderId(), 2, "完成发货"));
            } else {
                LOGGER.warn("订单{}当前状态不允许发货，已跳过", deliveryParam.getOrderId());
            }
        }
        if (!operateHistoryList.isEmpty()) {
            orderOperateHistoryDao.insertList(operateHistoryList);
        }
        return count;
    }

    @Override
    public int close(List<Long> ids, String note) {
        if (CollectionUtils.isEmpty(ids)) {
            return 0;
        }
        int count = 0;
        List<Long> failedOrderIds = new ArrayList<>();
        for (Long id : ids) {
            try {
                //每个订单独立开启事务：补偿失败只回滚当前订单，不会把同一批次其它已关闭订单一起回滚
                Boolean closed = transactionTemplate.execute(status -> closeOneOrder(id, note));
                if (Boolean.TRUE.equals(closed)) {
                    count++;
                }
            } catch (Exception e) {
                //补偿失败不允许被静默吞掉：记录订单信息后收集失败订单，方法末尾统一抛出
                failedOrderIds.add(id);
                LOGGER.error("订单{}关闭失败，订单状态转换已回滚", id, e);
            }
        }
        if (!failedOrderIds.isEmpty()) {
            Asserts.fail("订单关闭部分失败，已成功关闭" + count + "个，失败订单ID：" + failedOrderIds);
        }
        return count;
    }

    /**
     * 关闭单个待付款订单：只允许 0->4，只有状态转换命中且补偿全部成功后才计入成功数量并写操作历史
     *
     * @return true 表示本次真正关闭成功
     */
    private boolean closeOneOrder(Long id, String note) {
        OmsOrder order = orderMapper.selectByPrimaryKey(id);
        if (order == null || !Integer.valueOf(0).equals(order.getDeleteStatus())) {
            LOGGER.warn("订单{}不存在或已删除，跳过关闭", id);
            return false;
        }
        if (!Integer.valueOf(0).equals(order.getStatus())) {
            LOGGER.warn("订单{}当前状态{}不允许关闭，跳过", id, order.getStatus());
            return false;
        }
        //只有待付款订单会被关闭，影响行数为 1 才执行补偿，避免重复返还库存、优惠券和积分
        if (orderDao.closeOrderIfUnpaid(id) != 1) {
            LOGGER.warn("订单{}关闭状态转换未生效，跳过", id);
            return false;
        }
        compensateClosedOrder(order);
        orderOperateHistoryDao.insertList(Collections.singletonList(buildOperateHistory(id, 4, "订单关闭:" + note)));
        return true;
    }

    /**
     * 关闭待付款订单后的统一补偿：释放锁定库存、返还优惠券与积分。
     * <p>
     * 任何一项补偿失败都必须抛出异常回滚本次状态转换，不能只记录日志后提交已关闭状态，
     * 否则重试时只会跳过该订单，导致库存、优惠券或积分永久丢失。
     */
    private void compensateClosedOrder(OmsOrder order) {
        OmsOrderItemExample example = new OmsOrderItemExample();
        example.createCriteria().andOrderIdEqualTo(order.getId());
        List<OmsOrderItem> orderItemList = orderItemMapper.selectByExample(example);
        Map<Long, Integer> quantityBySku = new TreeMap<>();
        for (OmsOrderItem orderItem : orderItemList) {
            if (orderItem.getProductSkuId() == null || orderItem.getProductQuantity() == null) {
                continue;
            }
            quantityBySku.merge(orderItem.getProductSkuId(), orderItem.getProductQuantity(), Integer::sum);
        }
        for (Map.Entry<Long, Integer> entry : quantityBySku.entrySet()) {
            int rows = orderDao.releaseSkuStockLock(entry.getKey(), entry.getValue());
            if (rows != 1) {
                LOGGER.error("订单关闭补偿失败：释放锁定库存未命中，orderSn:{}, orderId:{}, memberId:{}, skuId:{}, quantity:{}",
                        order.getOrderSn(), order.getId(), order.getMemberId(), entry.getKey(), entry.getValue());
                Asserts.fail("订单关闭失败，锁定库存释放未完成");
            }
        }
        if (order.getCouponId() != null && order.getCouponId() > 0) {
            int rows = orderDao.returnCoupon(order.getId(), order.getMemberId());
            if (rows != 1) {
                LOGGER.error("订单关闭补偿失败：返还优惠券未命中，orderSn:{}, orderId:{}, memberId:{}, couponId:{}",
                        order.getOrderSn(), order.getId(), order.getMemberId(), order.getCouponId());
                Asserts.fail("订单关闭失败，优惠券返还未完成");
            }
        }
        if (order.getUseIntegration() != null && order.getUseIntegration() > 0) {
            int rows = orderDao.refundIntegration(order.getMemberId(), order.getUseIntegration());
            if (rows != 1) {
                LOGGER.error("订单关闭补偿失败：返还积分未命中，orderSn:{}, orderId:{}, memberId:{}, useIntegration:{}",
                        order.getOrderSn(), order.getId(), order.getMemberId(), order.getUseIntegration());
                Asserts.fail("订单关闭失败，积分返还未完成");
            }
        }
    }

    private OmsOrderOperateHistory buildOperateHistory(Long orderId, Integer orderStatus, String note) {
        OmsOrderOperateHistory history = new OmsOrderOperateHistory();
        history.setOrderId(orderId);
        history.setCreateTime(new Date());
        history.setOperateMan("后台管理员");
        history.setOrderStatus(orderStatus);
        history.setNote(note);
        return history;
    }

    @Override
    public int delete(List<Long> ids) {
        OmsOrder record = new OmsOrder();
        record.setDeleteStatus(1);
        OmsOrderExample example = new OmsOrderExample();
        example.createCriteria().andDeleteStatusEqualTo(0).andIdIn(ids);
        return orderMapper.updateByExampleSelective(record, example);
    }

    @Override
    public OmsOrderDetail detail(Long id) {
        return orderDao.getDetail(id);
    }

    @Override
    public int updateReceiverInfo(OmsReceiverInfoParam receiverInfoParam) {
        OmsOrder order = new OmsOrder();
        order.setId(receiverInfoParam.getOrderId());
        order.setReceiverName(receiverInfoParam.getReceiverName());
        order.setReceiverPhone(receiverInfoParam.getReceiverPhone());
        order.setReceiverPostCode(receiverInfoParam.getReceiverPostCode());
        order.setReceiverDetailAddress(receiverInfoParam.getReceiverDetailAddress());
        order.setReceiverProvince(receiverInfoParam.getReceiverProvince());
        order.setReceiverCity(receiverInfoParam.getReceiverCity());
        order.setReceiverRegion(receiverInfoParam.getReceiverRegion());
        order.setModifyTime(new Date());
        int count = orderMapper.updateByPrimaryKeySelective(order);
        //插入操作记录
        OmsOrderOperateHistory history = new OmsOrderOperateHistory();
        history.setOrderId(receiverInfoParam.getOrderId());
        history.setCreateTime(new Date());
        history.setOperateMan("后台管理员");
        history.setOrderStatus(receiverInfoParam.getStatus());
        history.setNote("修改收货人信息");
        orderOperateHistoryMapper.insert(history);
        return count;
    }

    @Override
    public int updateMoneyInfo(OmsMoneyInfoParam moneyInfoParam) {
        OmsOrder order = new OmsOrder();
        order.setId(moneyInfoParam.getOrderId());
        order.setFreightAmount(moneyInfoParam.getFreightAmount());
        order.setDiscountAmount(moneyInfoParam.getDiscountAmount());
        order.setModifyTime(new Date());
        int count = orderMapper.updateByPrimaryKeySelective(order);
        //插入操作记录
        OmsOrderOperateHistory history = new OmsOrderOperateHistory();
        history.setOrderId(moneyInfoParam.getOrderId());
        history.setCreateTime(new Date());
        history.setOperateMan("后台管理员");
        history.setOrderStatus(moneyInfoParam.getStatus());
        history.setNote("修改费用信息");
        orderOperateHistoryMapper.insert(history);
        return count;
    }

    @Override
    public int updateNote(Long id, String note, Integer status) {
        OmsOrder order = new OmsOrder();
        order.setId(id);
        order.setNote(note);
        order.setModifyTime(new Date());
        int count = orderMapper.updateByPrimaryKeySelective(order);
        OmsOrderOperateHistory history = new OmsOrderOperateHistory();
        history.setOrderId(id);
        history.setCreateTime(new Date());
        history.setOperateMan("后台管理员");
        history.setOrderStatus(status);
        history.setNote("修改备注信息："+note);
        orderOperateHistoryMapper.insert(history);
        return count;
    }
}

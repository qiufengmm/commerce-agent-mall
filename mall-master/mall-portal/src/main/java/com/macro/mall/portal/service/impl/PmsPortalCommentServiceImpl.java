package com.macro.mall.portal.service.impl;

import com.github.pagehelper.PageHelper;
import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.PmsCommentMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.model.PmsComment;
import com.macro.mall.model.PmsCommentExample;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.domain.PmsCommentParam;
import com.macro.mall.portal.service.PmsPortalCommentService;
import com.macro.mall.portal.service.UmsMemberService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 商品评价管理Service实现类
 */
@Service
public class PmsPortalCommentServiceImpl implements PmsPortalCommentService {
    @Autowired
    private PmsCommentMapper commentMapper;
    @Autowired
    private OmsOrderMapper orderMapper;
    @Autowired
    private OmsOrderItemMapper orderItemMapper;
    @Autowired
    private UmsMemberService memberService;

    @Override
    public int add(PmsCommentParam param) {
        if (param.getOrderId() == null || param.getOrderItemId() == null) {
            Asserts.fail("订单信息不完整！");
        }
        UmsMember member = memberService.getCurrentMember();
        // 校验订单是否存在、是否属于当前会员
        OmsOrder order = orderMapper.selectByPrimaryKey(param.getOrderId());
        if (order == null) {
            Asserts.fail("订单不存在！");
        }
        if (!order.getMemberId().equals(member.getId())) {
            Asserts.fail("不能评价他人的订单！");
        }
        // 只有确认收货（已完成）的订单才允许评价
        if (order.getStatus() != 3) {
            Asserts.fail("确认收货后才能评价！");
        }
        // 校验订单明细是否属于该订单
        OmsOrderItem orderItem = orderItemMapper.selectByPrimaryKey(param.getOrderItemId());
        if (orderItem == null || !orderItem.getOrderId().equals(order.getId())) {
            Asserts.fail("订单明细不存在！");
        }
        // 一条订单明细只允许评价一次
        PmsCommentExample existExample = new PmsCommentExample();
        existExample.createCriteria().andOrderItemIdEqualTo(param.getOrderItemId());
        if (commentMapper.countByExample(existExample) > 0) {
            Asserts.fail("该商品已评价，不能重复提交！");
        }
        PmsComment comment = new PmsComment();
        comment.setMemberId(member.getId());
        comment.setOrderId(order.getId());
        comment.setOrderItemId(orderItem.getId());
        comment.setProductId(orderItem.getProductId());
        comment.setProductName(orderItem.getProductName());
        comment.setProductAttribute(orderItem.getProductAttr());
        comment.setMemberNickName(member.getNickname());
        comment.setMemberIcon(member.getIcon());
        comment.setStar(param.getStar() == null ? 5 : param.getStar());
        comment.setContent(param.getContent());
        comment.setPics(param.getPics());
        comment.setCreateTime(new Date());
        comment.setShowStatus(1);
        comment.setCollectCouont(0);
        comment.setReadCount(0);
        comment.setReplayCount(0);
        return commentMapper.insertSelective(comment);
    }

    @Override
    public CommonPage<PmsComment> list(Long productId, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        PmsCommentExample example = new PmsCommentExample();
        example.createCriteria().andProductIdEqualTo(productId).andShowStatusEqualTo(1);
        example.setOrderByClause("create_time desc");
        List<PmsComment> commentList = commentMapper.selectByExampleWithBLOBs(example);
        return CommonPage.restPage(commentList);
    }

    @Override
    public boolean exists(Long orderItemId) {
        if (orderItemId == null) {
            return false;
        }
        PmsCommentExample example = new PmsCommentExample();
        example.createCriteria().andOrderItemIdEqualTo(orderItemId);
        return commentMapper.countByExample(example) > 0;
    }
}

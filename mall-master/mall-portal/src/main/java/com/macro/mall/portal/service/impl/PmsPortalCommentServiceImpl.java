package com.macro.mall.portal.service.impl;

import com.github.pagehelper.PageHelper;
import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.PmsCommentMapper;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.model.PmsComment;
import com.macro.mall.model.PmsCommentExample;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.model.PmsProductExample;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.domain.PmsCommentParam;
import com.macro.mall.portal.domain.PmsCommentResult;
import com.macro.mall.portal.service.PmsPortalCommentService;
import com.macro.mall.portal.service.UmsMemberService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    @Autowired
    private PmsProductMapper productMapper;

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
    public CommonPage<PmsCommentResult> listMy(Integer pageNum, Integer pageSize) {
        UmsMember member = memberService.getCurrentMember();
        PageHelper.startPage(pageNum, pageSize);
        PmsCommentExample example = new PmsCommentExample();
        // 只查当前会员自己的评价，避免越权查看他人评价
        example.createCriteria().andMemberIdEqualTo(member.getId());
        example.setOrderByClause("create_time desc");
        List<PmsComment> commentList = commentMapper.selectByExampleWithBLOBs(example);
        // 先基于 PageHelper 的分页对象生成分页信息，再转换数据，避免丢失分页参数
        CommonPage<PmsComment> commentPage = CommonPage.restPage(commentList);
        // 收集商品id，一次性批量查询商品图片，避免逐条查询
        Map<Long, String> productPicMap = new HashMap<>();
        List<Long> productIds = new ArrayList<>();
        for (PmsComment comment : commentList) {
            if (comment.getProductId() != null && !productIds.contains(comment.getProductId())) {
                productIds.add(comment.getProductId());
            }
        }
        if (!productIds.isEmpty()) {
            PmsProductExample productExample = new PmsProductExample();
            productExample.createCriteria().andIdIn(productIds);
            List<PmsProduct> productList = productMapper.selectByExample(productExample);
            for (PmsProduct product : productList) {
                productPicMap.put(product.getId(), product.getPic());
            }
        }
        List<PmsCommentResult> resultList = new ArrayList<>();
        for (PmsComment comment : commentList) {
            PmsCommentResult result = new PmsCommentResult();
            result.setId(comment.getId());
            result.setProductId(comment.getProductId());
            result.setMemberId(comment.getMemberId());
            result.setOrderId(comment.getOrderId());
            result.setOrderItemId(comment.getOrderItemId());
            result.setProductName(comment.getProductName());
            result.setProductPic(productPicMap.get(comment.getProductId()));
            result.setProductAttribute(comment.getProductAttribute());
            result.setMemberNickName(comment.getMemberNickName());
            result.setMemberIcon(comment.getMemberIcon());
            result.setStar(comment.getStar());
            result.setContent(comment.getContent());
            result.setPics(comment.getPics());
            result.setCreateTime(comment.getCreateTime());
            result.setShowStatus(comment.getShowStatus());
            resultList.add(result);
        }
        CommonPage<PmsCommentResult> result = new CommonPage<>();
        result.setPageNum(commentPage.getPageNum());
        result.setPageSize(commentPage.getPageSize());
        result.setTotalPage(commentPage.getTotalPage());
        result.setTotal(commentPage.getTotal());
        result.setList(resultList);
        return result;
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

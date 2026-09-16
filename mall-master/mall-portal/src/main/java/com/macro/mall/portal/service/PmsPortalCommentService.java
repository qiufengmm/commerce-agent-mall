package com.macro.mall.portal.service;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.model.PmsComment;
import com.macro.mall.portal.domain.PmsCommentParam;

/**
 * 商品评价管理Service
 */
public interface PmsPortalCommentService {
    /**
     * 提交商品评价，需满足：订单属于当前会员、订单已完成、订单明细属于该订单且未评价过
     */
    int add(PmsCommentParam param);

    /**
     * 分页查询某个商品的评价
     */
    CommonPage<PmsComment> list(Long productId, Integer pageNum, Integer pageSize);

    /**
     * 判断某个订单明细是否已经评价过
     */
    boolean exists(Long orderItemId);
}

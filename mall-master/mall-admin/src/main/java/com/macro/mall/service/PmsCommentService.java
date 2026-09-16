package com.macro.mall.service;

import com.macro.mall.dto.PmsCommentQueryParam;
import com.macro.mall.dto.PmsCommentReplyParam;
import com.macro.mall.model.PmsComment;
import com.macro.mall.model.PmsCommentReplay;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商品评价管理Service
 */
public interface PmsCommentService {
    /**
     * 分页查询商品评价
     */
    List<PmsComment> list(PmsCommentQueryParam queryParam, Integer pageSize, Integer pageNum);

    /**
     * 修改评价显示状态：0->不显示；1->显示
     */
    int updateShowStatus(Long id, Integer showStatus);

    /**
     * 商家回复评价，写入回复记录并同步回复数
     */
    @Transactional
    int reply(PmsCommentReplyParam replyParam);

    /**
     * 查询某条评价的回复列表
     */
    List<PmsCommentReplay> listReplay(Long commentId);
}

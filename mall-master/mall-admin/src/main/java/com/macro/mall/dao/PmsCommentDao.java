package com.macro.mall.dao;

import com.macro.mall.dto.PmsCommentQueryParam;
import com.macro.mall.model.PmsComment;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品评价管理自定义Dao
 */
public interface PmsCommentDao {
    /**
     * 根据条件查询评价列表
     */
    List<PmsComment> getList(@Param("queryParam") PmsCommentQueryParam queryParam);

    /**
     * 按回复表实际数量重算评价的回复数
     */
    int refreshReplayCount(@Param("commentId") Long commentId);
}

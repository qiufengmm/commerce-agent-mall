package com.macro.mall.portal.dao;

import org.apache.ibatis.annotations.Param;

/**
 * 前台会员自定义Dao
 * <p>
 * 积分增减必须使用带条件的原子更新，避免并发扣减导致积分丢失或变成负数。
 */
public interface PortalMemberDao {
    /**
     * 扣减会员积分，仅在积分充足时执行。
     *
     * @return 影响行数，0 表示积分不足
     */
    int deductIntegration(@Param("memberId") Long memberId, @Param("integration") Integer integration);

    /**
     * 返还会员积分。
     *
     * @return 影响行数，0 表示会员不存在
     */
    int refundIntegration(@Param("memberId") Long memberId, @Param("integration") Integer integration);
}

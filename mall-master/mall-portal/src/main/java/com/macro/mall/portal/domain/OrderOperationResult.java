package com.macro.mall.portal.domain;

/**
 * 订单状态操作的统一业务结果。
 * <p>
 * 用于支付、取消、确认收货等所有会改变订单状态的操作，避免用异常或魔法返回值表达业务结果。
 */
public enum OrderOperationResult {
    /**
     * 本次调用真正完成了状态转换，后续补偿已经执行
     */
    SUCCESS,
    /**
     * 目标状态已经达成，本次调用被安全地忽略，未重复执行任何补偿
     */
    IDEMPOTENT,
    /**
     * 订单归属不符或当前状态不允许该转换
     */
    REJECTED,
    /**
     * 订单不存在或已被删除
     */
    NOT_FOUND;

    /**
     * 是否可以作为成功结果返回给调用方（含幂等成功）
     */
    public boolean isOk() {
        return this == SUCCESS || this == IDEMPOTENT;
    }

    /**
     * 是否真正执行了状态转换
     */
    public boolean isTransitioned() {
        return this == SUCCESS;
    }
}

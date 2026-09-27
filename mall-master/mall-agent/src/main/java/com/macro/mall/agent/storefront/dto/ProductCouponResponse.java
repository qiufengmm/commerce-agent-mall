package com.macro.mall.agent.storefront.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 公开/可用优惠券。
 *
 * <p>字段与 Python {@code schemas.Coupon} 对齐：金额使用 {@link BigDecimal} 保留门户返回的精度；
 * 时间字段容忍 ISO-8601 字符串与 epoch 数值，无法解析时为 {@code null}。不属于商品事实的字段
 * （如 {@code count}）一律忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductCouponResponse(
        long id,
        String name,
        Integer type,
        Integer platform,
        BigDecimal amount,
        BigDecimal minPoint,
        Integer perLimit,
        Instant startTime,
        Instant endTime,
        Integer useType,
        String note,
        Integer memberLevel) {
}

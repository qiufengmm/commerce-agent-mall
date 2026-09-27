package com.macro.mall.agent.storefront.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 会员「未使用优惠券历史」条目。
 *
 * <p>字段与 Python {@code schemas.CouponHistory} 对齐；刻意不保存优惠码、会员 id、会员昵称、订单号等
 * 非必要个人字段。{@code createTime} 由安全 parser 解析为 {@link Instant}；无法解析时该行按 Python
 * 校验失败语义整条跳过。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CouponHistoryResponse(
        long id,
        Long couponId,
        Integer useStatus,
        Integer getType,
        Instant createTime) {
}

package com.macro.mall.agent.storefront.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 由 {@code /sso/info} 解析出的会员本人身份。
 *
 * <p>只保留公开身份字段；不保存密码、Token、状态、积分等非必要或敏感字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberInfoResponse(
        long memberId,
        String username,
        String nickname,
        String icon) {
}

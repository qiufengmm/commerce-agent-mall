package com.macro.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品评价回复参数
 */
@Getter
@Setter
public class PmsCommentReplyParam {
    @Schema(title = "评价id")
    private Long commentId;
    @Schema(title = "回复内容")
    private String content;
}

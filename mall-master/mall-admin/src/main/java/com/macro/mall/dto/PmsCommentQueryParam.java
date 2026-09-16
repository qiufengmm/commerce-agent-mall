package com.macro.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品评价查询参数
 */
@Getter
@Setter
public class PmsCommentQueryParam {
    @Schema(title = "商品名称关键字")
    private String productKeyword;
    @Schema(title = "会员昵称")
    private String memberNickName;
    @Schema(title = "显示状态：0->不显示；1->显示")
    private Integer showStatus;
}

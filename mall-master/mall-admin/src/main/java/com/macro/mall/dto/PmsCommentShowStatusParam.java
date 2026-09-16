package com.macro.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品评价显示状态修改参数
 */
@Getter
@Setter
public class PmsCommentShowStatusParam {
    @Schema(title = "显示状态：0->不显示；1->显示")
    private Integer showStatus;
}

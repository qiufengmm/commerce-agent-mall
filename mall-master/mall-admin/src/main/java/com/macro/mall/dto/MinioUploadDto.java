package com.macro.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件上传返回结果
 */
@Data
@EqualsAndHashCode
public class MinioUploadDto {
    @Schema(title = "文件访问URL")
    private String url;
    @Schema(title = "文件名称")
    private String name;
}

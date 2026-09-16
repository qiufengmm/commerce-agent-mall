package com.macro.mall.portal.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 搜索服务返回的商品信息（对应 mall-search 的 EsProduct）
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EsProductDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private String productSn;
    private Long brandId;
    private String brandName;
    private Long productCategoryId;
    private String productCategoryName;
    private String pic;
    private String name;
    private String subTitle;
    private String keywords;
    private BigDecimal price;
    private Integer sale;
    private Integer newStatus;
    private Integer recommandStatus;
    private Integer stock;
    private Integer promotionType;
    private Integer sort;
}

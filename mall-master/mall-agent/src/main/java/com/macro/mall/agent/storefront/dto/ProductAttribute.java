package com.macro.mall.agent.storefront.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 商品属性（名称 + 取值）；只有同时具备名称与非空取值的属性才会保留。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductAttribute(String name, String value) {
}

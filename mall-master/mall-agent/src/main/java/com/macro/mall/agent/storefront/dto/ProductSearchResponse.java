package com.macro.mall.agent.storefront.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code /product/search} 的分页结果。
 *
 * <p>{@code list} 即门户返回的 {@code list} 字段；{@code pageSize} 固定为 5（服务端强制，不来自请求）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductSearchResponse(
        int pageNum,
        int pageSize,
        int totalPage,
        int total,
        List<ProductSummary> list) {
}

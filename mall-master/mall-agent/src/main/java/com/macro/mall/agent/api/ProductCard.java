package com.macro.mall.agent.api;

/**
 * 商品卡片。全部字段由服务端根据本轮工具事实构造，模型无法提供。
 *
 * <p>{@code pic}、{@code price}、{@code subtitle} 允许为空，其余字段必有取值。
 */
public record ProductCard(
        long id,
        String name,
        String pic,
        String price,
        String subtitle,
        String stockStatus,
        int availableStock,
        String detailPath) {
}

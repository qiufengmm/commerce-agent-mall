package com.macro.mall.agent.presentation;

import com.macro.mall.agent.api.ProductCard;
import com.macro.mall.agent.tools.AgentTool;

/**
 * 单个商品的卡片事实，全部由服务端构造。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.presentation.products.ProductFact}：
 * 模型只能选择候选商品 ID，无法提供名称、价格、图片、库存或跳转路径。
 * {@code sourceRank} 表示事实来源优先级（{@code detail &gt; compare &gt; search}）。
 *
 * @param productId      商品 ID
 * @param name           商品名（缺省为空串）
 * @param pic            主图地址，可为 {@code null}
 * @param price          两位小数金额文本，可为 {@code null}
 * @param subtitle       副标题，可为 {@code null}
 * @param availableStock 服务端可售库存
 * @param stockStatus    库存状态（缺省无货）
 * @param sourceRank     事实来源优先级
 */
public record ProductFact(
        long productId,
        String name,
        String pic,
        String price,
        String subtitle,
        int availableStock,
        String stockStatus,
        int sourceRank) {

    public static final int SOURCE_RANK_SEARCH = 1;
    public static final int SOURCE_RANK_COMPARE = 2;
    public static final int SOURCE_RANK_DETAIL = 3;

    public ProductFact {
        name = name == null ? "" : name;
        stockStatus = stockStatus == null || stockStatus.isEmpty()
                ? AgentTool.OUT_OF_STOCK
                : stockStatus;
    }

    /** 构造服务端卡片；字段全部来自本事实，详情路径固定。 */
    public ProductCard toCard() {
        return new ProductCard(
                productId,
                name,
                pic,
                price,
                subtitle,
                stockStatus,
                availableStock,
                ProductCardBuilder.detailPath(productId));
    }

    /**
     * 用更高优先级事实覆盖时，保留已有事实的非空图片与副标题。
     *
     * <p>与 Python {@code ProductFactCollector._upsert} 一致：只回退 {@code pic} 与 {@code subtitle}。
     */
    ProductFact withFallbacks(ProductFact existing) {
        String mergedPic = pic == null || pic.isEmpty() ? existing.pic : pic;
        String mergedSubtitle = subtitle == null || subtitle.isEmpty() ? existing.subtitle : subtitle;
        return new ProductFact(
                productId, name, mergedPic, price, mergedSubtitle, availableStock, stockStatus, sourceRank);
    }
}

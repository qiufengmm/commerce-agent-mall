package com.macro.mall.agent.presentation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.macro.mall.agent.api.ProductCard;

/**
 * 服务端商品卡片构造。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.presentation.products.PresentationBuilder}：
 * 模型只能给出候选商品 ID 序列，构造入口没有任何价格、库存、图片或跳转路径参数；
 * 选定 ID 去重保序、只保留本轮事实中存在的 ID、最多 5 张；
 * 模型没有给出可用选择时回退到本轮工具结果的事实顺序。
 */
public final class ProductCardBuilder {

    /** 单次回答最多输出的卡片数。 */
    public static final int MAX_CARDS = 5;

    private static final String DETAIL_PATH_TEMPLATE = "/pages/product/product?id=%d";

    private final int maxCards;

    public ProductCardBuilder() {
        this(MAX_CARDS);
    }

    /** @param maxCards 卡片上限，小于 1 时按 1 处理 */
    public ProductCardBuilder(int maxCards) {
        this.maxCards = Math.max(1, maxCards);
    }

    /**
     * 按选定 ID 生成卡片。
     *
     * @param selectedIds 模型选定的候选 ID（可含未出现过的 ID，会被忽略）
     * @param facts       本轮服务端事实表（保持事实顺序）
     */
    public List<ProductCard> build(List<Long> selectedIds, Map<Long, ProductFact> facts) {
        Set<Long> ordered = new LinkedHashSet<>();
        if (selectedIds != null) {
            for (Long productId : selectedIds) {
                if (productId != null && facts.containsKey(productId)) {
                    ordered.add(productId);
                }
                if (ordered.size() >= maxCards) {
                    break;
                }
            }
        }
        if (ordered.isEmpty()) {
            for (Long productId : facts.keySet()) {
                ordered.add(productId);
                if (ordered.size() >= maxCards) {
                    break;
                }
            }
        }

        List<ProductCard> cards = new ArrayList<>(ordered.size());
        for (Long productId : ordered) {
            cards.add(facts.get(productId).toCard());
        }
        return cards;
    }

    /** 固定详情路径；ID 必须是正整数。 */
    public static String detailPath(long productId) {
        if (productId < 1) {
            throw new IllegalArgumentException("productId 必须是正整数");
        }
        return String.format(DETAIL_PATH_TEMPLATE, productId);
    }
}

package com.macro.mall.event;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 商品变更后需要同步搜索索引的事件
 * 事件只承载商品id，由搜索服务从MySQL读取最新数据后更新ES，保证提交后同步读到的是已提交数据
 */
public class ProductSyncEvent {
    private final List<Long> productIds;

    public ProductSyncEvent(List<Long> productIds) {
        this.productIds = productIds == null ? Collections.emptyList()
                : List.copyOf(productIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList()));
    }

    public List<Long> getProductIds() {
        return productIds;
    }

    /**
     * 是否没有需要同步的商品id
     */
    public boolean isEmpty() {
        return productIds.isEmpty();
    }
}

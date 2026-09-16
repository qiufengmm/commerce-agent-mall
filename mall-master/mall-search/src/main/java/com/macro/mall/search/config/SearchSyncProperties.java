package com.macro.mall.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 搜索服务同步相关配置
 */
@Component
@ConfigurationProperties(prefix = "mall.search")
public class SearchSyncProperties {
    /**
     * 批量上限默认值，配置非法时使用
     */
    public static final int DEFAULT_MAX_SYNC_BATCH_SIZE = 100;
    /**
     * 批量上限硬性最大值，配置超过该值时仍按该值处理，上限不可被配置关闭
     */
    public static final int MAX_SYNC_BATCH_SIZE_LIMIT = 100;
    /**
     * 内部服务调用令牌，建议通过环境变量MALL_SEARCH_INTERNAL_TOKEN注入，仓库内不存放真实值
     */
    private String internalToken = "";
    /**
     * 单次批量同步商品id数量上限，实际生效值经getEffectiveMaxSyncBatchSize钳制
     */
    private int maxSyncBatchSize = 100;

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public int getMaxSyncBatchSize() {
        return maxSyncBatchSize;
    }

    public void setMaxSyncBatchSize(int maxSyncBatchSize) {
        this.maxSyncBatchSize = maxSyncBatchSize;
    }

    /**
     * 实际生效的批量上限，规则固定为：
     * 配置值 <= 0：使用默认值100；配置值 > 100：仍使用100；配置值 1~100：使用配置值。
     * 无论配置如何错误，批量限制都不能被关闭。
     */
    public int getEffectiveMaxSyncBatchSize() {
        if (maxSyncBatchSize <= 0) {
            return DEFAULT_MAX_SYNC_BATCH_SIZE;
        }
        return Math.min(maxSyncBatchSize, MAX_SYNC_BATCH_SIZE_LIMIT);
    }
}

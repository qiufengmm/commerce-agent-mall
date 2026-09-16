package com.macro.mall.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 搜索服务(mall-search)相关配置
 */
@Component
@ConfigurationProperties(prefix = "mall.search")
public class SearchServiceProperties {
    /**
     * 搜索服务地址，默认值localhost:8081，可通过环境变量MALL_SEARCH_BASE_URL覆盖
     */
    private String baseUrl = "http://localhost:8081";
    /**
     * 连接超时时间，单位毫秒
     */
    private int connectTimeout = 2000;
    /**
     * 读取超时时间，单位毫秒
     */
    private int readTimeout = 5000;
    /**
     * 内部服务调用令牌，通过环境变量MALL_SEARCH_INTERNAL_TOKEN注入，仓库内不存放真实值
     */
    private String internalToken = "";
    /**
     * 单次批量同步商品id数量上限，需与mall-search的max-sync-batch-size保持一致
     */
    private int maxSyncBatchSize = 100;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

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
}

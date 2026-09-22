package com.macro.mall.portal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 搜索服务（mall-search）调用配置
 * 内部服务地址不对外暴露，支持通过环境变量 MALL_SEARCH_BASE_URL 覆盖
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.search")
public class MallSearchClientProperties {
    /**
     * 搜索服务地址
     */
    private String baseUrl = "http://localhost:8081";
    /**
     * 综合搜索接口路径
     */
    private String searchPath = "/esProduct/search";
    /**
     * 连接超时时间(毫秒)
     */
    private Integer connectTimeoutMillis = 3000;
    /**
     * 读取超时时间(毫秒)
     */
    private Integer readTimeoutMillis = 5000;
    /**
     * 是否在搜索服务调用失败时降级到MySQL查询，默认关闭。
     * 对应环境变量 MALL_SEARCH_MYSQL_FALLBACK_ENABLED，关闭时搜索服务异常原样暴露，不静默查询MySQL。
     */
    private boolean mysqlFallbackEnabled = false;
}

package com.macro.mall.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 搜索服务调用相关配置
 */
@Configuration
public class SearchServiceConfig {

    /**
     * 调用搜索服务使用的HTTP客户端，设置了连接超时
     */
    @Bean
    public HttpClient searchHttpClient(SearchServiceProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeout()))
                .build();
    }
}

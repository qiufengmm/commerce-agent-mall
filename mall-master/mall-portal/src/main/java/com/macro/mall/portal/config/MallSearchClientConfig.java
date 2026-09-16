package com.macro.mall.portal.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 搜索服务（mall-search）客户端配置
 */
@Configuration
@RequiredArgsConstructor
public class MallSearchClientConfig {
    /**
     * 调用搜索服务专用的RestTemplate名称
     */
    public static final String MALL_SEARCH_REST_TEMPLATE = "mallSearchRestTemplate";

    private final MallSearchClientProperties mallSearchClientProperties;

    /**
     * 带有超时时间的RestTemplate，避免使用无法控制超时的默认实现
     */
    @Bean(MALL_SEARCH_REST_TEMPLATE)
    public RestTemplate mallSearchRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(mallSearchClientProperties.getConnectTimeoutMillis());
        requestFactory.setReadTimeout(mallSearchClientProperties.getReadTimeoutMillis());
        return new RestTemplate(requestFactory);
    }
}

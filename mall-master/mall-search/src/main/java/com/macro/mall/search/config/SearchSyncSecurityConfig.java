package com.macro.mall.search.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 写接口访问保护配置
 * 过滤器注册在 /esProduct/* 上，覆盖该路径下的全部子路径，包括带尾斜杠和带contextPath的请求，
 * 但只有 importAll、create/{id}、delete/{id}、delete/batch、sync/{id}、sync/batch 会被校验令牌，
 * search 等只读接口始终匿名可访问。
 */
@Configuration
public class SearchSyncSecurityConfig {

    @Bean
    public FilterRegistrationBean<InternalTokenAuthFilter> internalTokenAuthFilter(
            SearchSyncProperties searchSyncProperties, ObjectMapper objectMapper) {
        FilterRegistrationBean<InternalTokenAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new InternalTokenAuthFilter(searchSyncProperties, objectMapper));
        registration.addUrlPatterns(InternalTokenAuthFilter.ES_PRODUCT_URL_PATTERN);
        registration.setName("internalTokenAuthFilter");
        registration.setOrder(1);
        return registration;
    }
}

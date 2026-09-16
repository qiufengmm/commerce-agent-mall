package com.macro.mall.search.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 同步接口访问保护配置，把内部令牌过滤器限定在 /esProduct/sync/**，其他搜索接口不受影响
 */
@Configuration
public class SearchSyncSecurityConfig {

    @Bean
    public FilterRegistrationBean<InternalTokenAuthFilter> internalTokenAuthFilter(
            SearchSyncProperties searchSyncProperties, ObjectMapper objectMapper) {
        FilterRegistrationBean<InternalTokenAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new InternalTokenAuthFilter(searchSyncProperties, objectMapper));
        registration.addUrlPatterns(InternalTokenAuthFilter.SYNC_PATH_PREFIX + "*");
        registration.setName("internalTokenAuthFilter");
        registration.setOrder(1);
        return registration;
    }
}

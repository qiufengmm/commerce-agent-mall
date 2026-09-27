package com.macro.mall.agent.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 配置。
 *
 * <p>来源列表取自 {@link AgentProperties#CORS_ALLOW_ORIGINS}：按逗号拆分并 trim。缺失或空白
 * 表示「不授权任何来源」，此时<strong>不注册任何跨域映射</strong>，因此同一来源或缺少
 * {@code Origin} 的普通请求不受影响，跨域预检也不会得到 {@code Access-Control-Allow-Origin}。
 *
 * <p>显式精确来源列表按原样生效。任何含通配 {@code *} 的取值（单独或与精确来源混合）一律拒绝，
 * 绝不产出通配许可，保证 CORS 默认 fail closed。
 *
 * <p>只允许 {@code GET/POST/DELETE/OPTIONS}，允许请求头 {@code *}，且始终
 * {@code allowCredentials=false}（因此即使不是通配来源也不会与凭据同时生效）。
 */
@Configuration
public class AgentCorsConfiguration implements WebMvcConfigurer {

    static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "DELETE", "OPTIONS");

    private static final String ALL_HEADERS = "*";
    private static final char WILDCARD = '*';

    private final AgentProperties properties;

    public AgentCorsConfiguration(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析逗号分隔的来源列表。
     *
     * <p>缺失或空白返回空列表（不注册任何跨域许可，fail closed）；含通配 {@code *} 的取值
     * 一律抛出 {@link IllegalArgumentException}，绝不产出通配来源。
     */
    public static List<String> parseAllowedOrigins(String rawValue) {
        String value = rawValue == null ? "" : rawValue.strip();
        if (value.isEmpty()) {
            return List.of();
        }
        List<String> origins = Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .toList();
        for (String origin : origins) {
            if (origin.indexOf(WILDCARD) >= 0) {
                throw new IllegalArgumentException(
                        AgentProperties.CORS_ALLOW_ORIGINS + " 不允许使用通配来源 *");
            }
        }
        return origins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = parseAllowedOrigins(properties.getCorsAllowOrigins());
        if (origins.isEmpty()) {
            return;
        }
        registry.addMapping("/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods(ALLOWED_METHODS.toArray(String[]::new))
                .allowedHeaders(ALL_HEADERS)
                .allowCredentials(false);
    }
}

package com.macro.mall.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 显式装配运行配置。
 *
 * <p>不使用 {@code @ConfigurationProperties}，以保证 {@code MALL_AGENT_*} 变量按精确名称读取，
 * 不会被宽松绑定拆成嵌套属性。
 */
@Configuration
public class AgentConfiguration {

    @Bean
    public AgentProperties agentProperties(Environment environment) {
        return AgentProperties.from(environment);
    }
}

package com.macro.mall.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * {@code application.yml} 中 Redis 连接/命令超时的离线配置契约测试。
 *
 * <p>只读取并解析真实的 {@code src/main/resources/application.yml}，不启动 Spring 上下文、
 * 不连接 Redis、不测量任何真实耗时：断言的是「配置键存在且被固定为 2s」这一静态事实。
 *
 * <p>固定超时的目的：Redis 不可达时，连接建立与命令读写都要在有限时间内失败，避免启动装配或
 * {@code /health/ready} 探针被默认超时无限拖住；这里用文本断言替代脆弱的真实计时测试。
 */
class RedisTimeoutConfigurationTest {

    private static final String CONNECT_TIMEOUT_KEY = "spring.data.redis.connect-timeout";
    private static final String COMMAND_TIMEOUT_KEY = "spring.data.redis.timeout";
    private static final String EXPECTED_TIMEOUT = "2s";

    private static Properties loadApplicationYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).as("application.yml 必须可加载并解析为属性").isNotNull();
        return properties;
    }

    @Test
    @DisplayName("application.yml 固定 Redis 连接建立超时为 2s")
    void connectTimeoutIsPinnedToTwoSeconds() {
        assertThat(loadApplicationYml().getProperty(CONNECT_TIMEOUT_KEY))
                .as("%s 必须显式声明且固定为 %s", CONNECT_TIMEOUT_KEY, EXPECTED_TIMEOUT)
                .isNotNull()
                .isEqualTo(EXPECTED_TIMEOUT);
    }

    @Test
    @DisplayName("application.yml 固定 Redis 命令读写超时为 2s")
    void commandTimeoutIsPinnedToTwoSeconds() {
        assertThat(loadApplicationYml().getProperty(COMMAND_TIMEOUT_KEY))
                .as("%s 必须显式声明且固定为 %s", COMMAND_TIMEOUT_KEY, EXPECTED_TIMEOUT)
                .isNotNull()
                .isEqualTo(EXPECTED_TIMEOUT);
    }

    @Test
    @DisplayName("Redis 超时不是依赖环境的占位符，而是固定字面量")
    void timeoutsAreFixedLiteralsNotEnvironmentPlaceholders() {
        Properties properties = loadApplicationYml();

        assertThat(properties.getProperty(CONNECT_TIMEOUT_KEY))
                .as("连接超时必须固定，不能把不可控的环境变量注入超时窗口")
                .doesNotContain("${");
        assertThat(properties.getProperty(COMMAND_TIMEOUT_KEY))
                .as("命令超时必须固定，不能把不可控的环境变量注入超时窗口")
                .doesNotContain("${");
    }
}

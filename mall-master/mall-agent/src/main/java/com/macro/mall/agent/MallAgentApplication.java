package com.macro.mall.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 独立商品导购智能体服务入口。
 *
 * <p>本服务只依赖 Web、Redis 与校验组件，不接入商城数据库、消息队列或搜索引擎客户端，
 * 也不参与其他商城模块的自动装配。
 */
@SpringBootApplication
public class MallAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallAgentApplication.class, args);
    }
}

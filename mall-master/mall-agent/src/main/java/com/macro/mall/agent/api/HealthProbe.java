package com.macro.mall.agent.api;

/**
 * 单个外部依赖的就绪探针。
 *
 * <p>实现必须是只读检查，不得产生任何写操作；探针失败只返回 {@code false}，
 * 不得把地址、凭据或异常正文暴露给接口响应。
 */
@FunctionalInterface
public interface HealthProbe {

    /** @return 该依赖当前是否可用 */
    boolean isHealthy();
}

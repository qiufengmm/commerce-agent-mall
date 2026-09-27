package com.macro.mall.agent.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 就绪判定：所有已注册探针都健康才算就绪。
 *
 * <p>未注册任何探针时安全地保持未就绪，避免在没有真实依赖检查的情况下假报健康。
 */
public class ReadinessService {

    private static final Logger log = LoggerFactory.getLogger(ReadinessService.class);

    private final Map<String, HealthProbe> probes;

    public ReadinessService(Map<String, HealthProbe> probes) {
        this.probes = probes == null ? Map.of() : new LinkedHashMap<>(probes);
    }

    public boolean isReady() {
        if (probes.isEmpty()) {
            log.debug("未配置任何依赖探针，就绪状态保持 not-ready");
            return false;
        }
        for (Map.Entry<String, HealthProbe> probe : probes.entrySet()) {
            if (!isHealthy(probe.getKey(), probe.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean isHealthy(String probeName, HealthProbe probe) {
        try {
            return probe.isHealthy();
        } catch (RuntimeException ex) {
            // 只记录探针名与异常类型，避免把依赖地址、凭据或异常正文写入日志
            log.warn("依赖探针执行异常，按未就绪处理：probe={}, type={}",
                    probeName, ex.getClass().getSimpleName());
            return false;
        }
    }
}

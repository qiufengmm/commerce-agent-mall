package com.macro.mall.agent.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康接口。
 *
 * <p>{@code /health/live} 只反映进程存活；{@code /health/ready} 依赖已注册的就绪探针。
 * 失败时只返回固定状态与文案，不包含依赖地址、凭据或异常正文。
 */
@RestController
public class HealthController {

    private static final String STATUS_UP = "UP";
    private static final String STATUS_DOWN = "DOWN";
    private static final String NOT_READY_MESSAGE = "服务依赖尚未就绪";

    private final ReadinessService readinessService;

    public HealthController(ReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @GetMapping("/health/live")
    public ApiEnvelope<Map<String, Object>> live() {
        return ApiEnvelope.success(status(STATUS_UP));
    }

    @GetMapping("/health/ready")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> ready() {
        if (!readinessService.isReady()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiEnvelope.failure(503, NOT_READY_MESSAGE, status(STATUS_DOWN)));
        }
        return ResponseEntity.ok(ApiEnvelope.success(status(STATUS_UP)));
    }

    private static Map<String, Object> status(String value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", value);
        return data;
    }
}

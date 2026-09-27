package com.macro.mall.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 健康接口契约测试。
 *
 * <p>使用 standalone MockMvc 与可替换的 {@link HealthProbe} 测试替身，
 * 不建立 Spring 上下文，也不连接真实 Redis、mall-portal 或模型。
 */
class HealthControllerTest {

    private static MockMvc mockMvc(Map<String, HealthProbe> probes) {
        return MockMvcBuilders
                .standaloneSetup(new HealthController(new ReadinessService(probes)))
                .build();
    }

    private static Map<String, HealthProbe> probesWith(HealthProbe portal, HealthProbe redis) {
        Map<String, HealthProbe> probes = new LinkedHashMap<>();
        probes.put("portalHealthProbe", portal);
        probes.put("redisHealthProbe", redis);
        return probes;
    }

    @Test
    @DisplayName("存活探针返回 200 与 code/message/data 包装")
    void liveReturnsUpEnvelope() throws Exception {
        mockMvc(Map.of())
                .perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    @DisplayName("存活探针不依赖任何外部探针结果")
    void liveStaysUpEvenWhenProbesAreUnavailable() throws Exception {
        mockMvc(probesWith(() -> false, () -> false))
                .perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    @DisplayName("全部依赖健康时就绪探针返回 200")
    void readyReturnsUpWhenEveryProbeIsHealthy() throws Exception {
        mockMvc(probesWith(() -> true, () -> true))
                .perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    @DisplayName("任一依赖失败时就绪探针返回 503 与 DOWN")
    void readyReturns503WhenAnyProbeFails() throws Exception {
        mockMvc(probesWith(() -> true, () -> false))
                .perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data.status").value("DOWN"));

        mockMvc(probesWith(() -> false, () -> true))
                .perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.status").value("DOWN"));
    }

    @Test
    @DisplayName("未配置任何依赖探针时安全保持 not-ready")
    void readyStaysDownWhenNoProbeIsConfigured() throws Exception {
        mockMvc(Map.of())
                .perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data.status").value("DOWN"));
    }

    @Test
    @DisplayName("探针抛出异常时返回 503，且不泄露 URL、Key 或异常正文")
    void readyDoesNotLeakProbeFailureDetails() throws Exception {
        HealthProbe failing = () -> {
            throw new IllegalStateException(
                    "connect failed for redis://user:secret@localhost:6379/0 and sk-credential-must-not-leak");
        };

        MvcResult result = mockMvc(probesWith(() -> true, failing))
                .perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.status").value("DOWN"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .doesNotContain("redis://")
                .doesNotContain("secret")
                .doesNotContain("sk-credential")
                .doesNotContain("IllegalStateException")
                .doesNotContain("Exception")
                .doesNotContain("localhost");
    }

    @Test
    @DisplayName("探针失败响应不包含依赖地址与凭据")
    void readyResponseContainsOnlyStatusCodeAndMessage() throws Exception {
        MvcResult result = mockMvc(probesWith(() -> false, () -> false))
                .perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .contains("\"code\":503")
                .contains("\"data\":{\"status\":\"DOWN\"}")
                .doesNotContain("http://")
                .doesNotContain("password")
                .doesNotContain("Bearer");
    }
}

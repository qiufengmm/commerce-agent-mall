package com.macro.mall.agent.deadline;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * deadline 感知的 {@link ClientHttpRequestFactory}：把请求级剩余预算与 per-client 超时合并为
 * <strong>单次 HTTP 请求的完整超时</strong>。
 *
 * <p>选型说明（相对 {@code SimpleClientHttpRequestFactory}）：后者把超时拆成 connect 与 read 两段
 * {@code HttpURLConnection} 不活动超时，两段可能相加、慢速流也可能整体越过预算。这里改用 JDK 17
 * {@link HttpClient} + Spring {@link JdkClientHttpRequestFactory}：Spring 将
 * {@code setReadTimeout(Duration)} 作为<strong>单次请求</strong>的整体超时使用，而不是逐次读取的不活动
 * 超时，因此不会出现「connect + read 相加」或慢速流越界。超时终止在异常链中表现为
 * {@link java.net.http.HttpTimeoutException}，由 {@code DeadlineHttpRequestFactoryTest} 的 loopback
 * 真实 socket 用例断言。
 *
 * <p>并发安全：{@link HttpClient} 是<strong>共享不可变</strong>的；每次 {@link #createRequest} 都新建一个
 * 独立的 {@link JdkClientHttpRequestFactory} 并只在该新实例上设置超时，绝不修改任何共享工厂的超时字段，
 * 因此并发请求之间不会互相覆盖预算。
 *
 * <p>超时取值：
 * <ul>
 *   <li>当前线程没有 deadline → 使用 per-client 配置的超时；</li>
 *   <li>有 deadline 且剩余预算更小 → 使用剩余预算，并标记该请求「已被 deadline 截断」；</li>
 *   <li>有 deadline 但剩余预算已耗尽 → 在发出网络调用<strong>之前</strong>抛出
 *       {@link RequestDeadlineExceededException}。</li>
 * </ul>
 */
public final class DeadlineClientHttpRequestFactory implements ClientHttpRequestFactory {

    private final HttpClient httpClient;
    private final Duration configuredTimeout;

    /**
     * @param httpClient        共享的不可变 JDK {@link HttpClient}
     * @param configuredTimeout 该客户端（模型或门户）自身配置的单次请求超时，必须大于 0
     */
    public DeadlineClientHttpRequestFactory(HttpClient httpClient, Duration configuredTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient 不能为空");
        Objects.requireNonNull(configuredTimeout, "configuredTimeout 不能为空");
        if (configuredTimeout.isZero() || configuredTimeout.isNegative()) {
            throw new IllegalArgumentException("configuredTimeout 必须大于 0");
        }
        this.configuredTimeout = configuredTimeout;
    }

    /** 该客户端自身配置的单次请求超时（不读取 deadline 上下文）。 */
    public Duration configuredTimeout() {
        return configuredTimeout;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
        // 每次请求独立工厂：setReadTimeout 只作用于这个新实例，不触碰任何共享状态
        JdkClientHttpRequestFactory perRequest = new JdkClientHttpRequestFactory(httpClient);
        perRequest.setReadTimeout(effectiveTimeout());
        return perRequest.createRequest(uri, method);
    }

    /**
     * 计算本次请求的完整超时，并把「本次请求是否被 deadline 截断」记录为<strong>请求级</strong>标记。
     *
     * <p>入口先 {@link RequestDeadline#beginRequest()} 重置标记，再按本次 remaining 与 configured 的
     * 大小关系重新设置，因此更早一次被截断的请求不会污染本次（或后续）未被截断的 per-client 超时判定。
     */
    private Duration effectiveTimeout() {
        RequestDeadline deadline = RequestDeadlineContext.current();
        if (deadline == null) {
            return configuredTimeout;
        }
        // 先重置：判定只描述“最近一次请求”，不是累计状态
        deadline.beginRequest();
        if (deadline.isExpired()) {
            throw new RequestDeadlineExceededException();
        }
        Duration remaining = deadline.remaining();
        if (remaining.compareTo(configuredTimeout) < 0) {
            deadline.markRequestClamped();
            return remaining;
        }
        return configuredTimeout;
    }
}

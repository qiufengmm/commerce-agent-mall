package com.macro.mall.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@link ClientIpResolver} 的可信代理边界与严格 IP 字面量校验测试。
 *
 * <p>覆盖：未配置可信代理/对端不匹配时忽略请求头、对端匹配且头合法时采信、头非法时回退
 * 合法对端、对端非法时返回空串、{@code X-Forwarded-For} 永不参与取值，以及 IPv6 文本等价的
 * 按字节比较。严格字面量矩阵覆盖 hostname、端口、zone id、方括号、逗号/分号/空白拼接、
 * 控制字符、非规范或越界 IPv4 段与非法分组数。
 *
 * <p>只使用 {@link MockHttpServletRequest}，不启动 Spring 上下文、不访问网络。
 */
class ClientIpResolverTest {

    private static final String TRUSTED = "10.0.0.5";
    private static final String HEADER_IP = "203.0.113.9";

    @Test
    @DisplayName("request 为 null 时返回空串")
    void nullRequestReturnsEmpty() {
        assertThat(ClientIpResolver.resolve(null, TRUSTED)).isEmpty();
    }

    @Test
    @DisplayName("未配置可信代理（空串或 null）时永不采信 X-Real-IP")
    void emptyTrustedProxyNeverTrustsHeader() {
        MockHttpServletRequest request = request(TRUSTED, HEADER_IP);

        assertThat(ClientIpResolver.resolve(request, "")).isEqualTo(TRUSTED);
        assertThat(ClientIpResolver.resolve(request, null)).isEqualTo(TRUSTED);
    }

    @Test
    @DisplayName("对端不等于可信代理时忽略 X-Real-IP")
    void mismatchedRemoteAddrIgnoresHeader() {
        MockHttpServletRequest request = request("198.51.100.7", HEADER_IP);

        assertThat(ClientIpResolver.resolve(request, TRUSTED)).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("可信代理来源且 X-Real-IP 合法时采用该头")
    void trustedProxyUsesValidHeader() {
        MockHttpServletRequest request = request(TRUSTED, HEADER_IP);

        assertThat(ClientIpResolver.resolve(request, TRUSTED)).isEqualTo(HEADER_IP);
    }

    @Test
    @DisplayName("可信代理来源但缺少 X-Real-IP 时回退 remoteAddr")
    void missingHeaderFallsBackToRemoteAddr() {
        MockHttpServletRequest request = request(TRUSTED, null);

        assertThat(ClientIpResolver.resolve(request, TRUSTED)).isEqualTo(TRUSTED);
    }

    @Test
    @DisplayName("X-Forwarded-For 永不参与取值")
    void forwardedForIsNeverUsed() {
        MockHttpServletRequest request = request(TRUSTED, null);
        request.addHeader("X-Forwarded-For", HEADER_IP);

        assertThat(ClientIpResolver.resolve(request, TRUSTED)).isEqualTo(TRUSTED);
    }

    @ParameterizedTest(name = "header=[{0}] 非法，回退 remoteAddr")
    @ValueSource(strings = {
        "evil.example.com", "1.2.3.4:80", "fe80::1%eth0", "[::1]", "1.2.3.4,5.6.7.8",
        "1.2.3.4;5.6.7.8", "1.2.3.4 5.6.7.8", " 1.2.3.4", "1.2.3.4 ", "1.2.3.04",
        "01.2.3.4", "1.2.3.256", "999.0.0.1", "1.2.3", "1.2.3.4.5", "1.2.3.4\t",
        "1.2.3.4\n", "0x7f.0.0.1", "1.2.3.4/24", "2001:db8::1::2", "1:2:3:4:5:6:7:8:9",
        "12345::1"
    })
    void illegalHeaderFallsBackToRemoteAddr(String illegal) {
        MockHttpServletRequest request = request(TRUSTED, illegal);

        assertThat(ClientIpResolver.resolve(request, TRUSTED)).isEqualTo(TRUSTED);
    }

    @ParameterizedTest(name = "内嵌 IPv4 之后仍跟压缩/十六进制组 [{0}] 非法，回退 remoteAddr")
    @ValueSource(strings = {
        "192.0.2.1::", "192.0.2.1::ffff", "1:2:3:4:5:6:192.0.2.1::", "192.0.2.1::1"
    })
    void embeddedIpv4NotAtAddressTailIsRejected(String illegal) {
        MockHttpServletRequest request = request(TRUSTED, illegal);

        assertThat(ClientIpResolver.resolve(request, TRUSTED)).isEqualTo(TRUSTED);
    }

    @ParameterizedTest(name = "remoteAddr=[{0}] 非法，返回空串")
    @ValueSource(strings = {
        "not-an-ip", "1.2.3.4:80", "fe80::1%eth0", "", " 127.0.0.1", "127.0.0.1 ",
        "127.0.0.1,10.0.0.5", "256.0.0.1", "::gggg"
    })
    void illegalRemoteAddrReturnsEmpty(String illegal) {
        MockHttpServletRequest request = request(illegal, HEADER_IP);

        assertThat(ClientIpResolver.resolve(request, TRUSTED)).isEmpty();
    }

    @ParameterizedTest(name = "合法 X-Real-IP [{0}] 被接受")
    @ValueSource(strings = {
        "0.0.0.0", "255.255.255.255", "203.0.113.9", "::1", "::", "2001:db8::1",
        "2001:db8:0:0:0:0:0:1", "::ffff:203.0.113.9", "FE80::1"
    })
    void validHeaderLiteralIsAcceptedWhenTrusted(String valid) {
        MockHttpServletRequest request = request(TRUSTED, valid);

        assertThat(ClientIpResolver.resolve(request, TRUSTED)).isEqualTo(valid);
    }

    @Test
    @DisplayName("非规范 IPv6 对端地址按字节仍被接受")
    void nonCanonicalIpv6RemoteAddrIsAccepted() {
        MockHttpServletRequest request = request("2001:0db8:0000:0000:0000:0000:0000:0001", null);

        assertThat(ClientIpResolver.resolve(request, TRUSTED))
                .isEqualTo("2001:0db8:0000:0000:0000:0000:0000:0001");
    }

    @Test
    @DisplayName("可信代理与对端按 IPv6 地址字节等价识别")
    void trustedProxyMatchesEquivalentIpv6Bytes() {
        MockHttpServletRequest request = request("2001:db8::1", "2001:db8::9");

        assertThat(ClientIpResolver.resolve(request, "2001:0db8:0000:0000:0000:0000:0000:0001"))
                .isEqualTo("2001:db8::9");
    }

    private static MockHttpServletRequest request(String remoteAddr, String headerIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (headerIp != null) {
            request.addHeader(ClientIpResolver.X_REAL_IP_HEADER, headerIp);
        }
        return request;
    }
}

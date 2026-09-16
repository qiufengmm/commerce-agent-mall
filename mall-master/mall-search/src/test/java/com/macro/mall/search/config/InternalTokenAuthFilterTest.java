package com.macro.mall.search.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同步接口内部令牌过滤器单元测试，不依赖Spring容器
 */
public class InternalTokenAuthFilterTest {
    private static final String TOKEN = "unit-test-token";

    private InternalTokenAuthFilter filter(String serverToken) {
        SearchSyncProperties properties = new SearchSyncProperties();
        properties.setInternalToken(serverToken);
        return new InternalTokenAuthFilter(properties, new ObjectMapper());
    }

    private MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    @Test
    public void testRejectWhenServerTokenNotConfigured() throws Exception {
        InternalTokenAuthFilter filter = filter("");
        MockHttpServletRequest request = request("POST", "/esProduct/sync/batch");
        request.addHeader(InternalTokenAuthFilter.TOKEN_HEADER, TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chained.set(true));

        assertFalse(chained.get(), "未配置令牌时不能放行同步接口");
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("未配置内部令牌"));
    }

    @Test
    public void testRejectWhenTokenHeaderMissing() throws Exception {
        InternalTokenAuthFilter filter = filter(TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.doFilter(request("POST", "/esProduct/sync/batch"), response, (req, res) -> chained.set(true));

        assertFalse(chained.get());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("内部令牌校验失败"));
    }

    @Test
    public void testRejectWhenTokenMismatch() throws Exception {
        InternalTokenAuthFilter filter = filter(TOKEN);
        MockHttpServletRequest request = request("POST", "/esProduct/sync/1");
        request.addHeader(InternalTokenAuthFilter.TOKEN_HEADER, "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chained.set(true));

        assertFalse(chained.get());
        assertEquals(401, response.getStatus());
    }

    @Test
    public void testPassWhenTokenMatched() throws Exception {
        InternalTokenAuthFilter filter = filter(TOKEN);
        MockHttpServletRequest request = request("POST", "/esProduct/sync/batch");
        request.addHeader(InternalTokenAuthFilter.TOKEN_HEADER, TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chained.set(true));

        assertTrue(chained.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testPassOtherSearchPathWithoutToken() throws Exception {
        InternalTokenAuthFilter filter = filter(TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.doFilter(request("GET", "/esProduct/search"), response, (req, res) -> chained.set(true));

        assertTrue(chained.get(), "非同步接口不应被令牌校验拦截");
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testPassOtherPathWithoutConfiguredToken() throws Exception {
        InternalTokenAuthFilter filter = filter("");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);

        filter.doFilter(request("POST", "/esProduct/importAll"), response, (req, res) -> chained.set(true));

        assertTrue(chained.get(), "令牌未配置也不应影响非同步接口");
        assertEquals(200, response.getStatus());
    }
}

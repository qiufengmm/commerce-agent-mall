package com.macro.mall.search.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写接口内部令牌过滤器单元测试，不依赖Spring容器，验证路径匹配与令牌判定规则
 */
public class InternalTokenAuthFilterTest {
    private static final String TOKEN = "unit-test-token";
    private static final List<String> PROTECTED_PATHS = List.of(
            "/esProduct/importAll",
            "/esProduct/create/1",
            "/esProduct/delete/1",
            "/esProduct/delete/batch",
            "/esProduct/sync/1",
            "/esProduct/sync/batch");
    private static final List<String> READ_ONLY_PATHS = List.of(
            "/esProduct/search",
            "/esProduct/search/simple",
            "/esProduct/search/relate",
            "/esProduct/recommend/1");
    private static final List<String> SIMILAR_PATHS = List.of(
            "/esProduct/importAllExtra",
            "/esProduct/sync-other",
            "/esProduct/createabc/1",
            "/esProduct/deleteBatch");

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
    public void testAllProtectedWritePathsAreRejectedWithoutToken() throws Exception {
        for (String path : PROTECTED_PATHS) {
            InternalTokenAuthFilter filter = filter(TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chained = new AtomicBoolean(false);
            String method = path.contains("importAll") || path.endsWith("batch") || path.contains("create")
                    ? "POST" : "GET";

            filter.doFilter(request(method, path), response, (req, res) -> chained.set(true));

            assertFalse(chained.get(), path + " 必须校验内部令牌");
            assertEquals(401, response.getStatus(), path + " 缺失令牌应返回401");
        }
    }

    @Test
    public void testAllProtectedWritePathsRejectWhenTokenNotConfigured() throws Exception {
        for (String path : PROTECTED_PATHS) {
            InternalTokenAuthFilter filter = filter("");
            MockHttpServletRequest httpRequest = request("POST", path);
            httpRequest.addHeader(InternalTokenAuthFilter.TOKEN_HEADER, TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chained = new AtomicBoolean(false);

            filter.doFilter(httpRequest, response, (req, res) -> chained.set(true));

            assertFalse(chained.get(), path + " 在未配置令牌时必须失败");
            assertEquals(503, response.getStatus(), path + " 未配置令牌应返回503");
        }
    }

    @Test
    public void testTrailingSlashAndContextPathStillProtected() throws Exception {
        for (String path : PROTECTED_PATHS) {
            InternalTokenAuthFilter filter = filter(TOKEN);
            MockHttpServletRequest trailingSlashRequest = request("POST", path + "/");
            MockHttpServletResponse trailingSlashResponse = new MockHttpServletResponse();
            AtomicBoolean trailingSlashChained = new AtomicBoolean(false);
            filter.doFilter(trailingSlashRequest, trailingSlashResponse, (req, res) -> trailingSlashChained.set(true));
            assertFalse(trailingSlashChained.get(), path + "/ 带尾斜杠仍必须校验令牌");
            assertEquals(401, trailingSlashResponse.getStatus());

            MockHttpServletRequest contextPathRequest = request("POST", "/search-api" + path);
            contextPathRequest.setContextPath("/search-api");
            MockHttpServletResponse contextPathResponse = new MockHttpServletResponse();
            AtomicBoolean contextPathChained = new AtomicBoolean(false);
            filter.doFilter(contextPathRequest, contextPathResponse, (req, res) -> contextPathChained.set(true));
            assertFalse(contextPathChained.get(), "/search-api" + path + " 带contextPath仍必须校验令牌");
            assertEquals(401, contextPathResponse.getStatus());
        }
    }

    @Test
    public void testMatrixParameterWritePathsStillRequireToken() throws Exception {
        List<String> matrixPaths = List.of(
                "/esProduct/importAll;x=1",
                "/esProduct/importAll;x=1/",
                "/esProduct/importAll%3Bx=1",
                "/esProduct/create/26;x=1",
                "/esProduct/delete/batch;x=1",
                "/esProduct/sync/26;x=1",
                "/esProduct/sync/batch;x=1;y=2");
        for (String path : matrixPaths) {
            InternalTokenAuthFilter filter = filter(TOKEN);
            MockHttpServletResponse missingTokenResponse = new MockHttpServletResponse();
            AtomicBoolean missingTokenChained = new AtomicBoolean(false);
            filter.doFilter(request("POST", path), missingTokenResponse, (req, res) -> missingTokenChained.set(true));
            assertFalse(missingTokenChained.get(), path + " 带分号矩阵参数不得绕过鉴权");
            assertEquals(401, missingTokenResponse.getStatus(), path + " 缺失令牌应返回401");

            MockHttpServletRequest matchedRequest = request("POST", path);
            matchedRequest.addHeader(InternalTokenAuthFilter.TOKEN_HEADER, TOKEN);
            MockHttpServletResponse matchedResponse = new MockHttpServletResponse();
            AtomicBoolean matchedChained = new AtomicBoolean(false);
            filter.doFilter(matchedRequest, matchedResponse, (req, res) -> matchedChained.set(true));
            assertTrue(matchedChained.get(), path + " 正确令牌应继续请求链");
        }
    }

    @Test
    public void testNonPositiveOrNonNumericPathParametersStillRequireToken() throws Exception {
        List<String> valueVariants = List.of(
                "/esProduct/create/0",
                "/esProduct/create/-1",
                "/esProduct/create/abc",
                "/esProduct/delete/0",
                "/esProduct/delete/-99",
                "/esProduct/sync/not-a-number");
        for (String path : valueVariants) {
            InternalTokenAuthFilter filter = filter(TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chained = new AtomicBoolean(false);

            filter.doFilter(request("POST", path), response, (req, res) -> chained.set(true));

            assertFalse(chained.get(), path + " 参数不是正整数也必须鉴权，不能绕过");
            assertEquals(401, response.getStatus(), path + " 缺失令牌应返回401");
        }
    }

    @Test
    public void testEncodedTraversalAndDotSegmentsAreProtected() throws Exception {
        List<String> traversalPaths = List.of(
                "/esProduct/search/../delete/1",
                "/esProduct/../esProduct/importAll",
                "/esProduct/%2e%2e/esProduct/importAll",
                "/esProduct/create%2F1");
        for (String path : traversalPaths) {
            InternalTokenAuthFilter filter = filter(TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chained = new AtomicBoolean(false);

            filter.doFilter(request("POST", path), response, (req, res) -> chained.set(true));

            assertFalse(chained.get(), path + " 路径穿越或编码路径不得绕过鉴权");
            assertEquals(401, response.getStatus(), path + " 缺失令牌应返回401");
        }
    }

    @Test
    public void testReadOnlySearchPathsWithMatrixParametersStayAnonymous() throws Exception {
        List<String> readOnlyMatrixPaths = List.of(
                "/esProduct/search;x=1",
                "/esProduct/search/simple;x=1",
                "/esProduct/recommend/26;x=1");
        for (String path : readOnlyMatrixPaths) {
            InternalTokenAuthFilter filter = filter(TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chained = new AtomicBoolean(false);

            filter.doFilter(request("GET", path), response, (req, res) -> chained.set(true));

            assertTrue(chained.get(), path + " 只读接口带矩阵参数也应保持匿名");
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    public void testReadOnlySearchPathsStayAnonymous() throws Exception {
        for (String path : READ_ONLY_PATHS) {
            InternalTokenAuthFilter filter = filter(TOKEN);
            MockHttpServletResponse configuredResponse = new MockHttpServletResponse();
            AtomicBoolean configuredChained = new AtomicBoolean(false);
            filter.doFilter(request("GET", path), configuredResponse, (req, res) -> configuredChained.set(true));
            assertTrue(configuredChained.get(), path + " 只读接口必须匿名可访问");
            assertEquals(200, configuredResponse.getStatus());

            InternalTokenAuthFilter noTokenFilter = filter("");
            MockHttpServletResponse blankTokenResponse = new MockHttpServletResponse();
            AtomicBoolean blankTokenChained = new AtomicBoolean(false);
            noTokenFilter.doFilter(request("GET", path), blankTokenResponse, (req, res) -> blankTokenChained.set(true));
            assertTrue(blankTokenChained.get(), path + " 令牌未配置时只读接口也必须匿名可访问");
            assertEquals(200, blankTokenResponse.getStatus());
        }
    }

    @Test
    public void testSimilarPathsAreNotSilentlyTreatedAsWritePaths() throws Exception {
        for (String path : SIMILAR_PATHS) {
            InternalTokenAuthFilter filter = filter(TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chained = new AtomicBoolean(false);

            filter.doFilter(request("POST", path), response, (req, res) -> chained.set(true));

            assertTrue(chained.get(), path + " 不是受保护写路径，不应被令牌校验接管");
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    public void testEmptyOrUnexpectedUriIsNotAllowed() throws Exception {
        InternalTokenAuthFilter filter = filter(TOKEN);
        MockHttpServletRequest emptyRequest = new MockHttpServletRequest("POST", "");
        MockHttpServletResponse emptyResponse = new MockHttpServletResponse();
        AtomicBoolean emptyChained = new AtomicBoolean(false);
        filter.doFilter(emptyRequest, emptyResponse, (req, res) -> emptyChained.set(true));
        assertFalse(emptyChained.get(), "空URI不得绕过写接口鉴权");
        assertEquals(401, emptyResponse.getStatus());
    }

    @Test
    public void testErrorMessageDoesNotContainToken() throws Exception {
        InternalTokenAuthFilter filter = filter(TOKEN);
        MockHttpServletRequest httpRequest = request("POST", "/esProduct/sync/batch");
        httpRequest.addHeader(InternalTokenAuthFilter.TOKEN_HEADER, "another-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(httpRequest, response, (req, res) -> {
        });

        assertFalse(response.getContentAsString().contains(TOKEN), "错误响应不得回显令牌");
        assertFalse(response.getContentAsString().contains("another-token"), "错误响应不得回显请求令牌");
    }
}

package com.macro.mall.search.config;

import com.macro.mall.search.service.EsProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写接口令牌保护端到端测试
 * 通过MockMvc走真实Servlet过滤链，验证FilterRegistrationBean注册模式确实覆盖写路径，
 * 包括带尾斜杠和带contextPath的请求，同时验证只读搜索接口保持匿名可访问。
 * 写路径不接受尾斜杠或相似路径绕过，只读接口不会被误伤。
 */
@WebMvcTest(controllers = com.macro.mall.search.controller.EsProductController.class)
@Import({SearchSyncSecurityConfig.class, SearchSyncProperties.class})
public class EsProductWriteEndpointSecurityTest {
    private static final String TOKEN = "mock-mvc-test-token";
    private static final String CONTEXT_PATH = "/search-api";
    private static final List<String> PROTECTED_PATHS = List.of(
            "/esProduct/importAll",
            "/esProduct/create/26",
            "/esProduct/delete/26",
            "/esProduct/delete/batch",
            "/esProduct/sync/26",
            "/esProduct/sync/batch");
    private static final List<String> READ_ONLY_PATHS = List.of(
            "/esProduct/search",
            "/esProduct/search/simple",
            "/esProduct/search/relate",
            "/esProduct/recommend/26");
    /**
     * 矩阵参数变体：第一项是真实映射路径，第二项是容器转发时携带的原始URI
     */
    private static final List<String[]> MATRIX_PARAM_VARIANTS = List.of(
            new String[]{"/esProduct/importAll", "/esProduct/importAll;x=1"},
            new String[]{"/esProduct/importAll", "/esProduct/importAll;x=1/"},
            new String[]{"/esProduct/importAll", "/esProduct/importAll%3Bx=1"},
            new String[]{"/esProduct/create/26", "/esProduct/create/26;x=1"},
            new String[]{"/esProduct/delete/26", "/esProduct/delete/26;x=1"},
            new String[]{"/esProduct/delete/batch", "/esProduct/delete/batch;x=1"},
            new String[]{"/esProduct/sync/26", "/esProduct/sync/26;x=1"},
            new String[]{"/esProduct/sync/batch", "/esProduct/sync/batch;x=1;y=2"});

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private SearchSyncProperties searchSyncProperties;
    @MockitoBean
    private EsProductService esProductService;

    @BeforeEach
    void setUp() {
        reset(esProductService);
        searchSyncProperties.setInternalToken(TOKEN);
        when(esProductService.importAll()).thenReturn(7);
        when(esProductService.search(any(), any(), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 5), 0));
        when(esProductService.search(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 5), 0));
        when(esProductService.recommend(any(), any(), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 5), 0));
    }

    @Test
    public void testFilterRegistrationCoversEsProductPaths() {
        Map<String, FilterRegistrationBean> registrations = applicationContext.getBeansOfType(FilterRegistrationBean.class);
        assertEquals(1, registrations.size(), "内部令牌过滤器必须注册为FilterRegistrationBean");
        FilterRegistrationBean registration = registrations.values().iterator().next();
        assertEquals("internalTokenAuthFilter", registration.getFilterName());
        assertTrue(registration.getUrlPatterns().contains(InternalTokenAuthFilter.ES_PRODUCT_URL_PATTERN),
                "过滤器注册模式必须覆盖/esProduct下全部子路径，包含尾斜杠请求");
    }

    @Test
    public void testWritePathsRejectMissingToken() throws Exception {
        for (String path : PROTECTED_PATHS) {
            mockMvc.perform(writeRequest(path)).andExpect(status().isUnauthorized());
        }
        verify(esProductService, never()).importAll();
    }

    @Test
    public void testWritePathsRejectWrongToken() throws Exception {
        for (String path : PROTECTED_PATHS) {
            mockMvc.perform(writeRequest(path).header(InternalTokenAuthFilter.TOKEN_HEADER, "wrong-token"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    public void testWritePathsRejectWhenServerTokenNotConfigured() throws Exception {
        searchSyncProperties.setInternalToken("");
        for (String path : PROTECTED_PATHS) {
            mockMvc.perform(writeRequest(path).header(InternalTokenAuthFilter.TOKEN_HEADER, TOKEN))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    @Test
    public void testWritePathsContinueFilterChainWithCorrectToken() throws Exception {
        for (String path : PROTECTED_PATHS) {
            mockMvc.perform(writeRequest(path).header(InternalTokenAuthFilter.TOKEN_HEADER, TOKEN))
                    .andExpect(status().isOk());
        }
        verify(esProductService, times(1)).importAll();
        verify(esProductService, times(1)).create(26L);
        verify(esProductService, times(1)).delete(26L);
    }

    @Test
    public void testTrailingSlashWritePathsStillRequireToken() throws Exception {
        mockMvc.perform(post("/esProduct/importAll/")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/esProduct/sync/26/")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/esProduct/sync/batch/")).andExpect(status().isUnauthorized());
        verify(esProductService, never()).importAll();
    }

    @Test
    public void testContextPathWritePathsStillRequireToken() throws Exception {
        mockMvc.perform(post(CONTEXT_PATH + "/esProduct/importAll").contextPath(CONTEXT_PATH))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(CONTEXT_PATH + "/esProduct/sync/26").contextPath(CONTEXT_PATH))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(CONTEXT_PATH + "/esProduct/importAll/").contextPath(CONTEXT_PATH))
                .andExpect(status().isUnauthorized());
        verify(esProductService, never()).importAll();
    }

    @Test
    public void testReadOnlySearchPathsStayAnonymous() throws Exception {
        for (String path : READ_ONLY_PATHS) {
            mockMvc.perform(get(path)).andExpect(status().isOk());
        }
        searchSyncProperties.setInternalToken("");
        for (String path : READ_ONLY_PATHS) {
            mockMvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    public void testMatrixParameterWritePathsRejectMissingToken() throws Exception {
        for (String[] variant : MATRIX_PARAM_VARIANTS) {
            mockMvc.perform(writeRequest(variant[0], variant[1])).andExpect(status().isUnauthorized());
        }
        verify(esProductService, never()).importAll();
        verify(esProductService, never()).create(anyLong());
        verify(esProductService, never()).delete(anyLong());
    }

    @Test
    public void testMatrixParameterWritePathsPassWithCorrectToken() throws Exception {
        for (String[] variant : MATRIX_PARAM_VARIANTS) {
            mockMvc.perform(writeRequest(variant[0], variant[1])
                            .header(InternalTokenAuthFilter.TOKEN_HEADER, TOKEN))
                    .andExpect(notRejectedByTokenFilter());
        }
        verify(esProductService, times(1)).importAll();
    }

    @Test
    public void testMatrixParameterWritePathsRejectWrongToken() throws Exception {
        for (String[] variant : MATRIX_PARAM_VARIANTS) {
            mockMvc.perform(writeRequest(variant[0], variant[1])
                            .header(InternalTokenAuthFilter.TOKEN_HEADER, "wrong-token"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    public void testNonPositiveAndNonNumericPathParametersRejectMissingToken() throws Exception {
        List<String> valueVariants = List.of(
                "/esProduct/create/0",
                "/esProduct/create/-1",
                "/esProduct/create/abc",
                "/esProduct/delete/0",
                "/esProduct/sync/1abc");
        for (String path : valueVariants) {
            mockMvc.perform(writeRequest(path, path)).andExpect(status().isUnauthorized());
        }
        verify(esProductService, never()).importAll();
    }

    @Test
    public void testTraversalRawUrisRejectMissingToken() throws Exception {
        mockMvc.perform(writeRequest("/esProduct/delete/26", "/esProduct/search/../delete/26"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(writeRequest("/esProduct/importAll", "/esProduct/%2e%2e/esProduct/importAll"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(writeRequest("/esProduct/create/26", "/esProduct/create%2F26"))
                .andExpect(status().isUnauthorized());
        verify(esProductService, never()).importAll();
        verify(esProductService, never()).create(anyLong());
    }

    @Test
    public void testSimilarPathsDoNotReachWriteHandlers() throws Exception {
        mockMvc.perform(post("/esProduct/importAllExtra").header(InternalTokenAuthFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/esProduct/sync-other").header(InternalTokenAuthFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound());
        verify(esProductService, never()).importAll();
        verify(esProductService, never()).sync(any(java.util.List.class));
    }

    /**
     * 按各写接口真实签名构造请求，保证正确令牌下请求能真正走到业务处理并返回200
     */
    private MockHttpServletRequestBuilder writeRequest(String path) {
        MockHttpServletRequestBuilder builder = path.startsWith("/esProduct/delete/26") ? get(path) : post(path);
        if (path.equals("/esProduct/delete/batch")) {
            builder = builder.param("ids", "26");
        }
        if (path.equals("/esProduct/sync/batch")) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content("[26]");
        }
        return builder;
    }

    /**
     * 在保持真实映射路径的同时，把原始requestURI替换为带矩阵参数或异常编码的变体，
     * 用于验证容器真实转发这类URI时过滤器仍然会鉴权
     */
    private MockHttpServletRequestBuilder writeRequest(String mappingPath, String rawUri) {
        return writeRequest(mappingPath).with(request -> {
            request.setRequestURI(rawUri);
            return request;
        });
    }

    /**
     * 只断言没有被令牌过滤器拦截，允许后续参数解析返回400或路径未匹配返回404
     */
    private static ResultMatcher notRejectedByTokenFilter() {
        return result -> {
            int status = result.getResponse().getStatus();
            assertTrue(status != 401 && status != 503,
                    "携带正确令牌时不应被过滤器拦截，实际状态码=" + status);
        };
    }
}

package com.macro.mall.search.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.common.api.ResultCode;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 同步接口内部令牌校验过滤器
 * 只对 /esProduct/sync/** 生效，其余搜索接口不做任何校验
 */
public class InternalTokenAuthFilter implements Filter {
    private static final Logger LOGGER = LoggerFactory.getLogger(InternalTokenAuthFilter.class);
    /**
     * 内部令牌请求头名称
     */
    public static final String TOKEN_HEADER = "X-Internal-Token";
    /**
     * 需要保护的路径前缀
     */
    public static final String SYNC_PATH_PREFIX = "/esProduct/sync/";
    private static final String UNAUTHORIZED_MESSAGE = "内部令牌校验失败，拒绝访问";
    private static final String NOT_CONFIGURED_MESSAGE = "搜索服务未配置内部令牌，同步接口已拒绝访问";

    private final SearchSyncProperties searchSyncProperties;
    private final ObjectMapper objectMapper;

    public InternalTokenAuthFilter(SearchSyncProperties searchSyncProperties, ObjectMapper objectMapper) {
        this.searchSyncProperties = searchSyncProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        if (!isSyncRequest(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }
        String serverToken = searchSyncProperties.getInternalToken();
        if (serverToken == null || serverToken.isBlank()) {
            //令牌未配置时必须明确失败，不能静默放行内部同步接口
            LOGGER.error("同步接口内部令牌未配置，请设置mall.search.internal-token或环境变量MALL_SEARCH_INTERNAL_TOKEN，已拒绝请求:{}",
                    httpRequest.getRequestURI());
            writeResult(httpResponse, HttpServletResponse.SC_SERVICE_UNAVAILABLE, CommonResult.failed(NOT_CONFIGURED_MESSAGE));
            return;
        }
        String requestToken = httpRequest.getHeader(TOKEN_HEADER);
        if (!tokenMatches(serverToken, requestToken)) {
            LOGGER.warn("同步接口内部令牌校验失败，已拒绝请求:{}，来源:{}", httpRequest.getRequestURI(), httpRequest.getRemoteAddr());
            writeResult(httpResponse, HttpServletResponse.SC_UNAUTHORIZED,
                    CommonResult.failed(ResultCode.UNAUTHORIZED, UNAUTHORIZED_MESSAGE));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 判断请求路径是否属于同步接口，去掉contextPath后再比较，避免前缀绕过
     */
    private boolean isSyncRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri.startsWith(SYNC_PATH_PREFIX);
    }

    /**
     * 常量时间比较，避免令牌比较被时序分析
     */
    private boolean tokenMatches(String serverToken, String requestToken) {
        if (requestToken == null || requestToken.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(serverToken.getBytes(StandardCharsets.UTF_8),
                requestToken.getBytes(StandardCharsets.UTF_8));
    }

    private void writeResult(HttpServletResponse response, int status, CommonResult<?> body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}

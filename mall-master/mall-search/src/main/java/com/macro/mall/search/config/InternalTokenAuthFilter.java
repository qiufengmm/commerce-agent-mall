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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 写接口内部令牌校验过滤器
 * 保护六条写路径：/esProduct/importAll、/esProduct/create/{id}、/esProduct/delete/{id}、
 * /esProduct/delete/batch、/esProduct/sync/{id}、/esProduct/sync/batch，
 * 其余esProduct只读搜索接口始终保持匿名可访问。
 * 路径判断先去掉contextPath、归一化尾斜杠、剥离分号矩阵参数，再按路径段匹配：
 * 首段是写动作（importAll/create/delete/sync）时一律要求鉴权，不再依赖参数是否为正整数，
 * 因此 /esProduct/create/0、/esProduct/delete/-1、/esProduct/sync/abc、
 * /esProduct/importAll;x=1 等变体都无法绕过鉴权；
 * /esProduct/importAllExtra、/esProduct/sync-other 等相似路径不会被放行也不会误判为写路径。
 */
public class InternalTokenAuthFilter implements Filter {
    private static final Logger LOGGER = LoggerFactory.getLogger(InternalTokenAuthFilter.class);
    /**
     * 内部令牌请求头名称
     */
    public static final String TOKEN_HEADER = "X-Internal-Token";
    /**
     * 过滤器注册路径，覆盖esProduct下全部子路径，尾斜杠与contextPath都落在该模式内
     */
    public static final String ES_PRODUCT_URL_PATTERN = "/esProduct/*";
    /**
     * esProduct基础路径
     */
    public static final String ES_PRODUCT_PATH = "/esProduct";
    /**
     * 受保护的单段写路径
     */
    public static final Set<String> PROTECTED_SINGLE_SEGMENT_PATHS = Set.of("importAll");
    /**
     * 受保护的写动作段，只要请求在该前缀下以此段开头就必须鉴权
     */
    public static final Set<String> PROTECTED_ID_ACTIONS = Set.of("create", "delete", "sync");
    /**
     * 分号矩阵参数分隔符，Spring MVC 默认剥离分号内容后再做路径匹配
     */
    private static final char MATRIX_PARAMETER_SEPARATOR = ';';
    private static final String UNAUTHORIZED_MESSAGE = "内部令牌校验失败，拒绝访问";
    private static final String NOT_CONFIGURED_MESSAGE = "搜索服务未配置内部令牌，写接口已拒绝访问";

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
        if (!isProtectedWriteRequest(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }
        String serverToken = searchSyncProperties.getInternalToken();
        if (serverToken == null || serverToken.isBlank()) {
            //令牌未配置时必须明确失败，不能静默放行内部写接口
            LOGGER.error("写接口内部令牌未配置，请设置mall.search.internal-token或环境变量MALL_SEARCH_INTERNAL_TOKEN，已拒绝请求:{}",
                    httpRequest.getRequestURI());
            writeResult(httpResponse, HttpServletResponse.SC_SERVICE_UNAVAILABLE, CommonResult.failed(NOT_CONFIGURED_MESSAGE));
            return;
        }
        String requestToken = httpRequest.getHeader(TOKEN_HEADER);
        if (!tokenMatches(serverToken, requestToken)) {
            LOGGER.warn("写接口内部令牌校验失败，已拒绝请求:{}，来源:{}", httpRequest.getRequestURI(), httpRequest.getRemoteAddr());
            writeResult(httpResponse, HttpServletResponse.SC_UNAUTHORIZED,
                    CommonResult.failed(ResultCode.UNAUTHORIZED, UNAUTHORIZED_MESSAGE));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 判断请求路径是否属于受保护的写路径。
     * 处理顺序：去掉contextPath → 去掉尾斜杠 → 剥离分号矩阵参数 → 按路径段匹配。
     * 首段命中写动作集合时一律按受保护处理，因此0、负数、非数字等参数形式都不会造成绕过；
     * 出现路径穿越段（. / ..）或编码后的点、斜杠时同样按受保护处理，采用fail-closed策略。
     * URI无法解析时按受保护处理，避免异常URI绕过鉴权。
     */
    private boolean isProtectedWriteRequest(HttpServletRequest request) {
        String requestPath = resolveRequestPath(request);
        if (requestPath == null) {
            return true;
        }
        if (!requestPath.equals(ES_PRODUCT_PATH) && !requestPath.startsWith(ES_PRODUCT_PATH + "/")) {
            return false;
        }
        String relativePath = requestPath.substring(ES_PRODUCT_PATH.length());
        if (containsEncodedTraversal(relativePath)) {
            //编码后的点或斜杠可能被容器解码后指向写接口，无法安全判断时按受保护处理
            return true;
        }
        List<String> segments = splitSegments(relativePath);
        if (containsTraversalSegment(segments)) {
            //容器可能先归一化再映射到写接口，无法安全判断时按受保护处理
            return true;
        }
        if (segments.isEmpty()) {
            return false;
        }
        if (PROTECTED_SINGLE_SEGMENT_PATHS.contains(segments.get(0))) {
            return true;
        }
        return PROTECTED_ID_ACTIONS.contains(segments.get(0));
    }

    /**
     * 路径中包含 . 或 .. 段时按受保护处理，避免被容器归一化后指向写接口
     */
    private boolean containsTraversalSegment(List<String> segments) {
        for (String segment : segments) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检测编码后的点或斜杠，这类路径可能被容器解码后指向写接口，采用fail-closed策略
     */
    private boolean containsEncodedTraversal(String path) {
        String lowerCasePath = path.toLowerCase(Locale.ROOT);
        return lowerCasePath.contains("%2e") || lowerCasePath.contains("%2f") || lowerCasePath.contains("%5c");
    }

    /**
     * 解析请求URI：去掉contextPath，去掉多余尾斜杠，无法解析时返回null
     */
    private String resolveRequestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.isEmpty() || uri.charAt(0) != '/') {
            return null;
        }
        while (uri.length() > 1 && uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return uri;
    }

    /**
     * 拆分路径段：剥离分号矩阵参数并丢弃空段，
     * 保证 /esProduct/importAll;x=1、/esProduct/importAll/;x=1 等变体仍能匹配到写路径
     */
    private List<String> splitSegments(String path) {
        List<String> segments = new ArrayList<>();
        for (String rawSegment : path.split("/")) {
            String segment = stripMatrixParameters(rawSegment);
            if (segment.isEmpty()) {
                continue;
            }
            segments.add(segment);
        }
        return segments;
    }

    /**
     * 剥离分号矩阵参数，同时处理分号的百分号编码形式，避免 ;x=1、%3Bx=1 绕过写路径匹配
     */
    private String stripMatrixParameters(String rawSegment) {
        if (rawSegment == null || rawSegment.isEmpty()) {
            return "";
        }
        int cutIndex = rawSegment.length();
        int semicolonIndex = rawSegment.indexOf(MATRIX_PARAMETER_SEPARATOR);
        if (semicolonIndex >= 0) {
            cutIndex = semicolonIndex;
        }
        for (int i = 0; i + 2 < rawSegment.length(); i++) {
            if (rawSegment.charAt(i) == '%' && rawSegment.charAt(i + 1) == '3'
                    && (rawSegment.charAt(i + 2) == 'b' || rawSegment.charAt(i + 2) == 'B')) {
                cutIndex = Math.min(cutIndex, i);
                break;
            }
        }
        return rawSegment.substring(0, cutIndex);
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

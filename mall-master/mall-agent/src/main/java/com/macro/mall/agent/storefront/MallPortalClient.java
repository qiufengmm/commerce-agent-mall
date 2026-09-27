package com.macro.mall.agent.storefront;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import com.macro.mall.agent.api.PythonText;
import com.macro.mall.agent.http.BoundedBodyReader;
import com.macro.mall.agent.storefront.dto.CouponHistoryResponse;
import com.macro.mall.agent.storefront.dto.MemberInfoResponse;
import com.macro.mall.agent.storefront.dto.ProductCouponResponse;
import com.macro.mall.agent.storefront.dto.ProductDetailResponse;
import com.macro.mall.agent.storefront.dto.ProductSearchResponse;

/**
 * mall-portal 只读客户端，行为对齐 Python
 * {@code mall_shopping_agent.storefront.mall_portal.MallPortalBackend}。
 *
 * <p>只暴露五条定型只读操作，URI 路径为固定常量、分页大小固定为 5；<strong>不提供</strong>任何接受
 * 任意 URL / HTTP method / header 的通用入口，调用方无法让关键词或 ID 影响 host 或 path 结构：
 * <ul>
 *   <li>{@code GET /product/search?pageNum=..&pageSize=5&sort=..}（可选 {@code keyword/brandId/productCategoryId}）</li>
 *   <li>{@code GET /product/detail/{positiveId}}</li>
 *   <li>{@code GET /sso/info}</li>
 *   <li>{@code GET /member/coupon/listHistory?useStatus=0}</li>
 *   <li>{@code GET /member/coupon/listByProduct/{positiveId}}</li>
 * </ul>
 * host/scheme/port 只来自构造参数（生产环境由 {@code AgentProperties.portalBaseUrl} 提供）。
 * {@code Authorization} 只对三条会员路由逐字透传，搜索与详情绝不携带。
 *
 * <p>本类只独占网络边界：构造并校验 {@code RestClient}、固定 URI、安全 {@code Authorization}、
 * HTTP 状态 / envelope / 传输错误分类与就绪探针。响应 {@code data} 节点到 storefront DTO 的结构校验、
 * Python 兼容字段转换与「列表非法行整行跳过」策略由同包 {@link MallPortalResponseParser} 承担。
 *
 * <p>错误映射对齐 Python，并额外区分超时：
 * <ul>
 *   <li>HTTP / envelope 401、403 → {@link PortalException.Kind#MEMBER_UNAUTHORIZED}（401）</li>
 *   <li>HTTP / envelope 404 → {@link PortalException.Kind#NOT_FOUND}（404）</li>
 *   <li>详情与商品券路径的 HTTP 500（Spring 默认错误结构，无业务 envelope）→ {@code NOT_FOUND}；
 *       有业务 envelope {@code code=500} 时仍是 {@code UNAVAILABLE}</li>
 *   <li>读/连接超时 → {@link PortalException.Kind#TIMEOUT}（502）；其他传输失败 → {@code UNAVAILABLE}（502）</li>
 *   <li>非法 JSON / 缺 code / 结构非法 → {@link PortalException.Kind#PROTOCOL}（502）</li>
 * </ul>
 * 所有金额使用 {@code java.math.BigDecimal}；门户未知字段一律忽略；异常与日志不保留上游正文、
 * {@code Authorization}、host 或完整 URL/query。
 *
 * <p>响应正文使用 {@link BoundedBodyReader} 有界读取，硬上限 {@code 1 MiB}；超过上限立即按固定
 * {@link PortalException.Kind#PROTOCOL} 失败，不会无界读取或在读到上限后继续 drain。
 */
public final class MallPortalClient {

    /** 服务端强制的分页大小；不接受外部传入。 */
    private static final int PAGE_SIZE = 5;
    private static final int MAX_PAGE_NUM = 20;
    private static final int MAX_SORT = 4;
    private static final int MAX_KEYWORD_LENGTH = 100;

    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private static final String PATH_SEARCH = "/product/search";
    private static final String PATH_DETAIL_PREFIX = "/product/detail/";
    private static final String PATH_SSO_INFO = "/sso/info";
    private static final String PATH_COUPON_HISTORY = "/member/coupon/listHistory";
    private static final String PATH_COUPON_HISTORY_QUERY = PATH_COUPON_HISTORY + "?useStatus=0";
    private static final String PATH_COUPON_BY_PRODUCT_PREFIX = "/member/coupon/listByProduct/";

    private static final Logger LOGGER = LoggerFactory.getLogger(MallPortalClient.class);

    /**
     * 门户响应 JSON（envelope 与就绪探针）解析专用的独立 ObjectMapper，与全局严格 Jackson 策略隔离。
     *
     * <p>{@code USE_BIG_DECIMAL_FOR_FLOATS} + 精确 BigDecimal 节点保证 {@code 1899.00} 这类金额
     * 按门户原始文本解析为两位精度，而不会先经 {@code double} 再退化。
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(new JsonNodeFactory(true));

    // 固定安全文案：不带 host、query、关键词、Token 或上游正文
    private static final String MESSAGE_UNAVAILABLE = "门户服务暂时不可用";
    private static final String MESSAGE_UNPARSEABLE = "门户返回结构无法解析";
    private static final String MESSAGE_BODY_TOO_LARGE = "门户响应体超出大小上限";

    private final RestClient restClient;
    private final String portalBaseUrl;

    /**
     * @param restClientBuilder 由调用方提供并已完成超时/连接配置的构建器；测试可对其绑定 HTTP 模拟
     * @param portalBaseUrl     门户基础地址（生产由 {@code AgentProperties.portalBaseUrl} 提供）；
     *                          scheme/host/port 与可配置路径前缀均来自此处，末尾斜杠会被去除
     * @throws IllegalArgumentException 基础地址为空、不是绝对 http(s)、含内嵌凭据/查询/片段或空白
     */
    public MallPortalClient(RestClient.Builder restClientBuilder, String portalBaseUrl) {
        if (restClientBuilder == null) {
            throw new IllegalArgumentException("restClientBuilder 不能为空");
        }
        this.portalBaseUrl = normalizeBaseUrl(portalBaseUrl);
        this.restClient = restClientBuilder.build();
    }

    /**
     * 校验并规范化门户基础地址：只接受绝对的 http(s) 地址，允许可配置路径前缀。
     *
     * <p>拒绝相对地址、非 http(s) 协议、内嵌凭据（user-info）、查询参数与片段。所有错误文案都是固定
     * 文本，<strong>不回显输入</strong>，避免把地址或内嵌凭据写进异常、日志或报告；约束与
     * {@code AgentProperties.httpUrl} 一致。
     */
    private static String normalizeBaseUrl(String value) {
        if (value == null) {
            throw invalidBaseUrl("不能为空");
        }
        String text = value.strip();
        if (text.isEmpty()) {
            throw invalidBaseUrl("不能为空");
        }
        if (text.chars().anyMatch(Character::isWhitespace)) {
            throw invalidBaseUrl("不能包含空白字符");
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException ex) {
            throw invalidBaseUrl("必须是合法的绝对 http(s) 地址");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw invalidBaseUrl("协议必须是 http 或 https");
        }
        if (uri.isOpaque() || uri.getHost() == null || uri.getHost().isEmpty()) {
            throw invalidBaseUrl("必须包含主机");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalidBaseUrl("不能包含内嵌凭据");
        }
        if (uri.getRawQuery() != null) {
            throw invalidBaseUrl("不能包含查询参数");
        }
        if (uri.getRawFragment() != null) {
            throw invalidBaseUrl("不能包含片段");
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw invalidBaseUrl("端口必须在 1 到 65535 之间");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        StringBuilder normalized = new StringBuilder()
                .append(scheme.toLowerCase(Locale.ROOT))
                .append("://")
                .append(uri.getHost());
        if (port != -1) {
            normalized.append(':').append(port);
        }
        return normalized.append(stripTrailingSlashes(path)).toString();
    }

    private static IllegalArgumentException invalidBaseUrl(String reason) {
        return new IllegalArgumentException("portalBaseUrl " + reason);
    }

    // ------------------------------------------------------------------ //
    // 五条定型只读操作
    // ------------------------------------------------------------------ //

    /**
     * {@code GET /product/search}；{@code data=null} 时返回保留请求页码、固定页大小的空页。
     *
     * <p>响应 {@code data} 节点到 {@link ProductSearchResponse} 的映射与错误分类委托
     * {@link MallPortalResponseParser#parseSearchPage(JsonNode, int)}。
     */
    public ProductSearchResponse searchProducts(SearchParams params) {
        if (params == null) {
            throw new IllegalArgumentException("params 不能为空");
        }
        JsonNode data = readData(searchUri(params), null, false);
        return MallPortalResponseParser.parseSearchPage(data, params.pageNum());
    }

    /**
     * {@code GET /product/detail/{productId}}；ID 非正数时不发请求。
     *
     * <p>响应 {@code data} 节点到 {@link ProductDetailResponse} 的映射与错误分类委托
     * {@link MallPortalResponseParser#parseProductDetail(JsonNode)}。
     */
    public ProductDetailResponse getProductDetail(long productId) {
        requirePositiveId(productId);
        JsonNode data = readData(fixedUri(PATH_DETAIL_PREFIX + productId), null, true);
        return MallPortalResponseParser.parseProductDetail(data);
    }

    /**
     * {@code GET /sso/info}；Authorization 逐字透传，缺 id 或身份非法时视为未授权。
     *
     * <p>响应 {@code data} 节点到 {@link MemberInfoResponse} 的映射与错误分类委托
     * {@link MallPortalResponseParser#parseMember(JsonNode)}。
     */
    public MemberInfoResponse resolveMember(String authorization) {
        JsonNode data = readData(fixedUri(PATH_SSO_INFO), authorization, false);
        return MallPortalResponseParser.parseMember(data);
    }

    /**
     * {@code GET /member/coupon/listHistory?useStatus=0}；{@code data=null} 时返回空列表。
     *
     * <p>响应 {@code data} 节点到 {@link CouponHistoryResponse} 列表的映射、非法行跳过与错误分类委托
     * {@link MallPortalResponseParser#parseCouponHistory(JsonNode)}。
     */
    public List<CouponHistoryResponse> listUnusedCouponHistory(String authorization) {
        JsonNode data = readData(fixedUri(PATH_COUPON_HISTORY_QUERY), authorization, false);
        return MallPortalResponseParser.parseCouponHistory(data);
    }

    /**
     * {@code GET /member/coupon/listByProduct/{productId}}；ID 非正数时不发请求，
     * {@code data=null} 时返回空列表。
     *
     * <p>响应 {@code data} 节点到 {@link ProductCouponResponse} 列表的映射、非法行跳过与错误分类委托
     * {@link MallPortalResponseParser#parseProductCoupons(JsonNode)}。
     */
    public List<ProductCouponResponse> listProductCoupons(long productId, String authorization) {
        requirePositiveId(productId);
        JsonNode data = readData(fixedUri(PATH_COUPON_BY_PRODUCT_PREFIX + productId), authorization, true);
        return MallPortalResponseParser.parseProductCoupons(data);
    }

    // ------------------------------------------------------------------ //
    // 就绪探针：固定匿名只读检查
    // ------------------------------------------------------------------ //

    /**
     * 就绪探针专用的固定匿名只读检查。
     *
     * <p>复用本客户端<strong>已配置超时</strong>的 {@code restClient}，请求固定为
     * {@code GET /product/search?pageNum=1&pageSize=1}：路径与 query 全部是常量，不接受任何外部参数
     * （无 URL/method/header 入口），也不携带 {@code Authorization}。
     *
     * <p>仅当同时满足以下条件时返回 {@code true}，否则返回 {@code false}：
     * <ul>
     *   <li>HTTP 状态为 200；</li>
     *   <li>业务 envelope {@code code=200}；</li>
     *   <li>{@code data.total} 存在且为非负 JSON 整数。</li>
     * </ul>
     *
     * <p>任何协议不符、HTTP 非 200、连接/读取超时或传输异常都<strong>安全返回 false</strong>，
     * 不抛出，也不记录依赖地址、上游正文或凭据。与业务 {@link #searchProducts}（固定
     * {@code pageSize=5}）完全分离，不共享参数。
     *
     * <p><strong>包级可见</strong>：本类的公开契约只有五条定型只读业务操作（见类注释与
     * {@code MallPortalClientTest#portalClientExposesOnlyTheFiveFixedReadOperations}）。就绪探针
     * 由同包的 {@link PortalSearchHealthProbe} 包装后对外提供，避免给本类增加第六个公开方法。
     *
     * @return 该门户当前是否可承载只读业务
     */
    boolean isServiceReady() {
        RawResponse raw;
        try {
            raw = restClient.get()
                    .uri(readinessUri())
                    .headers(headers -> headers.setAccept(List.of(MediaType.APPLICATION_JSON)))
                    .exchange((request, response) ->
                            new RawResponse(response.getStatusCode().value(), readBody(response)));
        } catch (RuntimeException ex) {
            // 只记录异常类型，绝不记录 URL、上游正文或凭据
            LOGGER.debug("mall-portal 就绪探针请求失败：{}", ex.getClass().getSimpleName());
            return false;
        }
        if (raw.status() != 200) {
            return false;
        }
        JsonNode payload = parseJson(raw.body());
        if (payload == null || !payload.isObject() || !isCode(payload.get("code"), 200)) {
            return false;
        }
        JsonNode data = payload.get("data");
        if (data == null || !data.isObject()) {
            return false;
        }
        JsonNode total = data.get("total");
        return total != null
                && total.isIntegralNumber()
                && total.canConvertToLong()
                && total.longValue() >= 0;
    }

    /** 就绪探针的固定 URI：所有组件都是常量，不接受任何外部参数。 */
    private URI readinessUri() {
        return URI.create(portalBaseUrl + PATH_SEARCH + "?pageNum=1&pageSize=1");
    }

    // ------------------------------------------------------------------ //
    // 请求发送与 envelope 解析
    // ------------------------------------------------------------------ //

    private JsonNode readData(URI uri, String authorization, boolean serverErrorIsNotFound) {
        String headerValue = safeAuthorizationValue(authorization);
        RawResponse raw;
        try {
            raw = restClient.get()
                    .uri(uri)
                    .headers(headers -> {
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                        // 只有会员路由才会传入 Authorization；null 时不设置该头
                        if (headerValue != null) {
                            headers.set(HttpHeaders.AUTHORIZATION, headerValue);
                        }
                    })
                    .exchange((request, response) ->
                            new RawResponse(response.getStatusCode().value(), readBody(response)));
        } catch (BoundedBodyReader.BodyTooLargeException ex) {
            // 有界读取超过硬上限：按既有固定 PROTOCOL 分类安全失败，绝不携带上游正文或 Token
            throw PortalException.protocol(MESSAGE_BODY_TOO_LARGE);
        } catch (ResourceAccessException ex) {
            PortalException failure = classifyTransportFailure(ex);
            LOGGER.debug("mall-portal 只读请求传输失败：{}", failure.kind());
            throw failure;
        } catch (RestClientException ex) {
            LOGGER.debug("mall-portal 只读请求失败：{}", PortalException.Kind.UNAVAILABLE);
            throw PortalException.unavailable();
        }
        return parseEnvelope(raw, serverErrorIsNotFound);
    }

    /**
     * 校验 Authorization 取值。
     *
     * <p>空字符串按 Python {@code if authorization:} 的 falsey 语义视为「不发送」；含 CR/LF、NUL 等
     * 非法 HTTP 头字符时，与 Python httpx 在真实传输层抛 {@code LocalProtocolError}（它是
     * {@code httpx.HTTPError} 的子类，因此被 {@code except httpx.HTTPError} 捕获）一致，映射为
     * {@link PortalException#unavailable()}。异常只带固定文案，绝不保留该取值或原始异常。
     */
    private static String safeAuthorizationValue(String authorization) {
        if (authorization == null || authorization.isEmpty()) {
            return null;
        }
        for (int index = 0; index < authorization.length(); index++) {
            char current = authorization.charAt(index);
            boolean illegal = current == '\r'
                    || current == '\n'
                    || current == 0x7F
                    || (current < 0x20 && current != '\t')
                    || current > 0xFF;
            if (illegal) {
                throw PortalException.unavailable();
            }
        }
        return authorization;
    }

    private static JsonNode parseEnvelope(RawResponse raw, boolean serverErrorIsNotFound) {
        int status = raw.status();

        if (status == 401 || status == 403) {
            throw PortalException.memberUnauthorized();
        }

        JsonNode payload = parseJson(raw.body());
        if (payload != null && payload.isObject() && payload.has("code")) {
            JsonNode code = payload.get("code");
            if (isCode(code, 200)) {
                return payload.get("data");
            }
            if (isCode(code, 401)) {
                throw PortalException.memberUnauthorized();
            }
            if (isCode(code, 404)) {
                throw PortalException.notFound();
            }
            throw PortalException.unavailable();
        }

        if (status == 404) {
            throw PortalException.notFound();
        }
        if (status == 500 && serverErrorIsNotFound) {
            // 门户详情/商品券实现对不存在的商品会抛出未捕获异常，返回 Spring 默认 500 结构
            throw PortalException.notFound();
        }
        if (status >= 400) {
            throw PortalException.unavailable();
        }
        throw PortalException.protocol(MESSAGE_UNPARSEABLE);
    }

    private static PortalException classifyTransportFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof HttpConnectTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException) {
                return PortalException.timeout();
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return PortalException.unavailable();
    }

    private static boolean isCode(JsonNode node, int expected) {
        return node != null && node.isNumber() && node.asDouble() == expected;
    }

    private static JsonNode parseJson(String body) {
        if (body == null) {
            return null;
        }
        try {
            return JSON.readTree(body);
        } catch (JsonProcessingException ex) {
            // 不链式持有解析异常：Jackson 异常消息可能包含原始响应片段
            return null;
        }
    }

    private static String readBody(ClientHttpResponse response) throws IOException {
        try (InputStream body = response.getBody()) {
            // 有界读取：超过 DEFAULT_MAX_BYTES 立即抛 BodyTooLargeException，不 drain 完整正文
            return BoundedBodyReader.readUtf8(body, BoundedBodyReader.DEFAULT_MAX_BYTES);
        }
    }

    // ------------------------------------------------------------------ //
    // URI 构造
    // ------------------------------------------------------------------ //

    private URI searchUri(SearchParams params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(portalBaseUrl)
                .path(PATH_SEARCH)
                .queryParam("pageNum", params.pageNum())
                .queryParam("pageSize", PAGE_SIZE)
                .queryParam("sort", params.sort());
        String keyword = params.keyword();
        if (keyword != null && !keyword.isEmpty()) {
            builder.queryParam("keyword", encodeQueryValue(keyword));
        }
        if (params.brandId() != null) {
            builder.queryParam("brandId", params.brandId());
        }
        if (params.productCategoryId() != null) {
            builder.queryParam("productCategoryId", params.productCategoryId());
        }
        // 关键词取值已按 httpx 语义编码，其余取值是受控整数；path 与 host/port 全部固定。
        // 用 build(true) 声明「各组件已编码」，避免 Spring 对已编码的 %XX 再次编码（双重编码）。
        return builder.build(true).toUri();
    }

    /**
     * 按 Python httpx（{@code urllib.parse.quote_plus(s, safe='')}）语义编码 query 取值：
     * RFC 3986 unreserved 原样保留、空格编码为 {@code +}、其余按 UTF-8 百分号编码。
     *
     * <p>这样 {@code & = + % # ? /} 等字符不会改变 query 结构，也不会被解析成 fragment；
     * 与 Python 线上 query 取值逐字节一致（见报告中的 Python 探针输出）。
     */
    private static String encodeQueryValue(String value) {
        StringBuilder encoded = new StringBuilder(value.length() + 16);
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == ' ') {
                encoded.append('+');
                continue;
            }
            if (isUnreserved(codePoint)) {
                encoded.appendCodePoint(codePoint);
                continue;
            }
            for (byte raw : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                encoded.append('%')
                        .append(HEX_DIGITS[(raw >> 4) & 0xF])
                        .append(HEX_DIGITS[raw & 0xF]);
            }
        }
        return encoded.toString();
    }

    private static boolean isUnreserved(int codePoint) {
        return (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '-'
                || codePoint == '.'
                || codePoint == '_'
                || codePoint == '~';
    }

    private URI fixedUri(String path) {
        return URI.create(portalBaseUrl + path);
    }

    private static void requirePositiveId(long productId) {
        if (productId <= 0) {
            throw new IllegalArgumentException("productId 必须是正整数");
        }
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    // ------------------------------------------------------------------ //
    // 受控搜索参数
    // ------------------------------------------------------------------ //

    /**
     * {@code GET /product/search} 的受控参数。
     *
     * <p>组件精确为 {@code keyword/brandId/productCategoryId/sort/pageNum}，刻意<strong>不</strong>包含
     * {@code pageSize}、url、method、header 等外部可控字段；非法取值在构造阶段即被拒绝。
     * {@code pageSize} 由客户端固定为 5。
     */
    public record SearchParams(
            String keyword, Integer brandId, Integer productCategoryId, int sort, int pageNum) {

        public SearchParams {
            if (pageNum < 1 || pageNum > MAX_PAGE_NUM) {
                throw new IllegalArgumentException("pageNum 必须在 1 到 20 之间");
            }
            if (sort < 0 || sort > MAX_SORT) {
                throw new IllegalArgumentException("sort 必须在 0 到 4 之间");
            }
            if (brandId != null && brandId < 1) {
                throw new IllegalArgumentException("brandId 必须是正整数");
            }
            if (productCategoryId != null && productCategoryId < 1) {
                throw new IllegalArgumentException("productCategoryId 必须是正整数");
            }
            if (keyword != null) {
                // 与 Python str_strip_whitespace 一致：先去空白再按 Unicode 码点校验长度
                String stripped = PythonText.strip(keyword);
                if (stripped.codePointCount(0, stripped.length()) > MAX_KEYWORD_LENGTH) {
                    throw new IllegalArgumentException("keyword 长度不能超过 100");
                }
                keyword = stripped;
            }
        }
    }

    private record RawResponse(int status, String body) {
    }
}

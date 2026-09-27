package com.macro.mall.agent.storefront;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.macro.mall.agent.api.PythonText;
import com.macro.mall.agent.storefront.dto.CouponHistoryResponse;
import com.macro.mall.agent.storefront.dto.MemberInfoResponse;
import com.macro.mall.agent.storefront.dto.ProductAttribute;
import com.macro.mall.agent.storefront.dto.ProductCouponResponse;
import com.macro.mall.agent.storefront.dto.ProductDetailResponse;
import com.macro.mall.agent.storefront.dto.ProductSearchResponse;
import com.macro.mall.agent.storefront.dto.ProductSummary;
import com.macro.mall.agent.storefront.dto.SkuStock;

/**
 * mall-portal 响应 JSON 节点到既有 storefront DTO 的包级解析器。
 *
 * <p>从 {@link MallPortalClient} 抽出的纯映射职责：把门户 {@code data} 节点按 Python
 * {@code mall_shopping_agent.storefront.schemas} 的 pydantic 宽松语义转换为
 * {@link ProductSearchResponse}、{@link ProductDetailResponse}、{@link MemberInfoResponse}、
 * {@link CouponHistoryResponse}、{@link ProductCouponResponse}。
 *
 * <p><strong>纯函数 + 无网络</strong>：本类不持有 {@code RestClient}、不构造 URL、不发请求、不读写配置，
 * 只接受已经由 {@link MallPortalClient} 完成 HTTP 与 envelope 校验后的 {@link JsonNode}。因此网络边界、
 * {@code Authorization}、固定 URI、HTTP 状态/传输错误分类与就绪探针仍由客户端独占。
 *
 * <p><strong>行为对齐 Python，逐项保持</strong>：
 * <ul>
 *   <li>结构非法/字段非法的异常分类与固定文案与迁移前完全一致：搜索整体映射
 *       {@link PortalException.Kind#PROTOCOL}、详情单对象映射 {@code PROTOCOL}、会员身份映射
 *       {@link PortalException.Kind#MEMBER_UNAUTHORIZED}、详情缺失映射
 *       {@link PortalException.Kind#NOT_FOUND}；</li>
 *   <li>列表行（SKU、属性值、券）非法时<strong>整条跳过</strong>，与 Python helper 一致；</li>
 *   <li>金额使用 {@link BigDecimal} 保留门户原始精度；时间字段容忍 ISO-8601 与 epoch（秒/毫秒、
 *       含小数），首尾空白与科学记数文本按 Python 校验失败处理。</li>
 * </ul>
 *
 * <p>包级可见（非 {@code public}），不扩大 storefront 之外的 API 表面。
 */
final class MallPortalResponseParser {

    /**
     * 搜索分页默认页大小，与 Python {@code ProductSearchPage.page_size} 模型默认值及
     * {@link MallPortalClient} 固定请求页大小一致。
     */
    private static final int PAGE_SIZE = 5;
    private static final int MAX_ATTRIBUTES = 10;

    /**
     * pydantic（speedate）把绝对值大于该阈值的 epoch 数值解释为毫秒，否则解释为秒。
     */
    private static final BigDecimal EPOCH_MILLIS_THRESHOLD = new BigDecimal("20000000000");

    // 固定安全文案：不带 host、query、关键词、Token 或上游正文
    private static final String MESSAGE_SEARCH_SHAPE = "门户搜索结构无法解析";
    private static final String MESSAGE_PRODUCT_SHAPE = "门户商品详情结构无法解析";
    private static final String MESSAGE_HISTORY_SHAPE = "门户优惠券历史结构无法解析";
    private static final String MESSAGE_COUPON_SHAPE = "门户商品优惠券结构无法解析";

    private MallPortalResponseParser() {
    }

    // ------------------------------------------------------------------ //
    // 五类 response 映射入口
    // ------------------------------------------------------------------ //

    /** {@code data=null} 时返回保留请求页码、固定页大小的空页；结构/字段非法映射固定 PROTOCOL。 */
    static ProductSearchResponse parseSearchPage(JsonNode data, int requestedPageNum) {
        if (isNullish(data)) {
            return new ProductSearchResponse(requestedPageNum, PAGE_SIZE, 0, 0, List.of());
        }
        if (!data.isObject()) {
            throw PortalException.protocol(MESSAGE_SEARCH_SHAPE);
        }
        try {
            return mapSearchPage(data);
        } catch (RowInvalid ex) {
            // 与 Python ProductSearchPage.model_validate 失败一致：整体映射 PROTOCOL
            throw PortalException.protocol(MESSAGE_SEARCH_SHAPE);
        }
    }

    /** 详情单对象：缺失/非对象/缺 id 映射 {@code NOT_FOUND}；字段非法映射固定 PROTOCOL。 */
    static ProductDetailResponse parseProductDetail(JsonNode data) {
        if (isNullish(data) || !data.isObject()) {
            throw PortalException.notFound();
        }
        JsonNode product = data.get("product");
        if (product == null || !product.isObject()) {
            throw PortalException.notFound();
        }
        Long id = exactLong(product.get("id"));
        if (id == null) {
            throw PortalException.notFound();
        }

        try {
            return new ProductDetailResponse(
                    id,
                    laxText(product.get("name"), ""),
                    laxTextNullable(product.get("pic")),
                    laxDecimal(product.get("price")),
                    laxTextNullable(product.get("subTitle")),
                    laxTextNullable(product.get("description")),
                    laxLong(product.get("brandId")),
                    laxTextNullable(product.get("brandName")),
                    laxLong(product.get("productCategoryId")),
                    laxTextNullable(product.get("productCategoryName")),
                    laxTextNullable(product.get("productSn")),
                    laxInteger(product.get("sale")),
                    laxInteger(product.get("stock")),
                    laxInteger(product.get("publishStatus")),
                    laxInteger(product.get("deleteStatus")),
                    parseSkuStocks(data.get("skuStockList")),
                    parseAttributes(data.get("productAttributeList"), data.get("productAttributeValueList")),
                    parseCoupons(data.get("couponList")));
        } catch (RowInvalid ex) {
            // 与 Python ProductDetail.model_validate 失败一致：映射 PROTOCOL
            throw PortalException.protocol(MESSAGE_PRODUCT_SHAPE);
        }
    }

    /** 会员身份：缺 id 或身份非法映射 {@code MEMBER_UNAUTHORIZED}。 */
    static MemberInfoResponse parseMember(JsonNode data) {
        if (isNullish(data) || !data.isObject()) {
            throw PortalException.memberUnauthorized();
        }
        Long memberId = exactLong(data.get("id"));
        if (memberId == null) {
            throw PortalException.memberUnauthorized();
        }
        try {
            return new MemberInfoResponse(
                    memberId,
                    laxTextNullable(data.get("username")),
                    laxTextNullable(data.get("nickname")),
                    laxTextNullable(data.get("icon")));
        } catch (RowInvalid ex) {
            // 与 Python `except ValueError: raise MemberUnauthorizedError` 一致
            throw PortalException.memberUnauthorized();
        }
    }

    /** 未使用券历史：{@code data=null} 返回空列表；非数组映射固定 PROTOCOL；非法行整条跳过。 */
    static List<CouponHistoryResponse> parseCouponHistory(JsonNode data) {
        if (isNullish(data)) {
            return List.of();
        }
        if (!data.isArray()) {
            throw PortalException.protocol(MESSAGE_HISTORY_SHAPE);
        }
        List<CouponHistoryResponse> result = new ArrayList<>();
        for (JsonNode item : data) {
            if (!item.isObject()) {
                continue;
            }
            Long id = exactLong(item.get("id"));
            if (id == null) {
                continue;
            }
            try {
                result.add(new CouponHistoryResponse(
                        id,
                        laxLong(item.get("couponId")),
                        laxInteger(item.get("useStatus")),
                        laxInteger(item.get("getType")),
                        laxInstant(item.get("createTime"))));
            } catch (RowInvalid ex) {
                // 与 Python list_unused_coupon_history 一致：非法行整条跳过
            }
        }
        return result;
    }

    /** 商品券：{@code data=null} 返回空列表；非数组映射固定 PROTOCOL；非法行整条跳过。 */
    static List<ProductCouponResponse> parseProductCoupons(JsonNode data) {
        if (isNullish(data)) {
            return List.of();
        }
        if (!data.isArray()) {
            throw PortalException.protocol(MESSAGE_COUPON_SHAPE);
        }
        return parseCoupons(data);
    }

    // ------------------------------------------------------------------ //
    // 映射实现
    // ------------------------------------------------------------------ //

    private static ProductSearchResponse mapSearchPage(JsonNode data) {
        int pageNum = defaultedInt(data.get("pageNum"), 1);
        int pageSize = defaultedInt(data.get("pageSize"), PAGE_SIZE);
        int totalPage = defaultedInt(data.get("totalPage"), 0);
        int total = defaultedInt(data.get("total"), 0);

        JsonNode rawList = data.get("list");
        List<ProductSummary> items = new ArrayList<>();
        if (rawList != null) {
            if (!rawList.isArray()) {
                // 缺 list 字段用空列表默认；显式 JSON null 或非数组按 Python 校验失败处理
                throw new RowInvalid();
            }
            for (JsonNode item : rawList) {
                if (!item.isObject()) {
                    throw new RowInvalid();
                }
                items.add(new ProductSummary(
                        requireLaxLong(item.get("id")),
                        laxText(item.get("name"), ""),
                        laxTextNullable(item.get("pic")),
                        laxDecimal(item.get("price")),
                        laxTextNullable(item.get("subTitle")),
                        laxLong(item.get("brandId")),
                        laxTextNullable(item.get("brandName")),
                        laxLong(item.get("productCategoryId")),
                        laxTextNullable(item.get("productCategoryName")),
                        laxTextNullable(item.get("productSn")),
                        laxTextNullable(item.get("keywords")),
                        laxInteger(item.get("sale")),
                        laxInteger(item.get("stock")),
                        laxInteger(item.get("publishStatus"))));
            }
        }
        return new ProductSearchResponse(pageNum, pageSize, totalPage, total, items);
    }

    private static List<SkuStock> parseSkuStocks(JsonNode raw) {
        if (raw == null || !raw.isArray()) {
            return List.of();
        }
        List<SkuStock> result = new ArrayList<>();
        for (JsonNode item : raw) {
            if (!item.isObject()) {
                continue;
            }
            Long id = exactLong(item.get("id"));
            if (id == null) {
                continue;
            }
            try {
                String skuCode = laxTextNullable(item.get("skuCode"));
                // Python SkuStock 也建模了这三个字段，非法值同样要整条跳过
                laxDecimal(item.get("promotionPrice"));
                laxTextNullable(item.get("pic"));
                String spData = laxTextNullable(item.get("spData"));
                result.add(new SkuStock(
                        id,
                        skuCode,
                        laxDecimal(item.get("price")),
                        laxInteger(item.get("stock")),
                        laxInteger(item.get("lockStock")),
                        laxInteger(item.get("sale")),
                        spData));
            } catch (RowInvalid ex) {
                // 与 Python _parse_sku_stocks 一致：非法行整条跳过
            }
        }
        return result;
    }

    private static List<ProductAttribute> parseAttributes(JsonNode rawAttributes, JsonNode rawValues) {
        Map<Long, String> valueByAttribute = new HashMap<>();
        if (rawValues != null && rawValues.isArray()) {
            for (JsonNode item : rawValues) {
                if (!item.isObject()) {
                    continue;
                }
                Long attributeId = exactLong(item.get("productAttributeId"));
                JsonNode valueNode = item.get("value");
                if (attributeId == null || valueNode == null || valueNode.isNull()) {
                    continue;
                }
                valueByAttribute.put(attributeId, PythonText.strip(valueNode.asText()));
            }
        }

        if (rawAttributes == null || !rawAttributes.isArray()) {
            return List.of();
        }
        List<ProductAttribute> result = new ArrayList<>();
        for (JsonNode item : rawAttributes) {
            if (!item.isObject()) {
                continue;
            }
            Long attributeId = exactLong(item.get("id"));
            JsonNode nameNode = item.get("name");
            if (attributeId == null || nameNode == null || nameNode.isNull()) {
                continue;
            }
            String rawName = nameNode.asText();
            if (rawName.isEmpty()) {
                continue;
            }
            String value = valueByAttribute.get(attributeId);
            if (value == null) {
                // 只保留有取值的属性，避免把空属性当成商品事实
                continue;
            }
            result.add(new ProductAttribute(PythonText.strip(rawName), value));
            if (result.size() >= MAX_ATTRIBUTES) {
                break;
            }
        }
        return result;
    }

    private static List<ProductCouponResponse> parseCoupons(JsonNode raw) {
        if (raw == null || !raw.isArray()) {
            return List.of();
        }
        List<ProductCouponResponse> result = new ArrayList<>();
        for (JsonNode item : raw) {
            if (!item.isObject()) {
                continue;
            }
            Long id = exactLong(item.get("id"));
            if (id == null) {
                continue;
            }
            try {
                result.add(new ProductCouponResponse(
                        id,
                        laxText(item.get("name"), ""),
                        laxInteger(item.get("type")),
                        laxInteger(item.get("platform")),
                        laxDecimal(item.get("amount")),
                        laxDecimal(item.get("minPoint")),
                        laxInteger(item.get("perLimit")),
                        laxInstant(item.get("startTime")),
                        laxInstant(item.get("endTime")),
                        laxInteger(item.get("useType")),
                        laxTextNullable(item.get("note")),
                        laxInteger(item.get("memberLevel"))));
            } catch (RowInvalid ex) {
                // 与 Python parse_coupons 一致：非法行整条跳过
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ //
    // 基础解析辅助
    // ------------------------------------------------------------------ //

    /**
     * 内部信号：单个对象/单行数据不符合 Python pydantic 模型。
     *
     * <p>不携带任何消息、cause 或堆栈，只在包内用于区分「整条跳过（列表行）」与
     * 「映射 PROTOCOL / MEMBER_UNAUTHORIZED（单对象）」，绝不会逃逸到调用方。
     */
    private static final class RowInvalid extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private RowInvalid() {
            super(null, null, false, false);
        }
    }

    /** 与 Python {@code _as_int} 一致：拒绝布尔与小数；只用于 ID 类字段。 */
    private static Long exactLong(JsonNode node) {
        if (node == null || node.isNull() || node.isBoolean()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToLong() ? node.longValue() : null;
        }
        if (node.isTextual()) {
            String text = PythonText.strip(node.asText());
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /**
     * pydantic 宽松 int/long（含 {@code int} 与 {@code int | None}）：缺失/显式 null -> null；
     * 布尔按 0/1；整值小数（{@code 100.0}）与数字字符串（{@code "100.0"}）按整数；
     * 非整值或非数字 -> {@link RowInvalid}。
     */
    private static Long laxLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? 1L : 0L;
        }
        if (node.isIntegralNumber()) {
            if (!node.canConvertToLong()) {
                throw new RowInvalid();
            }
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return integralLong(node.decimalValue());
        }
        if (node.isTextual()) {
            String text = PythonText.strip(node.asText());
            if (text.isEmpty()) {
                throw new RowInvalid();
            }
            try {
                return integralLong(new BigDecimal(text));
            } catch (NumberFormatException ex) {
                throw new RowInvalid();
            }
        }
        throw new RowInvalid();
    }

    /** 必填 pydantic int（如 {@code id: int}）：缺失、null 或非法都视为该对象不合法。 */
    private static long requireLaxLong(JsonNode node) {
        Long value = laxLong(node);
        if (value == null) {
            throw new RowInvalid();
        }
        return value;
    }

    private static Integer laxInteger(JsonNode node) {
        Long value = laxLong(node);
        if (value == null) {
            return null;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new RowInvalid();
        }
        return value.intValue();
    }

    /**
     * pydantic 带默认值的 int 字段：字段<strong>缺失</strong>时用默认值。
     *
     * <p>显式 JSON {@code null} 或非法值一律视为该对象不合法——Python 对有默认值的非 Optional
     * （{@code int}）字段同样抛 ValidationError，不能被静默降级为默认值。
     */
    private static int defaultedInt(JsonNode node, int fallback) {
        if (node == null) {
            return fallback;
        }
        Integer value = laxInteger(node);
        if (value == null) {
            throw new RowInvalid();
        }
        return value;
    }

    /** 只接受整值：pydantic int 拒绝 {@code 100.5}，但接受 {@code 100.0} 与 {@code "100.0"}。 */
    private static long integralLong(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > 0) {
            throw new RowInvalid();
        }
        try {
            return stripped.longValueExact();
        } catch (ArithmeticException ex) {
            throw new RowInvalid();
        }
    }

    /** pydantic str：缺失 -> 默认值；显式 null 或非字符串 -> 非法；字符串按 str_strip_whitespace 去空白。 */
    private static String laxText(JsonNode node, String defaultValue) {
        if (node == null) {
            return defaultValue;
        }
        if (!node.isTextual()) {
            throw new RowInvalid();
        }
        return PythonText.strip(node.asText());
    }

    /** pydantic {@code str | None}：缺失或显式 null -> null；非字符串 -> 非法。 */
    private static String laxTextNullable(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new RowInvalid();
        }
        return PythonText.strip(node.asText());
    }

    /** 与 Python {@code parse_float=Decimal} 一致：按门户原始文本保留金额精度。 */
    private static BigDecimal laxDecimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            throw new RowInvalid();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            String text = PythonText.strip(node.asText());
            if (text.isEmpty()) {
                throw new RowInvalid();
            }
            BigDecimal parsed = decimalOrNull(text);
            if (parsed == null) {
                throw new RowInvalid();
            }
            return parsed;
        }
        throw new RowInvalid();
    }

    /**
     * 时间字段：JSON 数值按 epoch（秒/毫秒，含小数，精度用 {@link BigDecimal} 保留）；
     * 文本只接受 ISO-8601（{@code Z}/{@code z}、带偏移、无偏移、空格分隔、仅日期）与普通十进制数字字符串。
     *
     * <p>不做无条件去空白：Python/Pydantic 的时间解析会拒绝首尾空白文本；科学记数文本
     * （{@code 1e9}）同样拒绝。无偏移时间按 UTC 规范化为 {@link Instant}。
     */
    private static Instant laxInstant(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            throw new RowInvalid();
        }
        if (node.isNumber()) {
            return epochInstant(node.decimalValue());
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (!PythonText.strip(text).equals(text)) {
                // 首尾空白在 Python 侧是校验失败（整行跳过），不能先 strip 再解析
                throw new RowInvalid();
            }
            BigDecimal epoch = plainDecimalOrNull(text);
            if (epoch != null) {
                return epochInstant(epoch);
            }
            return isoInstant(text);
        }
        throw new RowInvalid();
    }

    /**
     * 只接受 pydantic 实测接受的普通十进制数字字符串：{@code [+-]?digits[.digits?]} 或 {@code [+-]?.digits}。
     *
     * <p>刻意拒绝科学记数（{@code 1e9}/{@code 1E9}）等其它数值写法：Python 的时间解析会拒绝它们，
     * 而 {@link BigDecimal#BigDecimal(String)} 会接受，因此先做语法判定再交给 {@code BigDecimal}。
     */
    private static BigDecimal plainDecimalOrNull(String text) {
        int index = 0;
        int length = text.length();
        if (index < length && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
            index++;
        }
        int digits = 0;
        while (index < length && isAsciiDigit(text.charAt(index))) {
            index++;
            digits++;
        }
        if (index < length && text.charAt(index) == '.') {
            index++;
            while (index < length && isAsciiDigit(text.charAt(index))) {
                index++;
                digits++;
            }
        }
        if (digits == 0 || index != length) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static BigDecimal decimalOrNull(String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Instant epochInstant(BigDecimal value) {
        BigDecimal seconds = value.abs().compareTo(EPOCH_MILLIS_THRESHOLD) > 0
                ? value.movePointLeft(3)
                : value;
        BigDecimal[] parts = seconds.divideAndRemainder(BigDecimal.ONE);
        BigDecimal nanos = parts[1].movePointRight(9).setScale(0, RoundingMode.DOWN);
        try {
            return Instant.ofEpochSecond(parts[0].longValueExact(), nanos.longValueExact());
        } catch (ArithmeticException | DateTimeException ex) {
            throw new RowInvalid();
        }
    }

    private static Instant isoInstant(String text) {
        String normalized = text;
        if (normalized.length() > 10 && normalized.charAt(10) == ' ') {
            normalized = normalized.substring(0, 10) + 'T' + normalized.substring(11);
        }
        if (normalized.endsWith("Z") || normalized.endsWith("z")) {
            normalized = normalized.substring(0, normalized.length() - 1) + "+00:00";
        }
        try {
            return OffsetDateTime.parse(normalized).toInstant();
        } catch (DateTimeParseException ex) {
            // 无偏移：按 naive 语义规范化为 UTC
        }
        try {
            return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            // 仅有日期
        }
        try {
            return LocalDate.parse(normalized).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ex) {
            throw new RowInvalid();
        }
    }

    private static boolean isNullish(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }
}

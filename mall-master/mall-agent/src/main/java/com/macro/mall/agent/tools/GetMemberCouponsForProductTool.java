package com.macro.mall.agent.tools;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import com.macro.mall.agent.storefront.PortalException;
import com.macro.mall.agent.storefront.dto.CouponHistoryResponse;
import com.macro.mall.agent.storefront.dto.ProductCouponResponse;

/**
 * {@code getMemberCouponsForProduct}：解释本人已领取且适用于指定商品的优惠券。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.tools.coupon_tools.get_member_coupons_for_product}：
 * 只取「本人未使用历史券 {@code useStatus=0/None}」与「该商品适用券」的 {@code couponId} 交集；
 * 游客或无 Authorization 立即返回 {@code LOGIN_REQUIRED} 且零会员接口调用；
 * Authorization 只逐字透传给两条会员只读接口；最多返回 5 张，可用优先、金额降序；
 * 绝不调用任何领券或写接口，也不在结果中保留 Token。
 */
public final class GetMemberCouponsForProductTool implements AgentTool {

    public static final String NAME = "getMemberCouponsForProduct";

    /** 结果中最多解释的券数。 */
    static final int MAX_COUPONS_IN_RESULT = 5;

    private static final String NOTE = "只统计本人未使用且适用于该商品的优惠券交集，不代表可以代领或代下单。";
    private static final String LOGIN_REASON = "登录后可以查询您本人已领取、且适用于该商品的优惠券。";
    private static final String UNKNOWN_SCOPE = "未知适用范围";
    private static final String NOT_FOUND_REASON = "未找到该商品";

    private static final String STATUS_AVAILABLE = "AVAILABLE";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String STATUS_NOT_STARTED = "NOT_STARTED";

    private static final List<String> FIELDS = List.of("productId");

    /** 适用范围标签，与 Python {@code _SCOPE_LABELS} 一致。 */
    private static final Map<Integer, String> SCOPE_LABELS = new HashMap<>();

    static {
        SCOPE_LABELS.put(0, "全场通用");
        SCOPE_LABELS.put(1, "指定分类");
        SCOPE_LABELS.put(2, "指定商品");
    }

    private final Clock clock;

    /** 使用系统时钟。 */
    public GetMemberCouponsForProductTool() {
        this(Clock.systemUTC());
    }

    /** 使用可注入时钟，便于固定时间断言。 */
    public GetMemberCouponsForProductTool(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询当前登录会员已领取且适用于指定商品的优惠券，并解释门槛、"
                + "适用范围和有效期；游客调用时返回需要登录。";
    }

    @Override
    public boolean requiresMember() {
        return true;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("productId", AgentTool.longIdProperty());
        return AgentTool.objectSchema(properties, List.of("productId"));
    }

    @Override
    public ToolResult invoke(JsonNode arguments, ToolContext context) {
        AgentTool.requireOnlyKnownFields(arguments, FIELDS);
        long productId = AgentTool.requiredPositiveLong(arguments, "productId");

        if (!context.isMember()) {
            return loginRequired(productId);
        }
        String authorization = context.authorization();

        List<CouponHistoryResponse> history;
        try {
            history = context.backend().listUnusedCouponHistory(authorization);
        } catch (PortalException exception) {
            if (exception.kind() == PortalException.Kind.MEMBER_UNAUTHORIZED) {
                return loginRequired(productId);
            }
            return couponFailure(exception);
        }

        List<ProductCouponResponse> applicable;
        try {
            applicable = context.backend().listProductCoupons(productId, authorization);
        } catch (PortalException exception) {
            if (exception.kind() == PortalException.Kind.MEMBER_UNAUTHORIZED) {
                return loginRequired(productId);
            }
            if (exception.kind() == PortalException.Kind.NOT_FOUND) {
                return productNotFound(productId);
            }
            return couponFailure(exception);
        }

        Set<Long> receivedCouponIds = new HashSet<>();
        for (CouponHistoryResponse item : history) {
            if (item.couponId() != null && (item.useStatus() == null || item.useStatus() == 0)) {
                receivedCouponIds.add(item.couponId());
            }
        }

        List<ProductCouponResponse> intersection = new ArrayList<>();
        for (ProductCouponResponse coupon : applicable) {
            if (receivedCouponIds.contains(coupon.id())) {
                intersection.add(coupon);
            }
        }

        List<Map<String, Object>> explanations = buildCouponExplanations(intersection, clock);
        int usableCount = 0;
        for (Map<String, Object> item : explanations) {
            if (Boolean.TRUE.equals(item.get("usableForProduct"))) {
                usableCount++;
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "coupons");
        payload.put("productId", productId);
        payload.put("receivedCouponCount", receivedCouponIds.size());
        payload.put("applicableCouponCount", applicable.size());
        payload.put("matchedCouponCount", intersection.size());
        payload.put("usableCouponCount", usableCount);
        payload.put("coupons", explanations);
        payload.put("note", NOTE);

        return ToolResult.ok(NAME, payload, List.of(
                "商品 " + productId + " 共有 " + intersection.size() + " 张已领取且适用的优惠券",
                "其中 " + usableCount + " 张当前可用"));
    }

    /**
     * 按有效期、使用门槛与适用范围生成结构化解释，可用券优先、金额降序，最多 5 张。
     *
     * <p>与 Python {@code build_coupon_explanations} 一致：字段始终存在，缺失值为 {@code null}。
     */
    public static List<Map<String, Object>> buildCouponExplanations(
            List<ProductCouponResponse> coupons, Clock clock) {
        java.time.Instant now = clock.instant();
        List<Map<String, Object>> items = new ArrayList<>(coupons.size());
        for (ProductCouponResponse coupon : coupons) {
            items.add(couponPayload(coupon, now));
        }
        items.sort(Comparator
                .comparingInt((Map<String, Object> item) ->
                        Boolean.TRUE.equals(item.get("usableForProduct")) ? 0 : 1)
                .thenComparing(item -> amountOf(item), Comparator.reverseOrder()));
        return new ArrayList<>(items.subList(0, Math.min(MAX_COUPONS_IN_RESULT, items.size())));
    }

    private static Map<String, Object> couponPayload(ProductCouponResponse coupon, java.time.Instant now) {
        String status = couponStatus(coupon, now);
        boolean usable = STATUS_AVAILABLE.equals(status);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("couponId", coupon.id());
        item.put("name", coupon.name());
        item.put("amount", AgentTool.formatMoney(coupon.amount()));
        item.put("minPoint", AgentTool.formatMoney(coupon.minPoint()));
        item.put("useType", coupon.useType());
        item.put("scope", scopeLabel(coupon.useType()));
        item.put("startTime", AgentTool.isoPython(coupon.startTime()));
        item.put("endTime", AgentTool.isoPython(coupon.endTime()));
        item.put("status", status);
        item.put("usableForProduct", usable);
        item.put("explanation", explain(coupon, status));
        return item;
    }

    private static String couponStatus(ProductCouponResponse coupon, java.time.Instant now) {
        if (coupon.startTime() != null && now.isBefore(coupon.startTime())) {
            return STATUS_NOT_STARTED;
        }
        if (coupon.endTime() != null && now.isAfter(coupon.endTime())) {
            return STATUS_EXPIRED;
        }
        return STATUS_AVAILABLE;
    }

    private static String scopeLabel(Integer useType) {
        if (useType == null) {
            return UNKNOWN_SCOPE;
        }
        return SCOPE_LABELS.getOrDefault(useType, UNKNOWN_SCOPE);
    }

    private static String explain(ProductCouponResponse coupon, String status) {
        String amount = AgentTool.formatMoney(coupon.amount());
        if (amount == null) {
            amount = "0.00";
        }
        String minPoint = AgentTool.formatMoney(coupon.minPoint());
        String threshold = minPoint != null && new BigDecimal(minPoint).compareTo(BigDecimal.ZERO) > 0
                ? "满 " + minPoint + " 元可用"
                : "无使用门槛";
        String validity = switch (status) {
            case STATUS_EXPIRED -> "已过有效期，不能使用。";
            case STATUS_NOT_STARTED -> "尚未到生效时间。";
            default -> "当前在有效期内，可用于该商品。";
        };
        String name = coupon.name() == null || coupon.name().isEmpty() ? "优惠券" : coupon.name();
        return name + "：" + scopeLabel(coupon.useType()) + "，" + threshold + "，面额 " + amount + " 元，" + validity;
    }

    private static BigDecimal amountOf(Map<String, Object> item) {
        Object amount = item.get("amount");
        return amount == null ? BigDecimal.ZERO : new BigDecimal(amount.toString());
    }

    private static ToolResult loginRequired(long productId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "coupons");
        payload.put("productId", productId);
        payload.put("requiresLogin", true);
        payload.put("reason", LOGIN_REASON);
        return ToolResult.failure(NAME, ToolResult.Status.LOGIN_REQUIRED, payload,
                List.of("查询个人优惠券需要先登录"), null, null);
    }

    private static ToolResult productNotFound(long productId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId);
        payload.put("reason", NOT_FOUND_REASON);
        return ToolResult.failure(NAME, ToolResult.Status.PRODUCT_NOT_FOUND, payload,
                List.of("商品 " + productId + " 不可用"), null, null);
    }

    private static ToolResult couponFailure(PortalException exception) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errorCode", exception.code());
        payload.put("message", exception.getMessage());
        return ToolResult.failure(NAME, ToolResult.Status.ERROR, payload,
                List.of("会员优惠券查询失败：" + exception.code()),
                exception.code(), exception.getMessage());
    }
}

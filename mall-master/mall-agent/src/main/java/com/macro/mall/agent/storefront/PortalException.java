package com.macro.mall.agent.storefront;

/**
 * mall-portal 只读访问错误，与 Python {@code storefront.backend} 的错误层次对齐。
 *
 * <p>只保存安全分类 {@link Kind}、HTTP 状态码、稳定错误码与固定安全文案。
 * <strong>永不链式持有</strong>上游响应正文、{@code Authorization}、原始异常或其消息、
 * host 或完整 URL/query，从而保证异常与日志都无法泄漏这些内容。
 *
 * <p>与 Python 的差异：Python 把「读/连接超时」与「连接失败」统一归
 * {@code StorefrontUnavailableError}（HTTP 502）。Java 侧沿用本模块
 * {@code ModelException} 的做法，把超时单独归为 {@link Kind#TIMEOUT}，但 HTTP 状态同为 502，
 * 对外语义不变。
 *
 * <p>分类与状态码映射：
 * <ul>
 *   <li>{@link Kind#MEMBER_UNAUTHORIZED} → 401 {@code MEMBER_UNAUTHORIZED}</li>
 *   <li>{@link Kind#NOT_FOUND} → 404 {@code PRODUCT_NOT_FOUND}</li>
 *   <li>{@link Kind#TIMEOUT} → 502 {@code STOREFRONT_TIMEOUT}</li>
 *   <li>{@link Kind#UNAVAILABLE} → 502 {@code STOREFRONT_UNAVAILABLE}</li>
 *   <li>{@link Kind#PROTOCOL} → 502 {@code STOREFRONT_PROTOCOL_ERROR}</li>
 * </ul>
 */
public final class PortalException extends RuntimeException {

    public enum Kind {
        MEMBER_UNAUTHORIZED,
        NOT_FOUND,
        TIMEOUT,
        UNAVAILABLE,
        PROTOCOL
    }

    private static final long serialVersionUID = 1L;

    private final Kind kind;
    private final int httpStatus;
    private final String code;

    private PortalException(Kind kind, int httpStatus, String code, String message) {
        // 刻意不传入 cause：上游正文、原始异常、凭据与完整 URL 都不得被异常持有
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.code = code;
    }

    /** 会员 Token 缺失、失效或无权限。 */
    public static PortalException memberUnauthorized() {
        return new PortalException(
                Kind.MEMBER_UNAUTHORIZED, 401, "MEMBER_UNAUTHORIZED", "会员登录状态已失效");
    }

    /** 商品不存在、已下架或详情不可用。 */
    public static PortalException notFound() {
        return new PortalException(Kind.NOT_FOUND, 404, "PRODUCT_NOT_FOUND", "未找到对应商品");
    }

    /** 读/连接超时；HTTP 状态与 UNAVAILABLE 一致，便于上层统一按 502 处理。 */
    public static PortalException timeout() {
        return new PortalException(
                Kind.TIMEOUT, 502, "STOREFRONT_TIMEOUT", "门户服务响应超时");
    }

    /** 门户暂时不可用或返回可重试错误。 */
    public static PortalException unavailable() {
        return new PortalException(
                Kind.UNAVAILABLE, 502, "STOREFRONT_UNAVAILABLE", "门户服务暂时不可用");
    }

    /**
     * 门户返回结构非法。
     *
     * <p>刻意只保留<strong>固定安全文案</strong>：不提供接受任意消息的公开工厂，避免 storefront 包之外的
     * 代码把上游正文、完整 URL 或 Token 注入异常（进而写入日志或响应）。
     */
    public static PortalException protocol() {
        return protocol("门户返回结构无法解析");
    }

    /**
     * 包内可传固定文案的重载。
     *
     * <p>包可见而非 public：{@link MallPortalClient} 需要区分搜索/详情/优惠券等固定结构文案，
     * 但外部代码无法通过它注入任意文本。
     */
    static PortalException protocol(String message) {
        return new PortalException(Kind.PROTOCOL, 502, "STOREFRONT_PROTOCOL_ERROR", message);
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }
}

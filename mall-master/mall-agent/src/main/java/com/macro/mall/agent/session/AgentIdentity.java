package com.macro.mall.agent.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

import com.macro.mall.agent.api.SessionIdValidator;

/**
 * 会话身份命名空间，行为对齐 Python {@code mall_shopping_agent.session.identity.Identity}。
 *
 * <p>游客与会员使用不同 Redis 命名空间：
 * {@code mall:agent:session:guest:{sessionId}} 与
 * {@code mall:agent:session:member:{memberId}:{sessionId}}。会员身份只能由服务端经
 * {@code /sso/info} 解析得到（见 {@link IdentityResolver}），实例没有携带客户端可控 memberId 的入口。
 *
 * <p>进入日志或限流键的取值一律先做不可逆摘要：{@link #logLabel()} 与
 * {@link #rateLimitIdentity()} 都不含原始 sessionId。{@link #toString()} 同样只输出摘要，
 * 避免身份对象被日志框架直接打印时泄漏 sessionId。
 */
public final class AgentIdentity {

    public enum Kind {
        GUEST,
        MEMBER
    }

    public static final String SESSION_KEY_PREFIX = "mall:agent:session";
    public static final String GUEST_SESSION_PREFIX = SESSION_KEY_PREFIX + ":guest:";
    public static final String MEMBER_SESSION_PREFIX = SESSION_KEY_PREFIX + ":member:";

    private static final int DEFAULT_FINGERPRINT_LENGTH = 32;
    private static final int LOG_LABEL_LENGTH = 12;

    private final Kind kind;
    private final String sessionId;
    private final Long memberId;

    private AgentIdentity(Kind kind, String sessionId, Long memberId) {
        this.kind = kind;
        this.sessionId = sessionId;
        this.memberId = memberId;
    }

    /** 游客身份；sessionId 按 {@link SessionIdValidator#requireCanonical(String)} 规范化。 */
    public static AgentIdentity guest(String sessionId) {
        return new AgentIdentity(Kind.GUEST, SessionIdValidator.requireCanonical(sessionId), null);
    }

    /** 会员身份；memberId 必须是正整数，且只能来自服务端门户解析结果。 */
    public static AgentIdentity member(long memberId, String sessionId) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId 必须是正整数");
        }
        return new AgentIdentity(Kind.MEMBER, SessionIdValidator.requireCanonical(sessionId), memberId);
    }

    public Kind kind() {
        return kind;
    }

    public boolean isMember() {
        return kind == Kind.MEMBER;
    }

    /** 规范化后的小写 sessionId。 */
    public String sessionId() {
        return sessionId;
    }

    /** 会员 ID；游客为 {@code null}。 */
    public Long memberId() {
        return memberId;
    }

    /** 当前身份的会话键。 */
    public String sessionKey() {
        return isMember()
                ? MEMBER_SESSION_PREFIX + memberId + ":" + sessionId
                : GUEST_SESSION_PREFIX + sessionId;
    }

    /** 同一 sessionId 的游客命名空间键（会员身份也指向游客键）。 */
    public String guestKey() {
        return GUEST_SESSION_PREFIX + sessionId;
    }

    /** 限流标识：会话键的不可逆摘要。 */
    public String rateLimitIdentity() {
        return fingerprint(sessionKey());
    }

    /** 日志标签：类型 + sessionId 摘要，不含 sessionId 或 Token 原值。 */
    public String logLabel() {
        return (isMember() ? "member:" : "guest:") + fingerprint(sessionId, LOG_LABEL_LENGTH);
    }

    /** UTF-8 SHA-256 小写 hex 摘要的前 32 位。 */
    public static String fingerprint(String value) {
        return fingerprint(value, DEFAULT_FINGERPRINT_LENGTH);
    }

    /**
     * @param length 摘要前缀长度，1..64
     * @throws IllegalArgumentException 取值为 null 或长度越界
     */
    public static String fingerprint(String value, int length) {
        if (value == null) {
            throw new IllegalArgumentException("value 不能为空");
        }
        if (length < 1 || length > 64) {
            throw new IllegalArgumentException("length 必须在 1 到 64 之间");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境缺少 SHA-256");
        }
        byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hashed.length * 2);
        for (byte current : hashed) {
            hex.append(Character.forDigit((current >> 4) & 0xF, 16));
            hex.append(Character.forDigit(current & 0xF, 16));
        }
        return hex.substring(0, length).toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AgentIdentity that)) {
            return false;
        }
        return kind == that.kind
                && sessionId.equals(that.sessionId)
                && Objects.equals(memberId, that.memberId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, sessionId, memberId);
    }

    /** 只输出日志标签，避免 sessionId 被直接打印。 */
    @Override
    public String toString() {
        return "AgentIdentity{" + logLabel() + '}';
    }
}

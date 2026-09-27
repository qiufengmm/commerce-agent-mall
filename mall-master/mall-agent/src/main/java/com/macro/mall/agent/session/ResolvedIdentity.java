package com.macro.mall.agent.session;

/**
 * 身份解析结果，与 Python {@code _resolve_identity} 的 {@code (Identity | None, bool)} 对应。
 *
 * <p>两个结果互斥：解析成功时 {@code identity} 非空且 {@code requiresLogin} 为 false；
 * Token 失效时 {@code identity} 为空且 {@code requiresLogin} 为 true。
 */
public record ResolvedIdentity(AgentIdentity identity, boolean requiresLogin) {

    public ResolvedIdentity {
        if (identity == null && !requiresLogin) {
            throw new IllegalArgumentException("identity 为空时必须 requiresLogin");
        }
        if (identity != null && requiresLogin) {
            throw new IllegalArgumentException("requiresLogin 时不能携带 identity");
        }
    }

    public static ResolvedIdentity authenticated(AgentIdentity identity) {
        return new ResolvedIdentity(identity, false);
    }

    public static ResolvedIdentity loginRequired() {
        return new ResolvedIdentity(null, true);
    }

    /** @throws IllegalStateException 当前结果要求重新登录时 */
    public AgentIdentity requireIdentity() {
        if (identity == null) {
            throw new IllegalStateException("当前身份需要重新登录");
        }
        return identity;
    }
}

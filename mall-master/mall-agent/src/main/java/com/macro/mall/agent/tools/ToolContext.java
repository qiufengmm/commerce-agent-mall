package com.macro.mall.agent.tools;

import java.util.Objects;

import com.macro.mall.agent.storefront.MallPortalClient;

/**
 * 一次工具调用可用的只读依赖与会员凭据。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.tools.registry.ToolContext}：
 * 只暴露只读门户客户端与可选的会员 {@code Authorization}；没有 host、URL、HTTP method 或 headers 入口。
 * 空 Authorization 按 Python falsey 语义视为游客。
 *
 * @param backend       只读门户客户端
 * @param authorization 会员 Token；游客为 {@code null} 或空串
 */
public record ToolContext(MallPortalClient backend, String authorization) {

    public ToolContext {
        Objects.requireNonNull(backend, "backend");
    }

    /** 游客上下文。 */
    public static ToolContext guest(MallPortalClient backend) {
        return new ToolContext(backend, null);
    }

    /** 会员上下文。 */
    public static ToolContext member(MallPortalClient backend, String authorization) {
        return new ToolContext(backend, authorization);
    }

    /** 是否携带可用会员凭据（与 Python {@code if not context.authorization} 对齐）。 */
    public boolean isMember() {
        return authorization != null && !authorization.isEmpty();
    }

    /**
     * 脱敏的文本表示。
     *
     * <p>record 默认 {@code toString()} 会输出完整 Authorization 与 backend 的 {@code toString()}
     * （后者可能是含 host 的客户端描述），一旦被日志或异常带上就会泄漏凭据。
     * 这里只输出「是否有会员凭据」，既不包含 Token 内容，也不包含 backend 的文本表示。
     */
    @Override
    public String toString() {
        return "ToolContext[backend=<portal>, authorization=" + (isMember() ? "<present>" : "<absent>") + "]";
    }
}

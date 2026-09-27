package com.macro.mall.agent.agent;

import java.util.Objects;

import com.macro.mall.agent.session.AgentIdentity;
import com.macro.mall.agent.session.SessionSnapshot;

/**
 * 一次智能体问答的输入，行为对齐 Python {@code mall_shopping_agent.agent.types.AgentTurnRequest}。
 *
 * <p>刻意把身份、会话快照与凭据分开表达，供 API 层在完成身份解析后直接注入：
 * 编排层只读取 {@code message}、{@code session} 与本轮的 {@code authorization}，
 * 不解析请求体，也不与 Redis 或 HTTP 耦合。
 *
 * @param message       本轮用户消息（已由 API 层按契约校验与裁剪）
 * @param identity      服务端解析出的会话身份（游客或会员）
 * @param session       当前身份的会话快照（只含 user/assistant 消息与卡片）
 * @param authorization 会员 Token；游客为 {@code null}
 */
public record AgentTurnRequest(
        String message,
        AgentIdentity identity,
        SessionSnapshot session,
        String authorization) {

    public AgentTurnRequest {
        Objects.requireNonNull(message, "message 不能为空");
        Objects.requireNonNull(identity, "identity 不能为空");
        Objects.requireNonNull(session, "session 不能为空");
    }

    /** 游客请求：不携带任何会员凭据。 */
    public static AgentTurnRequest guest(String message, String sessionId, SessionSnapshot session) {
        return new AgentTurnRequest(message, AgentIdentity.guest(sessionId), session, null);
    }

    /** 会员请求：身份由服务端解析结果构造，凭据原样透传给会员只读工具。 */
    public static AgentTurnRequest member(
            String message, String sessionId, long memberId, SessionSnapshot session, String authorization) {
        return new AgentTurnRequest(
                message, AgentIdentity.member(memberId, sessionId), session, authorization);
    }
}

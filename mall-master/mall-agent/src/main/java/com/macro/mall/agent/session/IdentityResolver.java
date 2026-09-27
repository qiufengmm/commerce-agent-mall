package com.macro.mall.agent.session;

import java.util.Objects;

import com.macro.mall.agent.api.PythonText;
import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.storefront.PortalException;
import com.macro.mall.agent.storefront.dto.MemberInfoResponse;

/**
 * 身份解析，行为对齐 Python {@code mall_shopping_agent.api.chat._resolve_identity}。
 *
 * <p>规则：
 * <ul>
 *   <li>没有 Authorization（{@code null} 或按 Python {@code str.strip()} 语义去空白后为空）时返回游客身份，
 *       <strong>不访问门户，也不读写任何会话</strong>。</li>
 *   <li>Authorization 非空时<strong>只能</strong>通过 {@link MallPortalClient#resolveMember(String)}
 *       （{@code GET /sso/info}）取得会员身份；客户端无法提交 memberId。</li>
 *   <li>只有 {@link PortalException.Kind#MEMBER_UNAUTHORIZED} 转换为 {@code requiresLogin}；
 *       其余门户错误原样安全传播，绝不降级为游客。身份解析阶段的这类非 401 错误由上层按 Python
 *       {@code _resolve_identity} 行为收敛为固定内部 500，而不是编排/商品门户的 502。</li>
 *   <li>门户身份接口返回空对象（{@code null}）时归一为安全协议异常（无 cause、固定安全文案），
 *       绝不触发 NPE，也不泄漏门户细节。</li>
 *   <li>仅在本次请求同时持有有效认证时，才尝试把游客会话迁移到会员命名空间。</li>
 * </ul>
 *
 * <p>迁移语义与 Python {@code _migrate_guest_session} 一致：先读会员目标快照，
 * 非空（有消息或有卡片）时直接返回，<strong>不覆盖也不删除 guest 键</strong>；
 * 目标为空时才调用 {@link SessionRepository#copy(String, String)}。防覆盖判断在本类，
 * 不在仓储里，避免仓储方法被误用时静默丢数据。
 */
public final class IdentityResolver {

    private final MallPortalClient portalClient;
    private final SessionRepository sessionRepository;

    public IdentityResolver(MallPortalClient portalClient, SessionRepository sessionRepository) {
        this.portalClient = Objects.requireNonNull(portalClient, "portalClient 不能为空");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository 不能为空");
    }

    /**
     * @param sessionId     客户端提交的 sessionId，按 UUID v4 规范化
     * @param authorization 移动端保存的完整 Authorization 头原值，可为 {@code null}
     * @throws PortalException 非 401 的门户失败原样向上传播
     */
    public ResolvedIdentity resolve(String sessionId, String authorization) {
        String token = normalizeAuthorization(authorization);
        if (token == null) {
            return ResolvedIdentity.authenticated(AgentIdentity.guest(sessionId));
        }

        MemberInfoResponse member;
        try {
            member = portalClient.resolveMember(token);
        } catch (PortalException ex) {
            if (ex.kind() == PortalException.Kind.MEMBER_UNAUTHORIZED) {
                return ResolvedIdentity.loginRequired();
            }
            throw ex;
        }
        if (member == null) {
            // 门户身份接口返回空对象：与 Python `member.member_id` 触发 AttributeError 一致，
            // 防御性归一为安全协议异常（固定安全文案、无 cause），由上层收敛为固定内部 500，不 NPE、不泄漏
            throw PortalException.protocol();
        }

        AgentIdentity identity = AgentIdentity.member(member.memberId(), sessionId);
        migrateGuestSession(sessionId, identity);
        return ResolvedIdentity.authenticated(identity);
    }

    /**
     * 与 Python {@code _authorization} + {@code if not authorization} 的组合语义一致：去首尾空白后为空
     * 视为无凭据（返回 {@code null}）。
     *
     * <p>公开为静态方法，供 API 服务层在<strong>同一处</strong>得到规范值后，既用于身份解析、又用于
     * 会员工具的 {@code AgentTurnRequest} 透传，避免「身份解析用去空白值、后续工具却透传原始带空白值」
     * 这种不一致（Python {@code chat.py} 的 {@code _authorization} 只解析一次并复用同一取值）。
     */
    public static String normalizeAuthorization(String authorization) {
        if (authorization == null) {
            return null;
        }
        String stripped = PythonText.strip(authorization);
        return stripped.isEmpty() ? null : stripped;
    }

    private void migrateGuestSession(String sessionId, AgentIdentity identity) {
        String guestKey = AgentIdentity.guest(sessionId).guestKey();
        SessionSnapshot memberSnapshot = sessionRepository.load(identity.sessionKey());
        if (!memberSnapshot.isEmpty()) {
            return;
        }
        sessionRepository.copy(guestKey, identity.sessionKey());
    }
}

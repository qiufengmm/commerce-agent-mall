package com.macro.mall.agent.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.macro.mall.agent.agent.AgentLimits;
import com.macro.mall.agent.agent.AgentOrchestrator;
import com.macro.mall.agent.agent.AgentTurnRequest;
import com.macro.mall.agent.agent.AgentTurnResult;
import com.macro.mall.agent.agent.ToolRoundLimitException;
import com.macro.mall.agent.config.AgentProperties;
import com.macro.mall.agent.deadline.RequestDeadline;
import com.macro.mall.agent.deadline.RequestDeadlineContext;
import com.macro.mall.agent.deadline.RequestDeadlineExceededException;
import com.macro.mall.agent.model.ModelException;
import com.macro.mall.agent.safety.RefusalPolicy;
import com.macro.mall.agent.safety.ShoppingIntentClassifier;
import com.macro.mall.agent.session.AgentIdentity;
import com.macro.mall.agent.session.IdentityResolver;
import com.macro.mall.agent.session.InFlightGuard;
import com.macro.mall.agent.session.RateLimiter;
import com.macro.mall.agent.session.ResolvedIdentity;
import com.macro.mall.agent.session.SessionRepository;
import com.macro.mall.agent.session.SessionSnapshot;
import com.macro.mall.agent.storefront.PortalException;

/**
 * 智能体业务实现，行为对齐 Python {@code mall_shopping_agent.api.chat}。
 *
 * <p>{@link #chat} 的固定顺序（每一步都早于后续步骤，尤其早于任何模型调用）：
 * <ol>
 *   <li>模型未配置（{@link AgentProperties#isModelAvailable()} 为 false）→ 固定 503，
 *       不解析身份、不读写会话、不限流、不调用模型；</li>
 *   <li>并发占用（{@link InFlightGuard}）：<strong>在身份解析之前</strong>按规范 sessionId 占用整个
 *       chat 生命周期 → 同一 sessionId 的第二个并发请求固定 409。由此挡住两个写覆盖场景：并发提交
 *       不同问题时的 load→save 后写覆盖前写，以及失效 Token 记问与 guest→member 身份迁移的并发写。
 *       请求结束或异常一律释放；</li>
 *   <li>预认证限流（{@link RateLimiter#checkIp(String)}）：按真实客户端 IP 消耗一次 IP 桶 → 固定 429
 *       且携带 {@code Retry-After}。该步骤早于身份解析，因此失效 Token 超 IP 配额时 429 且
 *       <strong>不触达门户 {@code /sso/info}、不读写会话</strong>，「伪造 Token + 每次新 UUID」无法
 *       绕过 IP 维度；</li>
 *   <li>身份解析：只以传入的 {@code Authorization} 头为凭据，客户端无法指定 memberId；
 *       Token 失效转为 {@code requiresLogin} 结果，并按 Python {@code _remember_login_required_turn}
 *       把本轮提问写入游客会话（保留旧消息与卡片），登录回跳后可迁移恢复；此路径身份未确定，
 *       因此只占 IP 配额、<strong>不</strong>消耗会话桶。
 *       <strong>身份解析阶段的非 401 门户失败不在此捕获</strong>，与 Python {@code _resolve_identity}
 *       一致原样安全传播，由 {@code ApiExceptionHandler} 收敛为固定 500 信封；</li>
 *   <li>会话限流（{@link RateLimiter#checkSession(String)}）：身份确定后按 member/guest 会话键消耗一次
 *       会话桶 → 固定 429 且携带 {@code Retry-After}。一次正常请求因此恰好各消耗一次 IP 与会话预算，
 *       不重复计 IP；</li>
 *   <li>交易写操作拒绝（{@link RefusalPolicy}）→ 固定拒绝回答，对<strong>所有身份</strong>都是零模型、
 *       零商品/会员券门户工具读取；有效会员仍已在步骤 4 经历一次 {@code /sso/info} 身份验证，
 *       游客则门户零调用；</li>
 *   <li>游客询问本人优惠券（{@link ShoppingIntentClassifier}）→ 确定性要求登录，零模型、
 *       零会员/门户调用，并把本轮提问记入游客会话；</li>
 *   <li>读取会话 → 在请求级 deadline 内同步编排 → 写回会话。</li>
 * </ol>
 *
 * <p>步骤 6、7 是纯确定性的服务层短路：规则本身来自 {@link RefusalPolicy}（写拒绝优先）与
 * {@link ShoppingIntentClassifier}（个人券身份门槛），服务层只按固定优先级转发，不复制规则。
 * 写拒绝先于个人券门槛判定，避免「帮我领取我的优惠券」这类同时命中两者的问法被误判为登录要求。
 *
 * <p><strong>与 Python 的边界差异（预认证 IP 门槛引入）</strong>：Python 的
 * {@code ChatRateLimiter.check} 先判会话、会话超限时不再消耗 IP 预算。返工后正常请求的 IP 预算在
 * 身份解析<strong>之前</strong>就被消耗，因此「会话超限的请求不消耗 IP」这一性质在 chat 主流程
 * <strong>不可同时维持</strong>：已经通过 IP 门槛的请求即使随后被会话维度拒绝，其 IP 计数也已加一。
 * {@link RateLimiter#check(String, String)} 保留旧语义仅供兼容消费者，chat 主流程使用分阶段入口。
 *
 * <p>请求级 deadline（{@code MALL_AGENT_REQUEST_TIMEOUT_SECONDS}）对齐 Python
 * {@code asyncio.timeout(request_timeout_seconds)}，<strong>只包住编排阶段</strong>：
 * {@link RequestDeadline} 以单调时钟记录总预算，经 {@link RequestDeadlineContext} 在线程内可见；
 * 生产 RestClient 的每次 HTTP 请求都会读取剩余预算，把该次请求的完整超时收紧为
 * {@code min(既有模型/门户超时配置, 剩余预算)}，剩余预算耗尽则在网络调用前失败。编排返回后若已越过
 * deadline，绝不写回会话。整个过程同步、不使用线程池或后台任务，因此不会出现「已返回响应但请求仍在
 * 后台继续」。
 *
 * <p><strong>两类门户读取的错误映射刻意不同</strong>（都按 Python 行为，绝不回传门户细节、URL 或 Token）：
 * <ul>
 *   <li><strong>身份解析 {@code /sso/info}</strong>（{@link IdentityResolver} → {@code MallPortalClient#resolveMember}）：
 *       只有 {@code MEMBER_UNAUTHORIZED} 转为 {@code requiresLogin}；其余非 401 分类（timeout/unavailable/
 *       notFound/protocol）<strong>不在此层捕获</strong>，原样传播后由 {@code ApiExceptionHandler} 收敛为固定
 *       内部 500 信封——这是 Python {@code _resolve_identity} 的 generic 错误语义，不是 502。</li>
 *   <li><strong>编排期的商品/会员券读取</strong>（{@code /product/**}、{@code /member/coupon/**}）：
 *       <strong>全部</strong>分类（含 {@code MEMBER_UNAUTHORIZED}）→ 固定 502
 *       {@code STOREFRONT_FAILED_MESSAGE}，对齐 Python {@code api/chat.py} 的 {@code except StorefrontError}
 *       分支——{@code MemberUnauthorizedError} 是 {@code StorefrontError} 子类，编排期一律 502，
 *       <strong>不</strong>转 {@code requiresLogin}。</li>
 * </ul>
 *
 * <p>错误映射与 Python {@code api/chat.py} 的 try/except 结构一致，编排边界的映射只使用固定
 * 分类与固定文案，不读取异常内部文本：
 * <ul>
 *   <li>请求级 deadline 超时 → 固定 502 {@code AGENT_TIMEOUT}
 *       （{@link AgentApiException#REQUEST_TIMEOUT_MESSAGE}）；</li>
 *   <li>{@link ModelException}：沿用固定分类状态码；不可用/协议错误 → 503，超时/上游错误 → 502；</li>
 *   <li>{@link PortalException}（仅编排期的商品/会员券读取，见上）：<strong>全部</strong>分类 → 502 固定文案
 *       （含 {@code MEMBER_UNAUTHORIZED}，对齐 Python {@code StorefrontError} 的 {@code MemberUnauthorizedError}）；</li>
 *   <li>{@link ToolRoundLimitException}：固定 422。</li>
 * </ul>
 * 只有「deadline 才是绑定约束」的失败（deadline 已过期，或该次调用的完整超时被剩余预算截断后仍超时）
 * 才走 {@code AGENT_TIMEOUT}；per-client 自身超时在 deadline 尚未触及时保持上述模型/门户映射。
 * {@link com.macro.mall.agent.session.RateLimitExceededException} 不在此处捕获，原样传播以便
 * 异常处理层附加 {@code Retry-After}。因此 Key、Token、上游正文与堆栈都不会进入响应。
 *
 * <p>会话读写接口（GET/DELETE）行为对齐 Python {@code api/chat.py}：GET 只读当前已验证身份的
 * 命名空间（游客读 guest、会员经门户解析后读 member），失效 Token 返回 {@code requiresLogin=true}
 * 的空会话且绝不降级读取 guest；DELETE 只删除当前已验证身份的键，成功授权后 {@code deleted} 恒为
 * true（重复删除仍为 true，仓储删除返回值不进入对外契约），失效 Token 返回 {@code deleted=false}。
 * 两者都<strong>不调用编排器/模型</strong>，门户只用于 {@code /sso/info} 身份解析；身份解析阶段的
 * 非 401 门户错误按 Python 行为收敛为固定 500 信封，不回传门户细节、URL 或 Token。
 *
 * <p>GET/DELETE 与 {@link #chat} 共用同一可信客户端 IP 解析结果，在取得 in-flight guard 之后、
 * 身份解析<strong>之前</strong>按该 IP 各消耗一次<strong>现有</strong> IP 桶（不新增会话桶）：
 * 超限固定 429 且零门户身份查询、零会话读写；正常路径恰好消耗一次 IP 桶、不消耗会话桶；
 * guard 在 {@code finally} 中释放。
 *
 * <p>{@code Authorization} 与 Python {@code _authorization} 一致只解析一次：去首尾空白后同时用于身份
 * 解析与会员工具的 {@link AgentTurnRequest}，避免两处取值不一致。
 */
public final class DefaultAgentChatService implements AgentChatService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAgentChatService.class);

    /**
     * Token 失效时的固定登录说明，逐字符对齐 Python {@code api/chat.py LOGIN_REQUIRED_ANSWER}。
     *
     * <p>与 {@link AgentOrchestrator#LOGIN_REQUIRED_ANSWER} 不同：后者是「游客询问个人券」的服务端
     * 身份门槛文案，本常量是「已登录但 Token 失效」的文案。
     */
    public static final String LOGIN_REQUIRED_ANSWER =
            "登录状态已失效，请重新登录后继续当前对话，登录前的提问不会被清空。";

    /** Token 失效时返回的建议问题条数，对齐 Python {@code DEFAULT_SUGGESTED_QUESTIONS[:2]}。 */
    private static final int LOGIN_REQUIRED_SUGGESTED_QUESTIONS = 2;

    /** 门户读取失败的固定 HTTP 状态。 */
    private static final int UPSTREAM_GATEWAY_STATUS = 502;

    private final AgentProperties properties;
    private final IdentityResolver identityResolver;
    private final RateLimiter rateLimiter;
    private final InFlightGuard inFlightGuard;
    private final SessionRepository sessionRepository;
    private final AgentOrchestrator orchestrator;

    public DefaultAgentChatService(
            AgentProperties properties,
            IdentityResolver identityResolver,
            RateLimiter rateLimiter,
            InFlightGuard inFlightGuard,
            SessionRepository sessionRepository,
            AgentOrchestrator orchestrator) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver 不能为空");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter 不能为空");
        this.inFlightGuard = Objects.requireNonNull(inFlightGuard, "inFlightGuard 不能为空");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository 不能为空");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator 不能为空");
    }

    @Override
    public ChatData chat(ChatRequest request, String authorization, String clientIp) {
        Objects.requireNonNull(request, "request 不能为空");

        if (!properties.isModelAvailable()) {
            throw AgentApiException.modelUnavailable();
        }

        String sessionId = request.sessionId();
        // 占用键在身份解析之前获取：同一 sessionId 的第二个并发 chat 固定 409，
        // 挡住失效 Token 记问与 guest→member 身份迁移阶段的并发 load→save 覆盖
        String inFlightKey = InFlightGuard.keyForSession(sessionId);
        if (!inFlightGuard.acquire(inFlightKey)) {
            throw AgentApiException.duplicateRequest();
        }
        try {
            // 预认证 IP 门槛早于身份解析：失效 Token 超 IP 配额时 429 且不触达门户与会话
            rateLimiter.checkIp(clientIp);

            // 与 Python _authorization 一致：去首尾空白后只解析一次，同时用于身份解析与会员工具透传
            String token = IdentityResolver.normalizeAuthorization(authorization);

            // 身份解析阶段（Python _resolve_identity）：非 401 门户失败刻意不在此捕获，
            // 原样安全传播到 ApiExceptionHandler 收敛为固定 500 信封；失效 Token 转为 requiresLogin。
            ResolvedIdentity resolved = identityResolver.resolve(sessionId, token);
            if (resolved.requiresLogin()) {
                // 登录失效：按 Python _remember_login_required_turn 记入游客会话（保留旧对话与卡片），
                // 登录回跳后由既有迁移策略恢复；记录失败不影响 200/requiresLogin。
                // 此路径身份未确定，只占 IP 配额，不消耗会话桶。
                return loginRequiredAfterRemembering(sessionId, request.message());
            }
            AgentIdentity identity = resolved.requireIdentity();

            // 身份确定后按 member/guest 会话键消耗一次会话桶（一次正常请求 IP 与会话各一次）
            rateLimiter.checkSession(identity.sessionKey());

            // 交易写拒绝优先于个人券身份门槛与模型调用：规则取自 RefusalPolicy，服务层不复制规则
            Optional<RefusalPolicy.Refusal> refusal = RefusalPolicy.detectWriteRefusal(request.message());
            if (refusal.isPresent()) {
                return refuseWrite(request, identity, token, refusal.get());
            }

            // 游客询问本人优惠券：确定性要求登录，零模型调用，也不触达会员券或商品门户接口
            if (!identity.isMember()
                    && ShoppingIntentClassifier.classifyPersonalCoupon(request.message())) {
                rememberLoginRequiredTurn(
                        identity.guestKey(),
                        request.message(),
                        AgentOrchestrator.LOGIN_REQUIRED_ANSWER);
                return personalCouponLoginRequired(sessionId);
            }

            SessionSnapshot session = sessionRepository.load(identity.sessionKey());
            // 游客绝不携带 Authorization，避免把凭据带入商品只读工具
            String memberAuthorization = identity.isMember() ? token : null;
            AgentTurnRequest turn = new AgentTurnRequest(
                    request.message(), identity, session, memberAuthorization);
            return runOrchestratedTurn(sessionId, turn);
        } finally {
            inFlightGuard.release(inFlightKey);
        }
    }

    /**
     * 在请求级 deadline 内<strong>同步</strong>执行编排，成功后写回会话。
     *
     * <p>deadline 只包住 {@code orchestrator.run}：编排期间每次真实 HTTP 请求都会读取剩余预算，
     * 把该次请求的完整超时收紧为 {@code min(既有模型/门户超时配置, 剩余预算)}；剩余预算耗尽则在
     * 网络调用前失败。不使用线程池、{@code Future}/{@code CompletableFuture}/Servlet 异步等
     * 「提前返回但后台继续」的方案。编排返回后若已越过 deadline，绝不写回会话。
     *
     * <p>错误映射：deadline 才是绑定约束的失败统一映射固定 502 {@code AGENT_TIMEOUT}；per-client
     * （模型/门户）自身超时在 deadline 尚未触及时保持既有映射。只有固定分类与固定文案进入响应，
     * 不保留任何上游正文或 cause。
     */
    private ChatData runOrchestratedTurn(String sessionId, AgentTurnRequest turn) {
        RequestDeadline deadline = RequestDeadline.start(properties.getRequestTimeoutSeconds());
        AgentTurnResult result;
        try {
            result = RequestDeadlineContext.callWith(deadline, () -> orchestrator.run(turn));
        } catch (ToolRoundLimitException ex) {
            throw new AgentApiException(ex.httpStatus(), ex.getMessage());
        } catch (RequestDeadlineExceededException ex) {
            throw AgentApiException.requestTimeout();
        } catch (ModelException ex) {
            if (deadlineExceeded(deadline, ex.kind() == ModelException.Kind.TIMEOUT)) {
                throw AgentApiException.requestTimeout();
            }
            throw modelFailure(ex);
        } catch (PortalException ex) {
            if (deadlineExceeded(deadline, ex.kind() == PortalException.Kind.TIMEOUT)) {
                throw AgentApiException.requestTimeout();
            }
            // 编排期的商品/会员券读取全部按 Python api/chat.py 的 StorefrontError 语义处理：含会员工具
            // 失效 Token（MEMBER_UNAUTHORIZED）在内，一律收敛为固定 502 storefront 文案，既不转
            // requiresLogin，也不回传门户 Token 失效细节。身份解析阶段 /sso/info 的 401 才走
            // requiresLogin 边界（见 IdentityResolver），与本分支无关。
            throw new AgentApiException(UPSTREAM_GATEWAY_STATUS, AgentApiException.STOREFRONT_FAILED_MESSAGE);
        }
        if (deadline.isExpired()) {
            // 编排虽已返回，但总 deadline 已越过：按超时响应，绝不落库
            throw AgentApiException.requestTimeout();
        }
        sessionRepository.save(turn.identity().sessionKey(), orchestrator.buildSessionUpdate(turn, result));
        return toChatData(sessionId, result);
    }

    /**
     * 判断一次失败是否应归因于请求级 deadline。
     *
     * <p>deadline 已过期时，任何失败都发生在预算之外，统一按请求级超时处理；否则只有当失败本身是
     * 传输/读超时、且<strong>最近一次</strong> HTTP 请求的完整超时取自剩余预算（{@code isLastRequestClamped()}）
     * 时，才说明该超时来自 deadline（而不是 per-client 自身配置）。标记是请求级的，因此更早一次被
     * deadline 截断的成功请求不会污染后续未被截断的 per-client 超时判定。
     */
    private static boolean deadlineExceeded(RequestDeadline deadline, boolean transportTimeout) {
        if (deadline.isExpired()) {
            return true;
        }
        return transportTimeout && deadline.isLastRequestClamped();
    }

    /**
     * 会话恢复，对齐 Python {@code GET /agent/session/{session_id}}。
     *
     * <p>游客读 guest 命名空间；有效会员经门户 {@code /sso/info} 解析后读 member 命名空间（并触发既有迁移
     * 策略）；失效 Token 返回 {@code requiresLogin=true} 的空会话，绝不降级游客或泄漏 guest 内容；
     * 全程不调用编排器/模型，门户只用于 {@code /sso/info} 身份解析（不读取商品/会员券路由）。
     * 身份解析阶段的非 401 门户错误按 Python generic 语义收敛为固定内部 500 信封，不回传门户细节。
     *
     * <p>与 {@link #chat} 使用<strong>同一个</strong> {@link InFlightGuard} 占用键（
     * {@link InFlightGuard#keyForSession(String)} 的规范 sessionId 派生键）：在身份解析<strong>之前</strong>
     * 占用，请求结束或异常一律释放。由此 GET 的身份解析/guest→member 迁移不会与 chat 并发写冲突，
     * 同一 sessionId 上已有 chat/GET/DELETE 在飞行时，本方法固定 409 且不解析身份、不读写会话。
     *
     * <p>取得占用键后、身份解析之前，按 {@code clientIp} 消耗一次现有 IP 桶（与 chat 相同的可信
     * 客户端 IP 解析），超限固定 429 且零门户身份查询、零会话读取；不消耗会话桶。
     */
    @Override
    public SessionData getSession(String sessionId, String authorization, String clientIp) {
        String inFlightKey = InFlightGuard.keyForSession(sessionId);
        if (!inFlightGuard.acquire(inFlightKey)) {
            throw AgentApiException.duplicateRequest();
        }
        try {
            // 与 chat 一致：在身份解析之前按真实客户端 IP 消耗一次现有 IP 桶，超限固定 429 且零门户/零会话
            rateLimiter.checkIp(clientIp);

            String token = IdentityResolver.normalizeAuthorization(authorization);
            ResolvedIdentity resolved = identityResolver.resolve(sessionId, token);
            if (resolved.requiresLogin()) {
                return new SessionData(sessionId, List.of(), List.of(), true);
            }
            AgentIdentity identity = resolved.requireIdentity();
            SessionSnapshot snapshot = sessionRepository.load(identity.sessionKey());
            return new SessionData(sessionId, snapshot.messages(), snapshot.products(), false);
        } finally {
            inFlightGuard.release(inFlightKey);
        }
    }

    /**
     * 会话清空，对齐 Python {@code DELETE /agent/session/{session_id}}。
     *
     * <p>只删除当前已验证身份的命名空间：游客删 guest 键；有效会员先按既有身份迁移策略解析再删 member 键。
     * 失效 Token 返回 {@code deleted=false}/{@code requiresLogin=true}，<strong>绝不删除 guest 键</strong>。
     * 与 Python 一致，成功授权后 {@code deleted} 恒为 true（即使键不存在或重复删除），
     * 仓储 {@code delete} 的布尔返回值不进入对外契约。全程不调用编排器/模型，门户只用于 {@code /sso/info}
     * 身份解析（不读取商品/会员券路由）；身份解析阶段的非 401 门户错误同样收敛为固定内部 500 信封。
     *
     * <p>与 {@link #chat}、{@link #getSession(String, String, String)} 使用<strong>同一个</strong>
     * {@link InFlightGuard} 占用键，在身份解析<strong>之前</strong>占用、请求结束或异常一律释放：
     * DELETE 不可能与 chat 的 load→save 交错，也不会在删除后又被并发 chat 写回「复活」；
     * 同一 sessionId 上已有在飞行请求时固定 409，且不解析身份、不读写会话。
     *
     * <p>取得占用键后、身份解析之前，按 {@code clientIp} 消耗一次现有 IP 桶（与 chat 相同的可信
     * 客户端 IP 解析），超限固定 429 且零门户身份查询、零会话删除；不消耗会话桶。
     */
    @Override
    public DeleteSessionData deleteSession(String sessionId, String authorization, String clientIp) {
        String inFlightKey = InFlightGuard.keyForSession(sessionId);
        if (!inFlightGuard.acquire(inFlightKey)) {
            throw AgentApiException.duplicateRequest();
        }
        try {
            // 与 chat 一致：在身份解析之前按真实客户端 IP 消耗一次现有 IP 桶，超限固定 429 且零门户/零会话
            rateLimiter.checkIp(clientIp);

            String token = IdentityResolver.normalizeAuthorization(authorization);
            ResolvedIdentity resolved = identityResolver.resolve(sessionId, token);
            if (resolved.requiresLogin()) {
                return new DeleteSessionData(sessionId, false, true);
            }
            AgentIdentity identity = resolved.requireIdentity();
            sessionRepository.delete(identity.sessionKey());
            return new DeleteSessionData(sessionId, true, false);
        } finally {
            inFlightGuard.release(inFlightKey);
        }
    }

    /**
     * 登录失效时按 Python {@code _remember_login_required_turn} 记录本轮提问并返回固定登录失效答复。
     *
     * <p>写入的是<strong>该 sessionId 的游客命名空间</strong>（与失效 Token 无关的关键隔离：绝不写会员键，
     * 也不把 Token 写入会话）；随后由既有迁移策略在有效会员到达时安全迁移。
     */
    private ChatData loginRequiredAfterRemembering(String sessionId, String message) {
        rememberLoginRequiredTurn(
                AgentIdentity.guest(sessionId).guestKey(), message, LOGIN_REQUIRED_ANSWER);
        return loginRequired(sessionId);
    }

    /**
     * 交易写操作拒绝：只产生固定拒绝回答，不调用模型、不触发任何门户读取。
     *
     * <p>结果形状与 {@link AgentOrchestrator} 的拒绝分支一致（空卡片、{@code requiresLogin=false}、
     * 默认建议问题、{@code REFUSAL} 结束原因）；会话落库沿用编排器既有的
     * {@link AgentOrchestrator#buildSessionUpdate(AgentTurnRequest, AgentTurnResult)}，不复制会话逻辑。
     */
    private ChatData refuseWrite(
            ChatRequest request, AgentIdentity identity, String token, RefusalPolicy.Refusal refusal) {
        SessionSnapshot session = sessionRepository.load(identity.sessionKey());
        String memberAuthorization = identity.isMember() ? token : null;
        AgentTurnRequest turn = new AgentTurnRequest(
                request.message(), identity, session, memberAuthorization);
        AgentTurnResult result = refusalResult(refusal);
        sessionRepository.save(identity.sessionKey(), orchestrator.buildSessionUpdate(turn, result));
        return toChatData(request.sessionId(), result);
    }

    /** 与 {@link AgentOrchestrator} 拒绝分支一致的固定结果，建议问题取自同一受控模板。 */
    private static AgentTurnResult refusalResult(RefusalPolicy.Refusal refusal) {
        int bound = Math.min(
                Math.max(1, AgentLimits.defaults().maxSuggestedQuestions()),
                AgentOrchestrator.DEFAULT_SUGGESTED_QUESTIONS.size());
        return new AgentTurnResult(
                refusal.answer(),
                List.of(),
                false,
                List.copyOf(AgentOrchestrator.DEFAULT_SUGGESTED_QUESTIONS.subList(0, bound)),
                List.of(),
                0,
                AgentTurnResult.StoppedReason.REFUSAL);
    }

    /**
     * 游客个人券身份门槛的固定响应：个人券登录文案 + 登录类建议问题，绝不含商品卡。
     *
     * <p>文案与建议问题复用 {@link AgentOrchestrator} 的公开模板（原样透传，保证与经由工具链
     * 触发 {@code LOGIN_REQUIRED} 时的响应逐字段一致），本方法不调用编排器实例，也不触达模型。
     */
    private static ChatData personalCouponLoginRequired(String sessionId) {
        String answer = AgentOrchestrator.LOGIN_REQUIRED_ANSWER;
        List<String> questions = AgentOrchestrator.buildSuggestedQuestions(
                List.of(), true, answer, AgentLimits.defaults().maxSuggestedQuestions());
        return new ChatData(sessionId, newMessageId(), answer, List.of(), true, questions);
    }

    /**
     * 登录门槛命中时把本轮提问写入游客命名空间，供登录回跳后恢复。
     *
     * <p>语义对齐 Python {@code api/chat.py _remember_login_required_turn}：用
     * {@link SessionSnapshot#withAppended(List, int)} 追加「user 提问 + assistant 登录提示」，
     * <strong>保留已有对话与商品卡</strong>，并按键配置的会话消息上限裁剪。
     *
     * <p>读写失败不影响 200/requiresLogin：只记录异常类型，绝不记录消息文本、Token 或异常正文。
     * 直接使用 {@link AgentIdentity#sessionKey()} 传入的游客键，不跨身份读取。
     */
    private void rememberLoginRequiredTurn(String guestKey, String message, String assistantAnswer) {
        try {
            SessionSnapshot snapshot = sessionRepository.load(guestKey);
            List<SessionMessage> appended = List.of(
                    new SessionMessage("user", truncate(message, SessionSnapshot.MAX_MESSAGE_CHARS)),
                    new SessionMessage("assistant", assistantAnswer));
            sessionRepository.save(
                    guestKey, snapshot.withAppended(appended, properties.getSessionMaxMessages()));
        } catch (RuntimeException ex) {
            // 只记录异常类型，避免把消息、Token 或上游正文写进日志
            logger.warn("记录待登录提问失败：{}", ex.getClass().getSimpleName());
        }
    }

    private static AgentApiException modelFailure(ModelException ex) {
        String message = switch (ex.kind()) {
            case UNAVAILABLE, PROTOCOL -> AgentApiException.MODEL_UNAVAILABLE_MESSAGE;
            case TIMEOUT, UPSTREAM -> AgentApiException.MODEL_FAILED_MESSAGE;
        };
        return new AgentApiException(ex.httpStatus(), message);
    }

    private static ChatData loginRequired(String sessionId) {
        List<String> questions = AgentOrchestrator.DEFAULT_SUGGESTED_QUESTIONS.subList(
                0, LOGIN_REQUIRED_SUGGESTED_QUESTIONS);
        return new ChatData(sessionId, newMessageId(), LOGIN_REQUIRED_ANSWER, List.of(), true, questions);
    }

    private static ChatData toChatData(String sessionId, AgentTurnResult result) {
        return new ChatData(
                sessionId,
                newMessageId(),
                result.answer(),
                result.products(),
                result.requiresLogin(),
                result.suggestedQuestions());
    }

    private static String newMessageId() {
        return UUID.randomUUID().toString();
    }

    /** 按 Unicode 码点裁剪，避免切开代理对；语义对齐 Python 切片与编排层的裁剪规则。 */
    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxChars));
    }
}

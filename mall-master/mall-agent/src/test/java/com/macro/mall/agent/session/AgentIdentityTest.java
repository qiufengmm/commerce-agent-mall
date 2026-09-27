package com.macro.mall.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.macro.mall.agent.api.AgentApiException;
import com.macro.mall.agent.api.ProductCard;
import com.macro.mall.agent.api.SessionMessage;
import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.storefront.PortalException;
import com.macro.mall.agent.storefront.dto.MemberInfoResponse;

/**
 * 身份命名空间、会话快照与 {@link IdentityResolver} 的 TDD 契约测试（计划 Task 6 Step 1）。
 *
 * <p>行为对照 Python：
 * <ul>
 *   <li>{@code mall_shopping_agent.session.identity}</li>
 *   <li>{@code mall_shopping_agent.session.repository}（{@code SessionSnapshot} / {@code SessionMessage}）</li>
 *   <li>{@code mall_shopping_agent.api.chat} 的 {@code _resolve_identity} / {@code _migrate_guest_session}</li>
 * </ul>
 *
 * <p>断言中的摘要、键名与剩余窗口秒数来自一次性的 Python 探针实测输出，不是推断值。
 * 测试不连接 Redis、mall-portal 或模型：门户用 Mockito 替身，会话用
 * {@link InMemorySessionRepository} 或 Mockito 替身。
 */
class AgentIdentityTest {

    private static final String SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c";
    private static final String GUEST_KEY = "mall:agent:session:guest:" + SESSION_ID;
    private static final String MEMBER_KEY = "mall:agent:session:member:7:" + SESSION_ID;
    private static final String AUTHORIZATION = "Bearer placeholder-member-token";

    /** Python 探针实测：fingerprint(sessionId) 与 12 位前缀。 */
    private static final String SESSION_ID_FINGERPRINT = "5940886f02a7434341690ddb3fe60fe8";
    private static final String SESSION_ID_FINGERPRINT_12 = "5940886f02a7";

    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);

    // ------------------------------------------------------------------ //
    // 命名空间：游客与会员严格分离
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("游客会话键使用文档化命名空间，且不携带 memberId")
    void guestSessionKeyUsesDocumentedNamespace() {
        AgentIdentity identity = AgentIdentity.guest(SESSION_ID);

        assertThat(identity.sessionKey()).isEqualTo(GUEST_KEY);
        assertThat(identity.sessionKey()).isEqualTo("mall:agent:session:guest:" + SESSION_ID);
        assertThat(identity.isMember()).isFalse();
        assertThat(identity.kind()).isEqualTo(AgentIdentity.Kind.GUEST);
        assertThat(identity.memberId()).isNull();
        assertThat(identity.sessionId()).isEqualTo(SESSION_ID);
    }

    @Test
    @DisplayName("会员会话键包含服务端 memberId 与 sessionId")
    void memberSessionKeyIncludesMemberIdAndSessionId() {
        AgentIdentity identity = AgentIdentity.member(7, SESSION_ID);

        assertThat(identity.sessionKey()).isEqualTo(MEMBER_KEY);
        assertThat(identity.sessionKey()).isEqualTo("mall:agent:session:member:7:" + SESSION_ID);
        assertThat(identity.isMember()).isTrue();
        assertThat(identity.kind()).isEqualTo(AgentIdentity.Kind.MEMBER);
        assertThat(identity.memberId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("同一 sessionId 的游客键与会员键不同，且 guestKey 始终指向游客命名空间")
    void guestAndMemberKeysDifferForSameSessionId() {
        AgentIdentity guest = AgentIdentity.guest(SESSION_ID);
        AgentIdentity member = AgentIdentity.member(7, SESSION_ID);

        assertThat(guest.sessionKey()).isNotEqualTo(member.sessionKey());
        assertThat(member.guestKey()).isEqualTo(GUEST_KEY);
        assertThat(guest.guestKey()).isEqualTo(guest.sessionKey());
    }

    @Test
    @DisplayName("不同 memberId 不会共用会话键")
    void differentMemberIdsDoNotShareASessionKey() {
        assertThat(AgentIdentity.member(7, SESSION_ID).sessionKey())
                .isNotEqualTo(AgentIdentity.member(8, SESSION_ID).sessionKey());
    }

    @ParameterizedTest(name = "拒绝非正 memberId：{0}")
    @ValueSource(longs = {0L, -3L, Long.MIN_VALUE})
    @DisplayName("memberId 必须是正整数")
    void memberIdentityRejectsNonPositiveMemberId(long memberId) {
        assertThatThrownBy(() -> AgentIdentity.member(memberId, SESSION_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("身份相等性只由命名空间决定")
    void identityEqualityIsByNamespace() {
        assertThat(AgentIdentity.guest(SESSION_ID)).isEqualTo(AgentIdentity.guest(SESSION_ID));
        assertThat(AgentIdentity.guest(SESSION_ID)).isNotEqualTo(AgentIdentity.member(7, SESSION_ID));
        assertThat(AgentIdentity.guest(SESSION_ID).hashCode())
                .isEqualTo(AgentIdentity.guest(SESSION_ID).hashCode());
    }

    @Test
    @DisplayName("toString 不得泄漏原始 sessionId")
    void toStringNeverContainsRawSessionId() {
        assertThat(AgentIdentity.member(7, SESSION_ID).toString()).doesNotContain(SESSION_ID);
        assertThat(AgentIdentity.guest(SESSION_ID).toString()).doesNotContain(SESSION_ID);
    }

    // ------------------------------------------------------------------ //
    // sessionId 规范化
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("sessionId 规范化为小写规范形式（大写、首尾空白均可接受）")
    void sessionIdIsNormalisedToCanonicalLowercaseForm() {
        String upper = SESSION_ID.toUpperCase(java.util.Locale.ROOT);

        assertThat(AgentIdentity.guest(upper).sessionId()).isEqualTo(SESSION_ID);
        assertThat(AgentIdentity.guest("  " + upper + "  ").sessionId()).isEqualTo(SESSION_ID);
        assertThat(AgentIdentity.guest(upper).sessionKey()).isEqualTo(GUEST_KEY);
    }

    @Test
    @DisplayName("随机生成的 UUID v4 可被接受")
    void generatedUuidV4IsAccepted() {
        String value = UUID.randomUUID().toString();

        assertThat(AgentIdentity.guest(value).sessionId()).isEqualTo(value);
        assertThat(UUID.fromString(value).version()).isEqualTo(4);
    }

    @ParameterizedTest(name = "拒绝非法 sessionId：{0}")
    @ValueSource(strings = {
            "",
            "   ",
            "not-a-uuid",
            "2dc7b03e-7368-1d6a-a8ef-b0ea16f6c92c", // UUID v1
            "2dc7b03e73684d6aa8efb0ea16f6c92c", // 缺少连字符
            "{2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c}", // 非规范形式
            "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92", // 过短
            "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c0", // 过长
            "2dc7b03e-7368-cd6a-a8ef-b0ea16f6c92c", // 变体位非法
    })
    @DisplayName("非法 sessionId 被拒绝")
    void invalidSessionIdsAreRejected(String value) {
        assertThatThrownBy(() -> AgentIdentity.guest(value))
                .isInstanceOf(AgentApiException.class)
                .extracting(exception -> ((AgentApiException) exception).getStatus())
                .isEqualTo(400);
        assertThatThrownBy(() -> AgentIdentity.member(7, value))
                .isInstanceOf(AgentApiException.class);
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("null sessionId 被拒绝")
    void nullSessionIdIsRejected(String value) {
        assertThatThrownBy(() -> AgentIdentity.guest(value)).isInstanceOf(AgentApiException.class);
        assertThatThrownBy(() -> AgentIdentity.member(7, value)).isInstanceOf(AgentApiException.class);
    }

    // ------------------------------------------------------------------ //
    // fingerprint
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("fingerprint 是 UTF-8 SHA-256 小写 hex 前 32 位，且不可逆")
    void fingerprintHidesTheOriginalValue() {
        String raw = "203.0.113.10";

        String digest = AgentIdentity.fingerprint(raw);

        assertThat(digest).doesNotContain(raw);
        assertThat(digest).hasSize(32);
        assertThat(digest).isEqualTo(AgentIdentity.fingerprint(raw));
        assertThat(digest).matches("[0-9a-f]{32}");
        assertThat(AgentIdentity.fingerprint("203.0.113.11")).isNotEqualTo(digest);
        assertThat(AgentIdentity.fingerprint(SESSION_ID)).isEqualTo(SESSION_ID_FINGERPRINT);
    }

    @Test
    @DisplayName("fingerprint 长度可配置，且与 Python 12 位前缀一致")
    void fingerprintLengthIsConfigurable() {
        assertThat(AgentIdentity.fingerprint(SESSION_ID, 12)).isEqualTo(SESSION_ID_FINGERPRINT_12);
        assertThat(AgentIdentity.fingerprint(SESSION_ID, 12))
                .isEqualTo(SESSION_ID_FINGERPRINT.substring(0, 12));
        assertThat(AgentIdentity.fingerprint(SESSION_ID, 64)).hasSize(64);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65, 128})
    @DisplayName("fingerprint 长度越界被拒绝")
    void fingerprintRejectsInvalidLength(int length) {
        assertThatThrownBy(() -> AgentIdentity.fingerprint(SESSION_ID, length))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fingerprint 拒绝 null 取值")
    void fingerprintRejectsNull() {
        assertThatThrownBy(() -> AgentIdentity.fingerprint(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ //
    // 日志标签与限流标识
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("logLabel 用 sessionId 摘要，不含原值")
    void logLabelNeverContainsRawSessionId() {
        AgentIdentity guest = AgentIdentity.guest(SESSION_ID);
        AgentIdentity member = AgentIdentity.member(7, SESSION_ID);

        assertThat(guest.logLabel()).doesNotContain(SESSION_ID);
        assertThat(member.logLabel()).doesNotContain(SESSION_ID);
        assertThat(guest.logLabel()).isEqualTo("guest:" + SESSION_ID_FINGERPRINT_12);
        assertThat(member.logLabel()).isEqualTo("member:" + SESSION_ID_FINGERPRINT_12);
        assertThat(member.logLabel()).doesNotContain(AUTHORIZATION);
    }

    @Test
    @DisplayName("rateLimitIdentity 是 sessionKey 的摘要，不是原始 sessionKey")
    void rateLimitIdentityDiffersFromRawSessionKey() {
        AgentIdentity guest = AgentIdentity.guest(SESSION_ID);

        assertThat(guest.rateLimitIdentity()).isNotEqualTo(guest.sessionKey());
        assertThat(guest.rateLimitIdentity()).doesNotContain(SESSION_ID);
        assertThat(guest.rateLimitIdentity()).isEqualTo(AgentIdentity.fingerprint(GUEST_KEY));
        assertThat(guest.rateLimitIdentity()).hasSize(32);
    }

    // ------------------------------------------------------------------ //
    // IdentityResolver
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("无 Authorization 时返回游客身份，且不访问门户与会话仓储")
    void noAuthorizationResolvesGuestAndNeverTouchesPortalOrRepository() {
        MallPortalClient portal = mock(MallPortalClient.class);
        SessionRepository repository = mock(SessionRepository.class);

        ResolvedIdentity resolved = new IdentityResolver(portal, repository).resolve(SESSION_ID, null);

        assertThat(resolved.requiresLogin()).isFalse();
        assertThat(resolved.identity().isMember()).isFalse();
        assertThat(resolved.identity().sessionKey()).isEqualTo(GUEST_KEY);
        verifyNoInteractions(portal, repository);
    }

    @ParameterizedTest(name = "空 Authorization 视为游客：\"{0}\"")
    @ValueSource(strings = {"", "   ", "\t", "\n  \n"})
    @DisplayName("空或全空白 Authorization 返回游客身份")
    void blankAuthorizationResolvesGuest(String authorization) {
        MallPortalClient portal = mock(MallPortalClient.class);
        SessionRepository repository = mock(SessionRepository.class);

        ResolvedIdentity resolved =
                new IdentityResolver(portal, repository).resolve(SESSION_ID, authorization);

        assertThat(resolved.requiresLogin()).isFalse();
        assertThat(resolved.identity().sessionKey()).isEqualTo(GUEST_KEY);
        verifyNoInteractions(portal, repository);
    }

    @Test
    @DisplayName("Token 失效（MEMBER_UNAUTHORIZED）只转成 requiresLogin，不读写任何会话")
    void memberUnauthorizedMapsToRequiresLoginAndKeepsGuestSession() {
        MallPortalClient portal = mock(MallPortalClient.class);
        SessionRepository repository = mock(SessionRepository.class);
        given(portal.resolveMember(AUTHORIZATION)).willThrow(PortalException.memberUnauthorized());

        ResolvedIdentity resolved =
                new IdentityResolver(portal, repository).resolve(SESSION_ID, AUTHORIZATION);

        assertThat(resolved.requiresLogin()).isTrue();
        assertThat(resolved.identity()).isNull();
        verifyNoInteractions(repository);
    }

    static Stream<Arguments> nonUnauthorizedPortalFailures() {
        return Stream.of(
                Arguments.of("NOT_FOUND", (Supplier<PortalException>) PortalException::notFound),
                Arguments.of("TIMEOUT", (Supplier<PortalException>) PortalException::timeout),
                Arguments.of("UNAVAILABLE", (Supplier<PortalException>) PortalException::unavailable),
                Arguments.of("PROTOCOL", (Supplier<PortalException>) PortalException::protocol));
    }

    @ParameterizedTest(name = "门户 {0} 原样传播")
    @MethodSource("nonUnauthorizedPortalFailures")
    @DisplayName("非 401 门户错误原样传播，绝不降级为游客")
    void otherPortalErrorsPropagateWithoutDowngradingToGuest(
            String ignoredName, Supplier<PortalException> failure) {
        MallPortalClient portal = mock(MallPortalClient.class);
        SessionRepository repository = mock(SessionRepository.class);
        PortalException expected = failure.get();
        given(portal.resolveMember(AUTHORIZATION)).willThrow(expected);

        assertThatThrownBy(() -> new IdentityResolver(portal, repository).resolve(SESSION_ID, AUTHORIZATION))
                .isSameAs(expected);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("有效 Authorization 由门户解析出服务端 memberId，并把 Token 逐字透传")
    void validAuthorizationResolvesMemberByServerSideMemberId() {
        MallPortalClient portal = mock(MallPortalClient.class);
        SessionRepository repository = mock(SessionRepository.class);
        given(portal.resolveMember(AUTHORIZATION)).willReturn(new MemberInfoResponse(7, "u", "n", null));
        given(repository.load(MEMBER_KEY)).willReturn(SessionSnapshot.empty());

        ResolvedIdentity resolved =
                new IdentityResolver(portal, repository).resolve(SESSION_ID, AUTHORIZATION);

        assertThat(resolved.requiresLogin()).isFalse();
        assertThat(resolved.identity().isMember()).isTrue();
        assertThat(resolved.identity().memberId()).isEqualTo(7L);
        assertThat(resolved.identity().sessionKey()).isEqualTo(MEMBER_KEY);
        then(portal).should().resolveMember(AUTHORIZATION);
        then(repository).should().copy(GUEST_KEY, MEMBER_KEY);
    }

    @Test
    @DisplayName("有效认证后，仅在会员目标为空时把游客会话迁移过去并删除游客键")
    void validAuthorizationMigratesGuestSessionWhenMemberTargetEmpty() {
        MallPortalClient portal = mock(MallPortalClient.class);
        given(portal.resolveMember(AUTHORIZATION)).willReturn(new MemberInfoResponse(7, "u", "n", null));
        InMemorySessionRepository repository = new InMemorySessionRepository(86400, 20, CLOCK);
        repository.save(GUEST_KEY, snapshot("user", "有什么手机"));

        ResolvedIdentity resolved =
                new IdentityResolver(portal, repository).resolve(SESSION_ID, AUTHORIZATION);

        assertThat(resolved.identity().sessionKey()).isEqualTo(MEMBER_KEY);
        assertThat(repository.keys()).containsExactly(MEMBER_KEY);
        assertThat(repository.load(MEMBER_KEY).messages())
                .extracting(SessionMessage::content)
                .containsExactly("有什么手机");
        assertThat(repository.load(GUEST_KEY).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("会员目标非空时不覆盖，也不删除游客会话")
    void validAuthorizationDoesNotOverwriteNonEmptyMemberTargetAndKeepsGuest() {
        MallPortalClient portal = mock(MallPortalClient.class);
        given(portal.resolveMember(AUTHORIZATION)).willReturn(new MemberInfoResponse(7, "u", "n", null));
        InMemorySessionRepository repository = new InMemorySessionRepository(86400, 20, CLOCK);
        repository.save(GUEST_KEY, snapshot("user", "游客问题"));
        repository.save(MEMBER_KEY, snapshot("user", "会员问题"));

        new IdentityResolver(portal, repository).resolve(SESSION_ID, AUTHORIZATION);

        assertThat(repository.load(MEMBER_KEY).messages())
                .extracting(SessionMessage::content)
                .containsExactly("会员问题");
        assertThat(repository.load(GUEST_KEY).messages())
                .extracting(SessionMessage::content)
                .containsExactly("游客问题");
        assertThat(repository.keys()).containsExactlyInAnyOrder(GUEST_KEY, MEMBER_KEY);
    }

    @Test
    @DisplayName("会员目标仅含商品卡片时同样视为非空，不覆盖也不删除游客键")
    void productOnlyMemberTargetIsNotOverwritten() {
        MallPortalClient portal = mock(MallPortalClient.class);
        given(portal.resolveMember(AUTHORIZATION)).willReturn(new MemberInfoResponse(7, "u", "n", null));
        InMemorySessionRepository repository = new InMemorySessionRepository(86400, 20, CLOCK);
        repository.save(GUEST_KEY, snapshot("user", "游客问题"));
        repository.save(MEMBER_KEY, new SessionSnapshot(List.of(), List.of(card(27))));

        new IdentityResolver(portal, repository).resolve(SESSION_ID, AUTHORIZATION);

        assertThat(repository.load(MEMBER_KEY).products()).hasSize(1);
        assertThat(repository.load(GUEST_KEY).messages()).hasSize(1);
    }

    @Test
    @DisplayName("无有效认证时绝不读取或迁移游客会话内容")
    void guestSessionIsNeverReadOrMigratedWithoutValidAuthentication() {
        MallPortalClient portal = mock(MallPortalClient.class);
        SessionRepository repository = mock(SessionRepository.class);
        given(portal.resolveMember(AUTHORIZATION)).willThrow(PortalException.memberUnauthorized());
        IdentityResolver resolver = new IdentityResolver(portal, repository);

        resolver.resolve(SESSION_ID, null);
        resolver.resolve(SESSION_ID, "  ");
        resolver.resolve(SESSION_ID, AUTHORIZATION);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("RequiresLogin 与 identity 必须二选一")
    void resolvedIdentityEnforcesExactlyOneOutcome() {
        assertThatThrownBy(() -> new ResolvedIdentity(null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResolvedIdentity(AgentIdentity.guest(SESSION_ID), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ResolvedIdentity.loginRequired().identity()).isNull();
        assertThat(ResolvedIdentity.loginRequired().requiresLogin()).isTrue();
        assertThat(ResolvedIdentity.authenticated(AgentIdentity.guest(SESSION_ID)).requiresLogin()).isFalse();
    }

    @Test
    @DisplayName("游客身份没有携带会员身份的入口（服务端 memberId 只能来自门户）")
    void guestIdentityCannotCarryMemberId() {
        AgentIdentity guest = AgentIdentity.guest(SESSION_ID);

        assertThat(guest.memberId()).isNull();
        assertThat(guest.isMember()).isFalse();
        assertThat(guest.kind()).isEqualTo(AgentIdentity.Kind.GUEST);
        // 唯一的 guest 工厂只接受 sessionId，无法在领域层注入 memberId。
        // HTTP 层的 memberId/token 未知字段拒绝见 AgentValidationTest#unknownRequestFieldsAreRejected。
        List<Method> guestFactories = Arrays.stream(AgentIdentity.class.getMethods())
                .filter(method -> "guest".equals(method.getName()))
                .toList();
        assertThat(guestFactories).isNotEmpty();
        for (Method factory : guestFactories) {
            assertThat(factory.getParameterTypes()).containsExactly(String.class);
        }
    }

    @Test
    @DisplayName("仓库只按精确键操作：会员请求不会把游客键当作会员回退")
    void memberLookupNeverFallsBackToGuestKey() {
        MallPortalClient portal = mock(MallPortalClient.class);
        given(portal.resolveMember(AUTHORIZATION)).willReturn(new MemberInfoResponse(9, "u", "n", null));
        SessionRepository repository = mock(SessionRepository.class);
        given(repository.load(AgentIdentity.member(9, SESSION_ID).sessionKey()))
                .willReturn(SessionSnapshot.empty());

        new IdentityResolver(portal, repository).resolve(SESSION_ID, AUTHORIZATION);

        then(repository).should().load("mall:agent:session:member:9:" + SESSION_ID);
        then(repository).should(never()).load(GUEST_KEY);
        then(repository).should().copy(GUEST_KEY, "mall:agent:session:member:9:" + SESSION_ID);
    }

    // ------------------------------------------------------------------ //
    // 辅助
    // ------------------------------------------------------------------ //

    private static SessionSnapshot snapshot(String role, String content) {
        return new SessionSnapshot(List.of(new SessionMessage(role, content)), List.of());
    }

    private static ProductCard card(long id) {
        return new ProductCard(
                id,
                "示例手机 " + id,
                "http://localhost:9000/mall/example-" + id + ".jpg",
                "2999.00",
                "示例副标题",
                "IN_STOCK",
                112,
                "/pages/product/product?id=" + id);
    }
}

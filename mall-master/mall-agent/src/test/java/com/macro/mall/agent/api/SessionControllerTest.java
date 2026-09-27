package com.macro.mall.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.macro.mall.agent.agent.AgentOrchestrator;
import com.macro.mall.agent.agent.AgentTurnRequest;
import com.macro.mall.agent.agent.AgentTurnResult;
import com.macro.mall.agent.config.AgentProperties;
import com.macro.mall.agent.session.IdentityResolver;
import com.macro.mall.agent.session.InFlightGuard;
import com.macro.mall.agent.session.InMemorySessionRepository;
import com.macro.mall.agent.session.RateLimitExceededException;
import com.macro.mall.agent.session.RateLimiter;
import com.macro.mall.agent.session.SessionRepository;
import com.macro.mall.agent.session.SessionSnapshot;
import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.storefront.PortalException;
import com.macro.mall.agent.storefront.dto.MemberInfoResponse;

/**
 * {@code GET/DELETE /agent/session/{sessionId}} 与失效 Authorization 下 {@code POST /agent/chat}
 * 的接线测试。
 *
 * <p>与 {@code AgentChatControllerTest} 的差异：本类使用真实
 * {@link InMemorySessionRepository}（经 {@link TestSessionStore} 薄包装，只在需要时注入存取失败），
 * 因此迁移/删除/恢复断言的是<strong>真实仓储语义</strong>（JSON 编解码、copy 后删除源键、
 * 删除幂等），而不是「是否调用了某个 mock 方法」。外部依赖只有
 * {@link MallPortalClient}（{@code /sso/info} 身份解析）、{@link AgentProperties}、
 * {@link RateLimiter} 与 {@link AgentOrchestrator} 为 Mockito 替身。
 *
 * <p>仓储由 {@code new InMemorySessionRepository(86400, 20, Clock.systemUTC())} 构造，
 * 本类<strong>不</strong>断言 TTL 过期行为（未注入可推进时钟，也未验证过期），只断言上述真实
 * 存取语义。
 *
 * <p>覆盖边界：
 * <ul>
 *   <li>游客 GET 只读 guest 命名空间；有效会员 GET 只读 member 命名空间，不泄漏 guest 内容；</li>
 *   <li>失效 Token GET 返回 HTTP 200 / {@code requiresLogin=true} / 空 messages+products，绝不降级游客；</li>
 *   <li>有效会员首次到达时仅在<strong>会员目标完全为空</strong>时迁移并删除 guest 键，
 *       目标仅有消息或仅有卡片时都不覆盖、不删除 guest 键；</li>
 *   <li>DELETE 只删除已验证身份命名空间；失效 Token 返回 {@code deleted=false} 且不删 guest；
 *       成功授权后 {@code deleted} 恒为 true（重复删除仍为 true）；</li>
 *   <li>失效 Token 的 chat 返回登录失效文案，并把本轮提问写入 guest 会话（保留旧消息与卡片、
 *       应用 maxMessages），存取异常不影响 200 且只记异常类型；</li>
 *   <li>身份解析阶段的非 401 门户失败（{@code /sso/info} 超时/不可用等）按 Python
 *       {@code _resolve_identity} 行为收敛为 {@code ApiExceptionHandler} 的固定内部 500 信封，
 *       不回传门户细节、URL 或 Token；门户身份解析返回空对象同样归一为固定内部 500；</li>
 *   <li>Authorization 去首尾空白后<strong>同时</strong>用于身份解析与会员工具透传；</li>
 *   <li>GET/DELETE 零编排器/零模型调用，门户只调用 {@code /sso/info}；</li>
 *   <li>GET/DELETE 在身份解析之前各消耗一次 IP 桶（与 chat 同一可信客户端 IP 解析），
 *       不消耗会话桶；超限固定 429 + {@code Retry-After}，零门户身份查询、零会话读写，
 *       并在 {@code finally} 释放 in-flight guard。</li>
 * </ul>
 */
@WebMvcTest(AgentController.class)
@Import(SessionControllerTest.Wiring.class)
class SessionControllerTest {

    private static final String SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c";
    private static final String GUEST_KEY = "mall:agent:session:guest:" + SESSION_ID;
    private static final String MEMBER_KEY = "mall:agent:session:member:7:" + SESSION_ID;

    private static final String CLIENT_IP = "203.0.113.9";
    /** MockMvc 默认对端地址；与可信代理配置一致，使带 {@code X-Real-IP} 的用例按头分桶。 */
    private static final String TRUSTED_PROXY_ADDR = "127.0.0.1";
    /**
     * 直连场景的对端地址：与可信代理地址、伪造的 {@link #CLIENT_IP} 与 {@link #FORWARDED_IP}
     * 均不同，使「按对端分桶」与「不采信伪造头」两条断言可以同时成立。
     */
    private static final String DIRECT_REMOTE_ADDR = "192.0.2.55";
    private static final String FORWARDED_IP = "198.51.100.7";
    private static final String TOKEN = "Bearer unit-test-token-must-not-leak";
    private static final String UPSTREAM_TEXT = "upstream-body-must-not-leak";

    private static final String STOREFRONT_FAILED_MESSAGE = "商品数据暂时无法获取，请稍后再试。";
    /** 身份解析阶段非 401 门户失败：按 Python 行为收敛为 ApiExceptionHandler 的固定内部 500 信封。 */
    private static final String INTERNAL_ERROR_MESSAGE = "服务内部错误，请稍后再试。";

    private static final ProductCard CARD = new ProductCard(
            27L,
            "示例手机 B",
            "http://localhost:9000/mall/example-27.jpg",
            "2999.00",
            "示例副标题 B",
            "IN_STOCK",
            112,
            "/pages/product/product?id=27");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentProperties properties;

    @Autowired
    private AgentOrchestrator orchestrator;

    @Autowired
    private MallPortalClient portal;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private InFlightGuard inFlightGuard;

    @Autowired
    private TestSessionStore store;

    @BeforeEach
    void resetState() {
        // 共享 mock 替身由 @Bean 提供、Spring 不自动重置，必须显式清理上一用例的调用记录与 stubbing
        reset(properties, orchestrator, portal, rateLimiter);
        given(properties.isModelAvailable()).willReturn(true);
        given(properties.getSessionMaxMessages()).willReturn(20);
        // 可信代理地址：MockMvc 默认对端（127.0.0.1）被显式信任，使 X-Real-IP 用例仍按头分桶
        given(properties.getTrustedProxyIp()).willReturn(TRUSTED_PROXY_ADDR);
        // 请求级 deadline 预算取自配置；给足余量，避免身份/chat 用例被 deadline 误伤
        given(properties.getRequestTimeoutSeconds()).willReturn(30.0);
        given(orchestrator.buildSessionUpdate(any(AgentTurnRequest.class), any(AgentTurnResult.class)))
                .willReturn(SessionSnapshot.empty());
        store.resetFailures();
        store.clear();
    }

    // ------------------------------------------------------------------ //
    // GET：游客 / 会员命名空间隔离
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("游客 GET 只读 guest 命名空间，返回 messages/products 且 requiresLogin=false")
    void guestGetReadsGuestNamespace() throws Exception {
        store.save(GUEST_KEY, snapshot(
                List.of(new SessionMessage("user", "你好"), new SessionMessage("assistant", "您好")),
                List.of(CARD)));

        mockMvc.perform(get(sessionPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.requiresLogin").value(false))
                .andExpect(jsonPath("$.data.messages[0].role").value("user"))
                .andExpect(jsonPath("$.data.messages[0].content").value("你好"))
                .andExpect(jsonPath("$.data.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.data.products[0].id").value(27))
                .andExpect(jsonPath("$.data.products[0].name").value("示例手机 B"))
                .andExpect(jsonPath("$.data.products[0].availableStock").value(112))
                .andExpect(jsonPath("$.data.products[0].detailPath").value("/pages/product/product?id=27"));

        then(portal).shouldHaveNoInteractions();
        assertIpLimitedOnce(TRUSTED_PROXY_ADDR);
    }

    @Test
    @DisplayName("有效会员 GET 经门户解析后只读 member 命名空间，不泄漏 guest 内容")
    void memberGetReadsMemberNamespaceOnly() throws Exception {
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));
        store.save(MEMBER_KEY, snapshot(
                List.of(new SessionMessage("user", "会员问题"), new SessionMessage("assistant", "会员回答")),
                List.of()));
        store.save(GUEST_KEY, snapshot(List.of(new SessionMessage("user", "游客专属问题")), List.of(CARD)));

        MvcResult response = mockMvc.perform(get(sessionPath()).header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.requiresLogin").value(false))
                .andExpect(jsonPath("$.data.messages[0].content").value("会员问题"))
                .andExpect(jsonPath("$.data.messages[1].content").value("会员回答"))
                .andExpect(jsonPath("$.data.products").isEmpty())
                .andReturn();

        assertThat(body(response)).doesNotContain("游客专属问题");
        then(portal).should().resolveMember(TOKEN);
        then(portal).shouldHaveNoMoreInteractions();
        assertIpLimitedOnce(TRUSTED_PROXY_ADDR);
        // 会员目标非空：迁移不得覆盖，也不得删除 guest 键
        assertThat(store.keys()).contains(GUEST_KEY);
    }

    @Test
    @DisplayName("失效 Token GET 返回 200/requiresLogin=true 空会话，不降级游客也不泄漏 guest 内容")
    void invalidTokenGetReturnsLoginRequiredWithoutGuestFallback() throws Exception {
        given(portal.resolveMember(TOKEN)).willThrow(PortalException.memberUnauthorized());
        store.save(GUEST_KEY, snapshot(List.of(new SessionMessage("user", "游客专属问题")), List.of(CARD)));

        MvcResult response = mockMvc.perform(get(sessionPath()).header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.requiresLogin").value(true))
                .andExpect(jsonPath("$.data.messages").isEmpty())
                .andExpect(jsonPath("$.data.products").isEmpty())
                .andReturn();

        assertNoLeak(body(response), TOKEN, UPSTREAM_TEXT);
        assertThat(body(response)).doesNotContain("游客专属问题");
        // 绝不降级游客、绝不删除或读取 guest 键
        assertThat(store.keys()).contains(GUEST_KEY);
        assertIpLimitedOnce(TRUSTED_PROXY_ADDR);
    }

    // ------------------------------------------------------------------ //
    // GET：有效会员首次到达的迁移策略
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("会员目标完全为空时把 guest 快照迁移到 member 并删除 guest 键")
    void memberGetMigratesGuestWhenMemberTargetEmpty() throws Exception {
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));
        store.save(GUEST_KEY, snapshot(
                List.of(new SessionMessage("user", "登录前提问"), new SessionMessage("assistant", "登录前回答")),
                List.of(CARD)));

        mockMvc.perform(get(sessionPath()).header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresLogin").value(false))
                .andExpect(jsonPath("$.data.messages[0].content").value("登录前提问"))
                .andExpect(jsonPath("$.data.products[0].id").value(27));

        assertThat(store.keys()).as("迁移后 guest 键被删除").doesNotContain(GUEST_KEY);
        assertThat(store.keys()).as("迁移后 member 键存在").contains(MEMBER_KEY);
        assertThat(store.raw(MEMBER_KEY)).contains("登录前提问");
        assertIpLimitedOnce(TRUSTED_PROXY_ADDR);
    }

    @Test
    @DisplayName("会员目标仅有消息时不被 guest 快照覆盖，也不删除 guest 键")
    void memberGetDoesNotOverwriteTargetWithMessages() throws Exception {
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));
        store.save(MEMBER_KEY, snapshot(List.of(new SessionMessage("user", "会员已有消息")), List.of()));
        store.save(GUEST_KEY, snapshot(List.of(new SessionMessage("user", "游客待迁移")), List.of(CARD)));

        mockMvc.perform(get(sessionPath()).header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].content").value("会员已有消息"))
                .andExpect(jsonPath("$.data.products").isEmpty());

        assertThat(store.raw(MEMBER_KEY)).contains("会员已有消息").doesNotContain("游客待迁移");
        assertThat(store.keys()).contains(GUEST_KEY);
        assertIpLimitedOnce(TRUSTED_PROXY_ADDR);
    }

    @Test
    @DisplayName("会员目标仅有商品卡时不被 guest 快照覆盖，也不删除 guest 键")
    void memberGetDoesNotOverwriteTargetWithProductsOnly() throws Exception {
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));
        store.save(MEMBER_KEY, snapshot(List.of(), List.of(CARD)));
        store.save(GUEST_KEY, snapshot(List.of(new SessionMessage("user", "游客待迁移")), List.of()));

        mockMvc.perform(get(sessionPath()).header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages").isEmpty())
                .andExpect(jsonPath("$.data.products[0].id").value(27));

        assertThat(store.raw(MEMBER_KEY)).doesNotContain("游客待迁移").contains("\"id\":27");
        assertThat(store.keys()).contains(GUEST_KEY);
        assertIpLimitedOnce(TRUSTED_PROXY_ADDR);
    }

    // ------------------------------------------------------------------ //
    // DELETE：命名空间清理与幂等
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("游客 DELETE 删除自己的 guest 键并返回 deleted=true/requiresLogin=false")
    void guestDeleteRemovesGuestKey() throws Exception {
        store.save(GUEST_KEY, snapshot(List.of(new SessionMessage("user", "你好")), List.of(CARD)));

        mockMvc.perform(delete(sessionPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.requiresLogin").value(false));

        assertThat(store.keys()).doesNotContain(GUEST_KEY);
        then(portal).shouldHaveNoInteractions();
        assertIpLimitedOnce(TRUSTED_PROXY_ADDR);
    }

    @Test
    @DisplayName("有效会员 DELETE 先按身份迁移策略解析再删除 member 键，guest 键按迁移结果处理")
    void memberDeleteResolvesIdentityThenRemovesMemberKey() throws Exception {
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));
        // 会员目标为空 + guest 有内容：迁移会先 copy 到 member 并删除 guest，随后 member 被删除
        store.save(GUEST_KEY, snapshot(List.of(new SessionMessage("user", "登录前提问")), List.of()));

        mockMvc.perform(delete(sessionPath()).header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.requiresLogin").value(false));

        then(portal).should().resolveMember(TOKEN);
        assertThat(store.keys()).doesNotContain(MEMBER_KEY).doesNotContain(GUEST_KEY);
        assertIpLimitedOnce(TRUSTED_PROXY_ADDR);
    }

    @Test
    @DisplayName("会员目标非空时 DELETE 不迁移 guest 键，只删除 member 键")
    void memberDeleteKeepsGuestKeyWhenMemberTargetNonEmpty() throws Exception {
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));
        store.save(MEMBER_KEY, snapshot(List.of(new SessionMessage("user", "会员已有")), List.of()));
        store.save(GUEST_KEY, snapshot(List.of(new SessionMessage("user", "游客待迁移")), List.of()));

        mockMvc.perform(delete(sessionPath()).header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));

        assertThat(store.keys()).doesNotContain(MEMBER_KEY).contains(GUEST_KEY);
        assertIpLimitedOnce(TRUSTED_PROXY_ADDR);
    }

    @Test
    @DisplayName("重复 DELETE 结果稳定：键已不存在时仍返回 deleted=true")
    void repeatedDeleteStaysDeletedTrue() throws Exception {
        store.save(GUEST_KEY, snapshot(List.of(new SessionMessage("user", "你好")), List.of()));

        mockMvc.perform(delete(sessionPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
        assertThat(store.keys()).doesNotContain(GUEST_KEY);

        mockMvc.perform(delete(sessionPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.requiresLogin").value(false));

        // 两次 DELETE 各自消耗一次 IP 桶，且都不消耗会话桶
        then(rateLimiter).should(times(2)).checkIp(TRUSTED_PROXY_ADDR);
        then(rateLimiter).should(never()).checkSession(anyString());
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("失效 Token DELETE 返回 deleted=false/requiresLogin=true，绝不删除 guest 键")
    void invalidTokenDeleteDoesNotDeleteGuestKey() throws Exception {
        given(portal.resolveMember(TOKEN)).willThrow(PortalException.memberUnauthorized());
        store.save(GUEST_KEY, snapshot(List.of(new SessionMessage("user", "游客专属问题")), List.of(CARD)));

        mockMvc.perform(delete(sessionPath()).header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deleted").value(false))
                .andExpect(jsonPath("$.data.requiresLogin").value(true));

        assertThat(store.keys()).contains(GUEST_KEY);
        assertIpLimitedOnce(TRUSTED_PROXY_ADDR);
    }

    // ------------------------------------------------------------------ //
    // GET/DELETE：身份解析之前的 IP 维度限流
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("GET 超 IP 配额固定 429：零门户身份查询、零会话读取、释放 in-flight guard")
    void getOverIpQuotaReturns429BeforeIdentityResolution() throws Exception {
        willThrow(new RateLimitExceededException(42)).given(rateLimiter).checkIp(anyString());
        store.save(GUEST_KEY, snapshot(List.of(new SessionMessage("user", "游客专属问题")), List.of(CARD)));

        mockMvc.perform(get(sessionPath()).header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value(RateLimitExceededException.MESSAGE))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "42"));

        then(rateLimiter).should().checkIp(TRUSTED_PROXY_ADDR);
        then(rateLimiter).should(never()).checkSession(anyString());
        then(portal).shouldHaveNoInteractions();
        verifyNoInteractions(orchestrator);
        assertThat(store.loads()).as("限流早于身份解析，门户与会话都不得触达").isEmpty();
        assertThat(store.keys()).contains(GUEST_KEY);
        assertThat(inFlightGuard.keys()).as("429 后必须释放占用键").isEmpty();
    }

    @Test
    @DisplayName("DELETE 超 IP 配额固定 429：零门户身份查询、零会话删除、释放 in-flight guard")
    void deleteOverIpQuotaReturns429BeforeIdentityResolution() throws Exception {
        willThrow(new RateLimitExceededException(9)).given(rateLimiter).checkIp(anyString());
        store.save(GUEST_KEY, snapshot(List.of(new SessionMessage("user", "游客专属问题")), List.of(CARD)));

        mockMvc.perform(delete(sessionPath()).header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value(RateLimitExceededException.MESSAGE))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "9"));

        then(rateLimiter).should().checkIp(TRUSTED_PROXY_ADDR);
        then(rateLimiter).should(never()).checkSession(anyString());
        then(portal).shouldHaveNoInteractions();
        verifyNoInteractions(orchestrator);
        assertThat(store.keys()).as("限流早于删除，guest 键必须保留").contains(GUEST_KEY);
        assertThat(inFlightGuard.keys()).as("429 后必须释放占用键").isEmpty();
    }

    @Test
    @DisplayName("GET 在可信代理来源时按 X-Real-IP 分桶，且忽略 X-Forwarded-For")
    void getUsesXRealIpFromTrustedProxyForIpBucket() throws Exception {
        mockMvc.perform(get(sessionPath())
                        .with(request -> {
                            request.setRemoteAddr(TRUSTED_PROXY_ADDR);
                            return request;
                        })
                        .header("X-Real-IP", CLIENT_IP)
                        .header("X-Forwarded-For", FORWARDED_IP))
                .andExpect(status().isOk());

        then(rateLimiter).should().checkIp(CLIENT_IP);
        then(rateLimiter).should(never()).checkIp(TRUSTED_PROXY_ADDR);
        then(rateLimiter).should(never()).checkIp(FORWARDED_IP);
        then(rateLimiter).should(never()).checkSession(anyString());
    }

    @Test
    @DisplayName("DELETE 在可信代理来源时按 X-Real-IP 分桶，且忽略 X-Forwarded-For")
    void deleteUsesXRealIpFromTrustedProxyForIpBucket() throws Exception {
        mockMvc.perform(delete(sessionPath())
                        .with(request -> {
                            request.setRemoteAddr(TRUSTED_PROXY_ADDR);
                            return request;
                        })
                        .header("X-Real-IP", CLIENT_IP)
                        .header("X-Forwarded-For", FORWARDED_IP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.requiresLogin").value(false));

        then(rateLimiter).should().checkIp(CLIENT_IP);
        then(rateLimiter).should(never()).checkIp(TRUSTED_PROXY_ADDR);
        then(rateLimiter).should(never()).checkIp(FORWARDED_IP);
        then(rateLimiter).should(never()).checkSession(anyString());
        then(rateLimiter).should(never()).check(anyString(), anyString());
    }

    @Test
    @DisplayName("GET 直连（对端非可信代理）时伪造的 X-Real-IP 被忽略，按 remoteAddr 分桶")
    void getIgnoresForgedXRealIpWhenDirectConnection() throws Exception {
        mockMvc.perform(get(sessionPath())
                        .with(request -> {
                            request.setRemoteAddr(DIRECT_REMOTE_ADDR);
                            return request;
                        })
                        .header("X-Real-IP", CLIENT_IP)
                        .header("X-Forwarded-For", FORWARDED_IP))
                .andExpect(status().isOk());

        then(rateLimiter).should().checkIp(DIRECT_REMOTE_ADDR);
        then(rateLimiter).should(never()).checkIp(CLIENT_IP);
        then(rateLimiter).should(never()).checkIp(FORWARDED_IP);
        then(rateLimiter).should(never()).checkSession(anyString());
    }

    @Test
    @DisplayName("DELETE 直连时伪造的 X-Real-IP 被忽略，按 remoteAddr 分桶")
    void deleteIgnoresForgedXRealIpWhenDirectConnection() throws Exception {
        mockMvc.perform(delete(sessionPath())
                        .with(request -> {
                            request.setRemoteAddr(DIRECT_REMOTE_ADDR);
                            return request;
                        })
                        .header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk());

        then(rateLimiter).should().checkIp(DIRECT_REMOTE_ADDR);
        then(rateLimiter).should(never()).checkIp(CLIENT_IP);
        then(rateLimiter).should(never()).checkSession(anyString());
    }

    // ------------------------------------------------------------------ //
    // 身份解析阶段的非 401 门户错误 → 固定内部 500（按 Python 行为，不回传门户细节）
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("GET 身份解析遇门户超时按 Python 行为固定 500，不回传门户细节、URL 或 Token")
    void getMapsNonUnauthorizedPortalIdentityFailureToFixed500() throws Exception {
        given(portal.resolveMember(TOKEN)).willThrow(PortalException.timeout());

        MvcResult response = mockMvc.perform(get(sessionPath()).header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value(INTERNAL_ERROR_MESSAGE))
                .andReturn();

        assertNoLeak(body(response), TOKEN, UPSTREAM_TEXT, "http://", "Bearer");
        assertThat(body(response)).doesNotContain(STOREFRONT_FAILED_MESSAGE);
        assertIpLimitedOnce(TRUSTED_PROXY_ADDR);
    }

    @Test
    @DisplayName("DELETE 身份解析遇门户不可用按 Python 行为固定 500")
    void deleteMapsNonUnauthorizedPortalIdentityFailureToFixed500() throws Exception {
        given(portal.resolveMember(TOKEN)).willThrow(PortalException.unavailable());

        MvcResult response = mockMvc.perform(delete(sessionPath()).header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value(INTERNAL_ERROR_MESSAGE))
                .andReturn();

        assertNoLeak(body(response), TOKEN, UPSTREAM_TEXT, "http://", "Bearer");
        assertThat(body(response)).doesNotContain(STOREFRONT_FAILED_MESSAGE);
        assertIpLimitedOnce(TRUSTED_PROXY_ADDR);
    }

    @Test
    @DisplayName("门户身份解析返回空对象时归一为固定内部 500，不泄漏 NPE 细节且不删 guest 键")
    void nullMemberInfoMapsToFixed500WithoutNpeLeak() throws Exception {
        given(portal.resolveMember(TOKEN)).willReturn(null);
        store.save(GUEST_KEY, snapshot(List.of(new SessionMessage("user", "游客专属问题")), List.of(CARD)));

        MvcResult response = mockMvc.perform(get(sessionPath()).header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value(INTERNAL_ERROR_MESSAGE))
                .andReturn();

        assertNoLeak(body(response), TOKEN, UPSTREAM_TEXT);
        assertThat(body(response)).doesNotContain("游客专属问题");
        assertThat(store.keys()).contains(GUEST_KEY);
        assertIpLimitedOnce(TRUSTED_PROXY_ADDR);
    }

    // ------------------------------------------------------------------ //
    // POST /agent/chat：失效 Authorization 的待登录提问记录
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("失效 Token chat 返回登录失效文案，并把提问追加到 guest 会话且保留旧消息与卡片")
    void invalidTokenChatRemembersGuestTurn() throws Exception {
        given(portal.resolveMember(TOKEN)).willThrow(PortalException.memberUnauthorized());
        store.save(GUEST_KEY, snapshot(
                List.of(new SessionMessage("user", "旧问题"), new SessionMessage("assistant", "旧回答")),
                List.of(CARD)));

        MvcResult response = mockMvc.perform(chatRequest("登录后的问题")
                        .header(HttpHeaders.AUTHORIZATION, TOKEN)
                        .header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.requiresLogin").value(true))
                .andExpect(jsonPath("$.data.products").isEmpty())
                .andExpect(jsonPath("$.data.answer").value(DefaultAgentChatService.LOGIN_REQUIRED_ANSWER))
                .andReturn();

        assertNoLeak(body(response), TOKEN, UPSTREAM_TEXT);

        SessionSnapshot stored = store.load(GUEST_KEY);
        assertThat(stored.messages()).extracting(SessionMessage::role)
                .containsExactly("user", "assistant", "user", "assistant");
        assertThat(stored.messages().get(2).content()).isEqualTo("登录后的问题");
        assertThat(stored.messages().get(3).content())
                .isEqualTo(DefaultAgentChatService.LOGIN_REQUIRED_ANSWER);
        assertThat(stored.products()).extracting(ProductCard::id).containsExactly(27L);
        assertThat(store.raw(GUEST_KEY)).as("Token 绝不写入会话").doesNotContain(TOKEN);

        then(portal).should().resolveMember(TOKEN);
        // 预认证 IP 门槛已消耗；失效 Token 身份未确定，绝不消耗会话桶，也不进入编排
        then(rateLimiter).should().checkIp(CLIENT_IP);
        then(rateLimiter).should(never()).checkSession(anyString());
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("失效 Token chat 的 guest 记录按 sessionMaxMessages 裁剪，保留最近两条")
    void invalidTokenChatAppliesSessionMessageLimit() throws Exception {
        given(properties.getSessionMaxMessages()).willReturn(2);
        given(portal.resolveMember(TOKEN)).willThrow(PortalException.memberUnauthorized());
        store.save(GUEST_KEY, snapshot(
                List.of(new SessionMessage("user", "旧问题"), new SessionMessage("assistant", "旧回答")),
                List.of()));

        mockMvc.perform(chatRequest("登录后的问题").header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresLogin").value(true));

        SessionSnapshot stored = store.load(GUEST_KEY);
        assertThat(stored.messages()).extracting(SessionMessage::role)
                .containsExactly("user", "assistant");
        assertThat(stored.messages().get(0).content()).isEqualTo("登录后的问题");
        assertThat(stored.messages().get(1).content())
                .isEqualTo(DefaultAgentChatService.LOGIN_REQUIRED_ANSWER);
    }

    @Test
    @DisplayName("失效 Token chat 的会话写入失败仍返回 200/requiresLogin，且不泄漏异常正文")
    void invalidTokenChatSurvivesSessionStoreFailure() throws Exception {
        String secret = "session-store-down-must-not-leak";
        given(portal.resolveMember(TOKEN)).willThrow(PortalException.memberUnauthorized());
        store.save(GUEST_KEY, snapshot(List.of(new SessionMessage("user", "旧问题")), List.of()));
        store.failNextSave(secret);

        MvcResult response = mockMvc.perform(chatRequest("登录后的问题").header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.requiresLogin").value(true))
                .andExpect(jsonPath("$.data.products").isEmpty())
                .andReturn();

        assertNoLeak(body(response), secret, TOKEN, UPSTREAM_TEXT);
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("失效 Token chat 之后，有效会员 GET 安全迁移该 guest 快照")
    void guestTurnRemembersThenMigratesOnValidMemberGet() throws Exception {
        given(portal.resolveMember(TOKEN)).willThrow(PortalException.memberUnauthorized());

        mockMvc.perform(chatRequest("登录后的问题").header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresLogin").value(true));
        assertThat(store.keys()).contains(GUEST_KEY);

        // 之后携带有效 Token 的会员到达：会员目标为空，迁移 guest 快照并删除 guest 键
        reset(portal);
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));

        mockMvc.perform(get(sessionPath()).header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresLogin").value(false))
                .andExpect(jsonPath("$.data.messages[0].content").value("登录后的问题"))
                .andExpect(jsonPath("$.data.messages[1].content")
                        .value(DefaultAgentChatService.LOGIN_REQUIRED_ANSWER));

        assertThat(store.keys()).doesNotContain(GUEST_KEY).contains(MEMBER_KEY);
    }

    // ------------------------------------------------------------------ //
    // Authorization 正规化与不落库
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Authorization 去首尾空白后同时用于身份解析与会员工具透传")
    void authorizationIsStrippedForBothResolutionAndTurn() throws Exception {
        String raw = "   " + TOKEN + "   ";
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));
        given(orchestrator.run(any(AgentTurnRequest.class)))
                .willReturn(answer("会员回答"));

        mockMvc.perform(chatRequest("有哪些手机").header(HttpHeaders.AUTHORIZATION, raw))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresLogin").value(false));

        // 身份解析使用去空白后的规范值
        then(portal).should().resolveMember(TOKEN);
        then(portal).shouldHaveNoMoreInteractions();

        // 会员工具透传同样必须是去空白后的值，不能是原始带空白值
        ArgumentCaptor<AgentTurnRequest> turn = ArgumentCaptor.forClass(AgentTurnRequest.class);
        then(orchestrator).should().run(turn.capture());
        assertThat(turn.getValue().identity().isMember()).isTrue();
        assertThat(turn.getValue().identity().sessionKey()).isEqualTo(MEMBER_KEY);
        assertThat(turn.getValue().authorization())
                .as("会员工具透传的 Authorization 必须是去空白后的规范值")
                .isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("纯空白 Authorization 视为游客，不访问门户")
    void blankAuthorizationIsTreatedAsGuest() throws Exception {
        store.save(GUEST_KEY, snapshot(List.of(new SessionMessage("user", "游客问题")), List.of()));

        mockMvc.perform(get(sessionPath()).header(HttpHeaders.AUTHORIZATION, "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresLogin").value(false))
                .andExpect(jsonPath("$.data.messages[0].content").value("游客问题"));

        then(portal).shouldHaveNoInteractions();
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("有效会员 chat 的 Token 不落库、不进入响应")
    void validMemberChatNeverPersistsToken() throws Exception {
        given(portal.resolveMember(TOKEN)).willReturn(new MemberInfoResponse(7L, "u", "n", null));
        given(orchestrator.run(any(AgentTurnRequest.class))).willReturn(answer("会员回答"));
        given(orchestrator.buildSessionUpdate(any(AgentTurnRequest.class), any(AgentTurnResult.class)))
                .willReturn(snapshot(List.of(new SessionMessage("user", "有哪些手机")), List.of()));

        MvcResult response = mockMvc.perform(chatRequest("有哪些手机")
                        .header(HttpHeaders.AUTHORIZATION, TOKEN)
                        .header("X-Real-IP", CLIENT_IP))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(body(response)).doesNotContain(TOKEN);
        assertThat(store.raw(MEMBER_KEY)).as("Token 绝不写入会话").doesNotContain(TOKEN);
    }

    // ------------------------------------------------------------------ //
    // 路径校验
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("非 UUID v4 路径按既有 controller validator 返回固定 400，不触达业务层")
    void invalidSessionIdPathReturnsFixed400() throws Exception {
        mockMvc.perform(get("/agent/session/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(AgentApiException.INVALID_SESSION_ID_MESSAGE));

        verifyNoInteractions(orchestrator, portal);
    }

    // ------------------------------------------------------------------ //
    // 辅助
    // ------------------------------------------------------------------ //

    private static SessionSnapshot snapshot(List<SessionMessage> messages, List<ProductCard> products) {
        return new SessionSnapshot(messages, products);
    }

    private static AgentTurnResult answer(String text) {
        return new AgentTurnResult(
                text, List.of(), false, List.of(), List.of(), 1, AgentTurnResult.StoppedReason.ANSWER);
    }

    private static String sessionPath() {
        return "/agent/session/" + SESSION_ID;
    }

    private static MockHttpServletRequestBuilder chatRequest(String message) {
        return post("/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + SESSION_ID + "\",\"message\":\"" + message + "\"}");
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * GET/DELETE 的限流契约：在身份解析前只消耗一次 IP 桶，不消耗会话桶，且绝不调用聚合
     * {@code check}（后者会同时触碰会话维度）。
     */
    private void assertIpLimitedOnce(String expectedIp) {
        then(rateLimiter).should().checkIp(expectedIp);
        then(rateLimiter).should(never()).checkSession(anyString());
        then(rateLimiter).should(never()).check(anyString(), anyString());
        verifyNoInteractions(orchestrator);
    }

    private static void assertNoLeak(String body, String... secrets) {
        assertThat(body)
                .doesNotContain("Exception")
                .doesNotContain("java.")
                .doesNotContain("stackTrace")
                .doesNotContain("at com.")
                .doesNotContain("http://")
                .doesNotContain("https://");
        for (String secret : secrets) {
            assertThat(body).as("错误响应不得包含 %s", secret).doesNotContain(secret);
        }
    }

    /**
     * 真实 {@link InMemorySessionRepository} 的薄包装：只在显式注入失败标记时让 {@code save} 抛异常，
     * 其余请求全部委托给真实仓储，因此迁移/删除/恢复断言的是真实持久化语义而非 mock 的调用记录。
     */
    static final class TestSessionStore implements SessionRepository {

        private final InMemorySessionRepository delegate =
                new InMemorySessionRepository(86400, 20, Clock.systemUTC());
        private final List<String> loads = new ArrayList<>();
        private String saveFailureSecret;

        @Override
        public SessionSnapshot load(String key) {
            loads.add(key);
            return delegate.load(key);
        }

        @Override
        public void save(String key, SessionSnapshot snapshot) {
            if (saveFailureSecret != null) {
                throw new IllegalStateException(saveFailureSecret);
            }
            delegate.save(key, snapshot);
        }

        @Override
        public boolean delete(String key) {
            return delegate.delete(key);
        }

        @Override
        public void copy(String sourceKey, String targetKey) {
            delegate.copy(sourceKey, targetKey);
        }

        List<String> keys() {
            return delegate.keys();
        }

        List<String> loads() {
            return List.copyOf(loads);
        }

        String raw(String key) {
            return delegate.raw(key);
        }

        void failNextSave(String secret) {
            this.saveFailureSecret = secret;
        }

        void resetFailures() {
            this.saveFailureSecret = null;
            this.loads.clear();
        }

        void clear() {
            for (String key : new ArrayList<>(delegate.keys())) {
                delegate.delete(key);
            }
        }
    }

    /**
     * 真实 service 装配：会话仓储用真实 {@link TestSessionStore}，身份解析与并发防重用真实实现，
     * 门户/模型/限流/配置替换为 Mockito 替身。
     */
    @TestConfiguration
    static class Wiring {

        @Bean
        AgentProperties agentProperties() {
            return mock(AgentProperties.class);
        }

        @Bean
        MallPortalClient mallPortalClient() {
            return mock(MallPortalClient.class);
        }

        @Bean
        TestSessionStore sessionRepository() {
            return new TestSessionStore();
        }

        @Bean
        RateLimiter rateLimiter() {
            return mock(RateLimiter.class);
        }

        @Bean
        AgentOrchestrator agentOrchestrator() {
            return mock(AgentOrchestrator.class);
        }

        @Bean
        InFlightGuard inFlightGuard() {
            return new InFlightGuard();
        }

        @Bean
        IdentityResolver identityResolver(MallPortalClient portal, SessionRepository repository) {
            return new IdentityResolver(portal, repository);
        }

        @Bean
        AgentChatService agentChatService(
                AgentProperties properties,
                IdentityResolver identityResolver,
                RateLimiter rateLimiter,
                InFlightGuard inFlightGuard,
                SessionRepository sessionRepository,
                AgentOrchestrator orchestrator) {
            return new DefaultAgentChatService(
                    properties, identityResolver, rateLimiter, inFlightGuard, sessionRepository, orchestrator);
        }
    }
}

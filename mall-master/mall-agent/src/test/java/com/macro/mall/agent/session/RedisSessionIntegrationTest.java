package com.macro.mall.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.macro.mall.agent.api.ProductCard;
import com.macro.mall.agent.api.SessionMessage;

/**
 * 显式可选的 Redis 集成测试（计划 Task 6 Step 4）。
 *
 * <p>连接来源<strong>只</strong>是 {@code MALL_AGENT_TEST_REDIS_URL}：
 * <ul>
 *   <li>只有未设置或纯空白才视为未启用：所有真实 Redis 用例通过 {@code assumeTrue} 明确跳过，
 *       且<strong>不会</strong>建立任何连接；</li>
 *   <li>显式配置但非法或不允许（非 redis(s) 协议、非法 URL/query/fragment、非 0 号库、
 *       非本机 loopback host）时，{@link Target#resolve(Function)} 抛出固定且不含配置值的
 *       {@link IllegalArgumentException}，让错误配置<strong>明确失败</strong>而不是被静默跳过；</li>
 *   <li>只接受本机 loopback 目标（{@code localhost}、{@code 127.0.0.0/8}、{@code ::1}），
 *       显式远端地址一律拒绝；</li>
 *   <li>绝不回退到生产 {@code MALL_AGENT_REDIS_URL}；</li>
 *   <li>必须显式指定非 0 号库（{@code /1} 起），拒绝 {@code /0} 与缺省库，避免误连默认库；</li>
 *   <li>所有键都带唯一 {@code mall:agent:test:{runId}:} 前缀，清理只按本次创建过的精确键
 *       {@code DELETE}，绝不使用 {@code FLUSHDB}/{@code FLUSHALL}。</li>
 * </ul>
 *
 * <p>目标解析守卫（{@link Target#resolve(Function)}）是纯函数，因此即使本机没有测试 Redis，
 * 「拒绝 0 号库 / 拒绝回退生产变量 / 解析凭证与 TLS」这些安全断言仍然会被真实执行。
 *
 * <p>真实 Redis 分支除会话仓储外，也覆盖限流计数 Lua（{@link RedisRateCounter}）的原子递增
 * 与「首次设置 TTL、已有 TTL 不被重置」语义。
 */
class RedisSessionIntegrationTest {

    private static final String ENV_TEST_REDIS_URL = "MALL_AGENT_TEST_REDIS_URL";
    private static final String ENV_PRODUCTION_REDIS_URL = "MALL_AGENT_REDIS_URL";
    private static final String TEST_KEY_PREFIX = "mall:agent:test:";
    private static final String SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c";
    private static final int TTL_SECONDS = 30;
    private static final Duration TTL = Duration.ofSeconds(TTL_SECONDS);
    /** 压小后的既有 TTL：留足 10 秒余量，避免在慢机器上键于第二次递增前过期而偶发变红。 */
    private static final int REDUCED_TTL_SECONDS = 10;
    private static final String SKIP_REASON =
            "未配置 " + ENV_TEST_REDIS_URL + "，跳过真实 Redis 集成测试";

    private final List<String> createdKeys = new ArrayList<>();

    private Target target;
    private String prefix;
    private StringRedisTemplate template;
    private LettuceConnectionFactory connectionFactory;

    @BeforeEach
    void resolveTestTarget() {
        target = Target.resolve(System::getenv).orElse(null);
        prefix = TEST_KEY_PREFIX + UUID.randomUUID().toString().replace("-", "") + ":";
        createdKeys.clear();
    }

    @AfterEach
    void cleanUpOnlyExactTestKeysAndDisconnect() {
        try {
            if (template != null) {
                for (String key : createdKeys) {
                    template.delete(key);
                }
                template = null;
            }
        } finally {
            if (connectionFactory != null) {
                connectionFactory.destroy();
                connectionFactory = null;
            }
        }
    }

    // ------------------------------------------------------------------ //
    // 目标解析守卫：无 Redis 也一定会执行
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("未设置或空白测试变量时不启用集成测试")
    void missingOrBlankTestRedisUrlDisablesIntegration() {
        assertThat(Target.resolve(key -> null)).isEmpty();
        assertThat(Target.resolve(key -> "")).isEmpty();
        assertThat(Target.resolve(key -> "   ")).isEmpty();
    }

    @Test
    @DisplayName("绝不回退到生产 MALL_AGENT_REDIS_URL")
    void productionRedisUrlIsNeverUsedAsFallback() {
        // 生产变量即使放了一个合法的本机地址，也绝不能被当成测试目标
        Function<String, String> productionOnly =
                key -> ENV_PRODUCTION_REDIS_URL.equals(key) ? "redis://127.0.0.1:6379/1" : null;

        assertThat(Target.resolve(productionOnly)).isEmpty();
    }

    @Test
    @DisplayName("显式非法配置抛 IllegalArgumentException，而不是静默跳过")
    void illegalConfigurationFailsFastInsteadOfBeingSkipped() {
        for (String raw : new String[] {
                "not-a-url",
                "http://localhost:6379/1",
                "redis:///1",
                "redis://localhost:6379/0",
                "redis://localhost:6379",
                "redis://localhost:6379/",
                "redis://localhost:6379/-1",
                "redis://localhost:6379/1?db=2",
                "redis://localhost:6379/1#fragment",
                "redis://localhost:6379/abc",
        }) {
            assertThatThrownBy(() -> Target.resolve(env(raw)))
                    .as("显式非法配置 %s 必须明确失败而不是被跳过", raw)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("显式非 loopback（remote）目标被拒绝，绝不连到远程")
    void explicitRemoteHostIsRejected() {
        for (String raw : new String[] {
                "redis://cache.example.invalid:6379/1",
                "redis://example.test:6380/3",
                "redis://10.0.0.5:6379/1",
                "rediss://redis.internal:6380/2",
        }) {
            assertThatThrownBy(() -> Target.resolve(env(raw)))
                    .as("显式 remote 目标 %s 必须明确失败而不是被跳过", raw)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("异常信息固定，且不含 URL/host/端口/库号/凭证")
    void rejectionMessageIsFixedAndNeverLeaksTheConfiguredValue() {
        String message = rejectionMessage("rediss://agent:secret-value@cache.example.invalid:16379/1?db=2");

        assertThat(message).isNotBlank();
        // 与另一条完全不同的非法配置对比，证明信息是固定的，不回显任何输入片段
        assertThat(message).isEqualTo(rejectionMessage("redis://localhost:6379/0"));
        assertThat(message)
                .doesNotContain("redis://")
                .doesNotContain("rediss://")
                .doesNotContain("cache.example.invalid")
                .doesNotContain("16379")
                .doesNotContain("agent:secret")
                .doesNotContain("secret-value")
                .doesNotContain("db=2");
    }

    @Test
    @DisplayName("本机 loopback 非 0 库目标被解析为 host/port/database，且不建立连接")
    void localLoopbackNonZeroDatabaseTargetIsParsed() {
        Optional<Target> resolved = Target.resolve(env("redis://127.0.0.1:16379/15"));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().host()).isEqualTo("127.0.0.1");
        assertThat(resolved.get().port()).isEqualTo(16379);
        assertThat(resolved.get().database()).isEqualTo(15);
        assertThat(resolved.get().ssl()).isFalse();
        assertThat(resolved.get().safeDescription()).isEqualTo("redis://127.0.0.1:16379/15");
    }

    @Test
    @DisplayName("localhost 与 IPv6 ::1 本机目标被接受")
    void localhostAndIpv6LoopbackAreAccepted() {
        assertThat(Target.resolve(env("redis://localhost:6379/3")))
                .hasValueSatisfying(target -> {
                    assertThat(target.host()).isEqualTo("localhost");
                    assertThat(target.port()).isEqualTo(6379);
                    assertThat(target.database()).isEqualTo(3);
                });
        assertThat(Target.resolve(env("redis://[::1]:16380/7")))
                .hasValueSatisfying(target -> {
                    assertThat(target.host()).isEqualTo("::1");
                    assertThat(target.port()).isEqualTo(16380);
                    assertThat(target.database()).isEqualTo(7);
                });
    }

    @Test
    @DisplayName("rediss:// 本机目标启用 TLS，凭证不会出现在安全描述里")
    void redissLoopbackTargetEnablesTlsAndParsesCredentials() {
        Optional<Target> resolved = Target.resolve(env("rediss://agent:secret-value@127.0.0.1:16380/7"));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().ssl()).isTrue();
        assertThat(resolved.get().host()).isEqualTo("127.0.0.1");
        assertThat(resolved.get().username()).isEqualTo("agent");
        assertThat(resolved.get().password()).isNotNull();
        assertThat(resolved.get().safeDescription())
                .isEqualTo("rediss://127.0.0.1:16380/7")
                .doesNotContain("agent")
                .doesNotContain("secret-value");
    }

    // ------------------------------------------------------------------ //
    // 真实 Redis：仅在显式配置时执行
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("真实 Redis 上保存/读取会话文档并保留 TTL")
    void savesAndLoadsSessionAgainstRealRedis() {
        assumeTrue(target != null, SKIP_REASON);
        String guestKey = key("guest:" + SESSION_ID);
        RedisSessionRepository repository = repository();

        repository.save(guestKey, new SessionSnapshot(
                List.of(new SessionMessage("user", "有什么手机"),
                        new SessionMessage("assistant", "为您找到三款")),
                List.of(card())));

        SessionSnapshot loaded = repository.load(guestKey);
        assertThat(loaded.messages()).extracting(SessionMessage::content)
                .containsExactly("有什么手机", "为您找到三款");
        assertThat(loaded.products()).extracting(ProductCard::id).containsExactly(27L);
        assertThat(redis().getExpire(guestKey)).isBetween(1L, (long) TTL_SECONDS);
    }

    @Test
    @DisplayName("真实 Redis 上 GETEX 等价操作会续期，而不是只读取")
    void loadRenewsTtlAgainstRealRedis() {
        assumeTrue(target != null, SKIP_REASON);
        String guestKey = key("guest:" + SESSION_ID);
        RedisSessionRepository repository = repository();
        repository.save(guestKey, snapshot("user", "有什么手机"));

        redis().expire(guestKey, Duration.ofSeconds(REDUCED_TTL_SECONDS));
        assertThat(redis().getExpire(guestKey)).isLessThanOrEqualTo((long) REDUCED_TTL_SECONDS);

        repository.load(guestKey);

        assertThat(redis().getExpire(guestKey)).isGreaterThan((long) REDUCED_TTL_SECONDS);
    }

    @Test
    @DisplayName("真实 Redis 上游客与会员键互不影响，迁移复制后删除源键")
    void guestAndMemberNamespacesAreIsolatedInRedis() {
        assumeTrue(target != null, SKIP_REASON);
        String guestKey = key("guest:" + SESSION_ID);
        String memberKey = key("member:7:" + SESSION_ID);
        String emptyMemberKey = key("member:8:" + SESSION_ID);
        RedisSessionRepository repository = repository();
        repository.save(guestKey, snapshot("user", "游客问题"));
        repository.save(memberKey, snapshot("user", "会员问题"));

        assertThat(repository.load(guestKey).messages()).extracting(SessionMessage::content)
                .containsExactly("游客问题");
        assertThat(repository.load(memberKey).messages()).extracting(SessionMessage::content)
                .containsExactly("会员问题");

        repository.copy(guestKey, emptyMemberKey);

        assertThat(repository.load(guestKey).isEmpty()).isTrue();
        assertThat(repository.load(emptyMemberKey).messages()).extracting(SessionMessage::content)
                .containsExactly("游客问题");

        // 生产命名空间规则仍由 AgentIdentity 决定：同一 sessionId 的游客/会员键必须不同
        assertThat(AgentIdentity.guest(SESSION_ID).sessionKey())
                .isNotEqualTo(AgentIdentity.member(7, SESSION_ID).sessionKey());
    }

    @Test
    @DisplayName("真实 Redis 上坏文档被删除而不是被当成空会话保留")
    void corruptDocumentIsRemovedFromRealRedis() {
        assumeTrue(target != null, SKIP_REASON);
        String guestKey = key("guest:" + SESSION_ID);
        redis().opsForValue().set(guestKey, "{not json", TTL);
        assertThat(redis().hasKey(guestKey)).isTrue();

        assertThat(repository().load(guestKey).isEmpty()).isTrue();

        assertThat(redis().hasKey(guestKey)).isFalse();
    }

    @Test
    @DisplayName("真实 Redis 上限流 Lua 原子递增，首次设置 TTL 且已有 TTL 不被重置")
    void rateCounterIncrementsAndKeepsExistingTtlAgainstRealRedis() {
        assumeTrue(target != null, SKIP_REASON);
        String rateKey = key("rate:session:" + SESSION_ID);
        RedisRateCounter counter = new RedisRateCounter(redis());

        // 首次递增：INCR 到 1，并在同一条 Lua 里设置窗口 TTL
        assertThat(counter.increment(rateKey, TTL_SECONDS)).isEqualTo(1L);
        assertThat(redis().opsForValue().get(rateKey)).isEqualTo("1");
        assertThat(redis().getExpire(rateKey)).isBetween(1L, (long) TTL_SECONDS);

        // 人为把既有 TTL 改小到 10 秒，模拟「键已存在且带 TTL」
        redis().expire(rateKey, Duration.ofSeconds(REDUCED_TTL_SECONDS));
        assertThat(redis().getExpire(rateKey)).isLessThanOrEqualTo((long) REDUCED_TTL_SECONDS);

        // 再次递增：Lua 的 TTL<0 守卫应跳过 EXPIRE，已有 TTL 绝不被重置回 300 秒
        assertThat(counter.increment(rateKey, 300)).isEqualTo(2L);
        assertThat(redis().opsForValue().get(rateKey)).isEqualTo("2");
        assertThat(redis().getExpire(rateKey)).isLessThanOrEqualTo((long) REDUCED_TTL_SECONDS);
    }

    // ------------------------------------------------------------------ //
    // 连接与辅助
    // ------------------------------------------------------------------ //

    private RedisSessionRepository repository() {
        return new RedisSessionRepository(redis(), TTL_SECONDS, 20);
    }

    private StringRedisTemplate redis() {
        if (template == null) {
            connectionFactory = connect(target);
            template = new StringRedisTemplate(connectionFactory);
            template.afterPropertiesSet();
        }
        return template;
    }

    private static LettuceConnectionFactory connect(Target target) {
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(target.host(), target.port());
        standalone.setDatabase(target.database());
        if (target.username() != null) {
            standalone.setUsername(target.username());
        }
        if (target.password() != null) {
            standalone.setPassword(RedisPassword.of(target.password()));
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(5));
        LettuceClientConfiguration clientConfiguration =
                target.ssl() ? builder.useSsl().build() : builder.build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, clientConfiguration);
        factory.afterPropertiesSet();
        return factory;
    }

    private String key(String suffix) {
        String key = prefix + suffix;
        createdKeys.add(key);
        return key;
    }

    private static Function<String, String> env(String testRedisUrl) {
        return key -> ENV_TEST_REDIS_URL.equals(key) ? testRedisUrl : null;
    }

    /** 断言显式非法配置抛 {@link IllegalArgumentException}，并返回其（固定、不含配置值的）异常信息。 */
    private static String rejectionMessage(String raw) {
        Throwable thrown = catchThrowable(() -> Target.resolve(env(raw)));

        assertThat(thrown)
                .as("显式非法配置 %s 必须抛 IllegalArgumentException 而不是被跳过", raw)
                .isInstanceOf(IllegalArgumentException.class);
        return thrown.getMessage();
    }

    private static SessionSnapshot snapshot(String role, String content) {
        return new SessionSnapshot(List.of(new SessionMessage(role, content)), List.of());
    }

    private static ProductCard card() {
        return new ProductCard(
                27L,
                "示例手机 B",
                "http://localhost:9000/mall/example-27.jpg",
                "2999.00",
                "示例副标题 B",
                "IN_STOCK",
                112,
                "/pages/product/product?id=27");
    }

    /**
     * 测试 Redis 目标：只从 {@code MALL_AGENT_TEST_REDIS_URL} 解析。
     *
     * <p>解析契约（纯函数，绝不建立连接）：
     * <ul>
     *   <li>变量缺失或纯空白 → {@link Optional#empty()}，调用方据此跳过真实集成测试；</li>
     *   <li>变量非空但不合法/不被允许 → 抛出固定、不含配置值的 {@link IllegalArgumentException}，
     *       让错误配置明确失败，而不是被 {@code assumeTrue} 静默跳过；</li>
     *   <li>只接受本机 loopback host：{@code localhost}、{@code 127.0.0.0/8}、IPv6 {@code ::1}，
     *       显式远端 host 一律拒绝；</li>
     *   <li>只接受 {@code redis}/{@code rediss} 协议、显式非 0 库，并拒绝 query/fragment。</li>
     * </ul>
     *
     * <p>{@link #safeDescription()} 与异常信息都刻意不包含 user-info，方便写进跳过原因或日志而不泄漏凭证。
     */
    record Target(String host, int port, int database, String username, String password, boolean ssl) {

        /** 固定异常信息：只描述规则，绝不回显原始 URL、host、端口、库号或凭证。 */
        private static final String INVALID_TARGET_MESSAGE =
                "显式配置的 " + ENV_TEST_REDIS_URL
                        + " 非法或不被允许：只接受本机 loopback（localhost / 127.0.0.0/8 / ::1）"
                        + "且显式指定非 0 库的 redis 或 rediss 地址";

        static Optional<Target> resolve(Function<String, String> lookup) {
            String raw = lookup.apply(ENV_TEST_REDIS_URL);
            // 只有「未配置」与「纯空白」才表示未启用集成测试
            if (raw == null || raw.strip().isEmpty()) {
                return Optional.empty();
            }
            URI uri;
            try {
                uri = new URI(raw.strip());
            } catch (URISyntaxException ex) {
                throw invalidTarget();
            }
            String scheme = uri.getScheme();
            boolean ssl;
            if ("redis".equalsIgnoreCase(scheme)) {
                ssl = false;
            } else if ("rediss".equalsIgnoreCase(scheme)) {
                ssl = true;
            } else {
                throw invalidTarget();
            }
            // query/fragment 一律拒绝：避免用参数伪装目标或绕过显式库号约束
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw invalidTarget();
            }
            // Task11 只允许用户提供的本机测试 Redis：显式 remote host 必须失败而不是被静默跳过
            String host = stripIpv6Brackets(uri.getHost());
            if (!isLoopbackHost(host)) {
                throw invalidTarget();
            }
            int port = uri.getPort() == -1 ? 6379 : uri.getPort();
            if (port < 1 || port > 65535) {
                throw invalidTarget();
            }
            int database = parseDatabase(uri.getRawPath());
            // 必须显式使用非 0 号库：避免把集成测试写到默认库甚至生产库
            if (database < 1) {
                throw invalidTarget();
            }
            String username = null;
            String password = null;
            String userInfo = uri.getRawUserInfo();
            if (userInfo != null && !userInfo.isEmpty()) {
                int separator = userInfo.indexOf(':');
                if (separator < 0) {
                    username = userInfo;
                } else {
                    username = userInfo.substring(0, separator);
                    password = userInfo.substring(separator + 1);
                }
            }
            return Optional.of(new Target(host, port, database, username, password, ssl));
        }

        private static IllegalArgumentException invalidTarget() {
            return new IllegalArgumentException(INVALID_TARGET_MESSAGE);
        }

        /** 解析 {@code /<db>} 路径段；缺省或 {@code /} 视为 0 号库，非数字一律判为非法配置。 */
        private static int parseDatabase(String path) {
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return 0;
            }
            try {
                return Integer.parseInt(path.substring(1));
            } catch (NumberFormatException ex) {
                throw invalidTarget();
            }
        }

        /** {@link URI#getHost()} 对 IPv6 字面量返回带方括号的形式，这里统一归一化。 */
        private static String stripIpv6Brackets(String host) {
            if (host != null && host.length() > 1 && host.startsWith("[") && host.endsWith("]")) {
                return host.substring(1, host.length() - 1);
            }
            return host;
        }

        /** 只接受本机 loopback：{@code localhost}、{@code 127.0.0.0/8} 字面量与 IPv6 {@code ::1}。 */
        private static boolean isLoopbackHost(String host) {
            if (host == null || host.isEmpty()) {
                return false;
            }
            return "localhost".equalsIgnoreCase(host) || "::1".equals(host) || isIpv4Loopback(host);
        }

        /** 纯字符串校验 IPv4 字面量是否落在 loopback 网段 127.0.0.0/8，不做任何 DNS/IP 解析。 */
        private static boolean isIpv4Loopback(String host) {
            String[] octets = host.split("\\.", -1);
            if (octets.length != 4) {
                return false;
            }
            int firstOctet = -1;
            for (int index = 0; index < octets.length; index++) {
                String octet = octets[index];
                if (octet.isEmpty() || octet.length() > 3 || !isAsciiDigits(octet)) {
                    return false;
                }
                int value = Integer.parseInt(octet);
                if (value > 255) {
                    return false;
                }
                if (index == 0) {
                    firstOctet = value;
                }
            }
            return firstOctet == 127;
        }

        private static boolean isAsciiDigits(String value) {
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character < '0' || character > '9') {
                    return false;
                }
            }
            return true;
        }

        String safeDescription() {
            return (ssl ? "rediss://" : "redis://") + host + ":" + port + "/" + database;
        }

        @Override
        public String toString() {
            return "Target{" + safeDescription() + "}";
        }
    }
}

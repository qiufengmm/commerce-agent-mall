package com.macro.mall.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.macro.mall.agent.api.ProductCard;
import com.macro.mall.agent.api.SessionMessage;

/**
 * {@link RedisSessionRepository} 的 TDD 契约测试（计划 Task 6 Step 2）。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.session.redis_repository} 与
 * {@code session.repository}：
 * <ul>
 *   <li>{@code GETEX key EX ttl} 等价：单条原子操作读取并续期，不使用 {@code GET} + {@code EXPIRE} 两步；</li>
 *   <li>{@code SET key value EX ttl} 保存，并对消息/卡片做裁剪；</li>
 *   <li>坏 JSON、未知字段、非法消息一律删除键并返回空快照；</li>
 *   <li>Redis 连接/操作故障必须原样抛出，不能伪装成空会话；</li>
 *   <li>{@code copy} 严格复刻 Python：源内容非空才写目标，最后删除源键，方法自身不判断目标是否已有内容。</li>
 * </ul>
 *
 * <p>Redis 用 Mockito 替身加内存 {@code backing} 映射模拟，不连接任何真实 Redis。
 * {@link #PYTHON_DOCUMENT} 与 {@link #PYTHON_EMPTY_DOCUMENT} 是 Python
 * {@code SessionSnapshot.model_dump_json()} 的实测输出（一次性探针生成），用于断言双向 JSON 兼容。
 */
class RedisSessionRepositoryTest {

    private static final String SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c";
    private static final String GUEST_KEY = "mall:agent:session:guest:" + SESSION_ID;
    private static final String MEMBER_KEY = "mall:agent:session:member:7:" + SESSION_ID;
    private static final String MEMBER_TOKEN = "Bearer placeholder-member-token";

    private static final int TTL_SECONDS = 86400;
    private static final int MAX_MESSAGES = 20;
    private static final Duration TTL = Duration.ofSeconds(TTL_SECONDS);

    /** Python 探针实测的 {@code SessionSnapshot.model_dump_json()} 输出。 */
    private static final String PYTHON_DOCUMENT = """
            {"messages":[{"role":"user","content":"有什么手机"},{"role":"assistant","content":"为您找到三款"}],\
            "products":[{"id":27,"name":"示例手机 B","pic":"http://localhost:9000/mall/example-27.jpg",\
            "price":"2999.00","subtitle":"示例副标题 B","stockStatus":"IN_STOCK","availableStock":112,\
            "detailPath":"/pages/product/product?id=27"}]}""";

    private static final String PYTHON_EMPTY_DOCUMENT = "{\"messages\":[],\"products\":[]}";

    private StringRedisTemplate template;
    private ValueOperations<String, String> values;
    private Map<String, String> backing;
    private RedisSessionRepository repository;

    @BeforeEach
    void setUp() {
        template = mock(StringRedisTemplate.class);
        values = stringValueOperations();
        backing = new LinkedHashMap<>();
        given(template.opsForValue()).willReturn(values);
        given(values.getAndExpire(anyString(), any(Duration.class)))
                .willAnswer(invocation -> backing.get(invocation.<String>getArgument(0)));
        willAnswer(invocation -> {
            backing.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).given(values).set(anyString(), anyString(), any(Duration.class));
        given(template.delete(anyString()))
                .willAnswer(invocation -> backing.remove(invocation.<String>getArgument(0)) != null);
        repository = new RedisSessionRepository(template, TTL_SECONDS, MAX_MESSAGES);
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> stringValueOperations() {
        return (ValueOperations<String, String>) mock(ValueOperations.class);
    }

    // ------------------------------------------------------------------ //
    // JSON 兼容：读 Python 文档 / 写 Python 兼容文档
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("可以读取 Python 写入的会话文档（messages/products 与卡片字段完全一致）")
    void pythonWrittenDocumentIsReadable() {
        backing.put(GUEST_KEY, PYTHON_DOCUMENT);

        SessionSnapshot loaded = repository.load(GUEST_KEY);

        assertThat(loaded.messages()).extracting(SessionMessage::role)
                .containsExactly("user", "assistant");
        assertThat(loaded.messages()).extracting(SessionMessage::content)
                .containsExactly("有什么手机", "为您找到三款");
        assertThat(loaded.products()).hasSize(1);
        ProductCard card = loaded.products().get(0);
        assertThat(card.id()).isEqualTo(27L);
        assertThat(card.name()).isEqualTo("示例手机 B");
        assertThat(card.pic()).isEqualTo("http://localhost:9000/mall/example-27.jpg");
        assertThat(card.price()).isEqualTo("2999.00");
        assertThat(card.subtitle()).isEqualTo("示例副标题 B");
        assertThat(card.stockStatus()).isEqualTo("IN_STOCK");
        assertThat(card.availableStock()).isEqualTo(112);
        assertThat(card.detailPath()).isEqualTo("/pages/product/product?id=27");
    }

    @Test
    @DisplayName("写回的 JSON 与 Python model_dump_json 完全一致（字段名、顺序、类型）")
    void writtenDocumentMatchesPythonSerialisation() throws Exception {
        repository.save(GUEST_KEY, new SessionSnapshot(
                List.of(new SessionMessage("user", "有什么手机"),
                        new SessionMessage("assistant", "为您找到三款")),
                List.of(new ProductCard(
                        27L,
                        "示例手机 B",
                        "http://localhost:9000/mall/example-27.jpg",
                        "2999.00",
                        "示例副标题 B",
                        "IN_STOCK",
                        112,
                        "/pages/product/product?id=27"))));

        String written = backing.get(GUEST_KEY);

        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(written)).isEqualTo(mapper.readTree(PYTHON_DOCUMENT));
        assertThat(written).isEqualTo(PYTHON_DOCUMENT);
    }

    @Test
    @DisplayName("Python 的空文档是合法空快照，不触发删除")
    void pythonEmptyDocumentIsValidEmptySnapshot() {
        backing.put(GUEST_KEY, PYTHON_EMPTY_DOCUMENT);

        assertThat(repository.load(GUEST_KEY).isEmpty()).isTrue();
        then(template).should(never()).delete(anyString());
    }

    @Test
    @DisplayName("可选卡片字段为 null 或缺省时仍可读，缺省 name 回落到空串")
    void optionalCardFieldsFollowPythonDefaults() {
        backing.put(GUEST_KEY, """
                {"messages":[],"products":[{"id":27,"pic":null,"price":null,"subtitle":null,\
                "stockStatus":"IN_STOCK","availableStock":0,"detailPath":"/pages/product/product?id=27"}]}""");

        ProductCard card = repository.load(GUEST_KEY).products().get(0);

        assertThat(card.name()).isEmpty();
        assertThat(card.pic()).isNull();
        assertThat(card.price()).isNull();
        assertThat(card.subtitle()).isNull();
        assertThat(card.availableStock()).isZero();
    }

    @Test
    @DisplayName("缺失 products 字段时按空列表处理")
    void missingProductFieldMeansNoCards() {
        backing.put(GUEST_KEY, "{\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}");

        SessionSnapshot loaded = repository.load(GUEST_KEY);

        assertThat(loaded.products()).isEmpty();
        assertThat(loaded.messages()).hasSize(1);
    }

    // ------------------------------------------------------------------ //
    // GETEX 续期 / SET EX 保存 / 删除幂等
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("读取使用单条 GETEX 等价原子操作续期，不使用 GET + EXPIRE 两步")
    void loadRenewsTtlWithASingleAtomicRead() {
        backing.put(GUEST_KEY, PYTHON_DOCUMENT);

        repository.load(GUEST_KEY);

        then(values).should(times(1)).getAndExpire(GUEST_KEY, TTL);
        then(values).should(never()).get(anyString());
    }

    @Test
    @DisplayName("不存在的键返回空快照，且不删除任何键")
    void missingKeyReturnsEmptySnapshot() {
        assertThat(repository.load(GUEST_KEY).isEmpty()).isTrue();

        then(values).should().getAndExpire(GUEST_KEY, TTL);
        then(template).should(never()).delete(anyString());
    }

    @Test
    @DisplayName("保存使用 SET EX 等价操作设置 TTL，并对消息与卡片裁剪")
    void saveWritesTrimmedDocumentWithTtl() {
        List<SessionMessage> messages = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            messages.add(new SessionMessage("user", "消息 " + index));
        }
        List<ProductCard> cards = new ArrayList<>();
        for (long id = 1; id <= 8; id++) {
            cards.add(card(id));
        }

        repository.save(GUEST_KEY, new SessionSnapshot(messages, cards));

        then(values).should(times(1)).set(eq(GUEST_KEY), anyString(), eq(TTL));
        SessionSnapshot stored = repository.load(GUEST_KEY);
        assertThat(stored.messages()).hasSize(MAX_MESSAGES);
        assertThat(stored.messages().get(0).content()).isEqualTo("消息 10");
        assertThat(stored.messages().get(MAX_MESSAGES - 1).content()).isEqualTo("消息 29");
        assertThat(stored.products()).extracting(ProductCard::id)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    @DisplayName("删除幂等：不存在返回 false，删除后再次删除仍返回 false")
    void deleteIsIdempotent() {
        assertThat(repository.delete(GUEST_KEY)).isFalse();

        repository.save(GUEST_KEY, snapshot("user", "你好"));
        assertThat(repository.delete(GUEST_KEY)).isTrue();
        assertThat(repository.delete(GUEST_KEY)).isFalse();
    }

    // ------------------------------------------------------------------ //
    // copy 语义：完全复刻 Python
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("copy：源非空时复制到目标并删除源键")
    void copyWithContentCopiesThenDeletesSource() {
        repository.save(GUEST_KEY, snapshot("user", "有什么手机"));

        repository.copy(GUEST_KEY, MEMBER_KEY);

        assertThat(backing).doesNotContainKey(GUEST_KEY);
        assertThat(repository.load(MEMBER_KEY).messages())
                .extracting(SessionMessage::content)
                .containsExactly("有什么手机");
    }

    @Test
    @DisplayName("copy：源为空时不写目标，也不影响目标原有内容")
    void copyWithEmptySourceDoesNotTouchTarget() {
        repository.save(MEMBER_KEY, snapshot("user", "会员消息"));
        clearInvocations(values, template);

        repository.copy(GUEST_KEY, MEMBER_KEY);

        then(values).should(never()).set(anyString(), anyString(), any(Duration.class));
        assertThat(repository.load(MEMBER_KEY).messages())
                .extracting(SessionMessage::content)
                .containsExactly("会员消息");
    }

    @Test
    @DisplayName("copy 自身不判断目标：目标已有内容时由上层（IdentityResolver）阻止覆盖")
    void copyDoesNotGuardTargetItself() {
        repository.save(GUEST_KEY, snapshot("user", "游客问题"));
        repository.save(MEMBER_KEY, snapshot("user", "会员问题"));

        repository.copy(GUEST_KEY, MEMBER_KEY);

        assertThat(repository.load(MEMBER_KEY).messages())
                .extracting(SessionMessage::content)
                .containsExactly("游客问题");
        assertThat(backing).doesNotContainKey(GUEST_KEY);
    }

    // ------------------------------------------------------------------ //
    // 文档损坏与 Redis 故障的区分
    // ------------------------------------------------------------------ //

    @ParameterizedTest(name = "坏文档被删除并变成空快照：{0}")
    @ValueSource(strings = {
            "{not json",
            "[1,2,3]",
            "{\"messages\":null,\"products\":[]}",
            "{\"messages\":[],\"products\":null}",
            "{\"messages\":[{\"role\":\"system\",\"content\":\"x\"}],\"products\":[]}",
            "{\"messages\":[{\"role\":\"user\",\"content\":\"\"}],\"products\":[]}",
            "{\"messages\":[{\"role\":\"user\",\"content\":\"x\",\"tool_calls\":[]}],\"products\":[]}",
            "{\"messages\":[],\"products\":[],\"token\":\"must-not-exist\"}",
            "{\"messages\":[],\"products\":[],\"authorization\":\"Bearer x\"}",
            "{\"messages\":[{\"role\":\"user\"}],\"products\":[]}",
            "{\"messages\":[{\"role\":\"user\",\"content\":123}],\"products\":[]}",
            "{\"messages\":[],\"products\":[]}{\"messages\":[]}",
            "{\"messages\":\"not-an-array\",\"products\":[]}",
    })
    @DisplayName("坏 JSON / 未知字段 / 非法消息一律删除键并返回空快照")
    void corruptDocumentIsDeletedAndBecomesEmptySnapshot(String document) {
        backing.put(GUEST_KEY, document);

        SessionSnapshot loaded = repository.load(GUEST_KEY);

        assertThat(loaded.isEmpty()).isTrue();
        assertThat(backing).doesNotContainKey(GUEST_KEY);
        then(template).should(times(1)).delete(GUEST_KEY);
    }

    @Test
    @DisplayName("卡片带未知敏感字段或缺少必填字段时按损坏处理")
    void cardWithUnknownOrMissingFieldsIsCorrupt() {
        backing.put(GUEST_KEY, """
                {"messages":[{"role":"user","content":"x"}],"products":\
                [{"id":27,"stockStatus":"IN_STOCK","availableStock":1,"detailPath":"/p",\
                "authorization":"Bearer x"}]}""");
        assertThat(repository.load(GUEST_KEY).isEmpty()).isTrue();

        backing.put(GUEST_KEY, """
                {"messages":[{"role":"user","content":"x"}],"products":\
                [{"id":27,"stockStatus":"IN_STOCK","availableStock":1}]}""");
        assertThat(repository.load(GUEST_KEY).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("超过 4000 码点的消息内容按损坏处理，恰好 4000 码点可读")
    void messageContentLengthBoundaryMatchesPython() {
        String tooLong = "a".repeat(4001);
        backing.put(GUEST_KEY, "{\"messages\":[{\"role\":\"user\",\"content\":\"" + tooLong
                + "\"}],\"products\":[]}");
        assertThat(repository.load(GUEST_KEY).isEmpty()).isTrue();

        String atLimit = "a".repeat(4000);
        backing.put(GUEST_KEY, "{\"messages\":[{\"role\":\"user\",\"content\":\"" + atLimit
                + "\"}],\"products\":[]}");
        assertThat(repository.load(GUEST_KEY).messages()).hasSize(1);
    }

    @Test
    @DisplayName("Redis 连接故障原样抛出，不伪装成空会话，也不删除键")
    void redisFailureIsNotMaskedAsEmptySnapshot() {
        given(values.getAndExpire(anyString(), any(Duration.class)))
                .willThrow(new RedisConnectionFailureException("redis down"));

        assertThatThrownBy(() -> repository.load(GUEST_KEY))
                .isInstanceOf(RedisConnectionFailureException.class);
        then(template).should(never()).delete(anyString());
    }

    // ------------------------------------------------------------------ //
    // 敏感字段
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("会话文档只包含允许字段，不含 Token / Authorization / 工具载荷")
    void documentNeverContainsSensitiveFields() {
        repository.save(MEMBER_KEY, new SessionSnapshot(
                List.of(new SessionMessage("user", "我的优惠券"),
                        new SessionMessage("assistant", "您有一张满减券")),
                List.of(card(27))));

        String document = backing.get(MEMBER_KEY).toLowerCase(Locale.ROOT);

        assertThat(document)
                .doesNotContain("authorization")
                .doesNotContain("bearer")
                .doesNotContain("token")
                .doesNotContain(MEMBER_TOKEN.toLowerCase(Locale.ROOT))
                .doesNotContain("tool_calls")
                .doesNotContain("toolcalls")
                .doesNotContain("password");
        assertThat(backing.keySet()).allSatisfy(key -> assertThat(key).doesNotContain("Bearer"));
    }

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

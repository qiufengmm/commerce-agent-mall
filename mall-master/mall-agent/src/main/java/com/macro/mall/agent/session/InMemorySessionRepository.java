package com.macro.mall.agent.session;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存会话仓储，语义与 {@link RedisSessionRepository} 及 Python
 * {@code MemorySessionRepository} 保持一致：读取续期、写入 SET EX 等价语义、消息裁剪、
 * 删除幂等、迁移后删除源键、坏文档删除并返回空快照。
 *
 * <p>只用于单元测试与本地演示，不参与生产装配；键内容与 Redis 版本一样是 JSON 文档字符串，
 * 便于测试断言「文档里不含 Token」这类安全约束。
 */
public final class InMemorySessionRepository implements SessionRepository {

    private final int ttlSeconds;
    private final int maxMessages;
    private final Clock clock;
    private final Map<String, Entry> documents = new LinkedHashMap<>();

    public InMemorySessionRepository(int ttlSeconds, int maxMessages, Clock clock) {
        this.ttlSeconds = Math.max(1, ttlSeconds);
        this.maxMessages = maxMessages;
        this.clock = clock;
    }

    public InMemorySessionRepository() {
        this(86400, 20, Clock.systemUTC());
    }

    @Override
    public SessionSnapshot load(String key) {
        String payload = livePayload(key);
        if (payload == null) {
            return SessionSnapshot.empty();
        }
        // 每次有效访问续期
        documents.put(key, new Entry(payload, expiresAtMillis()));
        try {
            return SessionDocumentCodec.decode(payload);
        } catch (SessionDocumentCodec.InvalidDocument ex) {
            documents.remove(key);
            return SessionSnapshot.empty();
        }
    }

    @Override
    public void save(String key, SessionSnapshot snapshot) {
        String payload = SessionDocumentCodec.encode(snapshot.trimmed(maxMessages));
        documents.put(key, new Entry(payload, expiresAtMillis()));
    }

    @Override
    public boolean delete(String key) {
        purge(key);
        return documents.remove(key) != null;
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        String payload = livePayload(sourceKey);
        if (payload != null) {
            documents.put(targetKey, new Entry(payload, expiresAtMillis()));
        }
        documents.remove(sourceKey);
    }

    /** 当前未过期键，按写入顺序返回。 */
    public List<String> keys() {
        purgeExpired();
        return new ArrayList<>(documents.keySet());
    }

    /** 直接读取原始 JSON 文档（不续期、不清理），与 Python {@code raw} 一致。 */
    public String raw(String key) {
        Entry entry = documents.get(key);
        return entry == null ? null : entry.payload();
    }

    /** 剩余 TTL 秒数；键不存在时为 {@code null}。 */
    public Double ttlSeconds(String key) {
        purge(key);
        Entry entry = documents.get(key);
        if (entry == null) {
            return null;
        }
        return Math.max(entry.expiresAtMillis() - clock.millis(), 0L) / 1000.0;
    }

    private String livePayload(String key) {
        purge(key);
        Entry entry = documents.get(key);
        return entry == null ? null : entry.payload();
    }

    private void purge(String key) {
        Entry entry = documents.get(key);
        if (entry != null && clock.millis() >= entry.expiresAtMillis()) {
            documents.remove(key);
        }
    }

    private void purgeExpired() {
        for (String key : new ArrayList<>(documents.keySet())) {
            purge(key);
        }
    }

    private long expiresAtMillis() {
        return clock.millis() + ttlSeconds * 1000L;
    }

    private record Entry(String payload, long expiresAtMillis) {
    }
}

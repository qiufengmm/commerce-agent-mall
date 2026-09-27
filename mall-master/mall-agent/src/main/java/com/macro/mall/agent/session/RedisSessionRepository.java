package com.macro.mall.agent.session;

import java.time.Duration;
import java.util.Objects;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 会话仓储，语义对齐 Python {@code mall_shopping_agent.session.redis_repository.RedisSessionRepository}。
 *
 * <ul>
 *   <li>读取用 {@code GETEX key EX ttl} 等价操作（Spring Data Redis
 *       {@link org.springframework.data.redis.core.ValueOperations#getAndExpire(Object, Duration)}）：
 *       单条命令内完成「取值 + 续期」，避免 GET 与 EXPIRE 两步之间的竞态；</li>
 *   <li>写入用 {@code SET key value EX ttl} 等价的
 *       {@link org.springframework.data.redis.core.ValueOperations#set(Object, Object, Duration)}；</li>
 *   <li>文档损坏（非法 JSON、未知字段、非法消息）时删除该键并返回空快照，与 Python
 *       {@code except ValueError: await client.delete(key)} 一致；</li>
 *   <li>Redis 连接或命令故障<strong>不捕获</strong>：异常原样向上传播，绝不会被伪装成空会话，
 *       否则限流/会话判断会在 Redis 故障时静默放行或静默丢上下文；</li>
 *   <li>{@link #copy(String, String)} 与 Python 一致：源内容非空才写目标，最后删除源键，
 *       本方法<strong>不</strong>判断目标是否已有内容（防覆盖在 {@link IdentityResolver}）。</li>
 * </ul>
 *
 * <p>本类只做方法调用时的 Redis 访问，构造与 Bean 装配不建立任何连接。
 */
public final class RedisSessionRepository implements SessionRepository {

    private final StringRedisTemplate template;
    private final int ttlSeconds;
    private final int maxMessages;

    public RedisSessionRepository(StringRedisTemplate template, int ttlSeconds, int maxMessages) {
        this.template = Objects.requireNonNull(template, "template 不能为空");
        this.ttlSeconds = Math.max(1, ttlSeconds);
        this.maxMessages = maxMessages;
    }

    @Override
    public SessionSnapshot load(String key) {
        String payload = template.opsForValue().getAndExpire(key, Duration.ofSeconds(ttlSeconds));
        if (payload == null) {
            return SessionSnapshot.empty();
        }
        try {
            return SessionDocumentCodec.decode(payload);
        } catch (SessionDocumentCodec.InvalidDocument ex) {
            // 与 Python 一致：坏文档删除键并返回空快照，不保留损坏内容
            template.delete(key);
            return SessionSnapshot.empty();
        }
    }

    @Override
    public void save(String key, SessionSnapshot snapshot) {
        String payload = SessionDocumentCodec.encode(snapshot.trimmed(maxMessages));
        template.opsForValue().set(key, payload, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public boolean delete(String key) {
        return Boolean.TRUE.equals(template.delete(key));
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        SessionSnapshot snapshot = load(sourceKey);
        if (!snapshot.isEmpty()) {
            save(targetKey, snapshot);
        }
        delete(sourceKey);
    }
}

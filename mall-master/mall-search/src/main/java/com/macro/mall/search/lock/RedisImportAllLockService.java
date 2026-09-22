package com.macro.mall.search.lock;

import com.macro.mall.search.config.ImportAllLockProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis SET NX EX 的 importAll 分布式锁。
 * 释放和续租均校验 owner，避免误删其它实例的新锁。
 */
@Service
public class RedisImportAllLockService implements ImportAllLockService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisImportAllLockService.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('expire', KEYS[1], ARGV[2]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ImportAllLockProperties properties;
    private final ScheduledExecutorService scheduler;

    @Autowired
    public RedisImportAllLockService(StringRedisTemplate redisTemplate, ImportAllLockProperties properties) {
        this(redisTemplate, properties, newScheduler());
    }

    RedisImportAllLockService(StringRedisTemplate redisTemplate,
                              ImportAllLockProperties properties,
                              ScheduledExecutorService scheduler) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    @Override
    public ImportAllLock tryAcquire() {
        if (!properties.isEnabled()) {
            return ImportAllLock.noop();
        }
        String key = requireKey();
        String owner = UUID.randomUUID().toString();
        long leaseSeconds = normalizedLeaseSeconds();
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, owner, Duration.ofSeconds(leaseSeconds));
            if (!Boolean.TRUE.equals(acquired)) {
                throw new ImportAllLockException("商品索引全量导入正在其它实例执行中，请稍后重试");
            }
        } catch (ImportAllLockException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ImportAllLockException("Redis 分布式锁不可用，已拒绝执行商品索引全量导入", ex);
        }

        RedisLockHandle handle = new RedisLockHandle(key, owner, leaseSeconds);
        try {
            handle.renewalFuture = scheduler.scheduleAtFixedRate(
                    handle::renew,
                    normalizedRenewIntervalSeconds(),
                    normalizedRenewIntervalSeconds(),
                    TimeUnit.SECONDS);
            return handle;
        } catch (RuntimeException ex) {
            handle.release();
            throw new ImportAllLockException("无法启动 Redis 分布式锁续租，已拒绝执行商品索引全量导入", ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private String requireKey() {
        if (properties.getKey() == null || properties.getKey().isBlank()) {
            throw new ImportAllLockException("Redis 分布式锁 key 未配置");
        }
        return properties.getKey();
    }

    private long normalizedLeaseSeconds() {
        return Math.max(2, properties.getLeaseSeconds());
    }

    private long normalizedRenewIntervalSeconds() {
        return Math.max(1, Math.min(properties.getRenewIntervalSeconds(), normalizedLeaseSeconds() - 1));
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mall-search-import-lock-renew");
            thread.setDaemon(true);
            return thread;
        });
    }

    private final class RedisLockHandle implements ImportAllLock {
        private final String key;
        private final String owner;
        private final long leaseSeconds;
        private volatile boolean held = true;
        private volatile boolean closed;
        private ScheduledFuture<?> renewalFuture;

        private RedisLockHandle(String key, String owner, long leaseSeconds) {
            this.key = key;
            this.owner = owner;
            this.leaseSeconds = leaseSeconds;
        }

        @Override
        public boolean isHeld() {
            return held && !closed;
        }

        private void renew() {
            if (!isHeld()) {
                return;
            }
            try {
                Long result = redisTemplate.execute(RENEW_SCRIPT, Collections.singletonList(key),
                        owner, String.valueOf(leaseSeconds));
                if (!Objects.equals(result, 1L)) {
                    held = false;
                    LOGGER.warn("Redis importAll 锁续租失败，后续将禁止清理陈旧 ES 文档");
                }
            } catch (RuntimeException ex) {
                held = false;
                LOGGER.warn("Redis importAll 锁续租异常，后续将禁止清理陈旧 ES 文档", ex);
            }
        }

        private void release() {
            if (!held) {
                return;
            }
            try {
                redisTemplate.execute(RELEASE_SCRIPT, List.of(key), owner);
            } catch (RuntimeException ex) {
                LOGGER.warn("Redis importAll 锁释放异常，锁将依赖 TTL 自动过期", ex);
            } finally {
                held = false;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (renewalFuture != null) {
                renewalFuture.cancel(false);
            }
            release();
        }
    }
}

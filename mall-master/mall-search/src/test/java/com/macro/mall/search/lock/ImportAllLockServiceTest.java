package com.macro.mall.search.lock;

import com.macro.mall.search.config.ImportAllLockProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportAllLockServiceTest {
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> renewalFuture;
    private ImportAllLockProperties properties;
    private RedisImportAllLockService lockService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        scheduler = mock(ScheduledExecutorService.class);
        renewalFuture = mock(ScheduledFuture.class);
        properties = new ImportAllLockProperties();
        properties.setKey("test:import-all");
        properties.setLeaseSeconds(60);
        properties.setRenewIntervalSeconds(10);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doReturn(renewalFuture).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), eq(10L), eq(10L), eq(TimeUnit.SECONDS));
        lockService = new RedisImportAllLockService(redisTemplate, properties, scheduler);
    }

    @Test
    void acquiresOwnerWithLeaseAndReleasesItOnClose() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(60))))
                .thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("test:import-all")), any(Object[].class)))
                .thenReturn(1L);

        ImportAllLock lock = lockService.tryAcquire();

        assertTrue(lock.isHeld());
        verify(valueOperations).setIfAbsent(eq("test:import-all"), anyString(), eq(Duration.ofSeconds(60)));
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(10L), eq(10L), eq(TimeUnit.SECONDS));

        lock.close();

        assertFalse(lock.isHeld());
        verify(renewalFuture).cancel(false);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("test:import-all")), any(Object[].class));
    }

    @Test
    void rejectsWhenAnotherOwnerAlreadyHoldsTheKey() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(60))))
                .thenReturn(false);

        assertThrows(ImportAllLockException.class, () -> lockService.tryAcquire());

        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void convertsRedisAcquireFailureToControlledLockException() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(60))))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThrows(ImportAllLockException.class, () -> lockService.tryAcquire());
    }

    @Test
    void marksHandleLostWhenRenewalIsRejected() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(60))))
                .thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);
        ArgumentCaptor<Runnable> renewalCaptor = ArgumentCaptor.forClass(Runnable.class);

        ImportAllLock lock = lockService.tryAcquire();
        verify(scheduler).scheduleAtFixedRate(renewalCaptor.capture(), eq(10L), eq(10L), eq(TimeUnit.SECONDS));

        renewalCaptor.getValue().run();

        assertFalse(lock.isHeld());
    }
}

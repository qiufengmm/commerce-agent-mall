package com.macro.mall.agent.deadline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RequestDeadline} 与 {@link RequestDeadlineContext} 的纯逻辑测试。
 *
 * <p>全部使用注入的单调 ticker，不依赖墙钟、不 sleep、不访问网络；并发隔离用两个线程各自持有
 * 独立 deadline 验证 ThreadLocal 不跨线程共享。
 */
class DeadlineTest {

    @Test
    @DisplayName("剩余预算随单调 ticker 递减，越过 deadline 后恒为 0 且 isExpired 为 true")
    void remainingFollowsInjectedMonotonicTicker() {
        AtomicLong ticker = new AtomicLong(1_000_000_000L);
        RequestDeadline deadline = RequestDeadline.start(2.0, ticker::get);

        assertThat(deadline.isExpired()).isFalse();
        assertThat(deadline.remaining()).isEqualTo(Duration.ofSeconds(2));

        ticker.addAndGet(1_500_000_000L);
        assertThat(deadline.isExpired()).isFalse();
        assertThat(deadline.remaining()).isEqualTo(Duration.ofMillis(500));

        ticker.addAndGet(600_000_000L);
        assertThat(deadline.isExpired()).isTrue();
        assertThat(deadline.remaining()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("非正的超时配置被拒绝，避免产生语义不明的 deadline")
    void startRejectsNonPositiveTimeout() {
        assertThatThrownBy(() -> RequestDeadline.start(0.0, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestDeadline.start(-1.0, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("请求级截断标记在每次请求开始时重置，不被更早一次被截断的请求污染")
    void requestClampMarkerResetsPerRequest() {
        RequestDeadline deadline = RequestDeadline.start(30.0, () -> 0L);

        // 请求开始：标记为 false；本次请求被 deadline 截断后置为 true
        deadline.beginRequest();
        assertThat(deadline.isLastRequestClamped()).isFalse();
        deadline.markRequestClamped();
        assertThat(deadline.isLastRequestClamped()).isTrue();

        // 新一次请求（未被总预算截断）必须清除上一次的标记，绝不累计保持为 true
        deadline.beginRequest();
        assertThat(deadline.isLastRequestClamped())
                .as("请求级标记不得跨请求累计，否则会把后续 per-client 超时误判为 deadline 超时")
                .isFalse();
    }

    @Test
    @DisplayName("RequestDeadlineContext 仅在 action 执行期间可见，返回后必然移除")
    void contextVisibleOnlyDuringAction() {
        assertThat(RequestDeadlineContext.current()).isNull();
        RequestDeadline deadline = RequestDeadline.start(5.0, () -> 0L);

        String value = RequestDeadlineContext.callWith(deadline, () -> {
            assertThat(RequestDeadlineContext.current()).isSameAs(deadline);
            return "done";
        });

        assertThat(value).isEqualTo("done");
        assertThat(RequestDeadlineContext.current()).isNull();
    }

    @Test
    @DisplayName("action 抛异常时也必须移除 ThreadLocal")
    void contextRemovedWhenActionThrows() {
        RequestDeadline deadline = RequestDeadline.start(5.0, () -> 0L);

        Throwable thrown = catchThrowable(() -> RequestDeadlineContext.callWith(deadline, () -> {
            throw new IllegalStateException("boom");
        }));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(RequestDeadlineContext.current()).isNull();
    }

    @Test
    @DisplayName("拒绝空参数与嵌套设置 deadline，且失败后不残留 ThreadLocal")
    void contextRejectsNullAndNestedDeadline() {
        assertThatThrownBy(() -> RequestDeadlineContext.callWith(null, () -> "x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RequestDeadlineContext.callWith(
                        RequestDeadline.start(5.0, () -> 0L), null))
                .isInstanceOf(NullPointerException.class);

        RequestDeadline outer = RequestDeadline.start(5.0, () -> 0L);
        Throwable thrown = catchThrowable(() -> RequestDeadlineContext.callWith(outer, () ->
                RequestDeadlineContext.callWith(outer, () -> "x")));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(RequestDeadlineContext.current()).isNull();
    }

    @Test
    @DisplayName("并发请求互不共享 deadline，主线程不受工作线程影响")
    void contextIsThreadIsolated() throws Exception {
        RequestDeadline first = RequestDeadline.start(5.0, () -> 0L);
        RequestDeadline second = RequestDeadline.start(9.0, () -> 0L);
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<RequestDeadline> observedFirst = new AtomicReference<>();
        AtomicReference<RequestDeadline> observedSecond = new AtomicReference<>();

        Thread firstWorker = new Thread(() -> RequestDeadlineContext.callWith(first, () -> {
            bothEntered.countDown();
            awaitQuietly(release);
            observedFirst.set(RequestDeadlineContext.current());
            return "first";
        }));
        Thread secondWorker = new Thread(() -> RequestDeadlineContext.callWith(second, () -> {
            bothEntered.countDown();
            awaitQuietly(release);
            observedSecond.set(RequestDeadlineContext.current());
            return "second";
        }));

        firstWorker.start();
        secondWorker.start();
        try {
            assertThat(bothEntered.await(5, TimeUnit.SECONDS))
                    .as("两个工作线程都应已进入各自的 deadline 作用域")
                    .isTrue();
            assertThat(RequestDeadlineContext.current()).as("主线程不得看到工作线程的 deadline").isNull();
        } finally {
            release.countDown();
        }
        firstWorker.join(5_000L);
        secondWorker.join(5_000L);

        assertThat(observedFirst.get()).isSameAs(first);
        assertThat(observedSecond.get()).isSameAs(second);
        assertThat(RequestDeadlineContext.current()).isNull();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}

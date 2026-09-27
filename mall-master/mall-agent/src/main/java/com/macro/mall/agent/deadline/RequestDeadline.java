package com.macro.mall.agent.deadline;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 单次 HTTP 请求的总 deadline，行为对齐 Python {@code api/chat.py} 的
 * {@code asyncio.timeout(settings.request_timeout_seconds)}。
 *
 * <p><strong>单调时钟</strong>：剩余预算由 {@link System#nanoTime()}（或测试注入的等价 ticker）计算，
 * 只使用两次取值的差值，不读墙钟，因此系统时间被调整不会导致 deadline 提前或延后触发。
 *
 * <p>语义：deadline 只包住编排阶段（{@code orchestrator.run}）。编排期间每次真实 HTTP 请求都会读取
 * {@link #remaining()}，把该次请求的完整超时收紧为
 * {@code min(既有模型/门户超时配置, 剩余预算)}；剩余预算耗尽时在发出网络调用之前失败并抛出
 * {@link RequestDeadlineExceededException}。{@link #isExpired()} 为 true 时调用方<strong>不得</strong>
 * 继续执行会话落库等副作用。
 *
 * <p>本类不持有线程，也不在后台继续执行任何工作；它只表达「还剩多少预算」这一客观事实。
 * <strong>最近一次</strong> HTTP 请求是否被 deadline 提前截断由 {@link #isLastRequestClamped()} 记录，
 * 用于区分「deadline 超时」与「per-client 自身超时」两类失败。该标记是<strong>请求级</strong>而非累计：
 * 每次 HTTP 请求开始前由 {@link #beginRequest()} 重置，因此一次早期被截断的成功请求不会污染后续
 * 未被截断的 per-client 超时判定。
 */
public final class RequestDeadline {

    private static final LongSupplier SYSTEM_TICKER = System::nanoTime;

    private final long startedNanos;
    private final long budgetNanos;
    private final LongSupplier ticker;
    private volatile boolean lastRequestClamped;

    private RequestDeadline(long startedNanos, long budgetNanos, LongSupplier ticker) {
        this.startedNanos = startedNanos;
        this.budgetNanos = budgetNanos;
        this.ticker = ticker;
    }

    /**
     * 以当前单调时钟开始一个 deadline。
     *
     * @param timeoutSeconds 总预算秒数；必须大于 0（{@code AgentProperties} 已保证配置为正）
     * @throws IllegalArgumentException 预算不是正数
     */
    public static RequestDeadline start(double timeoutSeconds) {
        return start(timeoutSeconds, SYSTEM_TICKER);
    }

    /** 可注入单调 ticker 的入口，供确定性测试使用；语义与 {@link #start(double)} 完全一致。 */
    static RequestDeadline start(double timeoutSeconds, LongSupplier ticker) {
        Objects.requireNonNull(ticker, "ticker 不能为空");
        if (!(timeoutSeconds > 0)) {
            throw new IllegalArgumentException("请求超时必须大于 0 秒");
        }
        long budgetNanos = (long) Math.ceil(timeoutSeconds * 1_000_000_000.0);
        if (budgetNanos < 1L) {
            budgetNanos = 1L;
        }
        return new RequestDeadline(ticker.getAsLong(), budgetNanos, ticker);
    }

    /** 剩余预算是否已经耗尽。 */
    public boolean isExpired() {
        return remainingNanos() <= 0L;
    }

    /** 剩余预算；已耗尽时返回 {@link Duration#ZERO}，绝不为负。 */
    public Duration remaining() {
        return Duration.ofNanos(remainingNanos());
    }

    /**
     * 最近一次 HTTP 请求的完整超时是否取自剩余预算（即该次调用被 deadline 截断）。
     *
     * <p>该标记只描述<strong>最近一次</strong>请求，不是累计状态：调用方在每次发起 HTTP 请求前必须调用
     * {@link #beginRequest()}，请求工厂再按本次 effective timeout 决定是否 {@link #markRequestClamped()}。
     * 这样「请求级超时」判定始终对应当前正在处理的失败，而不会被更早一次被截断的成功请求污染。
     */
    public boolean isLastRequestClamped() {
        return lastRequestClamped;
    }

    /** 开始一次新的 HTTP 请求：清空上一次请求的截断标记，使判定只针对最新请求。 */
    void beginRequest() {
        this.lastRequestClamped = false;
    }

    /** 标记<strong>本次</strong> HTTP 请求的完整超时被 deadline 截断（取自剩余预算而非 per-client 配置）。 */
    void markRequestClamped() {
        this.lastRequestClamped = true;
    }

    private long remainingNanos() {
        long elapsed = ticker.getAsLong() - startedNanos;
        long remaining = budgetNanos - elapsed;
        return remaining < 0L ? 0L : remaining;
    }
}

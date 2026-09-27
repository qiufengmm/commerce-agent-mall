package com.macro.mall.agent.deadline;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link RequestDeadline} 的线程级上下文。
 *
 * <p>只以 {@link ThreadLocal} 暴露「当前请求的 deadline」，因此并发请求互不共享：一个请求的剩余预算
 * 不会泄漏到另一个线程。{@link #callWith(RequestDeadline, Supplier)} 在 {@code finally} 中
 * <strong>必然</strong>移除上下文，无论 action 正常返回还是抛异常，线程复用（线程池）时也不会残留
 * 上一个请求的 deadline。
 *
 * <p>刻意不支持嵌套：同一线程重复设置会被拒绝，避免内层覆盖外层 deadline 造成预算被放大。
 */
public final class RequestDeadlineContext {

    private static final ThreadLocal<RequestDeadline> CURRENT = new ThreadLocal<>();

    private RequestDeadlineContext() {
    }

    /** 当前线程的 deadline；不在 deadline 作用域内时返回 {@code null}。 */
    public static RequestDeadline current() {
        return CURRENT.get();
    }

    /**
     * 在指定 deadline 作用域内同步执行 {@code action} 并返回其结果。
     *
     * @throws NullPointerException  参数为空
     * @throws IllegalStateException 当前线程已处于另一个 deadline 作用域内（禁止嵌套）
     */
    public static <T> T callWith(RequestDeadline deadline, Supplier<T> action) {
        Objects.requireNonNull(deadline, "deadline 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        if (CURRENT.get() != null) {
            throw new IllegalStateException("不允许嵌套设置请求 deadline");
        }
        CURRENT.set(deadline);
        try {
            return action.get();
        } finally {
            CURRENT.remove();
        }
    }
}

package com.macro.mall.agent.deadline;

/**
 * 请求级 deadline 预算耗尽时抛出。
 *
 * <p>该异常<strong>刻意不携带</strong>任何消息、cause 或堆栈：它由 HTTP 请求工厂在发出网络调用之前
 * 抛出，既没有上游正文可以泄漏，也不允许把内部细节写入响应或日志。业务层只按类型把它统一映射为
 * 固定的 502 请求级超时文案。
 */
public final class RequestDeadlineExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RequestDeadlineExceededException() {
        super(null, null, false, false);
    }
}

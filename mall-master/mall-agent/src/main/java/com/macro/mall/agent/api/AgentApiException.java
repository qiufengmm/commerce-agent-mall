package com.macro.mall.agent.api;

/**
 * 应用级错误：只携带固定的 HTTP 状态码与面向客户端的固定文案。
 *
 * <p>不允许把异常堆栈、上游响应正文、请求 URL、Token 或其他凭据放进 message。
 */
public class AgentApiException extends RuntimeException {

    public static final String INVALID_REQUEST_MESSAGE = "请求参数不合法。";
    public static final String INVALID_SESSION_ID_MESSAGE = "sessionId 必须是 UUID v4。";
    public static final String SERVICE_UNAVAILABLE_MESSAGE = "智能导购暂时不可用，请稍后再试。";
    public static final String DUPLICATE_REQUEST_MESSAGE = "相同请求正在处理中，请勿重复提交。";
    /**
     * 业务限流 429 文案：逐字符对齐 Python {@code RateLimitExceededError.message}
     * （{@code safety/rate_limit.py}），<strong>不带句号</strong>。
     */
    public static final String RATE_LIMITED_MESSAGE = "请求过于频繁，请稍后再试";

    /**
     * 模型未配置/不可用的固定 503 文案，逐字符对齐 Python
     * {@code api/chat.py MODEL_UNAVAILABLE_MESSAGE}。
     */
    public static final String MODEL_UNAVAILABLE_MESSAGE = "智能导购暂时不可用：模型服务未配置或不可用。";

    /**
     * 模型调用失败（超时/上游错误）的固定 502 文案，逐字符对齐 Python
     * {@code api/chat.py MODEL_FAILED_MESSAGE}。
     */
    public static final String MODEL_FAILED_MESSAGE = "智能导购响应失败，请稍后再试。";

    /**
     * 门户读取失败的固定 502 文案，逐字符对齐 Python
     * {@code api/chat.py STOREFRONT_FAILED_MESSAGE}。
     */
    public static final String STOREFRONT_FAILED_MESSAGE = "商品数据暂时无法获取，请稍后再试。";

    /**
     * 请求级 deadline 超时的固定 502 文案，逐字符对齐 Python
     * {@code api/chat.py AGENT_TIMEOUT_MESSAGE}（{@code asyncio.timeout} 超时分支）。
     */
    public static final String REQUEST_TIMEOUT_MESSAGE = "智能导购响应超时，请稍后再试。";

    /**
     * Token 失效的固定文案，逐字符对齐 Python {@code api/chat.py LOGIN_REQUIRED_ANSWER}；
     * 仅用于身份解析已把 {@code MEMBER_UNAUTHORIZED} 转为 {@code requiresLogin} 的兜底分支。
     */
    public static final String LOGIN_STATE_EXPIRED_MESSAGE =
            "登录状态已失效，请重新登录后继续当前对话，登录前的提问不会被清空。";

    private final int status;

    public AgentApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static AgentApiException invalidRequest() {
        return new AgentApiException(400, INVALID_REQUEST_MESSAGE);
    }

    public static AgentApiException invalidSessionId() {
        return new AgentApiException(400, INVALID_SESSION_ID_MESSAGE);
    }

    public static AgentApiException serviceUnavailable() {
        return new AgentApiException(503, SERVICE_UNAVAILABLE_MESSAGE);
    }

    /** 模型未配置或不可用：固定 503，不得回显 API Key、Base URL 或模型名。 */
    public static AgentApiException modelUnavailable() {
        return new AgentApiException(503, MODEL_UNAVAILABLE_MESSAGE);
    }

    /** 相同会话的相同问题正在处理中。 */
    public static AgentApiException duplicateRequest() {
        return new AgentApiException(409, DUPLICATE_REQUEST_MESSAGE);
    }

    /**
     * 请求级 deadline 超时：固定 502，文案逐字符对齐 Python {@code AGENT_TIMEOUT_MESSAGE}。
     *
     * <p>该分支只承接 deadline 造成的超时；per-client（模型/门户）自身超时在 deadline 尚未触及时
     * 仍沿用既有的模型/门户错误映射。
     */
    public static AgentApiException requestTimeout() {
        return new AgentApiException(502, REQUEST_TIMEOUT_MESSAGE);
    }
}

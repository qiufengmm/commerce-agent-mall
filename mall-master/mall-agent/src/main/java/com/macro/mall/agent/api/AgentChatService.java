package com.macro.mall.agent.api;

/**
 * 智能体业务入口。
 *
 * <p>控制器只负责路径、请求体校验、Authorization 头与客户端 IP 的解析，以及统一响应封装；
 * 身份解析、限流、会话读写与工具编排由实现负责。
 *
 * <p>身份相关契约：
 * <ul>
 *   <li>会员身份只能由服务端根据 {@code Authorization} 头（经 mall-portal {@code /sso/info}）解析，
 *       客户端无法通过请求体、Cookie 或查询参数指定 memberId/Token。</li>
 *   <li>{@code clientIp} 由控制器解析：仅当请求对端等于配置的可信代理地址时才采用
 *       {@code X-Real-IP}，否则回退合法的 {@code remoteAddr}；实现不得读取任意
 *       {@code X-Forwarded-For}。</li>
 * </ul>
 *
 * <p>实现不得把 Token、上游响应正文或异常堆栈透出为 HTTP 响应内容。
 */
public interface AgentChatService {

    /**
     * @param request       已通过 Bean Validation 的聊天请求
     * @param authorization 移动端保存的完整 {@code Authorization} 头原值，可为 {@code null}
     * @param clientIp      控制器解析出的客户端 IP，可为空
     */
    ChatData chat(ChatRequest request, String authorization, String clientIp);

    /**
     * 会话恢复。
     *
     * @param sessionId     已规范化的 sessionId
     * @param authorization 原始 {@code Authorization} 头，可为 {@code null}
     * @param clientIp      控制器解析出的客户端 IP，可为空；实现须在身份解析前按该 IP 消耗一次 IP 桶
     */
    SessionData getSession(String sessionId, String authorization, String clientIp);

    /**
     * 会话清空。
     *
     * @param sessionId     已规范化的 sessionId
     * @param authorization 原始 {@code Authorization} 头，可为 {@code null}
     * @param clientIp      控制器解析出的客户端 IP，可为空；实现须在身份解析前按该 IP 消耗一次 IP 桶
     */
    DeleteSessionData deleteSession(String sessionId, String authorization, String clientIp);
}

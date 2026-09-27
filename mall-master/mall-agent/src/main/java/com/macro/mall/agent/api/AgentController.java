package com.macro.mall.agent.api;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.macro.mall.agent.config.AgentProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 智能体对外接口。
 *
 * <p>路径固定为 {@code POST /agent/chat}、{@code GET /agent/session/{sessionId}}、
 * {@code DELETE /agent/session/{sessionId}}；控制器只负责路径、请求体校验、{@code Authorization}
 * 头与客户端 IP 的解析，以及响应封装，业务处理委托给 {@link AgentChatService}。
 *
 * <p>{@code Authorization} 只从请求头读取（缺失时为 {@code null}），请求体中的身份字段由
 * {@code spring.jackson.deserialization.fail-on-unknown-properties=true} 直接拒绝；
 * 客户端 IP 仅在请求对端等于 {@code MALL_AGENT_TRUSTED_PROXY_IP} 所配置的可信代理地址时，
 * 才采用 {@code X-Real-IP}（见 {@link ClientIpResolver}）。
 */
@RestController
public class AgentController {

    private final AgentChatService chatService;
    private final AgentProperties properties;

    public AgentController(AgentChatService chatService, AgentProperties properties) {
        this.chatService = chatService;
        this.properties = properties;
    }

    @PostMapping("/agent/chat")
    public ApiEnvelope<ChatData> chat(
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest httpRequest) {
        return ApiEnvelope.success(chatService.chat(
                request,
                authorization,
                ClientIpResolver.resolve(httpRequest, properties.getTrustedProxyIp())));
    }

    @GetMapping("/agent/session/{sessionId}")
    public ApiEnvelope<SessionData> getSession(
            @PathVariable String sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest httpRequest) {
        String normalized = SessionIdValidator.requireCanonical(sessionId);
        return ApiEnvelope.success(chatService.getSession(
                normalized,
                authorization,
                ClientIpResolver.resolve(httpRequest, properties.getTrustedProxyIp())));
    }

    @DeleteMapping("/agent/session/{sessionId}")
    public ApiEnvelope<DeleteSessionData> deleteSession(
            @PathVariable String sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest httpRequest) {
        String normalized = SessionIdValidator.requireCanonical(sessionId);
        return ApiEnvelope.success(chatService.deleteSession(
                normalized,
                authorization,
                ClientIpResolver.resolve(httpRequest, properties.getTrustedProxyIp())));
    }
}

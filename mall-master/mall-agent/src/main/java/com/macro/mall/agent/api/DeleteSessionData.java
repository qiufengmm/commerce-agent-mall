package com.macro.mall.agent.api;

/**
 * 会话清空结果。{@code deleted} 表示本次调用是否清空了当前身份的会话。
 */
public record DeleteSessionData(String sessionId, boolean deleted, boolean requiresLogin) {
}

package com.macro.mall.agent.api;

/**
 * 会话中的一条消息。{@code role} 只允许 {@code user} 或 {@code assistant}。
 */
public record SessionMessage(String role, String content) {
}

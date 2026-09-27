package com.macro.mall.agent.model;

/**
 * 模型请求的一次工具调用。
 *
 * <p>{@code arguments} 保持供应商返回的原始字符串，不在模型层解析；
 * 参数合法性由后续的工具注册表按各自的 DTO 校验。
 */
public record ModelToolCall(String id, String name, String arguments) {
}

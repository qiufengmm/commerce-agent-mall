package com.macro.mall.agent.model;

/**
 * 模型客户端协议，便于在测试与离线评测中用 Stub 或 Fake 替换真实实现。
 *
 * <p>实现必须把供应商差异收敛为 {@link ModelResponse}，并且失败时只抛出携带安全分类的
 * {@link ModelException}，不得泄漏凭据或上游响应正文。
 */
public interface ModelClient {

    ModelResponse complete(ModelRequest request);
}

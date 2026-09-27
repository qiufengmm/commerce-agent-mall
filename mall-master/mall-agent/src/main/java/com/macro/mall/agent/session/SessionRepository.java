package com.macro.mall.agent.session;

/**
 * 会话仓储协议，对齐 Python {@code mall_shopping_agent.session.repository.SessionRepository}。
 *
 * <p>{@link #copy(String, String)} 严格复刻 Python：读取源键，<strong>源内容非空才写目标</strong>，
 * 最后删除源键；方法本身<strong>不判断目标键是否已有内容</strong>，防覆盖由上层
 * {@link IdentityResolver} 负责。
 */
public interface SessionRepository {

    SessionSnapshot load(String key);

    void save(String key, SessionSnapshot snapshot);

    boolean delete(String key);

    void copy(String sourceKey, String targetKey);
}

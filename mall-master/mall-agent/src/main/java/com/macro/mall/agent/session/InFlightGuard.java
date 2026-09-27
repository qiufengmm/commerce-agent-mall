package com.macro.mall.agent.session;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.macro.mall.agent.api.SessionIdValidator;

/**
 * 进程内并发防重，对齐 Python {@code mall_shopping_agent.api.deps.InFlightGuard}。
 *
 * <p>只使用进程内原子集合，<strong>不使用 Redis</strong>：它是<strong>单实例进程内</strong>保护，
 * 覆盖同一实例上同一会话的 {@code POST /agent/chat}、{@code GET /agent/session/{sessionId}} 与
 * {@code DELETE /agent/session/{sessionId}}：同一规范 sessionId 上只允许一个在飞请求，后续并发请求
 * 固定 409。它<strong>不提供跨实例互斥</strong>；限流与前端防重复提交都不等同于跨实例锁。
 *
 * <p><strong>占用键是整个请求生命周期的规范 sessionId</strong>：同一 sessionId 的第二个并发请求
 * 直接返回 409，不再按「sessionKey + 问题摘要」区分。这样可以挡住 Python 语义下无法覆盖的两个写覆盖
 * 场景：并发提交不同问题时，两次请求都会 load→save 同一会话键，后写覆盖前写；以及失效 Token 记问
 * 与身份迁移（guest→member）在身份解析阶段并发写会话。占用键在身份解析<strong>之前</strong>获取，
 * 因此对失效 Token 记问与身份迁移同样生效；请求结束或异常一律释放。
 *
 * <p>占用键由 {@link SessionIdValidator#requireCanonical(String)} 规范化，键内不含原始问题文本，
 * 因此问题内容不会出现在日志或指标标签里。不同 sessionId 互不阻塞。
 *
 * <p>因此该保护只在单实例进程内生效，<strong>多实例部署下不解决跨实例并发覆盖</strong>；本类刻意
 * 不引入分布式锁或新基础设施。
 */
public final class InFlightGuard {

    private static final String SESSION_OCCUPANCY_PREFIX = "mall:agent:inflight:session:";

    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    /**
     * @return {@code true} 表示获取成功；同一键已被占用时返回 {@code false}
     */
    public boolean acquire(String key) {
        Objects.requireNonNull(key, "key 不能为空");
        return keys.add(key);
    }

    /** 幂等释放；释放未知键不会报错。 */
    public void release(String key) {
        if (key != null) {
            keys.remove(key);
        }
    }

    public Set<String> keys() {
        return Set.copyOf(keys);
    }

    /** 整个请求生命周期的占用键：只由规范 sessionId 派生，与身份（游客/会员）和问题无关。 */
    public static String keyForSession(String sessionId) {
        return SESSION_OCCUPANCY_PREFIX + SessionIdValidator.requireCanonical(sessionId);
    }
}

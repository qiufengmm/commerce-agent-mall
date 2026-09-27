package com.macro.mall.agent.storefront;

import java.util.Objects;

import com.macro.mall.agent.api.HealthProbe;

/**
 * mall-portal 就绪探针的 {@link HealthProbe} 包装器。
 *
 * <p>把 {@link MallPortalClient} 的<strong>包级</strong>固定匿名只读检查适配为公共的
 * {@link HealthProbe}，从而让门户客户端只保留五条定型公开业务操作，不因就绪探针而增加第六个
 * 公开方法（见 {@code MallPortalClientTest#portalClientExposesOnlyTheFiveFixedReadOperations}）。
 *
 * <p>不变量：
 * <ul>
 *   <li><strong>委托而非新入口</strong>：{@link #isHealthy()} 只委托
 *       {@code MallPortalClient#isServiceReady()}，不新增任何 URL / HTTP method / header 入口；</li>
 *   <li><strong>惰性只读</strong>：构造时不发起任何请求，只有 {@link #isHealthy()} 被调用时才发送固定
 *       {@code GET /product/search?pageNum=1&pageSize=1} 匿名只读检查；</li>
 *   <li><strong>失败关闭</strong>：被包装方法已保证任何异常都安全返回 {@code false}；</li>
 *   <li>不记录地址、上游正文或凭据——本类不写日志。</li>
 * </ul>
 */
public final class PortalSearchHealthProbe implements HealthProbe {

    private final MallPortalClient portalClient;

    public PortalSearchHealthProbe(MallPortalClient portalClient) {
        this.portalClient = Objects.requireNonNull(portalClient, "portalClient 不能为空");
    }

    @Override
    public boolean isHealthy() {
        return portalClient.isServiceReady();
    }
}

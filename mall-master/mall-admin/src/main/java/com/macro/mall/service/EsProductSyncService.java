package com.macro.mall.service;

import java.util.List;

/**
 * 商品索引同步Service
 * 同步失败只记录日志，不抛出异常，不影响商品相关的业务操作
 */
public interface EsProductSyncService {
    /**
     * 同步单个商品到搜索服务
     *
     * @param id 商品id
     */
    void sync(Long id);

    /**
     * 批量同步商品到搜索服务
     *
     * @param ids 商品id集合
     */
    void syncBatch(List<Long> ids);
}

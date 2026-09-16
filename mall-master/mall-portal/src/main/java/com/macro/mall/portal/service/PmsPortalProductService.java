package com.macro.mall.portal.service;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.portal.domain.PmsPortalProductDetail;
import com.macro.mall.portal.domain.PmsProductCategoryNode;

import java.util.List;

/**
 * 前台商品管理Service
 */
public interface PmsPortalProductService {
    /**
     * 综合搜索商品
     * 当前通过 Elasticsearch 搜索服务完成，对移动端暴露 1-based 分页
     */
    CommonPage<PmsProduct> search(String keyword, Long brandId, Long productCategoryId, Integer pageNum, Integer pageSize, Integer sort);

    /**
     * 基于 MySQL 的综合搜索商品
     * 保留原有实现，供后续搜索服务不可用时降级使用，当前不在主链路调用
     */
    List<PmsProduct> searchByMySql(String keyword, Long brandId, Long productCategoryId, Integer pageNum, Integer pageSize, Integer sort);

    /**
     * 以树形结构获取所有商品分类
     */
    List<PmsProductCategoryNode> categoryTreeList();

    /**
     * 获取前台商品详情
     */
    PmsPortalProductDetail detail(Long id);
}

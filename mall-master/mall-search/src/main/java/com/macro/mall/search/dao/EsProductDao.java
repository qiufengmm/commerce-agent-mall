package com.macro.mall.search.dao;

import com.macro.mall.search.domain.EsProduct;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 搜索商品管理自定义Dao
 */
public interface EsProductDao {
    /**
     * 获取指定ID的搜索商品
     */
    List<EsProduct> getAllEsProductList(@Param("id") Long id);

    /**
     * 根据商品ID集合获取可上架的搜索商品
     */
    List<EsProduct> getEsProductListByIds(@Param("ids") List<Long> ids);
}

package com.macro.mall.search.service;

import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.domain.EsProductRelatedInfo;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 搜索商品管理Service
 */
public interface EsProductService {
    /**
     * 从数据库中导入所有商品到ES，并在导入成功后清理陈旧文档。
     * MySQL有效商品集合定义为delete_status=0且publish_status=1的商品，按商品id去重后保存。
     * 保存成功后才用search_after游标分批遍历pms索引现有文档（只读取文档id字段），
     * 删除已下架、已删除、MySQL中已不存在以及不在本次有效集合中的文档；
     * 游标翻批不使用from/size深层偏移，因此不受index.max_result_window限制。
     * MySQL查询异常或保存失败时直接抛出异常，不会继续删除任何ES文档；
     * 由于保存底层是bulk，保存失败时可能已有部分文档写入成功，本实现不做回滚补偿，
     * 已写入的部分只能通过再次执行importAll收敛。
     * 同一进程内不允许并发执行两个全量导入，并发请求会抛出{@link com.macro.mall.search.exception.ImportAllConflictException}。
     *
     * @return 本次保留/导入的有效商品数量，陈旧文档删除数量只写入日志，不通过该返回值透出商品明细
     */
    int importAll();

    /**
     * 根据id删除商品
     */
    void delete(Long id);

    /**
     * 根据id创建商品
     */
    EsProduct create(Long id);

    /**
     * 批量删除商品
     */
    void delete(List<Long> ids);

    /**
     * 同步单个商品到ES，商品可上架则保存或更新文档，否则删除文档，幂等
     */
    void sync(Long id);

    /**
     * 批量同步商品到ES，商品可上架则保存或更新文档，否则删除文档，幂等
     */
    void sync(List<Long> ids);

    /**
     * 根据关键字通过名称或副标题查询商品
     */
    Page<EsProduct> search(String keyword, Integer pageNum, Integer pageSize);

    /**
     * 根据关键字通过名称或副标题复合查询商品
     */
    Page<EsProduct> search(String keyword, Long brandId, Long productCategoryId, Integer pageNum, Integer pageSize,Integer sort);

    /**
     * 根据商品id推荐相关商品
     */
    Page<EsProduct> recommend(Long id, Integer pageNum, Integer pageSize);

    /**
     * 搜索关键字相关品牌、分类、属性
     */
    EsProductRelatedInfo searchRelatedInfo(String keyword);
}

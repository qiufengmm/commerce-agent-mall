package com.macro.mall.search.service.impl;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch._types.aggregations.*;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import co.elastic.clients.util.ObjectBuilder;
import com.macro.mall.search.dao.EsProductDao;
import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.domain.EsProductRelatedInfo;
import com.macro.mall.search.exception.ImportAllConflictException;
import com.macro.mall.search.repository.EsProductRepository;
import com.macro.mall.search.service.EsProductService;
import com.macro.mall.search.util.SearchPageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.data.elasticsearch.client.elc.*;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * 搜索商品管理Service实现类
 */
@Service
public class EsProductServiceImpl implements EsProductService {
    /**
     * 遍历pms索引现有文档时的单批次大小。
     * 使用search_after游标翻批，每批都是第0页，不依赖from/size深层偏移
     */
    private static final int STALE_DOCUMENT_SCAN_BATCH_SIZE = 500;
    private static final Logger LOGGER = LoggerFactory.getLogger(EsProductServiceImpl.class);
    @Autowired
    private EsProductDao productDao;
    @Autowired
    private EsProductRepository productRepository;
    @Autowired
    private ElasticsearchTemplate elasticsearchTemplate;
    /**
     * 进程内互斥锁，保证同一实例的importAll不会并发执行
     */
    private final ReentrantLock importAllLock = new ReentrantLock();

    /**
     * 全量导入执行流程固定为：先读取MySQL有效商品，再保存当前商品，最后清理陈旧文档。
     * 顺序不可颠倒，保证保存失败时不会删除任何ES文档。
     *
     * @return 本次保留/导入的有效商品数量，陈旧文档删除数量只写入日志，不包含商品明细
     */
    @Override
    public int importAll() {
        if (!importAllLock.tryLock()) {
            throw new ImportAllConflictException("商品索引全量导入正在执行中，同一实例不支持并发执行");
        }
        try {
            //MySQL查询异常会直接向上抛出，不会走到保存和删除逻辑，MySQL异常时绝不改动ES索引
            List<EsProduct> esProductList = productDao.getAllEsProductList(null);
            if (esProductList == null) {
                //返回null等同于查询失败，同样不能保存也不能清理，避免错误清空索引
                throw new IllegalStateException("查询MySQL有效商品列表返回null，已中止全量导入，ES索引未被修改");
            }
            Map<Long, EsProduct> validProductMap = dedupeByProductId(esProductList);
            //保存失败时异常继续向上抛出，后面的陈旧文档清理不会执行。
            //saveAll底层是bulk，可能部分文档已经写入成功，本实现不做回滚补偿，
            //只保证不再继续删除陈旧文档；索引最终一致依赖再次执行importAll收敛
            productRepository.saveAll(new ArrayList<>(validProductMap.values()));
            Set<Long> existingEsIds = scanExistingDocumentIds();
            List<Long> staleDocumentIds = resolveStaleDocumentIds(existingEsIds, validProductMap.keySet());
            if (!staleDocumentIds.isEmpty()) {
                productRepository.deleteAllById(staleDocumentIds);
            }
            LOGGER.info("商品索引全量导入完成，本次导入有效商品数量:{}，剔除陈旧ES文档数量:{}",
                    validProductMap.size(), staleDocumentIds.size());
            return validProductMap.size();
        } finally {
            importAllLock.unlock();
        }
    }

    /**
     * 按商品id去重，MySQL商品属性为一对多关联，同一商品可能出现多条结果
     */
    private Map<Long, EsProduct> dedupeByProductId(List<EsProduct> esProductList) {
        Map<Long, EsProduct> productMap = new LinkedHashMap<>();
        for (EsProduct esProduct : esProductList) {
            if (esProduct == null || esProduct.getId() == null) {
                continue;
            }
            productMap.putIfAbsent(esProduct.getId(), esProduct);
        }
        return productMap;
    }

    /**
     * 分批遍历pms索引中的现有文档，只获取文档id字段，避免把完整文档拉回应用。
     * 使用search_after游标翻批：每批都是第0页、批次大小固定，
     * 因此不会构造超过index.max_result_window的from/size深分页请求，也不会一次性加载全部文档；
     * 索引按id升序排序并取末条排序值作为下一批游标，能完整覆盖全部历史文档。
     */
    private Set<Long> scanExistingDocumentIds() {
        Set<Long> documentIds = new LinkedHashSet<>();
        List<Object> searchAfter = null;
        while (true) {
            NativeQueryBuilder nativeQueryBuilder = new NativeQueryBuilder()
                    .withQuery(QueryBuilders.matchAll(builder -> builder))
                    .withPageable(PageRequest.of(0, STALE_DOCUMENT_SCAN_BATCH_SIZE))
                    .withSort(Sort.by(Sort.Order.asc("id")))
                    .withSourceFilter(FetchSourceFilter.of(builder -> builder.withIncludes("id")));
            if (searchAfter != null) {
                nativeQueryBuilder.withSearchAfter(searchAfter);
            }
            SearchHits<EsProduct> searchHits = elasticsearchTemplate.search(nativeQueryBuilder.build(), EsProduct.class);
            List<SearchHit<EsProduct>> hits = searchHits.getSearchHits();
            if (CollectionUtils.isEmpty(hits)) {
                break;
            }
            for (SearchHit<EsProduct> searchHit : hits) {
                Long documentId = resolveDocumentId(searchHit);
                if (documentId != null) {
                    documentIds.add(documentId);
                }
            }
            if (hits.size() < STALE_DOCUMENT_SCAN_BATCH_SIZE) {
                break;
            }
            List<Object> nextSearchAfter = resolveNextSearchAfter(hits.get(hits.size() - 1));
            if (nextSearchAfter == null) {
                //无法推进游标时立即停止枚举，避免死循环，也不会误删未识别的文档
                LOGGER.warn("pms索引末条文档缺少可用排序值，已停止枚举，未识别的文档不会被清理");
                break;
            }
            searchAfter = nextSearchAfter;
        }
        return documentIds;
    }

    /**
     * 计算下一批的search_after游标：优先使用ES返回的排序值，缺失时回退为文档id，
     * 因为遍历固定按id升序，文档id与排序值等价
     */
    private List<Object> resolveNextSearchAfter(SearchHit<EsProduct> lastHit) {
        if (lastHit == null) {
            return null;
        }
        List<Object> sortValues = lastHit.getSortValues();
        if (!CollectionUtils.isEmpty(sortValues)) {
            return new ArrayList<>(sortValues);
        }
        Long lastDocumentId = resolveDocumentId(lastHit);
        if (lastDocumentId == null) {
            return null;
        }
        return Collections.singletonList(lastDocumentId);
    }

    /**
     * 解析ES文档id，优先使用文档内容中的商品id，内容缺失时回退到ES元数据_id，都无法解析时返回null由调用方跳过，
     * 保证文档内容id缺失不会导致异常，也不会误删无法识别的文档
     */
    private Long resolveDocumentId(SearchHit<EsProduct> searchHit) {
        if (searchHit == null) {
            return null;
        }
        EsProduct content = searchHit.getContent();
        if (content != null && content.getId() != null) {
            return content.getId();
        }
        return parseDocumentIdQuietly(searchHit.getId());
    }

    private Long parseDocumentIdQuietly(String documentId) {
        if (!StrUtil.isNotBlank(documentId)) {
            return null;
        }
        try {
            return Long.valueOf(documentId.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("忽略无法解析为商品id的ES文档id");
            return null;
        }
    }

    /**
     * 已下架、已删除、MySQL中已不存在、以及任何不在本次有效集合中的文档都属于陈旧文档
     */
    private List<Long> resolveStaleDocumentIds(Set<Long> existingEsIds, Set<Long> validProductIds) {
        List<Long> staleDocumentIds = new ArrayList<>();
        for (Long documentId : existingEsIds) {
            if (!validProductIds.contains(documentId)) {
                staleDocumentIds.add(documentId);
            }
        }
        return staleDocumentIds;
    }

    @Override
    public void delete(Long id) {
        productRepository.deleteById(id);
    }

    @Override
    public EsProduct create(Long id) {
        EsProduct result = null;
        List<EsProduct> esProductList = productDao.getAllEsProductList(id);
        if (esProductList.size() > 0) {
            EsProduct esProduct = esProductList.get(0);
            result = productRepository.save(esProduct);
        }
        return result;
    }

    @Override
    public void delete(List<Long> ids) {
        if (!CollectionUtils.isEmpty(ids)) {
            List<EsProduct> esProductList = new ArrayList<>();
            for (Long id : ids) {
                EsProduct esProduct = new EsProduct();
                esProduct.setId(id);
                esProductList.add(esProduct);
            }
            productRepository.deleteAll(esProductList);
        }
    }

    @Override
    public void sync(Long id) {
        if (id == null) {
            return;
        }
        sync(Collections.singletonList(id));
    }

    @Override
    public void sync(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        List<Long> distinctIds = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (CollectionUtils.isEmpty(distinctIds)) {
            return;
        }
        //只查询未删除且已上架的商品，查不到的商品需要从索引中剔除
        List<EsProduct> esProductList = productDao.getEsProductListByIds(distinctIds);
        Map<Long, EsProduct> esProductMap = new HashMap<>();
        for (EsProduct esProduct : esProductList) {
            esProductMap.putIfAbsent(esProduct.getId(), esProduct);
        }
        List<EsProduct> saveList = new ArrayList<>();
        List<Long> deleteIdList = new ArrayList<>();
        for (Long id : distinctIds) {
            EsProduct esProduct = esProductMap.get(id);
            if (esProduct != null) {
                saveList.add(esProduct);
            } else {
                deleteIdList.add(id);
            }
        }
        if (!CollectionUtils.isEmpty(saveList)) {
            productRepository.saveAll(saveList);
        }
        for (Long id : deleteIdList) {
            deleteDocumentQuietly(id);
        }
    }

    /**
     * 删除ES中不存在的文档时不做处理，保证同步操作可重复执行
     */
    private void deleteDocumentQuietly(Long id) {
        if (!productRepository.existsById(id)) {
            return;
        }
        productRepository.deleteById(id);
    }

    @Override
    public Page<EsProduct> search(String keyword, Integer pageNum, Integer pageSize) {
        if (SearchPageUtils.isBeyondMaxResultWindow(pageNum, pageSize)) {
            return SearchPageUtils.emptyPage(pageNum, pageSize);
        }
        Pageable pageable = SearchPageUtils.toPageable(pageNum, pageSize);
        return productRepository.findByNameOrSubTitleOrKeywords(keyword, keyword, keyword, pageable);
    }

    @Override
    public Page<EsProduct> search(String keyword, Long brandId, Long productCategoryId, Integer pageNum, Integer pageSize,Integer sort) {
        if (SearchPageUtils.isBeyondMaxResultWindow(pageNum, pageSize)) {
            return SearchPageUtils.emptyPage(pageNum, pageSize);
        }
        Pageable pageable = SearchPageUtils.toPageable(pageNum, pageSize);
        NativeQueryBuilder nativeQueryBuilder = new NativeQueryBuilder();
        //分页
        nativeQueryBuilder.withPageable(pageable);
        //过滤
        if (brandId != null || productCategoryId != null) {
            Query boolQuery = QueryBuilders.bool(builder -> {
                if (brandId != null) {
                    builder.must(QueryBuilders.term(b -> b.field("brandId").value(brandId)));
                }
                if (productCategoryId != null) {
                    builder.must(QueryBuilders.term(b -> b.field("productCategoryId").value(productCategoryId)));
                }
                return builder;
            });
            nativeQueryBuilder.withFilter(boolQuery);
        }
        //搜索
        if (StrUtil.isEmpty(keyword)) {
            nativeQueryBuilder.withQuery(QueryBuilders.matchAll(builder -> builder));
        } else {
            List<FunctionScore> functionScoreList = new ArrayList<>();
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("name").query(keyword)))
                    .weight(10.0)
                    .build());
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("subTitle").query(keyword)))
                    .weight(5.0)
                    .build());
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("keywords").query(keyword)))
                    .weight(2.0)
                    .build());
            FunctionScoreQuery.Builder functionScoreQueryBuilder = QueryBuilders.functionScore()
                    .functions(functionScoreList)
                    .scoreMode(FunctionScoreMode.Sum)
                    .minScore(2.0);
            nativeQueryBuilder.withQuery(builder -> builder.functionScore(functionScoreQueryBuilder.build()));
        }
        //排序
        if(sort==1){
            //按新品从新到旧
            nativeQueryBuilder.withSort(Sort.by(Sort.Order.desc("id")));
        }else if(sort==2){
            //按销量从高到低
            nativeQueryBuilder.withSort(Sort.by(Sort.Order.desc("sale")));
        }else if(sort==3){
            //按价格从低到高
            nativeQueryBuilder.withSort(Sort.by(Sort.Order.asc("price")));
        }else if(sort==4){
            //按价格从高到低
            nativeQueryBuilder.withSort(Sort.by(Sort.Order.desc("price")));
        }
        //按相关度
        nativeQueryBuilder.withSort(Sort.by(Sort.Order.desc("_score")));
        NativeQuery nativeQuery = nativeQueryBuilder.build();
        LOGGER.info("DSL:{}", nativeQuery.getQuery().toString());
        SearchHits<EsProduct> searchHits = elasticsearchTemplate.search(nativeQuery, EsProduct.class);
        if(searchHits.getTotalHits()<=0){
            return new PageImpl<>(ListUtil.empty(),pageable,0);
        }
        List<EsProduct> searchProductList = searchHits.stream().map(SearchHit::getContent).collect(Collectors.toList());
        return new PageImpl<>(searchProductList,pageable,searchHits.getTotalHits());
    }

    @Override
    public Page<EsProduct> recommend(Long id, Integer pageNum, Integer pageSize) {
        if (SearchPageUtils.isBeyondMaxResultWindow(pageNum, pageSize)) {
            return SearchPageUtils.emptyPage(pageNum, pageSize);
        }
        Pageable pageable = SearchPageUtils.toPageable(pageNum, pageSize);
        List<EsProduct> esProductList = productDao.getAllEsProductList(id);
        if (esProductList.size() > 0) {
            EsProduct esProduct = esProductList.get(0);
            String keyword = esProduct.getName();
            Long brandId = esProduct.getBrandId();
            Long productCategoryId = esProduct.getProductCategoryId();
            //构建查询条件
            NativeQueryBuilder nativeQueryBuilder = new NativeQueryBuilder();
            //分页
            nativeQueryBuilder.withPageable(pageable);
            //用于过滤掉相同的商品
            nativeQueryBuilder.withFilter(QueryBuilders.bool(build -> build.mustNot(QueryBuilders.term(b->b.field("id").value(id)))));
            //根据商品标题、品牌、分类进行搜索
            List<FunctionScore> functionScoreList = new ArrayList<>();
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("name").query(keyword)))
                    .weight(8.0)
                    .build());
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("subTitle").query(keyword)))
                    .weight(2.0)
                    .build());
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("keywords").query(keyword)))
                    .weight(2.0)
                    .build());
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("brandId").query(brandId)))
                    .weight(5.0)
                    .build());
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("productCategoryId").query(productCategoryId)))
                    .weight(3.0)
                    .build());
            FunctionScoreQuery.Builder functionScoreQueryBuilder = QueryBuilders.functionScore()
                    .functions(functionScoreList)
                    .scoreMode(FunctionScoreMode.Sum)
                    .minScore(2.0);
            nativeQueryBuilder.withQuery(builder -> builder.functionScore(functionScoreQueryBuilder.build()));
            NativeQuery nativeQuery = nativeQueryBuilder.build();
            LOGGER.info("DSL:{}", nativeQuery.getQuery().toString());
            SearchHits<EsProduct> searchHits = elasticsearchTemplate.search(nativeQuery, EsProduct.class);
            if(searchHits.getTotalHits()<=0){
                return new PageImpl<>(ListUtil.empty(),pageable,0);
            }
            List<EsProduct> searchProductList = searchHits.stream().map(SearchHit::getContent).collect(Collectors.toList());
            return new PageImpl<>(searchProductList,pageable,searchHits.getTotalHits());
        }
        return new PageImpl<>(ListUtil.empty());
    }

    @Override
    public EsProductRelatedInfo searchRelatedInfo(String keyword) {
        NativeQueryBuilder nativeQueryBuilder = new NativeQueryBuilder();
        //搜索条件
        if(StrUtil.isEmpty(keyword)){
            nativeQueryBuilder.withQuery(QueryBuilders.matchAll(builder -> builder));
        }else{
            nativeQueryBuilder.withQuery(QueryBuilders.multiMatch(builder -> builder.fields("name","subTitle","keywords").query(keyword)));
        }
        //聚合搜索品牌名称
        nativeQueryBuilder.withAggregation("brandNames",AggregationBuilders.terms(builder -> builder.field("brandName").size(10)));
        //聚合搜索分类名称
        nativeQueryBuilder.withAggregation("productCategoryNames",AggregationBuilders.terms(builder -> builder.field("productCategoryName").size(10)));
        //聚合搜索商品属性，去除type=0的属性
        Aggregation aggregation = new Aggregation.Builder().nested(builder -> builder.path("attrValueList"))
                .aggregations("productAttrs",new Aggregation.Builder()
                        .filter(b->b.term(a->a.field("attrValueList.type").value("1")))
                        .aggregations("attrIds",new Aggregation.Builder().terms(b->b.field("attrValueList.productAttributeId").size(10))
                                .aggregations("attrValues",new Aggregation.Builder().terms(b->b.field("attrValueList.value").size(10)).build())
                                .aggregations("attrNames",new Aggregation.Builder().terms(b->b.field("attrValueList.name").size(10)).build())
                                .build()).build()).build();
        nativeQueryBuilder.withAggregation("allAttrValues",aggregation);
        NativeQuery nativeQuery = nativeQueryBuilder.build();
        LOGGER.info("DSL:{}", nativeQueryBuilder.getQuery().toString());
        SearchHits<EsProduct> searchHits = elasticsearchTemplate.search(nativeQuery, EsProduct.class);
        return convertProductRelatedInfo(searchHits);
    }

    /**
     * 将返回结果转换为对象
     */
    private EsProductRelatedInfo convertProductRelatedInfo(SearchHits<EsProduct> response) {
        EsProductRelatedInfo productRelatedInfo = new EsProductRelatedInfo();
        Map<String, ElasticsearchAggregation> esAggregationMap = ((ElasticsearchAggregations) response.getAggregations()).aggregationsAsMap();
        //设置品牌
        ElasticsearchAggregation brandNames = esAggregationMap.get("brandNames");
        List<String> brandNameList = new ArrayList<>();
        List<StringTermsBucket> brandNameBuckets = ((StringTermsAggregate) brandNames.aggregation().getAggregate()._get()).buckets().array();
        for(int i = 0; i<brandNameBuckets.size(); i++){
            brandNameList.add(brandNameBuckets.get(i).key().stringValue());
        }
        productRelatedInfo.setBrandNames(brandNameList);
        //设置分类
        ElasticsearchAggregation productCategoryNames = esAggregationMap.get("productCategoryNames");
        List<String> productCategoryNameList = new ArrayList<>();
        List<StringTermsBucket> productCategoryNameBuckets = ((StringTermsAggregate) productCategoryNames.aggregation().getAggregate()._get()).buckets().array();
        for(int i = 0; i<productCategoryNameBuckets.size(); i++){
            productCategoryNameList.add(productCategoryNameBuckets.get(i).key().stringValue());
        }
        productRelatedInfo.setProductCategoryNames(productCategoryNameList);
        //设置参数
        ElasticsearchAggregation productAttrs = esAggregationMap.get("allAttrValues");
        List<LongTermsBucket> attrIdBuckets = ((LongTermsAggregate) ((FilterAggregate) ((NestedAggregate) productAttrs.aggregation().getAggregate()._get()).aggregations().get("productAttrs")._get()).aggregations().get("attrIds")._get()).buckets().array();
        List<EsProductRelatedInfo.ProductAttr> attrList = new ArrayList<>();
        for (LongTermsBucket item : attrIdBuckets) {
            EsProductRelatedInfo.ProductAttr attr = new EsProductRelatedInfo.ProductAttr();
            attr.setAttrId(item.key());
            List<String> attrValueList = new ArrayList<>();
            List<StringTermsBucket> attrValues = ((StringTermsAggregate) item.aggregations().get("attrValues")._get()).buckets().array();
            List<StringTermsBucket> attrNames = ((StringTermsAggregate) item.aggregations().get("attrNames")._get()).buckets().array();
            for (StringTermsBucket attrValue : attrValues) {
                attrValueList.add(attrValue.key().stringValue());
            }
            attr.setAttrValues(attrValueList);
            if(!CollectionUtils.isEmpty(attrNames)){
                String attrName = attrNames.get(0).key().stringValue();
                attr.setAttrName(attrName);
            }
            attrList.add(attr);
        }
        productRelatedInfo.setProductAttrs(attrList);
        return productRelatedInfo;
    }
}

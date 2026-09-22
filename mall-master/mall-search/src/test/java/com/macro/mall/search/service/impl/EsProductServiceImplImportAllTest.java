package com.macro.mall.search.service.impl;

import com.macro.mall.search.dao.EsProductDao;
import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.exception.ImportAllConflictException;
import com.macro.mall.search.repository.EsProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.BulkFailureException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * importAll全量导入测试
 * 全部使用Mockito测试替身，不连接真实Elasticsearch，不触发任何真实写接口。
 * 覆盖：空索引导入、陈旧文档清理、跨批次遍历、重复导入幂等、MySQL异常保留索引、
 * MySQL空集合清空陈旧文档、文档id缺失安全处理、保存失败不删除、并发互斥。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class EsProductServiceImplImportAllTest {
    @Mock
    private EsProductDao productDao;
    @Mock
    private EsProductRepository productRepository;
    @Mock
    private ElasticsearchTemplate elasticsearchTemplate;

    private EsProductServiceImpl esProductService;

    @BeforeEach
    void setUp() {
        esProductService = new EsProductServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(esProductService, "productDao", productDao);
        org.springframework.test.util.ReflectionTestUtils.setField(esProductService, "productRepository", productRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(esProductService, "elasticsearchTemplate", elasticsearchTemplate);
    }

    @Test
    public void testImportAllIntoEmptyIndex() {
        SearchHits<EsProduct> emptyHits = searchHits(Collections.emptyList(), 0L);
        when(productDao.getAllEsProductList(isNull())).thenReturn(Arrays.asList(product(1L), product(2L)));
        when(elasticsearchTemplate.search(any(NativeQuery.class), eq(EsProduct.class))).thenReturn(emptyHits);

        int result = esProductService.importAll();

        assertEquals(2, result, "返回值应为本次导入的有效商品数量");
        verify(productRepository, times(1)).saveAll(anyList());
        assertEquals(Arrays.asList(1L, 2L), captureSavedIds());
        verify(productRepository, never()).deleteAllById(any(Iterable.class));
        verify(productRepository, never()).deleteAll();
    }

    @Test
    public void testImportAllRemovesStaleDocuments() {
        SearchHits<EsProduct> existingHits = searchHits(products(1L, 2L, 3L, 4L), 4L);
        when(productDao.getAllEsProductList(isNull())).thenReturn(Arrays.asList(product(1L), product(2L)));
        when(elasticsearchTemplate.search(any(NativeQuery.class), eq(EsProduct.class))).thenReturn(existingHits);

        int result = esProductService.importAll();

        assertEquals(2, result);
        assertEquals(Arrays.asList(3L, 4L), captureDeletedIds(), "不在有效集合中的文档必须被删除");
    }

    @Test
    public void testImportAllScansEveryBatchWithSearchAfterCursor() {
        int batchSize = 500;
        List<Long> firstBatchIds = range(1, batchSize);
        List<Long> secondBatchIds = range(batchSize + 1, batchSize);
        List<Long> thirdBatchIds = range(1001, 200);
        SearchHits<EsProduct> firstBatch = searchHits(products(firstBatchIds), 1200L);
        SearchHits<EsProduct> secondBatch = searchHits(products(secondBatchIds), 1200L);
        SearchHits<EsProduct> thirdBatch = searchHits(products(thirdBatchIds), 1200L);
        when(productDao.getAllEsProductList(isNull())).thenReturn(Collections.singletonList(product(1L)));
        when(elasticsearchTemplate.search(any(NativeQuery.class), eq(EsProduct.class)))
                .thenReturn(firstBatch)
                .thenReturn(secondBatch)
                .thenReturn(thirdBatch);

        int result = esProductService.importAll();

        assertEquals(1, result);
        ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
        verify(elasticsearchTemplate, times(3)).search(queryCaptor.capture(), eq(EsProduct.class));
        List<NativeQuery> queries = queryCaptor.getAllValues();
        for (NativeQuery query : queries) {
            assertEquals(0, query.getPageable().getPageNumber(), "游标翻批必须始终使用第0页，不得使用from深层偏移");
            assertEquals(batchSize, query.getPageable().getPageSize());
            assertEquals("id", query.getSourceFilter().getIncludes()[0], "只读取必要的id字段");
            assertEquals(1, query.getSourceFilter().getIncludes().length);
        }
        assertNull(queries.get(0).getSearchAfter(), "首批没有游标");
        assertEquals(Collections.singletonList(500L), queries.get(1).getSearchAfter(),
                "第二批必须使用上一批末条文档id作为search_after游标");
        assertEquals(Collections.singletonList(1000L), queries.get(2).getSearchAfter());
        List<Long> deletedIds = captureDeletedIds();
        assertEquals(1199, deletedIds.size(), "1200条文档应跨3个批次全部枚举并剔除陈旧文档");
        assertFalse(deletedIds.contains(1L), "有效商品不得被删除");
    }

    @Test
    public void testImportAllScansMoreThanMaxResultWindowWithoutDeepPaging() {
        int batchSize = 500;
        int totalDocuments = 12000;
        List<SearchHits<EsProduct>> batches = buildBatchesWithTerminator(totalDocuments, batchSize);
        when(productDao.getAllEsProductList(isNull())).thenReturn(Collections.singletonList(product(1L)));
        when(elasticsearchTemplate.search(any(NativeQuery.class), eq(EsProduct.class)))
                .thenReturn(batches.get(0), batches.subList(1, batches.size()).toArray(new SearchHits[0]));

        int result = esProductService.importAll();

        assertEquals(1, result);
        ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
        verify(elasticsearchTemplate, times(batches.size())).search(queryCaptor.capture(), eq(EsProduct.class));
        List<NativeQuery> queries = queryCaptor.getAllValues();
        for (int i = 0; i < queries.size(); i++) {
            NativeQuery query = queries.get(i);
            assertEquals(0, query.getPageable().getPageNumber(),
                    "超过10000条时也必须使用第0页游标请求，不能构造超过默认窗口的深分页");
            assertEquals(batchSize, query.getPageable().getPageSize());
            if (i == 0) {
                assertNull(query.getSearchAfter(), "首批没有游标");
            } else {
                assertEquals(Collections.singletonList((long) i * batchSize), query.getSearchAfter(),
                        "游标必须严格来自上一批的末条文档id");
            }
        }
        List<Long> deletedIds = captureDeletedIds();
        assertEquals(totalDocuments - 1, deletedIds.size(),
                "超过10000条文档仍必须被完整识别为陈旧文档");
        assertFalse(deletedIds.contains(1L));
    }

    @Test
    public void testImportAllUsesElasticsearchSortValuesAsCursor() {
        int batchSize = 500;
        List<Long> firstBatchIds = range(1, batchSize);
        List<Long> secondBatchIds = range(1001, 10);
        List<SearchHit<EsProduct>> firstBatchHits = firstBatchIds.stream()
                .map(id -> searchHitFromContent(product(id)))
                .collect(Collectors.toList());
        SearchHit<EsProduct> lastHit = firstBatchHits.get(firstBatchHits.size() - 1);
        when(lastHit.getSortValues()).thenReturn(Collections.<Object>singletonList(500L));
        SearchHits<EsProduct> firstBatch = mock(SearchHits.class);
        when(firstBatch.getSearchHits()).thenReturn(firstBatchHits);
        when(firstBatch.getTotalHits()).thenReturn(510L);
        when(productDao.getAllEsProductList(isNull())).thenReturn(Collections.singletonList(product(1L)));
        when(elasticsearchTemplate.search(any(NativeQuery.class), eq(EsProduct.class)))
                .thenReturn(firstBatch)
                .thenReturn(searchHits(products(secondBatchIds), 510L));

        esProductService.importAll();

        ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
        verify(elasticsearchTemplate, times(2)).search(queryCaptor.capture(), eq(EsProduct.class));
        assertEquals(Collections.<Object>singletonList(500L), queryCaptor.getAllValues().get(1).getSearchAfter(),
                "有排序值时必须优先使用ES返回的排序值作为游标");
        assertEquals(509, captureDeletedIds().size());
    }

    @Test
    public void testImportAllIsIdempotent() {
        SearchHits<EsProduct> existingHits = searchHits(products(1L, 2L), 2L);
        when(productDao.getAllEsProductList(isNull())).thenReturn(Arrays.asList(product(1L), product(2L), product(1L)));
        when(elasticsearchTemplate.search(any(NativeQuery.class), eq(EsProduct.class))).thenReturn(existingHits);

        int firstResult = esProductService.importAll();
        int secondResult = esProductService.importAll();

        assertEquals(2, firstResult, "MySQL重复行必须按商品id去重");
        assertEquals(2, secondResult, "重复导入应保持幂等返回值");
        verify(productRepository, times(2)).saveAll(anyList());
        verify(productRepository, never()).deleteAllById(any(Iterable.class));
    }

    @Test
    public void testMySqlExceptionKeepsEsUntouched() {
        when(productDao.getAllEsProductList(isNull())).thenThrow(new IllegalStateException("MySQL不可用"));

        assertThrows(IllegalStateException.class, () -> esProductService.importAll());

        verify(productRepository, never()).saveAll(anyList());
        verify(productRepository, never()).deleteAllById(any(Iterable.class));
        verify(productRepository, never()).deleteAll();
        verify(elasticsearchTemplate, never()).search(any(NativeQuery.class), eq(EsProduct.class));
    }

    @Test
    public void testMySqlNullResultKeepsEsUntouched() {
        when(productDao.getAllEsProductList(isNull())).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> esProductService.importAll());

        verify(productRepository, never()).saveAll(anyList());
        verify(productRepository, never()).deleteAllById(any(Iterable.class));
    }

    @Test
    public void testEmptyMySqlResultClearsStaleDocuments() {
        SearchHits<EsProduct> existingHits = searchHits(products(7L, 8L), 2L);
        when(productDao.getAllEsProductList(isNull())).thenReturn(Collections.emptyList());
        when(elasticsearchTemplate.search(any(NativeQuery.class), eq(EsProduct.class))).thenReturn(existingHits);

        int result = esProductService.importAll();

        assertEquals(0, result, "MySQL明确返回空集合时返回0");
        assertEquals(Arrays.asList(7L, 8L), captureDeletedIds(), "MySQL明确为空时才允许清空全部陈旧文档");
    }

    @Test
    public void testMissingDocumentContentIdIsIgnoredSafely() {
        SearchHit<EsProduct> validHit = searchHitFromContent(product(1L));
        SearchHit<EsProduct> contentWithoutIdHit = searchHitFromContent(product(null));
        SearchHit<EsProduct> nullContentHit = searchHitWithoutContent("9");
        SearchHits<EsProduct> searchHits = mock(SearchHits.class);
        when(searchHits.getSearchHits()).thenReturn(Arrays.asList(validHit, contentWithoutIdHit, nullContentHit));
        when(searchHits.getTotalHits()).thenReturn(3L);
        when(productDao.getAllEsProductList(isNull())).thenReturn(Collections.singletonList(product(1L)));
        when(elasticsearchTemplate.search(any(NativeQuery.class), eq(EsProduct.class))).thenReturn(searchHits);

        int result = esProductService.importAll();

        assertEquals(1, result);
        assertEquals(Collections.singletonList(9L), captureDeletedIds(),
                "文档内容id缺失但元数据可读的文档按id识别，无法识别的文档跳过且不得删除");
    }

    @Test
    public void testSaveFailureDoesNotDeleteStaleDocuments() {
        SearchHits<EsProduct> existingHits = searchHits(products(1L, 5L), 2L);
        when(productDao.getAllEsProductList(isNull())).thenReturn(Collections.singletonList(product(1L)));
        when(productRepository.saveAll(anyList())).thenThrow(new IllegalStateException("ES写入失败"));
        when(elasticsearchTemplate.search(any(NativeQuery.class), eq(EsProduct.class))).thenReturn(existingHits);

        assertThrows(IllegalStateException.class, () -> esProductService.importAll());

        verify(productRepository, never()).deleteAllById(any(Iterable.class));
        verify(elasticsearchTemplate, never()).search(any(NativeQuery.class), eq(EsProduct.class));
    }

    @Test
    public void testBulkFailureRetriesOnlyFailedProductsBeforeStaleCleanup() {
        BulkFailureException failure = bulkFailure("2");
        SearchHits<EsProduct> existingHits = searchHits(products(1L, 2L, 9L), 3L);
        when(productDao.getAllEsProductList(isNull())).thenReturn(Arrays.asList(product(1L), product(2L)));
        when(productRepository.saveAll(anyList())).thenThrow(failure).thenReturn(Collections.emptyList());
        when(elasticsearchTemplate.search(any(NativeQuery.class), eq(EsProduct.class))).thenReturn(existingHits);

        int result = esProductService.importAll();

        assertEquals(2, result);
        ArgumentCaptor<Iterable<EsProduct>> savedCaptor = savedProductsCaptor();
        verify(productRepository, times(2)).saveAll(savedCaptor.capture());
        assertEquals(Arrays.asList(1L, 2L), ids(savedCaptor.getAllValues().get(0)));
        assertEquals(Collections.singletonList(2L), ids(savedCaptor.getAllValues().get(1)));
        assertEquals(Collections.singletonList(9L), captureDeletedIds());
    }

    @Test
    public void testBulkRetryFailureDoesNotDeleteStaleDocuments() {
        BulkFailureException failure = bulkFailure("2");
        SearchHits<EsProduct> existingHits = searchHits(products(1L, 2L, 9L), 3L);
        when(productDao.getAllEsProductList(isNull())).thenReturn(Arrays.asList(product(1L), product(2L)));
        when(productRepository.saveAll(anyList()))
                .thenThrow(failure)
                .thenThrow(new IllegalStateException("retry failed"));
        when(elasticsearchTemplate.search(any(NativeQuery.class), eq(EsProduct.class))).thenReturn(existingHits);

        assertThrows(IllegalStateException.class, () -> esProductService.importAll());

        verify(productRepository, times(2)).saveAll(anyList());
        verify(productRepository, never()).deleteAllById(any(Iterable.class));
        verify(elasticsearchTemplate, never()).search(any(NativeQuery.class), eq(EsProduct.class));
    }

    @Test
    public void testUnknownBulkFailureIdDoesNotAllowStaleCleanup() {
        BulkFailureException failure = bulkFailure("999");
        when(productDao.getAllEsProductList(isNull())).thenReturn(Collections.singletonList(product(1L)));
        when(productRepository.saveAll(anyList())).thenThrow(failure);

        assertThrows(BulkFailureException.class, () -> esProductService.importAll());

        verify(productRepository, times(1)).saveAll(anyList());
        verify(productRepository, never()).deleteAllById(any(Iterable.class));
        verify(elasticsearchTemplate, never()).search(any(NativeQuery.class), eq(EsProduct.class));
    }

    @Test
    public void testConcurrentImportAllIsRejected() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(productDao.getAllEsProductList(isNull())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Collections.singletonList(product(1L));
        });
        SearchHits<EsProduct> emptyHits = searchHits(Collections.emptyList(), 0L);
        when(elasticsearchTemplate.search(any(NativeQuery.class), eq(EsProduct.class))).thenReturn(emptyHits);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> running = executor.submit(() -> esProductService.importAll());
            assertTrue(started.await(5, TimeUnit.SECONDS), "首个导入应已进入MySQL读取阶段");
            assertThrows(ImportAllConflictException.class, () -> esProductService.importAll(),
                    "同一实例不允许并发执行两个全量导入");
            release.countDown();
            assertEquals(1, running.get(5, TimeUnit.SECONDS).intValue());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private List<Long> captureDeletedIds() {
        ArgumentCaptor<Iterable> idCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(productRepository, times(1)).deleteAllById(idCaptor.capture());
        List<Long> deletedIds = new ArrayList<>();
        for (Object id : idCaptor.getValue()) {
            deletedIds.add((Long) id);
        }
        return deletedIds;
    }

    private List<Long> captureSavedIds() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<EsProduct>> savedCaptor = (ArgumentCaptor<Iterable<EsProduct>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(Iterable.class);
        verify(productRepository, times(1)).saveAll(savedCaptor.capture());
        List<Long> savedIds = new ArrayList<>();
        for (EsProduct esProduct : savedCaptor.getValue()) {
            savedIds.add(esProduct.getId());
        }
        return savedIds;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Iterable<EsProduct>> savedProductsCaptor() {
        return (ArgumentCaptor<Iterable<EsProduct>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Iterable.class);
    }

    private List<Long> ids(Iterable<EsProduct> products) {
        List<Long> ids = new ArrayList<>();
        for (EsProduct product : products) {
            ids.add(product.getId());
        }
        return ids;
    }

    private BulkFailureException bulkFailure(String failedId) {
        return new BulkFailureException("bulk failed",
                Map.of(failedId, new BulkFailureException.FailureDetails(500, "simulated failure")));
    }

    private EsProduct product(Long id) {
        EsProduct esProduct = new EsProduct();
        esProduct.setId(id);
        esProduct.setName("test-product");
        return esProduct;
    }

    private List<EsProduct> products(Long... ids) {
        return Arrays.stream(ids).map(this::product).collect(Collectors.toList());
    }

    private List<EsProduct> products(List<Long> ids) {
        return ids.stream().map(this::product).collect(Collectors.toList());
    }

    private List<Long> range(long startInclusive, int count) {
        return LongStream.range(startInclusive, startInclusive + count).boxed().collect(Collectors.toList());
    }

    /**
     * 构造指定文档总量的批次序列，并在末尾追加一个空批次用于终止遍历，
     * 用于验证超过10000条时只使用第0页游标请求
     */
    private List<SearchHits<EsProduct>> buildBatchesWithTerminator(int totalDocuments, int batchSize) {
        List<SearchHits<EsProduct>> batches = new ArrayList<>();
        long nextId = 1L;
        while (nextId <= totalDocuments) {
            int size = (int) Math.min(batchSize, totalDocuments - nextId + 1);
            List<EsProduct> contentList = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                contentList.add(product(nextId + i));
            }
            batches.add(searchHits(contentList, totalDocuments));
            nextId += size;
        }
        batches.add(searchHits(Collections.emptyList(), totalDocuments));
        return batches;
    }

    private SearchHits<EsProduct> searchHits(List<EsProduct> contentList, long totalHits) {
        SearchHits<EsProduct> searchHits = mock(SearchHits.class);
        List<SearchHit<EsProduct>> hits = contentList.stream()
                .map(this::searchHitFromContent)
                .collect(Collectors.toList());
        when(searchHits.getSearchHits()).thenReturn(hits);
        when(searchHits.getTotalHits()).thenReturn(totalHits);
        return searchHits;
    }

    private SearchHit<EsProduct> searchHitFromContent(EsProduct esProduct) {
        SearchHit<EsProduct> searchHit = mock(SearchHit.class);
        when(searchHit.getContent()).thenReturn(esProduct);
        if (esProduct != null && esProduct.getId() != null) {
            when(searchHit.getId()).thenReturn(String.valueOf(esProduct.getId()));
        }
        return searchHit;
    }

    private SearchHit<EsProduct> searchHitWithoutContent(String documentId) {
        SearchHit<EsProduct> searchHit = mock(SearchHit.class);
        when(searchHit.getContent()).thenReturn(null);
        when(searchHit.getId()).thenReturn(documentId);
        return searchHit;
    }
}

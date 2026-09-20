package com.macro.mall.search.service.impl;

import com.macro.mall.search.dao.EsProductDao;
import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.repository.EsProductRepository;
import com.macro.mall.search.util.SearchPageUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 搜索服务分页边界测试，覆盖1-based页码转换、空页与超出ES分页窗口的处理。
 * <p>
 * 只 mock DAO 与 Repository，不连接 Elasticsearch。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EsProductServiceImplSearchTest {

    @Mock
    private EsProductDao productDao;
    @Mock
    private EsProductRepository productRepository;
    @Mock
    private ElasticsearchTemplate elasticsearchTemplate;

    @InjectMocks
    private EsProductServiceImpl esProductService;

    @BeforeEach
    void setUp() {
        when(productRepository.findByNameOrSubTitleOrKeywords(any(), any(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(3);
                    return new PageImpl<>(Collections.singletonList(esProduct()), pageable, 1);
                });
    }

    @Test
    @DisplayName("简单搜索：对外1-based页码转换为ES需要的0-based偏移量")
    void searchConvertsOneBasedPageNumToZeroBased() {
        Page<EsProduct> page = esProductService.search("手机", 2, 3);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findByNameOrSubTitleOrKeywords(eq("手机"), eq("手机"), eq("手机"), captor.capture());
        assertEquals(1, captor.getValue().getPageNumber(), "第2页应转换为偏移量1");
        assertEquals(3, captor.getValue().getPageSize());
        assertEquals(2, page.getNumber() + 1, "对外结果仍应以1-based呈现");
        assertEquals(1, page.getContent().size());
    }

    @Test
    @DisplayName("简单搜索：页码为空或非法时按第一页处理")
    void searchNormalizesInvalidPageNum() {
        esProductService.search("手机", null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findByNameOrSubTitleOrKeywords(anyString(), anyString(), anyString(), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(SearchPageUtils.DEFAULT_PAGE_SIZE, captor.getValue().getPageSize());
    }

    @Test
    @DisplayName("简单搜索：页码为0时按第一页处理，不产生负偏移量")
    void searchWithZeroPageNumUsesFirstPage() {
        esProductService.search("手机", 0, 5);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findByNameOrSubTitleOrKeywords(anyString(), anyString(), anyString(), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
    }

    @Test
    @DisplayName("简单搜索：每页数量超过上限时按上限处理，避免单次拉取过量数据")
    void searchCapsPageSize() {
        esProductService.search("手机", 1, 500);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findByNameOrSubTitleOrKeywords(anyString(), anyString(), anyString(), captor.capture());
        assertEquals(SearchPageUtils.MAX_PAGE_SIZE, captor.getValue().getPageSize());
    }

    @Test
    @DisplayName("简单搜索：超出ES分页窗口时直接返回空页，不再请求ES")
    void searchBeyondMaxResultWindowReturnsEmptyPage() {
        Page<EsProduct> page = esProductService.search("手机", 2000, 10);

        assertTrue(page.getContent().isEmpty(), "超出窗口应返回空列表");
        assertEquals(2000, page.getNumber() + 1, "空页也必须保留请求页码，避免前端跳回第一页");
        verify(productRepository, never()).findByNameOrSubTitleOrKeywords(any(), any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("推荐商品：超出ES分页窗口时返回空页且不查询商品详情")
    void recommendBeyondMaxResultWindowReturnsEmptyPage() {
        Page<EsProduct> page = esProductService.recommend(1L, 2000, 10);

        assertTrue(page.getContent().isEmpty());
        assertEquals(2000, page.getNumber() + 1);
        verify(productDao, never()).getAllEsProductList(any());
    }

    @Test
    @DisplayName("空结果：ES返回空列表时仍保持请求页码")
    void emptyResultKeepsPageNum() {
        when(productRepository.findByNameOrSubTitleOrKeywords(any(), any(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(3);
                    return new PageImpl<>(Collections.<EsProduct>emptyList(), pageable, 0);
                });

        Page<EsProduct> page = esProductService.search("不存在的商品", 3, 5);

        assertTrue(page.getContent().isEmpty());
        assertEquals(3, page.getNumber() + 1);
    }

    private EsProduct esProduct() {
        EsProduct esProduct = new EsProduct();
        esProduct.setId(1L);
        esProduct.setName("测试商品");
        return esProduct;
    }
}

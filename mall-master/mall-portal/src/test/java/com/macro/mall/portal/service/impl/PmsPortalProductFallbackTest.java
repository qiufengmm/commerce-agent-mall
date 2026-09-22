package com.macro.mall.portal.service.impl;

import com.github.pagehelper.PageHelper;
import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.exception.ApiException;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.model.PmsProductExample;
import com.macro.mall.portal.client.MallSearchClient;
import com.macro.mall.portal.config.MallSearchClientProperties;
import com.macro.mall.portal.domain.EsProductDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MySQL降级搜索测试
 * 使用Mockito替身验证降级开关、失败快速返回、分页契约、排序稳定性和筛选条件一致性，不查询真实数据库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class PmsPortalProductFallbackTest {
    private static final Long BRAND_ID = 23L;
    private static final Long CATEGORY_ID = 31L;

    @Mock
    private PmsProductMapper productMapper;
    @Mock
    private MallSearchClient mallSearchClient;

    private PmsPortalProductServiceImpl portalProductService;
    private MallSearchClientProperties searchClientProperties;

    @BeforeEach
    void setUp() {
        portalProductService = new PmsPortalProductServiceImpl();
        searchClientProperties = new MallSearchClientProperties();
        ReflectionTestUtils.setField(portalProductService, "productMapper", productMapper);
        ReflectionTestUtils.setField(portalProductService, "mallSearchClient", mallSearchClient);
        ReflectionTestUtils.setField(portalProductService, "mallSearchClientProperties", searchClientProperties);
    }

    @AfterEach
    void tearDown() {
        PageHelper.clearPage();
    }

    @Test
    public void testEsSuccessDoesNotQueryMySql() {
        searchClientProperties.setMysqlFallbackEnabled(true);
        when(mallSearchClient.searchProduct(any(), any(), any(), any(), any(), any())).thenReturn(esPage());

        CommonPage<PmsProduct> result = portalProductService.search("手机", BRAND_ID, CATEGORY_ID, 1, 5, 1);

        assertEquals(1, result.getList().size());
        verify(productMapper, never()).selectByExample(any(PmsProductExample.class));
    }

    @Test
    public void testEsFailureFailsFastWhenFallbackDisabled() {
        searchClientProperties.setMysqlFallbackEnabled(false);
        ApiException failure = new ApiException("模拟搜索服务不可用");
        when(mallSearchClient.searchProduct(any(), any(), any(), any(), any(), any())).thenThrow(failure);

        ApiException thrown = assertThrows(ApiException.class,
                () -> portalProductService.search("手机", BRAND_ID, CATEGORY_ID, 1, 5, 1));

        assertSame(failure, thrown, "降级开关关闭时必须原样暴露搜索服务异常");
        verify(productMapper, never()).selectByExample(any(PmsProductExample.class));
    }

    @Test
    public void testEsFailureFallsBackToMySqlWhenEnabled() {
        searchClientProperties.setMysqlFallbackEnabled(true);
        whenSearchServiceFails();
        when(productMapper.selectByExample(any(PmsProductExample.class))).thenReturn(mySqlProducts(2));

        CommonPage<PmsProduct> result = portalProductService.search("手机", BRAND_ID, CATEGORY_ID, 2, 5, 1);

        assertEquals(2, result.getList().size());
        assertEquals(2, result.getPageNum(), "降级结果必须保持对外指定的pageNum");
        assertEquals(5, result.getPageSize(), "降级结果必须保持对外指定的pageSize");
        assertEquals(2L, result.getTotal(), "降级结果必须保留MySQL查询的总数");
        assertEquals(1, result.getTotalPage(), "降级结果必须计算总页数");
    }

    @Test
    public void testPageHelperPageTotalIsPreferred() {
        searchClientProperties.setMysqlFallbackEnabled(true);
        whenSearchServiceFails();
        com.github.pagehelper.Page<PmsProduct> page = new com.github.pagehelper.Page<>();
        page.addAll(mySqlProducts(2));
        page.setTotal(12L);
        when(productMapper.selectByExample(any(PmsProductExample.class))).thenReturn(page);

        CommonPage<PmsProduct> result = portalProductService.search("手机", BRAND_ID, CATEGORY_ID, 3, 5, 1);

        assertEquals(12L, result.getTotal());
        assertEquals(3, result.getTotalPage());
        assertEquals(3, result.getPageNum());
    }

    @Test
    public void testIllegalPageParamsAreNormalized() {
        for (int[] illegalPage : new int[][]{{0, 0}, {-1, -2}, {0, 5}}) {
            PageHelper.clearPage();
            resetFallbackStubs();
            whenSearchServiceFails();
            when(productMapper.selectByExample(any(PmsProductExample.class))).thenReturn(mySqlProducts(1));

            CommonPage<PmsProduct> result = portalProductService.search("手机", null, null, illegalPage[0], illegalPage[1], null);

            int expectedPageSize = illegalPage[1] < 1 ? 5 : illegalPage[1];
            assertEquals(1, result.getPageNum(), "非法pageNum必须归一化为1");
            assertEquals(expectedPageSize, result.getPageSize(), "非法pageSize必须归一化为默认值");
            assertTrue(result.getTotalPage() >= 1);
            verify(mallSearchClient).searchProduct("手机", null, null, 1, expectedPageSize, null);
        }
    }

    @Test
    public void testSortValuesUseExpectedOrderBy() {
        assertFallbackOrderBy(null, "id desc");
        assertFallbackOrderBy(0, "id desc");
        assertFallbackOrderBy(9, "id desc");
        assertFallbackOrderBy(1, "id desc");
        assertFallbackOrderBy(2, "sale desc");
        assertFallbackOrderBy(3, "price asc");
        assertFallbackOrderBy(4, "price desc");
    }

    @Test
    public void testMySqlExceptionIsNotSwallowed() {
        searchClientProperties.setMysqlFallbackEnabled(true);
        whenSearchServiceFails();
        IllegalStateException mySqlFailure = new IllegalStateException("模拟MySQL查询失败");
        when(productMapper.selectByExample(any(PmsProductExample.class))).thenThrow(mySqlFailure);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> portalProductService.search("手机", BRAND_ID, CATEGORY_ID, 1, 5, 1));

        assertSame(mySqlFailure, thrown, "降级查询自身的异常不得被降级逻辑吞掉");
    }

    @Test
    public void testFallbackKeepsOriginalFilters() {
        searchClientProperties.setMysqlFallbackEnabled(true);
        whenSearchServiceFails();
        when(productMapper.selectByExample(any(PmsProductExample.class))).thenReturn(mySqlProducts(1));

        portalProductService.search("手机", BRAND_ID, CATEGORY_ID, 1, 5, 1);

        PmsProductExample example = captureExample();
        List<String> conditions = ((PmsProductExample.Criteria) example.getOredCriteria().get(0)).getAllCriteria()
                .stream().map(PmsProductExample.Criterion::getCondition).collect(Collectors.toList());
        assertTrue(containsCondition(conditions, "delete_status"), "降级查询必须保留删除状态筛选");
        assertTrue(containsCondition(conditions, "publish_status"), "降级查询必须保留上架状态筛选");
        assertTrue(containsCondition(conditions, "name"), "降级查询必须保留关键字筛选");
        assertTrue(containsCondition(conditions, "brand_id"), "降级查询必须保留品牌筛选");
        assertTrue(containsCondition(conditions, "product_category_id"), "降级查询必须保留分类筛选");
        assertNotNull(example.getOrderByClause(), "降级查询必须设置稳定排序");
    }

    @Test
    public void testMallSearchClientCallUsesNormalizedPage() {
        searchClientProperties.setMysqlFallbackEnabled(true);
        when(mallSearchClient.searchProduct(any(), any(), any(), any(), any(), any())).thenReturn(esPage());

        portalProductService.search("手机", BRAND_ID, CATEGORY_ID, null, null, 1);

        verify(mallSearchClient).searchProduct("手机", BRAND_ID, CATEGORY_ID, 1, 5, 1);
    }

    /**
     * 每个降级动作重放前重置替身，保证单次动作内的调用次数断言准确
     */
    private void resetFallbackStubs() {
        reset(productMapper, mallSearchClient);
        searchClientProperties.setMysqlFallbackEnabled(true);
        when(productMapper.selectByExample(any(PmsProductExample.class))).thenReturn(mySqlProducts(1));
    }

    /**
     * 使用doThrow打桩搜索服务失败，避免重复打桩时先触发上一次的异常
     */
    private void whenSearchServiceFails() {
        doThrow(new ApiException("模拟搜索服务不可用")).when(mallSearchClient)
                .searchProduct(any(), any(), any(), any(), any(), any());
    }

    private boolean containsCondition(List<String> conditions, String column) {
        return conditions.stream().anyMatch(condition -> condition != null && condition.contains(column));
    }

    private void assertFallbackOrderBy(Integer sort, String expectedOrderBy) {
        PageHelper.clearPage();
        resetFallbackStubs();
        whenSearchServiceFails();

        portalProductService.search("手机", BRAND_ID, CATEGORY_ID, 1, 5, sort);

        assertEquals(expectedOrderBy, captureExample().getOrderByClause(), "sort=" + sort + " 必须使用预期排序");
    }

    private PmsProductExample captureExample() {
        ArgumentCaptor<PmsProductExample> exampleCaptor = ArgumentCaptor.forClass(PmsProductExample.class);
        verify(productMapper).selectByExample(exampleCaptor.capture());
        return exampleCaptor.getValue();
    }

    private CommonPage<EsProductDTO> esPage() {
        CommonPage<EsProductDTO> page = new CommonPage<>();
        EsProductDTO esProduct = new EsProductDTO();
        esProduct.setId(1L);
        esProduct.setName("搜索服务商品");
        page.setList(Collections.singletonList(esProduct));
        page.setPageNum(1);
        page.setPageSize(5);
        page.setTotal(1L);
        page.setTotalPage(1);
        return page;
    }

    private List<PmsProduct> mySqlProducts(int count) {
        List<PmsProduct> products = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PmsProduct product = new PmsProduct();
            product.setId((long) (i + 1));
            product.setName("MySQL商品" + i);
            products.add(product);
        }
        return products;
    }
}

package com.macro.mall.search.controller;

import com.macro.mall.common.api.ResultCode;
import com.macro.mall.search.config.SearchSyncProperties;
import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.service.EsProductService;
import com.macro.mall.search.util.SearchPageUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 搜索接口控制器测试，重点验证对外保持 1-based pageNum。
 * <p>
 * 使用 standalone MockMvc，不启动 Spring 容器，不连接 Elasticsearch。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EsProductControllerSearchTest {

    @Mock
    private EsProductService esProductService;
    @Mock
    private SearchSyncProperties searchSyncProperties;

    @InjectMocks
    private EsProductController esProductController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(esProductController).build();
    }

    @Test
    @DisplayName("简单搜索：页码与每页数量原样透传给服务，响应页码保持1-based")
    void simpleSearchKeepsOneBasedPageNum() throws Exception {
        when(esProductService.search(eq("手机"), anyInt(), anyInt()))
                .thenAnswer(invocation -> page(invocation.getArgument(1), invocation.getArgument(2), 1));

        mockMvc.perform(get("/esProduct/search/simple")
                        .param("keyword", "手机")
                        .param("pageNum", "2")
                        .param("pageSize", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.pageNum").value(2))
                .andExpect(jsonPath("$.data.list[0].id").value(1));

        verify(esProductService).search("手机", 2, 3);
    }

    @Test
    @DisplayName("简单搜索：缺省参数使用 pageNum=1、pageSize=5")
    void simpleSearchUsesDefaultPageParams() throws Exception {
        when(esProductService.search(isNull(), anyInt(), anyInt()))
                .thenAnswer(invocation -> page(invocation.getArgument(1), invocation.getArgument(2), 1));

        mockMvc.perform(get("/esProduct/search/simple"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNum").value(1));

        verify(esProductService).search(null, 1, 5);
    }

    @Test
    @DisplayName("综合搜索：关键字、品牌、分类与排序参数原样透传")
    void searchPassesAllFilterParams() throws Exception {
        when(esProductService.search(any(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenAnswer(invocation -> page(invocation.getArgument(3), invocation.getArgument(4), 1));

        mockMvc.perform(get("/esProduct/search")
                        .param("keyword", "手机")
                        .param("brandId", "1")
                        .param("productCategoryId", "2")
                        .param("pageNum", "2")
                        .param("pageSize", "10")
                        .param("sort", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNum").value(2));

        verify(esProductService).search("手机", 1L, 2L, 2, 10, 3);
    }

    @Test
    @DisplayName("综合搜索：缺省参数使用 pageNum=1、pageSize=5、sort=0")
    void searchUsesDefaultParams() throws Exception {
        when(esProductService.search(isNull(), isNull(), isNull(), anyInt(), anyInt(), anyInt()))
                .thenAnswer(invocation -> page(invocation.getArgument(3), invocation.getArgument(4), 1));

        mockMvc.perform(get("/esProduct/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNum").value(1));

        verify(esProductService).search(null, null, null, 1, 5, 0);
    }

    @Test
    @DisplayName("综合搜索：只传排序时其余筛选条件为空")
    void searchWithSortOnlyKeepsNullFilters() throws Exception {
        when(esProductService.search(any(), isNull(), isNull(), anyInt(), anyInt(), anyInt()))
                .thenAnswer(invocation -> page(invocation.getArgument(3), invocation.getArgument(4), 1));

        mockMvc.perform(get("/esProduct/search").param("keyword", "手机").param("sort", "4"))
                .andExpect(status().isOk());

        verify(esProductService).search("手机", null, null, 1, 5, 4);
    }

    @Test
    @DisplayName("空结果：返回空列表但页码仍然是请求页码，避免前端误判为第一页")
    void emptyResultKeepsRequestedPageNum() throws Exception {
        when(esProductService.search(eq("不存在的商品"), anyInt(), anyInt()))
                .thenReturn(SearchPageUtils.emptyPage(3, 10));

        mockMvc.perform(get("/esProduct/search/simple")
                        .param("keyword", "不存在的商品")
                        .param("pageNum", "3")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.pageNum").value(3))
                .andExpect(jsonPath("$.data.list").isEmpty());
    }

    @Test
    @DisplayName("超出ES分页窗口：返回空页而不是把异常抛给调用方")
    void beyondMaxResultWindowReturnsEmptyPage() throws Exception {
        when(esProductService.search(eq("手机"), anyInt(), anyInt()))
                .thenAnswer(invocation -> SearchPageUtils.emptyPage(invocation.getArgument(1), invocation.getArgument(2)));

        mockMvc.perform(get("/esProduct/search/simple")
                        .param("keyword", "手机")
                        .param("pageNum", "2000")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.pageNum").value(2000))
                .andExpect(jsonPath("$.data.list").isEmpty());
    }

    private Page<EsProduct> page(Integer pageNum, Integer pageSize, int itemCount) {
        EsProduct esProduct = new EsProduct();
        esProduct.setId(1L);
        esProduct.setName("测试商品");
        List<EsProduct> content = itemCount > 0 ? Collections.singletonList(esProduct) : Collections.emptyList();
        return new PageImpl<>(content, SearchPageUtils.toPageable(pageNum, pageSize), itemCount);
    }
}

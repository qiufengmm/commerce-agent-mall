package com.macro.mall.portal.client;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.exception.ApiException;
import com.macro.mall.portal.config.MallSearchClientProperties;
import com.macro.mall.portal.domain.EsProductDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * MallSearchClient 分页契约回归测试
 * 锁定“门户 1-based 页码原样透传给 mall-search”的契约，防止两端再次出现页码基准换算导致的分页漂移
 */
class MallSearchClientTest {

    private MockRestServiceServer mockServer;
    private MallSearchClient mallSearchClient;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();
        mallSearchClient = new MallSearchClient(restTemplate, new MallSearchClientProperties());
    }

    @Test
    void shouldPassThroughOneBasedPageNum() {
        //MockRestServiceServer 要求先登记全部期望，再发起请求
        for (int pageNum = 1; pageNum <= 3; pageNum++) {
            int expectedPageNum = pageNum;
            mockServer.expect(request -> assertEquals(String.valueOf(expectedPageNum),
                            queryParamOf(request.getURI(), "pageNum"),
                            "门户必须把 1-based 页码原样透传给 mall-search"))
                    .andRespond(withSuccess(successBody(expectedPageNum, 5, 12L, List.of(10L * expectedPageNum)),
                            MediaType.APPLICATION_JSON));
        }

        for (int pageNum = 1; pageNum <= 3; pageNum++) {
            CommonPage<EsProductDTO> page = mallSearchClient.searchProduct(null, null, null, pageNum, 5, 0);

            assertEquals(pageNum, page.getPageNum());
            assertEquals(5, page.getPageSize());
            assertEquals(12L, page.getTotal());
            assertEquals(3, page.getTotalPage());
            assertEquals(1, page.getList().size());
            assertEquals(10L * pageNum, page.getList().get(0).getId());
        }
        mockServer.verify();
    }

    @Test
    void shouldNormalizeIllegalPageNumToOne() {
        mockServer.expect(request -> assertEquals("1", queryParamOf(request.getURI(), "pageNum")))
                .andRespond(withSuccess(successBody(1, 5, 0L, List.of()), MediaType.APPLICATION_JSON));
        mockServer.expect(request -> assertEquals("1", queryParamOf(request.getURI(), "pageNum")))
                .andRespond(withSuccess(successBody(1, 5, 0L, List.of()), MediaType.APPLICATION_JSON));

        mallSearchClient.searchProduct(null, null, null, 0, 5, 0);
        mallSearchClient.searchProduct(null, null, null, null, 5, 0);

        mockServer.verify();
    }

    @Test
    void shouldPassThroughPageSizeAndOtherFilters() {
        mockServer.expect(request -> {
                    URI uri = request.getURI();
                    assertEquals("2", queryParamOf(uri, "pageNum"));
                    assertEquals("5", queryParamOf(uri, "pageSize"));
                    assertEquals("0", queryParamOf(uri, "sort"));
                    assertEquals("1", queryParamOf(uri, "brandId"));
                    assertEquals("2", queryParamOf(uri, "productCategoryId"));
                })
                .andRespond(withSuccess(successBody(2, 5, 6L, List.of(20L)), MediaType.APPLICATION_JSON));

        CommonPage<EsProductDTO> page = mallSearchClient.searchProduct(null, 1L, 2L, 2, 5, 0);

        assertEquals(2, page.getPageNum());
        mockServer.verify();
    }

    @Test
    void shouldEncodeChineseKeywordAsUtf8() {
        String keyword = "小米手机";
        mockServer.expect(request -> {
                    URI uri = request.getURI();
                    String rawQuery = uri.getRawQuery();
                    assertNotNull(rawQuery);
                    assertTrue(rawQuery.contains("keyword=%E5%B0%8F%E7%B1%B3%E6%89%8B%E6%9C%BA"),
                            "中文关键词必须按 UTF-8 转义，实际查询串: " + rawQuery);
                    assertEquals(keyword, decodedQueryParamOf(uri, "keyword"));
                })
                .andRespond(withSuccess(successBody(1, 5, 1L, List.of(1L)), MediaType.APPLICATION_JSON));

        CommonPage<EsProductDTO> page = mallSearchClient.searchProduct(keyword, null, null, 1, 5, 0);

        assertEquals(1, page.getList().size());
        mockServer.verify();
    }

    @Test
    void shouldThrowApiExceptionWhenSearchServiceReturnsFailedResult() {
        mockServer.expect(anything()).andRespond(withSuccess(
                "{\"code\":500,\"message\":\"ES 索引异常\",\"data\":null}", MediaType.APPLICATION_JSON));

        ApiException exception = assertThrows(ApiException.class,
                () -> mallSearchClient.searchProduct(null, null, null, 1, 5, 0));

        assertTrue(exception.getMessage().contains("ES 索引异常"));
        mockServer.verify();
    }

    @Test
    void shouldThrowApiExceptionWhenSearchServiceUnavailable() {
        mockServer.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        ApiException exception = assertThrows(ApiException.class,
                () -> mallSearchClient.searchProduct(null, null, null, 1, 5, 0));

        assertTrue(exception.getMessage().contains("搜索服务"));
        mockServer.verify();
    }

    @Test
    void shouldReturnEmptyPageWhenSearchServiceReturnsNoData() {
        mockServer.expect(anything()).andRespond(withSuccess(
                "{\"code\":200,\"message\":\"操作成功\",\"data\":null}", MediaType.APPLICATION_JSON));

        CommonPage<EsProductDTO> page = mallSearchClient.searchProduct(null, null, null, 1, 5, 0);

        assertNotNull(page.getList());
        assertTrue(page.getList().isEmpty());
        assertEquals(0L, page.getTotal());
        assertEquals(0, page.getTotalPage());
        assertEquals(5, page.getPageSize());
        mockServer.verify();
    }

    private static Map<String, String> queryParamsOf(URI uri) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap();
    }

    private static String queryParamOf(URI uri, String name) {
        return queryParamsOf(uri).get(name);
    }

    private static String decodedQueryParamOf(URI uri, String name) {
        String value = queryParamOf(uri, name);
        return value == null ? null : UriUtils.decode(value, StandardCharsets.UTF_8);
    }

    private static String successBody(int pageNum, int pageSize, long total, List<Long> productIds) {
        int totalPage = total == 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
        String list = productIds.stream()
                .map(id -> "{\"id\":" + id + ",\"name\":\"product-" + id + "\"}")
                .collect(Collectors.joining(","));
        return "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"pageNum\":" + pageNum
                + ",\"pageSize\":" + pageSize
                + ",\"totalPage\":" + totalPage
                + ",\"total\":" + total
                + ",\"list\":[" + list + "]}}";
    }
}

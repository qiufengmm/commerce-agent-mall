package com.macro.mall.portal.client;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.common.api.ResultCode;
import com.macro.mall.common.exception.ApiException;
import com.macro.mall.portal.config.MallSearchClientConfig;
import com.macro.mall.portal.config.MallSearchClientProperties;
import com.macro.mall.portal.domain.EsProductDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * 搜索服务（mall-search）内部客户端
 * 只被 mall-portal 内部调用，内部服务地址不会返回给移动端
 */
@Component
public class MallSearchClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(MallSearchClient.class);
    /**
     * 外部（移动端/门户）与搜索服务统一使用的 1-based 起始页码
     */
    private static final int DEFAULT_PAGE_NUM = 1;
    /**
     * 默认排序方式（按相关度）
     */
    private static final int DEFAULT_SORT = 0;

    private final RestTemplate mallSearchRestTemplate;
    private final MallSearchClientProperties mallSearchClientProperties;

    public MallSearchClient(@Qualifier(MallSearchClientConfig.MALL_SEARCH_REST_TEMPLATE) RestTemplate mallSearchRestTemplate,
                            MallSearchClientProperties mallSearchClientProperties) {
        this.mallSearchRestTemplate = mallSearchRestTemplate;
        this.mallSearchClientProperties = mallSearchClientProperties;
    }

    /**
     * 调用 mall-search 的 /esProduct/search 查询商品
     *
     * @param pageNum 对外统一的 1-based 页码，直接透传给搜索服务（mall-search 对外同样为 1-based）
     */
    public CommonPage<EsProductDTO> searchProduct(String keyword, Long brandId, Long productCategoryId,
                                                  Integer pageNum, Integer pageSize, Integer sort) {
        //门户与 mall-search 已统一为 1-based，直接透传，仅对空值与非法页码做兜底归一化
        int searchPageNum = pageNum == null || pageNum < DEFAULT_PAGE_NUM ? DEFAULT_PAGE_NUM : pageNum;
        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        if (StringUtils.hasText(keyword)) {
            queryParams.add("keyword", keyword);
        }
        if (brandId != null) {
            queryParams.add("brandId", String.valueOf(brandId));
        }
        if (productCategoryId != null) {
            queryParams.add("productCategoryId", String.valueOf(productCategoryId));
        }
        queryParams.add("pageNum", String.valueOf(searchPageNum));
        queryParams.add("pageSize", String.valueOf(pageSize));
        queryParams.add("sort", String.valueOf(sort == null ? DEFAULT_SORT : sort));
        //先构建未编码的URI，再统一 encode，保证中文关键字等参数被正确转义
        URI uri = UriComponentsBuilder
                .fromUriString(mallSearchClientProperties.getBaseUrl())
                .path(mallSearchClientProperties.getSearchPath())
                .queryParams(queryParams)
                .build()
                .encode()
                .toUri();
        try {
            ResponseEntity<CommonResult<CommonPage<EsProductDTO>>> responseEntity = mallSearchRestTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    new ParameterizedTypeReference<CommonResult<CommonPage<EsProductDTO>>>() {
                    });
            CommonResult<CommonPage<EsProductDTO>> result = responseEntity.getBody();
            if (result == null) {
                LOGGER.error("调用搜索服务失败，返回结果为空，请求地址: {}", uri);
                throw new ApiException("商品搜索服务暂时不可用，请稍后重试");
            }
            if (ResultCode.SUCCESS.getCode() != result.getCode()) {
                LOGGER.error("调用搜索服务返回失败结果，请求地址: {}, code: {}, message: {}", uri, result.getCode(), result.getMessage());
                throw new ApiException("商品搜索服务返回异常：" + result.getMessage());
            }
            CommonPage<EsProductDTO> page = result.getData();
            if (page == null) {
                return emptyPage(pageSize);
            }
            if (page.getList() == null) {
                page.setList(List.of());
            }
            return page;
        } catch (RestClientException e) {
            LOGGER.error("调用搜索服务异常，请求地址: {}, 请求参数: {}, 异常原因: {}", uri, queryParams, e.getMessage(), e);
            throw new ApiException("商品搜索服务暂时不可用，请稍后重试", e);
        }
    }

    private CommonPage<EsProductDTO> emptyPage(Integer pageSize) {
        CommonPage<EsProductDTO> page = new CommonPage<>();
        page.setList(List.of());
        page.setPageSize(pageSize);
        page.setTotal(0L);
        page.setTotalPage(0);
        return page;
    }
}

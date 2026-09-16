package com.macro.mall.search.controller;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.common.api.ResultCode;
import com.macro.mall.search.config.SearchSyncProperties;
import com.macro.mall.search.service.EsProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 批量同步接口参数校验单元测试，不启动Spring容器
 * 使用真实SearchSyncProperties实例，同时验证非法配置下的钳制逻辑
 */
@ExtendWith(MockitoExtension.class)
public class EsProductControllerSyncTest {
    private static final int MAX_BATCH_SIZE = 100;

    @Mock
    private EsProductService esProductService;
    @InjectMocks
    private EsProductController esProductController;
    private SearchSyncProperties searchSyncProperties;

    @BeforeEach
    public void setUp() {
        searchSyncProperties = new SearchSyncProperties();
        ReflectionTestUtils.setField(esProductController, "searchSyncProperties", searchSyncProperties);
    }

    private List<Long> ids(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(Long::valueOf).collect(Collectors.toList());
    }

    @Test
    public void testRejectNullIds() {
        ResponseEntity<CommonResult<Object>> response = esProductController.sync((List<Long>) null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertValidateFailed(response);
        verifyNoInteractions(esProductService);
    }

    @Test
    public void testRejectEmptyIds() {
        ResponseEntity<CommonResult<Object>> response = esProductController.sync(Collections.emptyList());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertValidateFailed(response);
        verifyNoInteractions(esProductService);
    }

    @Test
    public void testRejectNullOnlyIds() {
        ResponseEntity<CommonResult<Object>> response = esProductController.sync(Arrays.asList(null, null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertValidateFailed(response);
        verifyNoInteractions(esProductService);
    }

    @Test
    public void testRejectWhenExceedMaxBatchSize() {
        searchSyncProperties.setMaxSyncBatchSize(MAX_BATCH_SIZE);

        ResponseEntity<CommonResult<Object>> response = esProductController.sync(ids(MAX_BATCH_SIZE + 1));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertValidateFailed(response);
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains(String.valueOf(MAX_BATCH_SIZE)),
                "错误信息应包含实际限制数量");
        verifyNoInteractions(esProductService);
    }

    @Test
    public void testPassWhenEqualsMaxBatchSize() {
        List<Long> requestIds = ids(MAX_BATCH_SIZE);
        searchSyncProperties.setMaxSyncBatchSize(MAX_BATCH_SIZE);

        ResponseEntity<CommonResult<Object>> response = esProductController.sync(requestIds);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(esProductService).sync(requestIds);
    }

    @Test
    public void testDistinctBeforeLimitCheck() {
        List<Long> requestIds = new ArrayList<>(ids(MAX_BATCH_SIZE));
        requestIds.addAll(ids(MAX_BATCH_SIZE));
        searchSyncProperties.setMaxSyncBatchSize(MAX_BATCH_SIZE);

        ResponseEntity<CommonResult<Object>> response = esProductController.sync(requestIds);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(esProductService).sync(ids(MAX_BATCH_SIZE));
    }

    @Test
    public void testFilterNullBeforeSync() {
        searchSyncProperties.setMaxSyncBatchSize(MAX_BATCH_SIZE);

        ResponseEntity<CommonResult<Object>> response = esProductController.sync(Arrays.asList(1L, null, 2L));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(esProductService).sync(Arrays.asList(1L, 2L));
    }

    /**
     * 配置为0时限制不能被关闭，101个ID仍被拒绝
     */
    @Test
    public void testRejectWhenConfigIsZero() {
        searchSyncProperties.setMaxSyncBatchSize(0);

        ResponseEntity<CommonResult<Object>> response = esProductController.sync(ids(101));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertValidateFailed(response);
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains(String.valueOf(MAX_BATCH_SIZE)));
        verifyNoInteractions(esProductService);
    }

    /**
     * 配置为负数时限制不能被关闭，101个ID仍被拒绝
     */
    @Test
    public void testRejectWhenConfigIsNegative() {
        searchSyncProperties.setMaxSyncBatchSize(-1);

        ResponseEntity<CommonResult<Object>> response = esProductController.sync(ids(101));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertValidateFailed(response);
        verifyNoInteractions(esProductService);
    }

    /**
     * 配置超过硬上限时仍按上限处理，101个ID被拒绝
     */
    @Test
    public void testRejectWhenConfigExceedsHardLimit() {
        searchSyncProperties.setMaxSyncBatchSize(101);

        ResponseEntity<CommonResult<Object>> response = esProductController.sync(ids(101));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertValidateFailed(response);
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains(String.valueOf(MAX_BATCH_SIZE)),
                "错误信息应包含实际限制数量100而不是配置值101");
        verifyNoInteractions(esProductService);
    }

    /**
     * 配置为硬上限100时，100个ID正常处理
     */
    @Test
    public void testPassWhenConfigIsHardLimitAndIdsEqualLimit() {
        searchSyncProperties.setMaxSyncBatchSize(100);
        List<Long> requestIds = ids(100);

        ResponseEntity<CommonResult<Object>> response = esProductController.sync(requestIds);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(esProductService).sync(requestIds);
    }

    /**
     * 配置为硬上限100时，101个ID被拒绝
     */
    @Test
    public void testRejectWhenConfigIsHardLimitAndIdsExceedLimit() {
        searchSyncProperties.setMaxSyncBatchSize(100);

        ResponseEntity<CommonResult<Object>> response = esProductController.sync(ids(101));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertValidateFailed(response);
        verifyNoInteractions(esProductService);
    }

    private void assertValidateFailed(ResponseEntity<CommonResult<Object>> response) {
        CommonResult<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(ResultCode.VALIDATE_FAILED.getCode(), body.getCode());
    }
}

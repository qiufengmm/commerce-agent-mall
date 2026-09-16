package com.macro.mall.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.config.SearchServiceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 商品索引同步HTTP调用单元测试，不依赖mall-search真实进程
 */
@ExtendWith(MockitoExtension.class)
public class EsProductSyncServiceImplTest {
    private static final String TOKEN = "unit-test-token";
    private static final String BASE_URL = "http://localhost:8081";

    @Mock
    private SearchServiceProperties searchServiceProperties;
    @Mock
    private HttpClient searchHttpClient;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks
    private EsProductSyncServiceImpl esProductSyncService;

    private void mockReachableConfig() {
        when(searchServiceProperties.getBaseUrl()).thenReturn(BASE_URL);
        when(searchServiceProperties.getReadTimeout()).thenReturn(5000);
        when(searchServiceProperties.getInternalToken()).thenReturn(TOKEN);
    }

    private HttpResponse<String> mockResponse(int statusCode) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        if (statusCode < 200 || statusCode >= 300) {
            when(response.body()).thenReturn("body");
        }
        return response;
    }

    @Test
    public void testSyncCarryInternalTokenHeader() throws Exception {
        mockReachableConfig();
        doReturn(mockResponse(200)).when(searchHttpClient).send(any(HttpRequest.class), any());

        esProductSyncService.sync(1L);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(searchHttpClient).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertEquals(TOKEN, request.headers().firstValue("X-Internal-Token").orElse(null));
        assertEquals(BASE_URL + "/esProduct/sync/1", request.uri().toString());
    }

    @Test
    public void testSyncBatchCarryInternalTokenHeader() throws Exception {
        mockReachableConfig();
        when(searchServiceProperties.getMaxSyncBatchSize()).thenReturn(100);
        doReturn(mockResponse(200)).when(searchHttpClient).send(any(HttpRequest.class), any());

        esProductSyncService.syncBatch(Arrays.asList(1L, 2L));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(searchHttpClient).send(captor.capture(), any());
        assertEquals(TOKEN, captor.getValue().headers().firstValue("X-Internal-Token").orElse(null));
        assertEquals(BASE_URL + "/esProduct/sync/batch", captor.getValue().uri().toString());
    }

    @Test
    public void testSyncNotFailWhenResponseIsNot2xx() throws Exception {
        mockReachableConfig();
        doReturn(mockResponse(401)).when(searchHttpClient).send(any(HttpRequest.class), any());

        assertDoesNotThrow(() -> esProductSyncService.sync(1L));
    }

    @Test
    public void testSyncNotFailWhenRequestTimeout() throws Exception {
        mockReachableConfig();
        doThrow(new IOException("connect timed out")).when(searchHttpClient).send(any(HttpRequest.class), any());

        assertDoesNotThrow(() -> esProductSyncService.sync(1L));
    }

    @Test
    public void testSyncNotFailWhenInterruptedAndRestoreFlag() throws Exception {
        mockReachableConfig();
        doThrow(new InterruptedException("interrupted")).when(searchHttpClient).send(any(HttpRequest.class), any());

        assertDoesNotThrow(() -> esProductSyncService.sync(1L));

        assertTrue(Thread.currentThread().isInterrupted(), "中断标记应被恢复");
        Thread.interrupted();
    }

    @Test
    public void testSyncBatchSplitByMaxBatchSize() throws Exception {
        mockReachableConfig();
        when(searchServiceProperties.getMaxSyncBatchSize()).thenReturn(100);
        doReturn(mockResponse(200)).when(searchHttpClient).send(any(HttpRequest.class), any());
        List<Long> ids = new ArrayList<>();
        for (long id = 1L; id <= 250L; id++) {
            ids.add(id);
        }

        esProductSyncService.syncBatch(ids);

        verify(searchHttpClient, times(3)).send(any(HttpRequest.class), any());
    }

    @Test
    public void testSyncSkipWhenTokenNotConfigured() {
        when(searchServiceProperties.getInternalToken()).thenReturn("");

        esProductSyncService.sync(1L);
        esProductSyncService.syncBatch(Arrays.asList(1L, 2L));

        verifyNoInteractions(searchHttpClient);
    }

    @Test
    public void testSyncSkipWhenIdsEmpty() {
        esProductSyncService.sync((Long) null);
        esProductSyncService.syncBatch(Collections.emptyList());
        esProductSyncService.syncBatch(Arrays.asList(null, null));

        verifyNoInteractions(searchHttpClient);
        verifyNoInteractions(searchServiceProperties);
    }

    @Test
    public void testSyncBatchIgnoreNullElement() throws Exception {
        mockReachableConfig();
        when(searchServiceProperties.getMaxSyncBatchSize()).thenReturn(100);
        doReturn(mockResponse(200)).when(searchHttpClient).send(any(HttpRequest.class), any());

        esProductSyncService.syncBatch(Arrays.asList(1L, null, 1L, 2L));

        verify(searchHttpClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    public void testSyncBatchFallbackWhenMaxBatchSizeIllegal() throws Exception {
        mockReachableConfig();
        when(searchServiceProperties.getMaxSyncBatchSize()).thenReturn(0);
        doReturn(mockResponse(200)).when(searchHttpClient).send(any(HttpRequest.class), any());
        List<Long> ids = new ArrayList<>();
        for (long id = 1L; id <= 150L; id++) {
            ids.add(id);
        }

        esProductSyncService.syncBatch(ids);

        verify(searchHttpClient, times(2)).send(any(HttpRequest.class), any());
    }

    /**
     * 配置为负数时同样回退到默认步长100，不允许步长为0
     */
    @Test
    public void testSyncBatchFallbackWhenMaxBatchSizeNegative() throws Exception {
        mockReachableConfig();
        when(searchServiceProperties.getMaxSyncBatchSize()).thenReturn(-1);
        doReturn(mockResponse(200)).when(searchHttpClient).send(any(HttpRequest.class), any());
        List<Long> ids = new ArrayList<>();
        for (long id = 1L; id <= 150L; id++) {
            ids.add(id);
        }

        esProductSyncService.syncBatch(ids);

        verify(searchHttpClient, times(2)).send(any(HttpRequest.class), any());
    }

    /**
     * 配置超过硬上限时步长被钳制为100，不会发出超过搜索服务上限的批量请求
     */
    @Test
    public void testSyncBatchClampWhenMaxBatchSizeExceedsHardLimit() throws Exception {
        mockReachableConfig();
        when(searchServiceProperties.getMaxSyncBatchSize()).thenReturn(101);
        doReturn(mockResponse(200)).when(searchHttpClient).send(any(HttpRequest.class), any());
        List<Long> ids = new ArrayList<>();
        for (long id = 1L; id <= 250L; id++) {
            ids.add(id);
        }

        esProductSyncService.syncBatch(ids);

        //步长按100处理而不是101，250个ID分3批
        verify(searchHttpClient, times(3)).send(any(HttpRequest.class), any());
    }

    @Test
    public void testSyncNotFailWhenSerializeFailed() throws Exception {
        mockReachableConfig();
        when(searchServiceProperties.getMaxSyncBatchSize()).thenReturn(100);
        doThrow(new JsonProcessingException("serialize failed") {
        }).when(objectMapper).writeValueAsString(any());

        assertDoesNotThrow(() -> esProductSyncService.syncBatch(Arrays.asList(1L, 2L)));

        verifyNoInteractions(searchHttpClient);
    }
}

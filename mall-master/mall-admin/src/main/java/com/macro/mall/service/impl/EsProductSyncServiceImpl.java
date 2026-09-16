package com.macro.mall.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.config.SearchServiceProperties;
import com.macro.mall.service.EsProductSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 商品索引同步Service实现类
 * 只把商品id传给搜索服务，由搜索服务从MySQL读取最新商品后更新或删除ES文档
 */
@Service
public class EsProductSyncServiceImpl implements EsProductSyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EsProductSyncServiceImpl.class);
    /**
     * 内部令牌请求头名称，与mall-search的校验过滤器保持一致
     */
    private static final String TOKEN_HEADER = "X-Internal-Token";
    private static final String SYNC_PATH = "/esProduct/sync/";
    private static final String SYNC_BATCH_PATH = "/esProduct/sync/batch";
    private static final String TOKEN_MISSING_REASON =
            "未配置mall.search.internal-token或环境变量MALL_SEARCH_INTERNAL_TOKEN";
    /**
     * 日志中响应内容的最大长度，避免异常响应把日志刷爆
     */
    private static final int MAX_LOG_BODY_LENGTH = 500;
    @Autowired
    private SearchServiceProperties searchServiceProperties;
    @Autowired
    private HttpClient searchHttpClient;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void sync(Long id) {
        if (id == null) {
            return;
        }
        String token = resolveToken();
        if (token == null) {
            LOGGER.warn("同步商品到Elasticsearch已跳过，商品id:{}，原因:{}", Collections.singletonList(id), TOKEN_MISSING_REASON);
            return;
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(SYNC_PATH + id)))
                .timeout(Duration.ofMillis(searchServiceProperties.getReadTimeout()))
                .header(TOKEN_HEADER, token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        send(request, Collections.singletonList(id));
    }

    @Override
    public void syncBatch(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        List<Long> idList = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (CollectionUtils.isEmpty(idList)) {
            return;
        }
        String token = resolveToken();
        if (token == null) {
            LOGGER.warn("同步商品到Elasticsearch已跳过，商品id:{}，原因:{}", idList, TOKEN_MISSING_REASON);
            return;
        }
        //搜索服务对单次批量数量有上限，超过上限时分批发送，避免整批被拒绝导致索引长期不一致
        int batchSize = resolveBatchSize();
        for (int fromIndex = 0; fromIndex < idList.size(); fromIndex += batchSize) {
            int toIndex = Math.min(fromIndex + batchSize, idList.size());
            sendBatch(idList.subList(fromIndex, toIndex), token);
        }
    }

    /**
     * 获取内部令牌，未配置时返回null，调用方必须明确失败而不是静默请求
     */
    private String resolveToken() {
        String token = searchServiceProperties.getInternalToken();
        if (token == null || token.isBlank()) {
            return null;
        }
        return token;
    }

    /**
     * 单次批量同步的商品数量上限，与mall-search服务端硬上限保持一致
     */
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int MAX_BATCH_SIZE_LIMIT = 100;

    /**
     * 单次批量同步的商品数量上限，双向钳制规则：
     * 配置值 <= 0：回退默认值100并记录warn；配置值 > 100：钳制为100并记录warn；配置值 1~100：使用配置值。
     * 保证分批步长永不为0，也不会超过搜索服务的批量上限。
     */
    private int resolveBatchSize() {
        int batchSize = searchServiceProperties.getMaxSyncBatchSize();
        if (batchSize <= 0) {
            LOGGER.warn("mall.search.max-sync-batch-size配置非法:{}，本次按默认值{}处理", batchSize, DEFAULT_BATCH_SIZE);
            return DEFAULT_BATCH_SIZE;
        }
        if (batchSize > MAX_BATCH_SIZE_LIMIT) {
            LOGGER.warn("mall.search.max-sync-batch-size配置:{}超过上限{}，本次按上限{}处理",
                    batchSize, MAX_BATCH_SIZE_LIMIT, MAX_BATCH_SIZE_LIMIT);
            return MAX_BATCH_SIZE_LIMIT;
        }
        return batchSize;
    }

    /**
     * 发送一批商品id，只在必要时用于分批发送
     */
    private void sendBatch(List<Long> idList, String token) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(buildUrl(SYNC_BATCH_PATH)))
                    .timeout(Duration.ofMillis(searchServiceProperties.getReadTimeout()))
                    .header("Content-Type", "application/json")
                    .header(TOKEN_HEADER, token)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(idList)))
                    .build();
        } catch (Exception e) {
            LOGGER.warn("同步商品到Elasticsearch失败，商品id:{}，原因:{}", idList, e.getMessage());
            return;
        }
        send(request, idList);
    }

    /**
     * 拼接搜索服务地址，去掉配置地址末尾多余的斜杠
     */
    private String buildUrl(String path) {
        String baseUrl = searchServiceProperties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8081";
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }

    /**
     * 发送同步请求，同步失败只记录warn日志，不向业务方法抛出异常
     */
    private void send(HttpRequest request, List<Long> ids) {
        try {
            HttpResponse<String> response = searchHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                LOGGER.warn("同步商品到Elasticsearch失败，商品id:{}，HTTP状态码:{}，响应内容:{}",
                        ids, statusCode, truncate(response.body()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("同步商品到Elasticsearch失败，商品id:{}，原因:{}", ids, e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("同步商品到Elasticsearch失败，商品id:{}，原因:{}", ids, e.getMessage());
        }
    }

    private String truncate(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() <= MAX_LOG_BODY_LENGTH) {
            return body;
        }
        return body.substring(0, MAX_LOG_BODY_LENGTH) + "...";
    }
}

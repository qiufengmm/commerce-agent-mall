package com.macro.mall.search.util;

import com.macro.mall.common.api.CommonPage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collections;

/**
 * 搜索分页参数处理工具
 * 对外统一使用1-based的pageNum，转换为Spring Data需要的0-based分页参数
 */
public final class SearchPageUtils {
    /**
     * 默认页码，对外从1开始
     */
    public static final int DEFAULT_PAGE_NUM = 1;
    /**
     * 默认每页数量
     */
    public static final int DEFAULT_PAGE_SIZE = 5;
    /**
     * 每页数量上限，超过该值按上限处理，避免单次拉取过量数据
     */
    public static final int MAX_PAGE_SIZE = 100;
    /**
     * Elasticsearch 默认的 index.max_result_window，from + size 超过该值会被ES拒绝
     */
    public static final int MAX_RESULT_WINDOW = 10000;

    private SearchPageUtils() {
    }

    /**
     * 归一化页码，null或小于1时按1处理
     */
    public static int normalizePageNum(Integer pageNum) {
        if (pageNum == null || pageNum < DEFAULT_PAGE_NUM) {
            return DEFAULT_PAGE_NUM;
        }
        return pageNum;
    }

    /**
     * 归一化每页数量，null或非正数时使用默认值，超过上限时按上限处理
     */
    public static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * 将1-based的页码转换为Spring Data使用的分页参数
     */
    public static Pageable toPageable(Integer pageNum, Integer pageSize) {
        return PageRequest.of(normalizePageNum(pageNum) - 1, normalizePageSize(pageSize));
    }

    /**
     * 判断分页偏移量是否超出Elasticsearch的max_result_window。
     * 超限时ES会返回search_phase_execution_exception，需要提前返回空页而不是把异常抛给调用方。
     */
    public static boolean isBeyondMaxResultWindow(Integer pageNum, Integer pageSize) {
        int size = normalizePageSize(pageSize);
        long offset = (long) (normalizePageNum(pageNum) - 1) * size;
        return offset + size > MAX_RESULT_WINDOW;
    }

    /**
     * 构造空分页结果，页码与每页数量沿用归一化后的入参，保证对外仍是1-based
     */
    public static <T> Page<T> emptyPage(Integer pageNum, Integer pageSize) {
        return new PageImpl<>(Collections.emptyList(), toPageable(pageNum, pageSize), 0);
    }

    /**
     * 将Spring Data分页结果转换为通用分页对象，对外页码保持1-based
     */
    public static <T> CommonPage<T> restPageOneBased(Page<T> page) {
        CommonPage<T> commonPage = CommonPage.restPage(page);
        commonPage.setPageNum(page.getNumber() + 1);
        return commonPage;
    }
}

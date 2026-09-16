package com.macro.mall.search.util;

import com.macro.mall.common.api.CommonPage;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 搜索分页参数处理工具单元测试，不依赖Spring容器、ES和MySQL
 */
public class SearchPageUtilsTest {

    @Test
    public void testNormalizePageNum() {
        assertEquals(1, SearchPageUtils.normalizePageNum(1));
        assertEquals(2, SearchPageUtils.normalizePageNum(2));
        assertEquals(1, SearchPageUtils.normalizePageNum(0));
        assertEquals(1, SearchPageUtils.normalizePageNum(-1));
        assertEquals(1, SearchPageUtils.normalizePageNum(null));
    }

    @Test
    public void testNormalizePageSize() {
        assertEquals(5, SearchPageUtils.normalizePageSize(5));
        assertEquals(10, SearchPageUtils.normalizePageSize(10));
        assertEquals(5, SearchPageUtils.normalizePageSize(0));
        assertEquals(5, SearchPageUtils.normalizePageSize(-3));
        assertEquals(5, SearchPageUtils.normalizePageSize(null));
    }

    @Test
    public void testNormalizePageSizeClampToMaxPageSize() {
        assertEquals(SearchPageUtils.MAX_PAGE_SIZE, SearchPageUtils.normalizePageSize(SearchPageUtils.MAX_PAGE_SIZE));
        assertEquals(SearchPageUtils.MAX_PAGE_SIZE, SearchPageUtils.normalizePageSize(SearchPageUtils.MAX_PAGE_SIZE + 1));
        assertEquals(SearchPageUtils.MAX_PAGE_SIZE, SearchPageUtils.normalizePageSize(1000));
        assertEquals(SearchPageUtils.MAX_PAGE_SIZE, SearchPageUtils.normalizePageSize(Integer.MAX_VALUE));
    }

    @Test
    public void testToPageableClampPageSize() {
        Pageable pageable = SearchPageUtils.toPageable(2, 1000);
        assertEquals(1, pageable.getPageNumber());
        assertEquals(SearchPageUtils.MAX_PAGE_SIZE, pageable.getPageSize());
    }

    @Test
    public void testToPageableUseZeroBasedPageIndex() {
        Pageable pageable = SearchPageUtils.toPageable(1, 10);
        assertEquals(0, pageable.getPageNumber());
        assertEquals(10, pageable.getPageSize());

        Pageable thirdPage = SearchPageUtils.toPageable(3, 20);
        assertEquals(2, thirdPage.getPageNumber());
        assertEquals(20, thirdPage.getPageSize());

        Pageable invalidPage = SearchPageUtils.toPageable(0, 0);
        assertEquals(0, invalidPage.getPageNumber());
        assertEquals(SearchPageUtils.DEFAULT_PAGE_SIZE, invalidPage.getPageSize());
    }

    @Test
    public void testRestPageOneBased() {
        Pageable pageable = PageRequest.of(1, 5);
        Page<String> page = new PageImpl<>(List.of("a", "b"), pageable, 12);
        CommonPage<String> commonPage = SearchPageUtils.restPageOneBased(page);
        assertEquals(2, commonPage.getPageNum());
        assertEquals(5, commonPage.getPageSize());
        assertEquals(3, commonPage.getTotalPage());
        assertEquals(12L, commonPage.getTotal());

        CommonPage<String> firstPage = SearchPageUtils.restPageOneBased(new PageImpl<>(List.of("a"), PageRequest.of(0, 5), 12));
        assertEquals(1, firstPage.getPageNum());
    }
}

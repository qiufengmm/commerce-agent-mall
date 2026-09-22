package com.macro.mall.search.service.impl;

import com.macro.mall.search.dao.EsProductDao;
import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.exception.ImportAllConflictException;
import com.macro.mall.search.lock.ImportAllLock;
import com.macro.mall.search.lock.ImportAllLockException;
import com.macro.mall.search.lock.ImportAllLockService;
import com.macro.mall.search.repository.EsProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EsProductServiceImplDistributedLockTest {
    @Mock
    private EsProductDao productDao;
    @Mock
    private EsProductRepository productRepository;
    @Mock
    private ElasticsearchTemplate elasticsearchTemplate;
    @Mock
    private ImportAllLockService importAllLockService;
    @Mock
    private ImportAllLock importAllLock;

    private EsProductServiceImpl esProductService;

    @BeforeEach
    void setUp() {
        esProductService = new EsProductServiceImpl();
        ReflectionTestUtils.setField(esProductService, "productDao", productDao);
        ReflectionTestUtils.setField(esProductService, "productRepository", productRepository);
        ReflectionTestUtils.setField(esProductService, "elasticsearchTemplate", elasticsearchTemplate);
        ReflectionTestUtils.setField(esProductService, "importAllLockService", importAllLockService);
    }

    @Test
    void rejectsWhenAnotherInstanceHoldsImportLock() {
        when(importAllLockService.tryAcquire())
                .thenThrow(new ImportAllLockException("already running"));

        assertThrows(ImportAllConflictException.class, () -> esProductService.importAll());

        verify(productDao, never()).getAllEsProductList(isNull());
        verify(productRepository, never()).saveAll(anyList());
    }

    @Test
    void doesNotScanOrDeleteWhenDistributedLockIsLost() {
        when(importAllLockService.tryAcquire()).thenReturn(importAllLock);
        when(importAllLock.isHeld()).thenReturn(false);

        assertThrows(ImportAllLockException.class, () -> esProductService.importAll());

        verify(productRepository, never()).saveAll(anyList());
        verify(elasticsearchTemplate, never()).search(any(NativeQuery.class), eq(EsProduct.class));
        verify(productRepository, never()).deleteAllById(any(Iterable.class));
        verify(importAllLock).close();
    }

    @Test
    void closesDistributedLockAfterSuccessfulImport() {
        when(importAllLockService.tryAcquire()).thenReturn(importAllLock);
        when(importAllLock.isHeld()).thenReturn(true);
        when(productDao.getAllEsProductList(isNull()))
                .thenReturn(Collections.emptyList());
        SearchHits<EsProduct> emptyHits = mock(SearchHits.class);
        when(emptyHits.getSearchHits()).thenReturn(Collections.emptyList());
        when(elasticsearchTemplate.search(any(NativeQuery.class), eq(EsProduct.class))).thenReturn(emptyHits);

        esProductService.importAll();

        verify(importAllLock).close();
    }

    private EsProduct product(Long id) {
        EsProduct product = new EsProduct();
        product.setId(id);
        return product;
    }
}

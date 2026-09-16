package com.macro.mall.search.service.impl;

import com.macro.mall.search.dao.EsProductDao;
import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.repository.EsProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 商品索引同步单元测试，只mock MySQL与ES访问，不依赖真实MySQL、ES
 */
@ExtendWith(MockitoExtension.class)
public class EsProductServiceImplSyncTest {
    @Mock
    private EsProductDao productDao;
    @Mock
    private EsProductRepository productRepository;
    @Mock
    private ElasticsearchTemplate elasticsearchTemplate;
    @InjectMocks
    private EsProductServiceImpl esProductService;

    private EsProduct product(Long id) {
        EsProduct esProduct = new EsProduct();
        esProduct.setId(id);
        return esProduct;
    }

    @Test
    public void testSyncSaveWhenProductCanBePublished() {
        EsProduct onShelf = product(1L);
        when(productDao.getEsProductListByIds(Collections.singletonList(1L)))
                .thenReturn(Collections.singletonList(onShelf));

        esProductService.sync(1L);

        ArgumentCaptor<List<EsProduct>> captor = ArgumentCaptor.forClass(List.class);
        verify(productRepository).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals(1L, captor.getValue().get(0).getId());
        verify(productRepository, never()).deleteById(anyLong());
    }

    @Test
    public void testSyncDeleteWhenProductOffShelfOrDeleted() {
        when(productDao.getEsProductListByIds(Arrays.asList(1L, 2L))).thenReturn(Collections.emptyList());
        when(productRepository.existsById(1L)).thenReturn(true);
        when(productRepository.existsById(2L)).thenReturn(true);

        esProductService.sync(Arrays.asList(1L, 2L));

        verify(productRepository, never()).saveAll(anyList());
        verify(productRepository).deleteById(1L);
        verify(productRepository).deleteById(2L);
    }

    @Test
    public void testSyncNotDeleteWhenDocumentNotExistsInEs() {
        when(productDao.getEsProductListByIds(Collections.singletonList(3L))).thenReturn(Collections.emptyList());
        when(productRepository.existsById(3L)).thenReturn(false);

        esProductService.sync(3L);

        verify(productRepository, never()).deleteById(anyLong());
    }

    @Test
    public void testSyncMixedIdsSaveAndDelete() {
        when(productDao.getEsProductListByIds(Arrays.asList(1L, 2L)))
                .thenReturn(Collections.singletonList(product(2L)));
        when(productRepository.existsById(1L)).thenReturn(true);

        esProductService.sync(Arrays.asList(1L, 2L));

        verify(productRepository).saveAll(anyList());
        verify(productRepository).deleteById(1L);
        verify(productRepository, never()).deleteById(2L);
    }

    @Test
    public void testSyncIsIdempotent() {
        when(productDao.getEsProductListByIds(Collections.singletonList(5L)))
                .thenReturn(Collections.singletonList(product(5L)));

        esProductService.sync(5L);
        esProductService.sync(5L);

        verify(productDao, times(2)).getEsProductListByIds(Collections.singletonList(5L));
        verify(productRepository, times(2)).saveAll(anyList());
        verify(productRepository, never()).deleteById(anyLong());
    }

    @Test
    public void testSyncIgnoreEmptyOrNullOnlyIds() {
        esProductService.sync((Long) null);
        esProductService.sync(Collections.emptyList());
        esProductService.sync(Arrays.asList(null, null));

        verifyNoInteractions(productDao, productRepository);
    }
}

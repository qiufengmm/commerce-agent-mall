package com.macro.mall.service.impl;

import com.macro.mall.dto.PmsProductParam;
import com.macro.mall.event.ProductSyncEvent;
import com.macro.mall.mapper.CmsPrefrenceAreaProductRelationMapper;
import com.macro.mall.mapper.CmsSubjectProductRelationMapper;
import com.macro.mall.mapper.PmsMemberPriceMapper;
import com.macro.mall.mapper.PmsProductAttributeValueMapper;
import com.macro.mall.mapper.PmsProductFullReductionMapper;
import com.macro.mall.mapper.PmsProductLadderMapper;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.model.PmsProductExample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 商品写入后只发布同步事件、由监听器在提交后同步的测试
 */
@ExtendWith(MockitoExtension.class)
public class PmsProductServiceImplSyncTest {
    @Mock
    private PmsProductMapper productMapper;
    @Mock
    private PmsMemberPriceMapper memberPriceMapper;
    @Mock
    private PmsProductLadderMapper productLadderMapper;
    @Mock
    private PmsProductFullReductionMapper productFullReductionMapper;
    @Mock
    private PmsSkuStockMapper skuStockMapper;
    @Mock
    private PmsProductAttributeValueMapper productAttributeValueMapper;
    @Mock
    private CmsSubjectProductRelationMapper subjectProductRelationMapper;
    @Mock
    private CmsPrefrenceAreaProductRelationMapper prefrenceAreaProductRelationMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @InjectMocks
    private PmsProductServiceImpl pmsProductService;

    @Test
    public void testCreatePublishSyncEventWithNewProductId() {
        PmsProductParam productParam = new PmsProductParam();
        doAnswer(invocation -> {
            PmsProduct product = invocation.getArgument(0);
            product.setId(9L);
            return 1;
        }).when(productMapper).insertSelective(any(PmsProduct.class));

        pmsProductService.create(productParam);

        assertEquals(Collections.singletonList(9L), captureEvent().getProductIds());
    }

    @Test
    public void testUpdatePublishSyncEvent() {
        pmsProductService.update(9L, new PmsProductParam());

        assertEquals(Collections.singletonList(9L), captureEvent().getProductIds());
    }

    @Test
    public void testUpdatePublishStatusPublishBatchEvent() {
        List<Long> ids = Arrays.asList(1L, 2L);
        when(productMapper.updateByExampleSelective(any(PmsProduct.class), any(PmsProductExample.class))).thenReturn(2);

        pmsProductService.updatePublishStatus(ids, 1);

        assertEquals(ids, captureEvent().getProductIds());
    }

    @Test
    public void testUpdateDeleteStatusPublishBatchEvent() {
        List<Long> ids = Collections.singletonList(3L);
        when(productMapper.updateByExampleSelective(any(PmsProduct.class), any(PmsProductExample.class))).thenReturn(1);

        pmsProductService.updateDeleteStatus(ids, 1);

        assertEquals(ids, captureEvent().getProductIds());
    }

    @Test
    public void testUpdateRecommendStatusPublishBatchEvent() {
        List<Long> ids = Arrays.asList(4L, 5L);
        when(productMapper.updateByExampleSelective(any(PmsProduct.class), any(PmsProductExample.class))).thenReturn(2);

        pmsProductService.updateRecommendStatus(ids, 1);

        assertEquals(ids, captureEvent().getProductIds());
    }

    @Test
    public void testUpdateNewStatusPublishBatchEvent() {
        List<Long> ids = Collections.singletonList(6L);
        when(productMapper.updateByExampleSelective(any(PmsProduct.class), any(PmsProductExample.class))).thenReturn(1);

        pmsProductService.updateNewStatus(ids, 1);

        assertEquals(ids, captureEvent().getProductIds());
    }

    @Test
    public void testUpdateStatusWithoutIdsNotPublishEvent() {
        pmsProductService.updatePublishStatus(Collections.emptyList(), 1);

        verifyNoInteractions(eventPublisher);
    }

    private ProductSyncEvent captureEvent() {
        ArgumentCaptor<ProductSyncEvent> captor = ArgumentCaptor.forClass(ProductSyncEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }
}

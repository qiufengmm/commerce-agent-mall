package com.macro.mall.event;

import com.macro.mall.service.EsProductSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 商品索引同步事件监听器
 * 有事务时在MySQL事务成功提交后触发，没有事务时立即触发，保证搜索服务读到的是已提交数据
 */
@Component
public class ProductSyncEventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProductSyncEventListener.class);

    @Autowired
    private EsProductSyncService esProductSyncService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProductChanged(ProductSyncEvent event) {
        if (event == null || event.isEmpty()) {
            return;
        }
        List<Long> productIds = event.getProductIds();
        try {
            if (productIds.size() == 1) {
                esProductSyncService.sync(productIds.get(0));
            } else {
                esProductSyncService.syncBatch(productIds);
            }
        } catch (Exception e) {
            //此时事务已经提交，异常会直接导致业务接口失败，因此只记录warn日志
            LOGGER.warn("同步商品到Elasticsearch失败，商品id:{}，原因:{}", productIds, e.getMessage());
        }
    }
}

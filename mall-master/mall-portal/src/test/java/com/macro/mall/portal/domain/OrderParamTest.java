package com.macro.mall.portal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderParamTest {

    @Test
    void shouldIdentifyDirectBuyOrder() {
        OrderParam orderParam = new OrderParam();
        DirectBuyParam directBuy = new DirectBuyParam();
        directBuy.setProductId(1L);
        directBuy.setProductSkuId(2L);
        directBuy.setQuantity(1);
        orderParam.setDirectBuy(directBuy);

        assertTrue(orderParam.isDirectBuy());
    }

    @Test
    void shouldNotIdentifyCartOrderAsDirectBuy() {
        assertFalse(new OrderParam().isDirectBuy());
    }
}

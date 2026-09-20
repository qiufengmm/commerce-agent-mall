package com.macro.mall.portal.controller;

import com.macro.mall.common.api.ResultCode;
import com.macro.mall.model.OmsCartItem;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.domain.CartPromotionItem;
import com.macro.mall.portal.service.OmsCartItemService;
import com.macro.mall.portal.service.UmsMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 购物车接口控制器测试。
 * <p>
 * 使用 standalone MockMvc，不启动 Spring 容器，不连接 MySQL 和 Redis。
 * 覆盖添加、列表、促销列表、改数量、改规格、删除、清空共 7 个接口的参数绑定与成功/失败结果。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OmsCartItemControllerTest {

    private static final Long MEMBER_ID = 1L;

    @Mock
    private OmsCartItemService cartItemService;
    @Mock
    private UmsMemberService memberService;
    @InjectMocks
    private OmsCartItemController cartItemController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(cartItemController).build();
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        when(memberService.getCurrentMember()).thenReturn(member);
    }

    @Test
    @DisplayName("添加购物车：JSON 参数正确绑定且新增成功时返回影响行数")
    void addCartItemBindsBodyAndReturnsCount() throws Exception {
        when(cartItemService.add(any(OmsCartItem.class))).thenReturn(1);

        mockMvc.perform(post("/cart/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":11,\"productSkuId\":22,\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data").value(1));

        ArgumentCaptor<OmsCartItem> captor = ArgumentCaptor.forClass(OmsCartItem.class);
        verify(cartItemService).add(captor.capture());
        assertEquals(11L, captor.getValue().getProductId());
        assertEquals(22L, captor.getValue().getProductSkuId());
        assertEquals(3, captor.getValue().getQuantity());
    }

    @Test
    @DisplayName("添加购物车：影响行数为 0 时返回失败")
    void addCartItemWithoutEffectReturnsFailed() throws Exception {
        when(cartItemService.add(any(OmsCartItem.class))).thenReturn(0);

        mockMvc.perform(post("/cart/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":11,\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FAILED.getCode()));
    }

    @Test
    @DisplayName("购物车列表：只查询当前会员的购物车")
    void listUsesCurrentMemberId() throws Exception {
        OmsCartItem cartItem = new OmsCartItem();
        cartItem.setId(101L);
        when(cartItemService.list(MEMBER_ID)).thenReturn(Collections.singletonList(cartItem));

        mockMvc.perform(get("/cart/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data[0].id").value(101));

        verify(cartItemService).list(MEMBER_ID);
    }

    @Test
    @DisplayName("促销购物车列表：cartIds 多选参数正确绑定")
    void listPromotionBindsCartIds() throws Exception {
        CartPromotionItem promotionItem = new CartPromotionItem();
        promotionItem.setProductId(11L);
        promotionItem.setPrice(new BigDecimal("99.00"));
        when(cartItemService.listPromotion(eq(MEMBER_ID), anyList())).thenReturn(Collections.singletonList(promotionItem));

        mockMvc.perform(get("/cart/list/promotion").param("cartIds", "1").param("cartIds", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data[0].productId").value(11));

        verify(cartItemService).listPromotion(eq(MEMBER_ID), eq(Arrays.asList(1L, 2L)));
    }

    @Test
    @DisplayName("促销购物车列表：不传 cartIds 时按全部购物车计算")
    void listPromotionWithoutCartIdsPassesNull() throws Exception {
        when(cartItemService.listPromotion(eq(MEMBER_ID), anyList())).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/cart/list/promotion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()));

        verify(cartItemService).listPromotion(eq(MEMBER_ID), isNull());
    }

    @Test
    @DisplayName("修改数量：参数绑定成功且更新生效时返回影响行数")
    void updateQuantityReturnsCount() throws Exception {
        when(cartItemService.updateQuantity(101L, MEMBER_ID, 5)).thenReturn(1);

        mockMvc.perform(get("/cart/update/quantity").param("id", "101").param("quantity", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data").value(1));
    }

    @Test
    @DisplayName("修改数量：缺少 quantity 参数时接口报错且不调用服务")
    void updateQuantityWithoutQuantityIsRejected() throws Exception {
        mockMvc.perform(get("/cart/update/quantity").param("id", "101"))
                .andExpect(status().isBadRequest());

        verify(cartItemService, never()).updateQuantity(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("修改规格：JSON 参数绑定成功后返回影响行数")
    void updateAttrBindsBodyAndReturnsCount() throws Exception {
        when(cartItemService.updateAttr(any(OmsCartItem.class))).thenReturn(1);

        mockMvc.perform(post("/cart/update/attr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":101,\"productId\":11,\"productSkuId\":33,\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data").value(1));

        ArgumentCaptor<OmsCartItem> captor = ArgumentCaptor.forClass(OmsCartItem.class);
        verify(cartItemService).updateAttr(captor.capture());
        assertEquals(101L, captor.getValue().getId());
        assertEquals(33L, captor.getValue().getProductSkuId());
    }

    @Test
    @DisplayName("删除购物车：ids 多选参数绑定且带当前会员归属")
    void deleteBindsIdsAndMemberId() throws Exception {
        when(cartItemService.delete(eq(MEMBER_ID), anyList())).thenReturn(2);

        mockMvc.perform(post("/cart/delete").param("ids", "101").param("ids", "102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data").value(2));

        verify(cartItemService).delete(eq(MEMBER_ID), eq(Arrays.asList(101L, 102L)));
    }

    @Test
    @DisplayName("清空购物车：成功后返回影响行数")
    void clearReturnsCount() throws Exception {
        when(cartItemService.clear(MEMBER_ID)).thenReturn(3);

        mockMvc.perform(post("/cart/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data").value(3));

        verify(cartItemService).clear(MEMBER_ID);
    }

    @Test
    @DisplayName("清空购物车：影响行数为 0 时返回失败")
    void clearWithoutEffectReturnsFailed() throws Exception {
        when(cartItemService.clear(MEMBER_ID)).thenReturn(0);

        mockMvc.perform(post("/cart/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FAILED.getCode()));
    }

}

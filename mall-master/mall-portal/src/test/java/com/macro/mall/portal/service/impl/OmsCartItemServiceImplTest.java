package com.macro.mall.portal.service.impl;

import com.macro.mall.mapper.OmsCartItemMapper;
import com.macro.mall.model.OmsCartItem;
import com.macro.mall.model.OmsCartItemExample;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.dao.PortalProductDao;
import com.macro.mall.portal.domain.CartPromotionItem;
import com.macro.mall.portal.service.OmsPromotionService;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 购物车服务单元测试，覆盖会员归属、数量累加与删除边界。
 * <p>
 * 只 mock Mapper 与依赖服务，不启动 Spring 容器，不连接 MySQL。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OmsCartItemServiceImplTest {

    private static final Long MEMBER_ID = 1L;

    @Mock
    private OmsCartItemMapper cartItemMapper;
    @Mock
    private PortalProductDao productDao;
    @Mock
    private OmsPromotionService promotionService;
    @Mock
    private UmsMemberService memberService;

    @InjectMocks
    private OmsCartItemServiceImpl cartItemService;

    private UmsMember currentMember;

    @BeforeEach
    void setUp() {
        currentMember = new UmsMember();
        currentMember.setId(MEMBER_ID);
        currentMember.setNickname("测试会员");
        when(memberService.getCurrentMember()).thenReturn(currentMember);
    }

    @Test
    @DisplayName("新增购物车：补齐会员归属、昵称与未删除状态")
    void addFillsMemberOwnership() {
        when(cartItemMapper.selectByExample(any(OmsCartItemExample.class))).thenReturn(Collections.emptyList());
        when(cartItemMapper.insert(any(OmsCartItem.class))).thenReturn(1);

        int count = cartItemService.add(cartItem(null, 11L, 22L, 2));

        assertEquals(1, count);
        ArgumentCaptor<OmsCartItem> captor = ArgumentCaptor.forClass(OmsCartItem.class);
        verify(cartItemMapper).insert(captor.capture());
        OmsCartItem inserted = captor.getValue();
        assertEquals(MEMBER_ID, inserted.getMemberId());
        assertEquals("测试会员", inserted.getMemberNickname());
        assertEquals(0, inserted.getDeleteStatus());
        assertNotNull(inserted.getCreateDate());
    }

    @Test
    @DisplayName("重复添加同一规格商品：累加数量并更新原记录，不产生新行")
    void addSameSkuAccumulatesQuantity() {
        OmsCartItem exist = cartItem(101L, 11L, 22L, 3);
        exist.setMemberId(MEMBER_ID);
        when(cartItemMapper.selectByExample(any(OmsCartItemExample.class)))
                .thenReturn(Collections.singletonList(exist));
        when(cartItemMapper.updateByPrimaryKey(any(OmsCartItem.class))).thenReturn(1);

        cartItemService.add(cartItem(null, 11L, 22L, 2));

        ArgumentCaptor<OmsCartItem> captor = ArgumentCaptor.forClass(OmsCartItem.class);
        verify(cartItemMapper).updateByPrimaryKey(captor.capture());
        verify(cartItemMapper, never()).insert(any(OmsCartItem.class));
        assertEquals(101L, captor.getValue().getId());
        assertEquals(5, captor.getValue().getQuantity());
    }

    @Test
    @DisplayName("购物车列表：只查询未删除且属于当前会员的记录")
    void listOnlyCurrentMemberNotDeletedItems() {
        cartItemService.list(MEMBER_ID);

        ArgumentCaptor<OmsCartItemExample> captor = ArgumentCaptor.forClass(OmsCartItemExample.class);
        verify(cartItemMapper).selectByExample(captor.capture());
        OmsCartItemExample example = captor.getValue();
        List<String> conditions = conditions(example);
        assertTrue(conditions.contains("delete_status ="), "必须过滤已删除记录，实际条件：" + conditions);
        assertTrue(conditions.contains("member_id ="), "必须按会员过滤，实际条件：" + conditions);
    }

    @Test
    @DisplayName("促销购物车列表：只计算传入的购物车ID")
    void listPromotionFiltersByCartIds() {
        OmsCartItem first = cartItem(101L, 11L, 22L, 1);
        OmsCartItem second = cartItem(102L, 12L, 23L, 1);
        when(cartItemMapper.selectByExample(any(OmsCartItemExample.class)))
                .thenReturn(Arrays.asList(first, second));
        when(promotionService.calcCartPromotion(anyList())).thenReturn(Collections.emptyList());

        cartItemService.listPromotion(MEMBER_ID, Collections.singletonList(101L));

        ArgumentCaptor<List<OmsCartItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(promotionService).calcCartPromotion(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals(101L, captor.getValue().get(0).getId());
    }

    @Test
    @DisplayName("促销购物车列表：购物车为空时不计算促销")
    void listPromotionWithEmptyCartDoesNotCalcPromotion() {
        when(cartItemMapper.selectByExample(any(OmsCartItemExample.class))).thenReturn(Collections.emptyList());

        List<CartPromotionItem> result = cartItemService.listPromotion(MEMBER_ID, null);

        assertTrue(result.isEmpty());
        verify(promotionService, never()).calcCartPromotion(anyList());
    }

    @Test
    @DisplayName("修改数量：更新条件必须带上会员归属，防止改他人购物车")
    void updateQuantityChecksMemberOwnership() {
        when(cartItemMapper.updateByExampleSelective(any(OmsCartItem.class), any(OmsCartItemExample.class))).thenReturn(1);

        cartItemService.updateQuantity(101L, MEMBER_ID, 5);

        ArgumentCaptor<OmsCartItemExample> exampleCaptor = ArgumentCaptor.forClass(OmsCartItemExample.class);
        ArgumentCaptor<OmsCartItem> recordCaptor = ArgumentCaptor.forClass(OmsCartItem.class);
        verify(cartItemMapper).updateByExampleSelective(recordCaptor.capture(), exampleCaptor.capture());

        assertEquals(5, recordCaptor.getValue().getQuantity());
        List<String> conditions = conditions(exampleCaptor.getValue());
        assertTrue(conditions.contains("member_id ="), "更新条件必须带会员归属，实际条件：" + conditions);
        assertTrue(conditions.contains("id ="), "更新条件必须限定购物车记录，实际条件：" + conditions);
        assertTrue(conditions.contains("delete_status ="), "只能更新未删除记录，实际条件：" + conditions);
    }

    @Test
    @DisplayName("删除购物车：逻辑删除且更新条件带会员归属")
    void deleteUsesLogicDeleteWithMemberOwnership() {
        when(cartItemMapper.updateByExampleSelective(any(OmsCartItem.class), any(OmsCartItemExample.class))).thenReturn(2);

        int count = cartItemService.delete(MEMBER_ID, Arrays.asList(101L, 102L));

        assertEquals(2, count);
        ArgumentCaptor<OmsCartItem> recordCaptor = ArgumentCaptor.forClass(OmsCartItem.class);
        ArgumentCaptor<OmsCartItemExample> exampleCaptor = ArgumentCaptor.forClass(OmsCartItemExample.class);
        verify(cartItemMapper).updateByExampleSelective(recordCaptor.capture(), exampleCaptor.capture());

        assertEquals(1, recordCaptor.getValue().getDeleteStatus());
        List<String> conditions = conditions(exampleCaptor.getValue());
        assertTrue(conditions.contains("member_id ="), "删除必须带会员归属，实际条件：" + conditions);
        assertTrue(conditions.contains("id in"), "删除必须限定传入的购物车ID，实际条件：" + conditions);
    }

    @Test
    @DisplayName("清空购物车：逻辑删除当前会员全部记录")
    void clearUsesLogicDeleteForCurrentMember() {
        when(cartItemMapper.updateByExampleSelective(any(OmsCartItem.class), any(OmsCartItemExample.class))).thenReturn(3);

        cartItemService.clear(MEMBER_ID);

        ArgumentCaptor<OmsCartItem> recordCaptor = ArgumentCaptor.forClass(OmsCartItem.class);
        ArgumentCaptor<OmsCartItemExample> exampleCaptor = ArgumentCaptor.forClass(OmsCartItemExample.class);
        verify(cartItemMapper).updateByExampleSelective(recordCaptor.capture(), exampleCaptor.capture());

        assertEquals(1, recordCaptor.getValue().getDeleteStatus());
        assertTrue(conditions(exampleCaptor.getValue()).contains("member_id ="));
    }

    @Test
    @DisplayName("重选规格：旧记录逻辑删除后重新新增，且新记录不带原ID")
    void updateAttrDeletesOldAndInsertsNew() {
        when(cartItemMapper.selectByExample(any(OmsCartItemExample.class))).thenReturn(Collections.emptyList());
        when(cartItemMapper.insert(any(OmsCartItem.class))).thenReturn(1);

        OmsCartItem newItem = cartItem(101L, 11L, 33L, 1);
        int count = cartItemService.updateAttr(newItem);

        assertEquals(1, count);
        ArgumentCaptor<OmsCartItem> updateCaptor = ArgumentCaptor.forClass(OmsCartItem.class);
        verify(cartItemMapper).updateByPrimaryKeySelective(updateCaptor.capture());
        assertEquals(101L, updateCaptor.getValue().getId());
        assertEquals(1, updateCaptor.getValue().getDeleteStatus());

        ArgumentCaptor<OmsCartItem> insertCaptor = ArgumentCaptor.forClass(OmsCartItem.class);
        verify(cartItemMapper).insert(insertCaptor.capture());
        assertNull(insertCaptor.getValue().getId(), "重选规格必须新增记录，不能复用原ID");
        assertEquals(33L, insertCaptor.getValue().getProductSkuId());
    }

    @Test
    @DisplayName("查询购物车商品：直接透传商品ID给DAO")
    void getCartProductDelegatesToDao() {
        cartItemService.getCartProduct(11L);

        verify(productDao).getCartProduct(11L);
    }

    private OmsCartItem cartItem(Long id, Long productId, Long skuId, Integer quantity) {
        OmsCartItem cartItem = new OmsCartItem();
        cartItem.setId(id);
        cartItem.setProductId(productId);
        cartItem.setProductSkuId(skuId);
        cartItem.setQuantity(quantity);
        return cartItem;
    }

    /**
     * 读取 MBG Example 中实际生成的条件表达式，用于校验会员归属等过滤条件
     */
    private List<String> conditions(OmsCartItemExample example) {
        List<OmsCartItemExample.Criterion> criteria = example.getOredCriteria().get(0).getAllCriteria();
        return criteria.stream().map(OmsCartItemExample.Criterion::getCondition).collect(Collectors.toList());
    }
}

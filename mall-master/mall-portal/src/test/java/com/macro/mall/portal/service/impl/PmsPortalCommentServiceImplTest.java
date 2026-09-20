package com.macro.mall.portal.service.impl;

import com.github.pagehelper.PageHelper;
import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.exception.ApiException;
import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.PmsCommentMapper;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.model.PmsComment;
import com.macro.mall.model.PmsCommentExample;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.model.PmsProductExample;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.domain.PmsCommentParam;
import com.macro.mall.portal.domain.PmsCommentResult;
import com.macro.mall.portal.service.UmsMemberService;
import org.junit.jupiter.api.AfterEach;
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

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品评价服务单元测试，覆盖重复评价、越权评价、状态校验与分页查询边界。
 * <p>
 * 只 mock Mapper，不启动 Spring 容器，不连接 MySQL。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PmsPortalCommentServiceImplTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long OTHER_MEMBER_ID = 2L;
    private static final Long ORDER_ID = 100L;
    private static final Long ORDER_ITEM_ID = 200L;

    @Mock
    private PmsCommentMapper commentMapper;
    @Mock
    private OmsOrderMapper orderMapper;
    @Mock
    private OmsOrderItemMapper orderItemMapper;
    @Mock
    private UmsMemberService memberService;
    @Mock
    private PmsProductMapper productMapper;

    @InjectMocks
    private PmsPortalCommentServiceImpl commentService;

    @BeforeEach
    void setUp() {
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setNickname("测试会员");
        when(memberService.getCurrentMember()).thenReturn(member);
    }

    @AfterEach
    void tearDown() {
        // 清理 PageHelper 的线程变量，避免影响同一线程上的其他测试
        PageHelper.clearPage();
    }

    @Test
    @DisplayName("新增评价：缺少订单信息时拒绝提交")
    void addWithoutOrderInfoIsRejected() {
        PmsCommentParam param = new PmsCommentParam();

        ApiException exception = assertThrows(ApiException.class, () -> commentService.add(param));

        assertTrue(exception.getMessage().contains("订单信息不完整"));
        verify(commentMapper, never()).insertSelective(any(PmsComment.class));
    }

    @Test
    @DisplayName("新增评价：订单不存在时拒绝提交")
    void addWithUnknownOrderIsRejected() {
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(null);

        ApiException exception = assertThrows(ApiException.class, () -> commentService.add(commentParam()));

        assertTrue(exception.getMessage().contains("订单不存在"));
        verify(commentMapper, never()).insertSelective(any(PmsComment.class));
    }

    @Test
    @DisplayName("新增评价：不能评价其他会员的订单")
    void addForOtherMemberOrderIsRejected() {
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(OTHER_MEMBER_ID, 3));

        ApiException exception = assertThrows(ApiException.class, () -> commentService.add(commentParam()));

        assertTrue(exception.getMessage().contains("不能评价他人的订单"));
        verify(commentMapper, never()).insertSelective(any(PmsComment.class));
    }

    @Test
    @DisplayName("新增评价：未确认收货的订单不能评价")
    void addForUnfinishedOrderIsRejected() {
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(MEMBER_ID, 2));

        ApiException exception = assertThrows(ApiException.class, () -> commentService.add(commentParam()));

        assertTrue(exception.getMessage().contains("确认收货后才能评价"));
        verify(commentMapper, never()).insertSelective(any(PmsComment.class));
    }

    @Test
    @DisplayName("新增评价：订单明细不属于该订单时拒绝提交")
    void addWithMismatchedOrderItemIsRejected() {
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(MEMBER_ID, 3));
        OmsOrderItem otherOrderItem = orderItem();
        otherOrderItem.setOrderId(999L);
        when(orderItemMapper.selectByPrimaryKey(ORDER_ITEM_ID)).thenReturn(otherOrderItem);

        ApiException exception = assertThrows(ApiException.class, () -> commentService.add(commentParam()));

        assertTrue(exception.getMessage().contains("订单明细不存在"));
        verify(commentMapper, never()).insertSelective(any(PmsComment.class));
    }

    @Test
    @DisplayName("新增评价：同一订单明细不能重复评价")
    void addDuplicateIsRejected() {
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(MEMBER_ID, 3));
        when(orderItemMapper.selectByPrimaryKey(ORDER_ITEM_ID)).thenReturn(orderItem());
        when(commentMapper.countByExample(any(PmsCommentExample.class))).thenReturn(1L);

        ApiException exception = assertThrows(ApiException.class, () -> commentService.add(commentParam()));

        assertTrue(exception.getMessage().contains("不能重复提交"));
        verify(commentMapper, never()).insertSelective(any(PmsComment.class));
    }

    @Test
    @DisplayName("新增评价成功：补齐会员归属、默认五星与展示状态")
    void addSuccessFillsCommentFields() {
        when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order(MEMBER_ID, 3));
        when(orderItemMapper.selectByPrimaryKey(ORDER_ITEM_ID)).thenReturn(orderItem());
        when(commentMapper.countByExample(any(PmsCommentExample.class))).thenReturn(0L);
        when(commentMapper.insertSelective(any(PmsComment.class))).thenReturn(1);

        int count = commentService.add(commentParam());

        assertEquals(1, count);
        ArgumentCaptor<PmsComment> captor = ArgumentCaptor.forClass(PmsComment.class);
        verify(commentMapper).insertSelective(captor.capture());
        PmsComment comment = captor.getValue();
        assertEquals(MEMBER_ID, comment.getMemberId());
        assertEquals(ORDER_ID, comment.getOrderId());
        assertEquals(ORDER_ITEM_ID, comment.getOrderItemId());
        assertEquals(11L, comment.getProductId());
        assertEquals(5, comment.getStar(), "未传星级时默认五星");
        assertEquals(1, comment.getShowStatus());
        assertEquals("测试会员", comment.getMemberNickName());
        assertNotNull(comment.getCreateTime());
    }

    @Test
    @DisplayName("商品评价列表：只查询已展示且属于该商品的评价")
    void listFiltersShowStatusAndProductId() {
        when(commentMapper.selectByExampleWithBLOBs(any(PmsCommentExample.class)))
                .thenReturn(Collections.singletonList(comment()));

        CommonPage<PmsComment> page = commentService.list(11L, 1, 5);

        assertEquals(1, page.getList().size());
        ArgumentCaptor<PmsCommentExample> captor = ArgumentCaptor.forClass(PmsCommentExample.class);
        verify(commentMapper).selectByExampleWithBLOBs(captor.capture());
        PmsCommentExample example = captor.getValue();
        List<String> conditions = example.getOredCriteria().get(0).getAllCriteria()
                .stream().map(PmsCommentExample.Criterion::getCondition).collect(Collectors.toList());
        assertTrue(conditions.contains("product_id ="), "必须按商品过滤，实际条件：" + conditions);
        assertTrue(conditions.contains("show_status ="), "只能展示已审核评价，实际条件：" + conditions);
        assertEquals("create_time desc", example.getOrderByClause());
    }

    @Test
    @DisplayName("会员评价列表：只查当前会员的评价并批量回填商品图片")
    void listMyOnlyCurrentMemberAndBatchQueryProductPic() {
        when(commentMapper.selectByExampleWithBLOBs(any(PmsCommentExample.class)))
                .thenReturn(Collections.singletonList(comment()));
        PmsProduct product = new PmsProduct();
        product.setId(11L);
        product.setPic("https://example.com/pic.jpg");
        when(productMapper.selectByExample(any(PmsProductExample.class)))
                .thenReturn(Collections.singletonList(product));

        CommonPage<PmsCommentResult> page = commentService.listMy(1, 10);

        assertEquals(1, page.getList().size());
        assertEquals("https://example.com/pic.jpg", page.getList().get(0).getProductPic());
        assertEquals(11L, page.getList().get(0).getProductId());

        ArgumentCaptor<PmsCommentExample> captor = ArgumentCaptor.forClass(PmsCommentExample.class);
        verify(commentMapper).selectByExampleWithBLOBs(captor.capture());
        List<String> conditions = captor.getValue().getOredCriteria().get(0).getAllCriteria()
                .stream().map(PmsCommentExample.Criterion::getCondition).collect(Collectors.toList());
        assertTrue(conditions.contains("member_id ="), "必须按会员过滤，实际条件：" + conditions);

        // 商品图片只允许一次批量查询，避免逐条查询数据库
        verify(productMapper).selectByExample(any(PmsProductExample.class));
    }

    @Test
    @DisplayName("是否已评价：orderItemId 为空时直接返回 false，不查库")
    void existsWithNullOrderItemIdReturnsFalse() {
        assertFalse(commentService.exists(null));
        verify(commentMapper, never()).countByExample(any(PmsCommentExample.class));
    }

    @Test
    @DisplayName("是否已评价：存在评价记录时返回 true")
    void existsWithCommentReturnsTrue() {
        when(commentMapper.countByExample(any(PmsCommentExample.class))).thenReturn(1L);

        assertTrue(commentService.exists(ORDER_ITEM_ID));
    }

    private PmsCommentParam commentParam() {
        PmsCommentParam param = new PmsCommentParam();
        param.setOrderId(ORDER_ID);
        param.setOrderItemId(ORDER_ITEM_ID);
        return param;
    }

    private OmsOrder order(Long memberId, int status) {
        OmsOrder order = new OmsOrder();
        order.setId(ORDER_ID);
        order.setMemberId(memberId);
        order.setStatus(status);
        return order;
    }

    private OmsOrderItem orderItem() {
        OmsOrderItem orderItem = new OmsOrderItem();
        orderItem.setId(ORDER_ITEM_ID);
        orderItem.setOrderId(ORDER_ID);
        orderItem.setProductId(11L);
        orderItem.setProductName("测试商品");
        return orderItem;
    }

    private PmsComment comment() {
        PmsComment comment = new PmsComment();
        comment.setId(1L);
        comment.setProductId(11L);
        comment.setMemberId(MEMBER_ID);
        comment.setProductName("测试商品");
        comment.setStar(5);
        comment.setContent("很好");
        return comment;
    }
}

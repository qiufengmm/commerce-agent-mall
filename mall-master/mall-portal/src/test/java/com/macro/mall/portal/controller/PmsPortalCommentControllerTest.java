package com.macro.mall.portal.controller;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.api.ResultCode;
import com.macro.mall.model.PmsComment;
import com.macro.mall.portal.domain.PmsCommentParam;
import com.macro.mall.portal.domain.PmsCommentResult;
import com.macro.mall.portal.service.PmsPortalCommentService;
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

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品评价接口控制器测试。
 * <p>
 * 使用 standalone MockMvc，不启动 Spring 容器，不连接 MySQL。
 * 覆盖新增评价、商品评价分页、会员评价分页和是否已评价四个接口的参数绑定与结果映射。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PmsPortalCommentControllerTest {

    @Mock
    private PmsPortalCommentService commentService;
    @InjectMocks
    private PmsPortalCommentController commentController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(commentController).build();
    }

    @Test
    @DisplayName("新增评价：JSON 参数正确绑定且成功时返回评价成功")
    void addBindsBodyAndReturnsSuccess() throws Exception {
        when(commentService.add(any(PmsCommentParam.class))).thenReturn(1);

        mockMvc.perform(post("/comment/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":100,\"orderItemId\":200,\"star\":4,\"content\":\"很好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.message").value("评价成功"));

        ArgumentCaptor<PmsCommentParam> captor = ArgumentCaptor.forClass(PmsCommentParam.class);
        verify(commentService).add(captor.capture());
        assertEquals(100L, captor.getValue().getOrderId());
        assertEquals(200L, captor.getValue().getOrderItemId());
        assertEquals(4, captor.getValue().getStar());
        assertEquals("很好", captor.getValue().getContent());
    }

    @Test
    @DisplayName("新增评价：影响行数为 0 时返回失败")
    void addWithoutEffectReturnsFailed() throws Exception {
        when(commentService.add(any(PmsCommentParam.class))).thenReturn(0);

        mockMvc.perform(post("/comment/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":100,\"orderItemId\":200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FAILED.getCode()));
    }

    @Test
    @DisplayName("商品评价列表：分页参数使用默认值 pageNum=1、pageSize=5")
    void listUsesDefaultPageParams() throws Exception {
        when(commentService.list(anyLong(), anyInt(), anyInt())).thenReturn(commentPage());

        mockMvc.perform(get("/comment/list").param("productId", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.list[0].id").value(1));

        verify(commentService).list(11L, 1, 5);
    }

    @Test
    @DisplayName("商品评价列表：显式分页参数原样透传")
    void listPassesExplicitPageParams() throws Exception {
        when(commentService.list(anyLong(), anyInt(), anyInt())).thenReturn(commentPage());

        mockMvc.perform(get("/comment/list")
                        .param("productId", "11")
                        .param("pageNum", "2")
                        .param("pageSize", "10"))
                .andExpect(status().isOk());

        verify(commentService).list(11L, 2, 10);
    }

    @Test
    @DisplayName("商品评价列表：缺少 productId 时接口报错")
    void listWithoutProductIdIsRejected() throws Exception {
        mockMvc.perform(get("/comment/list"))
                .andExpect(status().isBadRequest());

        verify(commentService, never()).list(anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("会员评价列表：分页参数使用默认值 pageNum=1、pageSize=10")
    void mineUsesDefaultPageParams() throws Exception {
        when(commentService.listMy(anyInt(), anyInt())).thenReturn(commentResultPage());

        mockMvc.perform(get("/comment/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.list[0].productName").value("测试商品"));

        verify(commentService).listMy(1, 10);
    }

    @Test
    @DisplayName("会员评价列表：显式分页参数原样透传")
    void minePassesExplicitPageParams() throws Exception {
        when(commentService.listMy(anyInt(), anyInt())).thenReturn(commentResultPage());

        mockMvc.perform(get("/comment/mine").param("pageNum", "3").param("pageSize", "20"))
                .andExpect(status().isOk());

        verify(commentService).listMy(3, 20);
    }

    @Test
    @DisplayName("是否已评价：已评价返回 true")
    void existsReturnsTrue() throws Exception {
        when(commentService.exists(200L)).thenReturn(true);

        mockMvc.perform(get("/comment/exists").param("orderItemId", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("是否已评价：未评价返回 false")
    void existsReturnsFalse() throws Exception {
        when(commentService.exists(200L)).thenReturn(false);

        mockMvc.perform(get("/comment/exists").param("orderItemId", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));
    }

    private CommonPage<PmsComment> commentPage() {
        PmsComment comment = new PmsComment();
        comment.setId(1L);
        comment.setContent("很好");
        CommonPage<PmsComment> page = new CommonPage<>();
        page.setPageNum(1);
        page.setPageSize(5);
        page.setTotal(1L);
        page.setTotalPage(1);
        page.setList(Collections.singletonList(comment));
        return page;
    }

    private CommonPage<PmsCommentResult> commentResultPage() {
        PmsCommentResult result = new PmsCommentResult();
        result.setId(1L);
        result.setProductName("测试商品");
        CommonPage<PmsCommentResult> page = new CommonPage<>();
        page.setPageNum(1);
        page.setPageSize(10);
        page.setTotal(1L);
        page.setTotalPage(1);
        page.setList(Collections.singletonList(result));
        return page;
    }
}

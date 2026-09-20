package com.macro.mall.portal.controller;

import com.macro.mall.common.api.ResultCode;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.service.UmsMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会员接口控制器测试。
 * <p>
 * 使用 standalone MockMvc，只装配控制器本身，不启动 Spring 容器，不连接 MySQL、Redis 或 RabbitMQ。
 * 覆盖注册、登录、会员信息、刷新 token 的参数绑定、成功结果与失败结果。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UmsMemberControllerTest {

    private static final String TOKEN_HEADER = "Authorization";
    private static final String TOKEN_HEAD = "Bearer ";

    @Mock
    private UmsMemberService memberService;
    @InjectMocks
    private UmsMemberController memberController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(memberController).build();
        ReflectionTestUtils.setField(memberController, "tokenHeader", TOKEN_HEADER);
        ReflectionTestUtils.setField(memberController, "tokenHead", TOKEN_HEAD);
    }

    @Test
    @DisplayName("注册成功：参数完整时调用服务并返回注册成功")
    void registerWithFullParamsReturnsSuccess() throws Exception {
        mockMvc.perform(post("/sso/register")
                        .param("username", "testMember")
                        .param("password", "123456")
                        .param("telephone", "13800138000")
                        .param("authCode", "654321"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.message").value("注册成功"));

        verify(memberService).register("testMember", "123456", "13800138000", "654321");
    }

    @Test
    @DisplayName("注册参数缺失：缺少验证码时参数绑定失败且不会调用服务")
    void registerWithoutAuthCodeIsRejected() throws Exception {
        mockMvc.perform(post("/sso/register")
                        .param("username", "testMember")
                        .param("password", "123456")
                        .param("telephone", "13800138000"))
                .andExpect(status().isBadRequest());

        verify(memberService, never()).register(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("登录成功：返回 token 与 token 前缀")
    void loginSuccessReturnsToken() throws Exception {
        when(memberService.login("testMember", "123456")).thenReturn("jwt-token");

        mockMvc.perform(post("/sso/login")
                        .param("username", "testMember")
                        .param("password", "123456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.token").value("jwt-token"))
                .andExpect(jsonPath("$.data.tokenHead").value(TOKEN_HEAD));
    }

    @Test
    @DisplayName("登录失败：服务返回 null 时返回校验失败而不是成功")
    void loginFailedReturnsValidateFailed() throws Exception {
        when(memberService.login("testMember", "wrongPassword")).thenReturn(null);

        mockMvc.perform(post("/sso/login")
                        .param("username", "testMember")
                        .param("password", "wrongPassword"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.VALIDATE_FAILED.getCode()))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("获取会员信息：无 Principal 时返回未授权且不查询会员")
    void infoWithoutPrincipalReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/sso/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));

        verify(memberService, never()).getCurrentMember();
    }

    @Test
    @DisplayName("获取会员信息：已登录时返回当前会员")
    void infoWithPrincipalReturnsMember() throws Exception {
        UmsMember member = new UmsMember();
        member.setId(1L);
        member.setUsername("testMember");
        when(memberService.getCurrentMember()).thenReturn(member);

        mockMvc.perform(get("/sso/info").principal((Principal) () -> "testMember"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.username").value("testMember"));
    }

    @Test
    @DisplayName("刷新 token：请求头携带 token 时返回新 token")
    void refreshTokenWithHeaderReturnsNewToken() throws Exception {
        when(memberService.refreshToken("old-token")).thenReturn("new-token");

        mockMvc.perform(get("/sso/refreshToken").header(TOKEN_HEADER, "old-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.token").value("new-token"))
                .andExpect(jsonPath("$.data.tokenHead").value(TOKEN_HEAD));
    }

    @Test
    @DisplayName("刷新 token：已过期时返回失败提示")
    void refreshTokenExpiredReturnsFailed() throws Exception {
        when(memberService.refreshToken(anyString())).thenReturn(null);

        mockMvc.perform(get("/sso/refreshToken").header(TOKEN_HEADER, "expired-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FAILED.getCode()))
                .andExpect(jsonPath("$.message").value("token已经过期！"));
    }
}

package com.macro.mall.portal.service.impl;

import com.macro.mall.common.exception.ApiException;
import com.macro.mall.mapper.UmsMemberLevelMapper;
import com.macro.mall.mapper.UmsMemberMapper;
import com.macro.mall.model.UmsMember;
import com.macro.mall.model.UmsMemberExample;
import com.macro.mall.model.UmsMemberLevel;
import com.macro.mall.model.UmsMemberLevelExample;
import com.macro.mall.portal.dao.PortalMemberDao;
import com.macro.mall.portal.domain.MemberDetails;
import com.macro.mall.portal.service.UmsMemberCacheService;
import com.macro.mall.security.util.JwtTokenUtil;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会员服务单元测试，覆盖登录成功/失败、注册参数边界与积分扣减边界。
 * <p>
 * 只 mock 依赖，不启动 Spring 容器，不连接 MySQL 和 Redis。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UmsMemberServiceImplTest {

    private static final Long MEMBER_ID = 1L;
    private static final String USERNAME = "testMember";
    private static final String TELEPHONE = "13800138000";

    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenUtil jwtTokenUtil;
    @Mock
    private UmsMemberMapper memberMapper;
    @Mock
    private UmsMemberLevelMapper memberLevelMapper;
    @Mock
    private UmsMemberCacheService memberCacheService;
    @Mock
    private PortalMemberDao portalMemberDao;

    @InjectMocks
    private UmsMemberServiceImpl memberService;

    @BeforeEach
    void setUp() {
        when(memberCacheService.getMember(anyString())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("注册：验证码错误时不写库")
    void registerWithWrongAuthCodeIsRejected() {
        when(memberCacheService.getAuthCode(TELEPHONE)).thenReturn("123456");

        ApiException exception = assertThrows(ApiException.class,
                () -> memberService.register(USERNAME, "123456", TELEPHONE, "000000"));

        assertTrue(exception.getMessage().contains("验证码错误"));
        verify(memberMapper, never()).insert(any(UmsMember.class));
    }

    @Test
    @DisplayName("注册：验证码为空时不写库")
    void registerWithEmptyAuthCodeIsRejected() {
        ApiException exception = assertThrows(ApiException.class,
                () -> memberService.register(USERNAME, "123456", TELEPHONE, ""));

        assertTrue(exception.getMessage().contains("验证码错误"));
        verify(memberMapper, never()).insert(any(UmsMember.class));
    }

    @Test
    @DisplayName("注册：用户名或手机号已存在时拒绝注册")
    void registerWithExistingMemberIsRejected() {
        when(memberCacheService.getAuthCode(TELEPHONE)).thenReturn("123456");
        when(memberMapper.selectByExample(any(UmsMemberExample.class)))
                .thenReturn(Collections.singletonList(member()));

        ApiException exception = assertThrows(ApiException.class,
                () -> memberService.register(USERNAME, "123456", TELEPHONE, "123456"));

        assertTrue(exception.getMessage().contains("该用户已经存在"));
        verify(memberMapper, never()).insert(any(UmsMember.class));
    }

    @Test
    @DisplayName("注册成功：密码加密存储、启用账号并绑定默认会员等级")
    void registerSuccessEncryptsPasswordAndBindsDefaultLevel() {
        when(memberCacheService.getAuthCode(TELEPHONE)).thenReturn("123456");
        when(memberMapper.selectByExample(any(UmsMemberExample.class))).thenReturn(Collections.emptyList());
        when(memberLevelMapper.selectByExample(any(UmsMemberLevelExample.class)))
                .thenReturn(Collections.singletonList(defaultLevel()));
        when(passwordEncoder.encode("123456")).thenReturn("encodedPassword");
        // 服务在入库后会把密码置空，因此必须在插入调用发生的瞬间校验密文
        when(memberMapper.insert(any(UmsMember.class))).thenAnswer(invocation -> {
            UmsMember saved = invocation.getArgument(0);
            assertEquals("encodedPassword", saved.getPassword());
            return 1;
        });

        memberService.register(USERNAME, "123456", TELEPHONE, "123456");

        ArgumentCaptor<UmsMember> captor = ArgumentCaptor.forClass(UmsMember.class);
        verify(memberMapper).insert(captor.capture());
        UmsMember inserted = captor.getValue();
        assertEquals(USERNAME, inserted.getUsername());
        assertEquals(TELEPHONE, inserted.getPhone());
        assertEquals(1, inserted.getStatus());
        assertEquals(9L, inserted.getMemberLevelId());
        assertNotNull(inserted.getCreateTime());
        assertNull(inserted.getPassword(), "注册完成后不应在内存中保留密码");
    }

    @Test
    @DisplayName("登录成功：返回 token 并写入安全上下文")
    void loginSuccessReturnsToken() {
        UmsMember member = member();
        member.setPassword("encodedPassword");
        when(memberMapper.selectByExample(any(UmsMemberExample.class))).thenReturn(Collections.singletonList(member));
        when(passwordEncoder.matches("123456", "encodedPassword")).thenReturn(true);
        when(jwtTokenUtil.generateToken(any(UserDetails.class))).thenReturn("jwt-token");

        String token = memberService.login(USERNAME, "123456");

        assertEquals("jwt-token", token);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        verify(memberCacheService).setMember(member);
    }

    @Test
    @DisplayName("登录失败：密码不正确时返回 null，不生成 token")
    void loginWithWrongPasswordReturnsNull() {
        UmsMember member = member();
        member.setPassword("encodedPassword");
        when(memberMapper.selectByExample(any(UmsMemberExample.class))).thenReturn(Collections.singletonList(member));
        when(passwordEncoder.matches("wrong", "encodedPassword")).thenReturn(false);

        assertNull(memberService.login(USERNAME, "wrong"));
        verify(jwtTokenUtil, never()).generateToken(any(UserDetails.class));
    }

    @Test
    @DisplayName("登录失败：用户名不存在时返回 null，不生成 token")
    void loginWithUnknownUsernameReturnsNull() {
        when(memberMapper.selectByExample(any(UmsMemberExample.class))).thenReturn(Collections.emptyList());

        assertNull(memberService.login("nobody", "123456"));
        verify(jwtTokenUtil, never()).generateToken(any(UserDetails.class));
    }

    @Test
    @DisplayName("加载用户：优先使用缓存中的会员")
    void loadUserByUsernameUsesCacheFirst() {
        when(memberCacheService.getMember(USERNAME)).thenReturn(member());

        UserDetails userDetails = memberService.loadUserByUsername(USERNAME);

        assertTrue(userDetails instanceof MemberDetails);
        verify(memberMapper, never()).selectByExample(any(UmsMemberExample.class));
    }

    @Test
    @DisplayName("修改密码：账号不存在时拒绝修改")
    void updatePasswordWithUnknownAccountIsRejected() {
        when(memberMapper.selectByExample(any(UmsMemberExample.class))).thenReturn(Collections.emptyList());

        ApiException exception = assertThrows(ApiException.class,
                () -> memberService.updatePassword(TELEPHONE, "newPassword", "123456"));

        assertTrue(exception.getMessage().contains("该账号不存在"));
        verify(memberMapper, never()).updateByPrimaryKeySelective(any(UmsMember.class));
    }

    @Test
    @DisplayName("修改密码：验证码错误时拒绝修改")
    void updatePasswordWithWrongAuthCodeIsRejected() {
        when(memberMapper.selectByExample(any(UmsMemberExample.class))).thenReturn(Collections.singletonList(member()));
        when(memberCacheService.getAuthCode(TELEPHONE)).thenReturn("123456");

        ApiException exception = assertThrows(ApiException.class,
                () -> memberService.updatePassword(TELEPHONE, "newPassword", "000000"));

        assertTrue(exception.getMessage().contains("验证码错误"));
        verify(memberMapper, never()).updateByPrimaryKeySelective(any(UmsMember.class));
    }

    @Test
    @DisplayName("扣减积分：非正数积分不访问数据库，直接判定失败")
    void deductIntegrationWithNonPositiveAmountIsRejected() {
        assertEquals(false, memberService.deductIntegration(MEMBER_ID, 0));
        assertEquals(false, memberService.deductIntegration(null, 10));
        assertEquals(false, memberService.deductIntegration(MEMBER_ID, null));

        verify(portalMemberDao, never()).deductIntegration(anyLong(), anyInt());
    }

    @Test
    @DisplayName("扣减积分：原子更新成功返回 true 并清除缓存")
    void deductIntegrationSuccessClearsCache() {
        when(portalMemberDao.deductIntegration(MEMBER_ID, 30)).thenReturn(1);

        assertEquals(true, memberService.deductIntegration(MEMBER_ID, 30));
        verify(memberCacheService).delMember(MEMBER_ID);
    }

    @Test
    @DisplayName("返还积分：原子更新未命中时返回 false，不能伪造成功")
    void refundIntegrationWithoutEffectReturnsFalse() {
        when(portalMemberDao.refundIntegration(MEMBER_ID, 30)).thenReturn(0);

        assertEquals(false, memberService.refundIntegration(MEMBER_ID, 30));
    }

    private UmsMember member() {
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setUsername(USERNAME);
        member.setStatus(1);
        return member;
    }

    private UmsMemberLevel defaultLevel() {
        UmsMemberLevel level = new UmsMemberLevel();
        level.setId(9L);
        level.setDefaultStatus(1);
        return level;
    }
}

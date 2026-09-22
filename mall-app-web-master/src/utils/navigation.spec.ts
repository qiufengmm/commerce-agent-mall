import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  AGENT_CHAT_PAGE,
  LOGIN_PAGE,
  buildLoginUrl,
  goBackOrHome,
  navigateAfterLogin,
  normalizeLoginRedirect,
} from './navigation'

describe('goBackOrHome', () => {
  const navigateBack = vi.fn()
  const switchTab = vi.fn()

  beforeEach(() => {
    navigateBack.mockReset()
    switchTab.mockReset()
    vi.stubGlobal('uni', { navigateBack, switchTab })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('navigates back when the page stack has a previous page', () => {
    vi.stubGlobal('getCurrentPages', vi.fn().mockReturnValue([{}, {}]))

    goBackOrHome()

    expect(navigateBack).toHaveBeenCalledWith({ delta: 1 })
    expect(switchTab).not.toHaveBeenCalled()
  })

  it('switches to the home tab when refresh leaves only the current page', () => {
    vi.stubGlobal('getCurrentPages', vi.fn().mockReturnValue([{}]))

    goBackOrHome()

    expect(switchTab).toHaveBeenCalledWith({ url: '/pages/index/index' })
    expect(navigateBack).not.toHaveBeenCalled()
  })
})

describe('normalizeLoginRedirect', () => {
  it('接受白名单内的项目内路径', () => {
    expect(normalizeLoginRedirect(AGENT_CHAT_PAGE)).toBe(AGENT_CHAT_PAGE)
    expect(normalizeLoginRedirect(`  ${AGENT_CHAT_PAGE}  `)).toBe(AGENT_CHAT_PAGE)
  })

  it('接受编码后的白名单路径', () => {
    expect(normalizeLoginRedirect('%2Fpages%2Fagent%2Fchat')).toBe(AGENT_CHAT_PAGE)
  })

  it('拒绝带协议或域名的地址', () => {
    expect(normalizeLoginRedirect('https://evil.example.com/pages/agent/chat')).toBeNull()
    expect(normalizeLoginRedirect('http://evil.example.com')).toBeNull()
    expect(normalizeLoginRedirect('weixin://pages/agent/chat')).toBeNull()
  })

  it('拒绝协议相对地址、反斜杠与编码双斜杠', () => {
    expect(normalizeLoginRedirect('//evil.example.com/pages/agent/chat')).toBeNull()
    expect(normalizeLoginRedirect('\\/pages/agent/chat')).toBeNull()
    expect(normalizeLoginRedirect('%2F%2Fevil.example.com')).toBeNull()
  })

  it('拒绝非白名单页面与相对路径', () => {
    expect(normalizeLoginRedirect(LOGIN_PAGE)).toBeNull()
    expect(normalizeLoginRedirect('/pages/index/index')).toBeNull()
    expect(normalizeLoginRedirect('pages/agent/chat')).toBeNull()
    expect(normalizeLoginRedirect('/pages/agent/chat/../../index/index')).toBeNull()
  })

  it('拒绝空值与非法类型', () => {
    expect(normalizeLoginRedirect('')).toBeNull()
    expect(normalizeLoginRedirect('   ')).toBeNull()
    expect(normalizeLoginRedirect(undefined)).toBeNull()
    expect(normalizeLoginRedirect(null)).toBeNull()
    expect(normalizeLoginRedirect(123)).toBeNull()
    expect(normalizeLoginRedirect({ path: AGENT_CHAT_PAGE })).toBeNull()
    expect(normalizeLoginRedirect('%E0%A4%A')).toBeNull()
  })

  it('保留白名单页面上的简单查询参数', () => {
    expect(normalizeLoginRedirect(`${AGENT_CHAT_PAGE}?from=login`)).toBe(
      `${AGENT_CHAT_PAGE}?from=login`
    )
    expect(normalizeLoginRedirect(`${AGENT_CHAT_PAGE}?a=1;b=2`)).toBeNull()
  })
})

describe('buildLoginUrl', () => {
  it('把白名单目标编码进 redirect 参数', () => {
    expect(buildLoginUrl(AGENT_CHAT_PAGE)).toBe(
      '/pages/public/login?redirect=%2Fpages%2Fagent%2Fchat'
    )
  })

  it('目标非法时不携带 redirect 参数', () => {
    expect(buildLoginUrl('https://evil.example.com')).toBe(LOGIN_PAGE)
    expect(buildLoginUrl(undefined)).toBe(LOGIN_PAGE)
  })
})

describe('navigateAfterLogin', () => {
  const redirectTo = vi.fn()
  const navigateBack = vi.fn()
  const switchTab = vi.fn()

  beforeEach(() => {
    redirectTo.mockReset()
    navigateBack.mockReset()
    switchTab.mockReset()
    vi.stubGlobal('uni', { redirectTo, navigateBack, switchTab })
    vi.stubGlobal('getCurrentPages', vi.fn().mockReturnValue([{}, {}]))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('白名单目标使用 redirectTo 回到导购页', () => {
    expect(navigateAfterLogin(AGENT_CHAT_PAGE)).toBe(true)
    expect(redirectTo).toHaveBeenCalledWith({
      url: AGENT_CHAT_PAGE,
      fail: expect.any(Function),
    })
    expect(navigateBack).not.toHaveBeenCalled()
  })

  it('非法目标不跳转，返回 false 由调用方维持原行为', () => {
    expect(navigateAfterLogin('https://evil.example.com')).toBe(false)
    expect(redirectTo).not.toHaveBeenCalled()
  })

  it('跳转失败时回退到常规返回逻辑', () => {
    redirectTo.mockImplementation((options: { fail: () => void }) => {
      options.fail()
    })

    navigateAfterLogin(AGENT_CHAT_PAGE)

    expect(navigateBack).toHaveBeenCalledWith({ delta: 1 })
  })
})

export const goBackOrHome = () => {
  const pages = getCurrentPages()

  if (pages.length > 1) {
    uni.navigateBack({ delta: 1 })
  } else {
    uni.switchTab({ url: '/pages/index/index' })
  }
}

/** 登录页路径 */
export const LOGIN_PAGE = '/pages/public/login'

/** 本期只允许智能导购页作为登录回跳目标 */
export const AGENT_CHAT_PAGE = '/pages/agent/chat'

const ALLOWED_LOGIN_REDIRECTS: readonly string[] = [AGENT_CHAT_PAGE]
const SAFE_QUERY_PATTERN = /^[A-Za-z0-9_=&%.\-]*$/
const PROTOCOL_PATTERN = /^[a-zA-Z][a-zA-Z0-9+.-]*:/

/**
 * 校验并规范化登录回跳路径。
 *
 * 只接受白名单内的项目内页面路径，拒绝协议、域名、协议相对地址、反斜杠、
 * 编码后的双斜杠以及非白名单页面，避免开放重定向。
 */
export const normalizeLoginRedirect = (raw: unknown): string | null => {
  if (typeof raw !== 'string') return null

  let value = raw.trim()
  if (!value) return null

  try {
    value = decodeURIComponent(value).trim()
  } catch {
    return null
  }
  if (!value) return null

  if (PROTOCOL_PATTERN.test(value)) return null
  if (value.includes('\\') || value.startsWith('//') || value.includes('//')) return null
  if (!value.startsWith('/')) return null

  const [path, ...rest] = value.split('?')
  if (!ALLOWED_LOGIN_REDIRECTS.includes(path)) return null

  const query = rest.join('?')
  if (query && !SAFE_QUERY_PATTERN.test(query)) return null

  return query ? `${path}?${query}` : path
}

/** 构造带安全回跳参数的登录页地址 */
export const buildLoginUrl = (redirect?: unknown): string => {
  const target = normalizeLoginRedirect(redirect)
  if (!target) return LOGIN_PAGE
  return `${LOGIN_PAGE}?redirect=${encodeURIComponent(target)}`
}

/** 登录成功后优先回到白名单目标页；返回 false 表示调用方应保持原有返回逻辑 */
export const navigateAfterLogin = (redirect?: unknown): boolean => {
  const target = normalizeLoginRedirect(redirect)
  if (!target) return false

  uni.redirectTo({ url: target, fail: () => goBackOrHome() })
  return true
}

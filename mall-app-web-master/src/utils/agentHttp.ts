/**
 * 智能导购专用请求器。
 *
 * 与门户 `http.ts` 的差异：
 * 1. 使用独立的 `VITE_AGENT_API_BASE_URL`；
 * 2. 使用更长的单次请求超时；
 * 3. 不触发门户 401 自动登出逻辑；
 * 4. 不写入任何 Token 日志。
 */
import type { CommonResult } from '@/types/common'

/** 单次聊天请求的前端超时（与服务端 35 秒总超时对齐） */
export const AGENT_REQUEST_TIMEOUT = 35 * 1000

/** 未显式配置时使用的默认前缀（Nginx 反向代理路径） */
export const DEFAULT_AGENT_BASE_URL = '/agent-api'

const stripTrailingSlash = (value: string): string => value.replace(/\/+$/, '')

const h5Origin = (): string => {
  const location = (globalThis as { location?: { origin?: unknown } }).location
  const origin = location?.origin
  if (typeof origin !== 'string' || !/^https?:\/\//i.test(origin)) {
    return ''
  }
  return stripTrailingSlash(origin)
}

/** 解析智能体基础地址；相对路径在 H5 下补全为同源绝对地址 */
export const resolveAgentBaseUrl = (): string => {
  const configured = String(import.meta.env.VITE_AGENT_API_BASE_URL ?? '').trim()
  const value = configured || DEFAULT_AGENT_BASE_URL

  if (/^https?:\/\//i.test(value)) {
    return stripTrailingSlash(value)
  }

  if (value.startsWith('/')) {
    const origin = h5Origin()
    return origin ? `${origin}${stripTrailingSlash(value)}` : stripTrailingSlash(value)
  }

  return stripTrailingSlash(`/${value}`)
}

/** 拼接智能体请求地址 */
export const buildAgentUrl = (path: string): string => {
  const normalized = path.startsWith('/') ? path : `/${path}`
  return `${resolveAgentBaseUrl()}${normalized}`
}

/** 前端统一错误对象（不用 instanceof，避免各端类继承差异） */
export type AgentRequestError = {
  isAgentError: true
  /** HTTP 状态码，网络失败时为 0 */
  statusCode: number
  /** 业务码 */
  code: number
  message: string
}

/** 类型守卫 */
export const isAgentRequestError = (value: unknown): value is AgentRequestError =>
  typeof value === 'object' && value !== null && (value as AgentRequestError).isAgentError === true

export interface AgentRequestOptions {
  /** 以 / 开头的相对路径，例如 /agent/chat */
  url: string
  method?: 'GET' | 'POST' | 'DELETE'
  /** JSON 请求体 */
  data?: Record<string, unknown>
}

const readToken = (): string => {
  const token = uni.getStorageSync('token')
  return typeof token === 'string' ? token : ''
}

/** 发送智能体请求；Token 只放在请求头，不写入日志与错误信息 */
export const agentRequest = <T>(options: AgentRequestOptions): Promise<CommonResult<T>> => {
  return new Promise<CommonResult<T>>((resolve, reject) => {
    const header: Record<string, string> = { Accept: 'application/json' }
    const token = readToken()
    if (token) {
      // 完整透传移动端保存的 Bearer Token，不做前缀解析
      header.Authorization = token
    }
    if (options.data !== undefined) {
      header['content-type'] = 'application/json'
    }

    const requestOptions = {
      url: buildAgentUrl(options.url),
      method: options.method ?? 'GET',
      data: options.data,
      header,
      timeout: AGENT_REQUEST_TIMEOUT,
      // 智能体使用独立服务地址与超时，跳过门户请求拦截器
      skipPortalInterceptor: true,
      success(res: UniApp.RequestSuccessCallbackResult) {
        const statusCode = res.statusCode ?? 0
        const body = res.data as CommonResult<T> | undefined
        const message =
          (body && typeof body.message === 'string' && body.message) || '智能导购请求失败'

        if (statusCode >= 200 && statusCode < 300 && body && body.code === 200) {
          resolve(body)
          return
        }

        reject({
          isAgentError: true,
          statusCode,
          code: body && typeof body.code === 'number' ? body.code : statusCode,
          message,
        } satisfies AgentRequestError)
      },
      fail() {
        reject({
          isAgentError: true,
          statusCode: 0,
          code: 0,
          message: '网络异常，请检查网络后重试',
        } satisfies AgentRequestError)
      },
    }

    uni.request(requestOptions as unknown as UniApp.RequestOptions)
  })
}

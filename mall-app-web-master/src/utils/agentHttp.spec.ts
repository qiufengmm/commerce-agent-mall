import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  AGENT_REQUEST_TIMEOUT,
  DEFAULT_AGENT_BASE_URL,
  agentRequest,
  buildAgentUrl,
  isAgentRequestError,
  resolveAgentBaseUrl,
} from './agentHttp'

const TOKEN = 'Bearer placeholder-member-token'

type RequestOptions = {
  url: string
  method: string
  data?: unknown
  header: Record<string, string>
  timeout: number
  skipPortalInterceptor: boolean
  success: (res: { statusCode: number; data: unknown }) => void
  fail: (err: unknown) => void
}

describe('agentHttp', () => {
  const getStorageSync = vi.fn(() => TOKEN)
  const request = vi.fn()

  beforeEach(() => {
    getStorageSync.mockReset()
    getStorageSync.mockReturnValue(TOKEN)
    request.mockReset()
    vi.stubGlobal('uni', { getStorageSync, request })
    vi.stubEnv('VITE_AGENT_API_BASE_URL', 'http://localhost:8086')
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
  })

  it('使用 VITE_AGENT_API_BASE_URL 拼接绝对地址', () => {
    expect(resolveAgentBaseUrl()).toBe('http://localhost:8086')
    expect(buildAgentUrl('/agent/chat')).toBe('http://localhost:8086/agent/chat')
    expect(buildAgentUrl('agent/session/abc')).toBe('http://localhost:8086/agent/session/abc')
  })

  it('未配置时回退到默认的 /agent-api 前缀', () => {
    vi.stubEnv('VITE_AGENT_API_BASE_URL', '')

    expect(resolveAgentBaseUrl()).toBe(DEFAULT_AGENT_BASE_URL)
    expect(buildAgentUrl('/agent/chat')).toBe('/agent-api/agent/chat')
  })

  it('去掉配置里的多余结尾斜杠', () => {
    vi.stubEnv('VITE_AGENT_API_BASE_URL', 'http://agent.example.com//')

    expect(buildAgentUrl('/health/live')).toBe('http://agent.example.com/health/live')
  })

  it('只给智能体请求透传完整 Authorization Token', async () => {
    request.mockImplementation((options: RequestOptions) => {
      options.success({ statusCode: 200, data: { code: 200, message: '操作成功', data: { ok: 1 } } })
    })

    const result = await agentRequest<{ ok: number }>({
      url: '/agent/chat',
      method: 'POST',
      data: { sessionId: 'abc', message: '你好' },
    })

    const options = request.mock.calls[0][0] as RequestOptions
    expect(options.header.Authorization).toBe(TOKEN)
    expect(options.header['source-client']).toBeUndefined()
    expect(options.skipPortalInterceptor).toBe(true)
    expect(options.timeout).toBe(AGENT_REQUEST_TIMEOUT)
    expect(options.url).toBe('http://localhost:8086/agent/chat')
    expect(options.method).toBe('POST')
    expect(result.data).toEqual({ ok: 1 })
  })

  it('没有 Token 时不设置 Authorization 头', async () => {
    getStorageSync.mockReturnValue('')
    request.mockImplementation((options: RequestOptions) => {
      options.success({ statusCode: 200, data: { code: 200, message: '操作成功', data: {} } })
    })

    await agentRequest({ url: '/agent/session/abc' })

    const options = request.mock.calls[0][0] as RequestOptions
    expect(options.header.Authorization).toBeUndefined()
  })

  it('业务成功之外的响应转换为带状态码的错误', async () => {
    request.mockImplementation((options: RequestOptions) => {
      options.success({
        statusCode: 429,
        data: { code: 429, message: '请求过于频繁，请稍后再试', data: null },
      })
    })

    const error = await agentRequest({ url: '/agent/chat', method: 'POST', data: {} }).catch(
      (value) => value
    )

    expect(isAgentRequestError(error)).toBe(true)
    expect(error.statusCode).toBe(429)
    expect(error.message).toContain('稍后再试')
    expect(JSON.stringify(error)).not.toContain(TOKEN)
  })

  it('模型未配置时保留 503 状态码，便于前端差异化提示', async () => {
    request.mockImplementation((options: RequestOptions) => {
      options.success({
        statusCode: 503,
        data: { code: 503, message: '智能导购暂时不可用', data: null },
      })
    })

    const error = await agentRequest({ url: '/agent/chat', method: 'POST', data: {} }).catch(
      (value) => value
    )

    expect(error.statusCode).toBe(503)
    expect(error.message).toContain('智能导购')
  })

  it('网络失败时返回统一错误且不包含 Token', async () => {
    request.mockImplementation((options: RequestOptions) => {
      options.fail({ errMsg: 'request:fail' })
    })

    const error = await agentRequest({ url: '/agent/chat', method: 'POST', data: {} }).catch(
      (value) => value
    )

    expect(isAgentRequestError(error)).toBe(true)
    expect(error.statusCode).toBe(0)
    expect(String(error.message)).not.toContain(TOKEN)
    expect(JSON.stringify(error)).not.toContain(TOKEN)
  })

  it('非 200 业务码且 HTTP 为 2xx 时也判定为失败', async () => {
    request.mockImplementation((options: RequestOptions) => {
      options.success({
        statusCode: 200,
        data: { code: 422, message: '本次问题需要缩小范围', data: null },
      })
    })

    const error = await agentRequest({ url: '/agent/chat', method: 'POST', data: {} }).catch(
      (value) => value
    )

    expect(error.statusCode).toBe(200)
    expect(error.code).toBe(422)
  })
})

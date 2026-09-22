import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  AGENT_SESSION_STORAGE_KEY,
  generateAgentSessionId,
  getAgentSessionId,
  isValidAgentSessionId,
  resetAgentSessionId,
} from './agentSession'

const UUID_V4_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

describe('agentSession', () => {
  const store = new Map<string, unknown>()
  const getStorageSync = vi.fn((key: string) => store.get(key) ?? '')
  const setStorageSync = vi.fn((key: string, value: unknown) => {
    store.set(key, value)
  })

  beforeEach(() => {
    store.clear()
    getStorageSync.mockClear()
    setStorageSync.mockClear()
    vi.stubGlobal('uni', { getStorageSync, setStorageSync })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('使用固定存储键 mall-agent-session-id', () => {
    expect(AGENT_SESSION_STORAGE_KEY).toBe('mall-agent-session-id')
  })

  it('首次访问生成 UUID v4 并写回本地', () => {
    const sessionId = getAgentSessionId()

    expect(UUID_V4_PATTERN.test(sessionId)).toBe(true)
    expect(setStorageSync).toHaveBeenCalledWith(AGENT_SESSION_STORAGE_KEY, sessionId)
    expect(store.get(AGENT_SESSION_STORAGE_KEY)).toBe(sessionId)
  })

  it('复用本地已存在的合法 sessionId 并规范化为小写', () => {
    store.set(AGENT_SESSION_STORAGE_KEY, '2DC7B03E-7368-4D6A-A8EF-B0EA16F6C92C')

    const sessionId = getAgentSessionId()

    expect(sessionId).toBe('2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c')
    expect(setStorageSync).toHaveBeenCalledWith(
      AGENT_SESSION_STORAGE_KEY,
      '2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c'
    )
  })

  it('本地值非法时重新生成', () => {
    store.set(AGENT_SESSION_STORAGE_KEY, 'not-a-uuid')

    const sessionId = getAgentSessionId()

    expect(sessionId).not.toBe('not-a-uuid')
    expect(UUID_V4_PATTERN.test(sessionId)).toBe(true)
    expect(store.get(AGENT_SESSION_STORAGE_KEY)).toBe(sessionId)
  })

  it('清空对话时生成新的 sessionId', () => {
    const first = getAgentSessionId()

    const reset = resetAgentSessionId()

    expect(reset).not.toBe(first)
    expect(UUID_V4_PATTERN.test(reset)).toBe(true)
    expect(getAgentSessionId()).toBe(reset)
  })

  it('生成的 sessionId 不重复', () => {
    const ids = new Set(Array.from({ length: 20 }, () => generateAgentSessionId()))

    expect(ids.size).toBe(20)
  })

  it('只接受规范 UUID v4', () => {
    expect(isValidAgentSessionId('2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c')).toBe(true)
    expect(isValidAgentSessionId('2dc7b03e-7368-1d6a-a8ef-b0ea16f6c92c')).toBe(false)
    expect(isValidAgentSessionId('2dc7b03e73684d6aa8efb0ea16f6c92c')).toBe(false)
    expect(isValidAgentSessionId('')).toBe(false)
    expect(isValidAgentSessionId(undefined)).toBe(false)
    expect(isValidAgentSessionId(123)).toBe(false)
  })
})

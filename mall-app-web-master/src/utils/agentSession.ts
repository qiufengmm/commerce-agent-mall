/** 智能导购会话本地存储：只保存随机 sessionId，不保存任何模型凭据 */

/** 固定存储键 */
export const AGENT_SESSION_STORAGE_KEY = 'mall-agent-session-id'

const UUID_V4_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

/** 校验是否为规范 UUID v4 */
export const isValidAgentSessionId = (value: unknown): value is string => {
  if (typeof value !== 'string') return false
  const text = value.trim()
  return UUID_V4_PATTERN.test(text) && text.length === 36
}

/** 生成 UUID v4；优先使用平台加密随机数，缺失时退化为位运算填充 */
export const generateAgentSessionId = (): string => {
  const globalCrypto = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto
  const randomUUID = globalCrypto?.randomUUID
  if (typeof randomUUID === 'function') {
    return randomUUID.call(globalCrypto).toLowerCase()
  }

  const bytes: number[] = []
  for (let index = 0; index < 16; index += 1) {
    bytes.push(Math.floor(Math.random() * 256))
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80

  const hex = bytes.map((value) => value.toString(16).padStart(2, '0'))
  return [
    hex.slice(0, 4).join(''),
    hex.slice(4, 6).join(''),
    hex.slice(6, 8).join(''),
    hex.slice(8, 10).join(''),
    hex.slice(10, 16).join(''),
  ].join('-')
}

/** 读取本地 sessionId；不存在或非法时重新生成并写回 */
export const getAgentSessionId = (): string => {
  const stored = uni.getStorageSync(AGENT_SESSION_STORAGE_KEY)
  if (isValidAgentSessionId(stored)) {
    const normalized = stored.trim().toLowerCase()
    if (normalized !== stored) {
      uni.setStorageSync(AGENT_SESSION_STORAGE_KEY, normalized)
    }
    return normalized
  }

  const created = generateAgentSessionId()
  uni.setStorageSync(AGENT_SESSION_STORAGE_KEY, created)
  return created
}

/** 重新生成 sessionId，用于「清空对话」 */
export const resetAgentSessionId = (): string => {
  const created = generateAgentSessionId()
  uni.setStorageSync(AGENT_SESSION_STORAGE_KEY, created)
  return created
}

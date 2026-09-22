/** 商品导购智能体相关类型 */

/** 服务端构造的商品卡片；这里的字段全部来自后端工具事实 */
export type AgentProductCard = {
  /** 商品 id */
  id: number
  /** 商品名称 */
  name: string
  /** 商品主图 */
  pic?: string | null
  /** 价格（两位小数字符串） */
  price?: string | null
  /** 副标题 */
  subtitle?: string | null
  /** 库存状态：IN_STOCK / LOW_STOCK / OUT_OF_STOCK */
  stockStatus: string
  /** 可售库存 */
  availableStock: number
  /** 详情页路径，服务端固定为 /pages/product/product?id=<id> */
  detailPath: string
}

/** 发送消息请求体 */
export type AgentChatRequest = {
  /** 会话 id（UUID v4） */
  sessionId: string
  /** 用户问题 */
  message: string
}

/** 发送消息响应数据 */
export type AgentChatData = {
  sessionId: string
  messageId: string
  answer: string
  products: AgentProductCard[]
  requiresLogin: boolean
  suggestedQuestions: string[]
}

/** 会话消息 */
export type AgentSessionMessage = {
  role: 'user' | 'assistant'
  content: string
}

/** 恢复会话响应数据 */
export type AgentSessionData = {
  sessionId: string
  messages: AgentSessionMessage[]
  products: AgentProductCard[]
  requiresLogin: boolean
}

/** 清空会话响应数据 */
export type AgentDeleteSessionData = {
  sessionId: string
  deleted: boolean
  requiresLogin: boolean
}

/** 页面内展示的一条聊天消息 */
export type AgentChatMessage = {
  id: string
  role: 'user' | 'assistant'
  content: string
  /** 助手消息附带的商品卡片 */
  products?: AgentProductCard[]
  /** 建议追问 */
  suggestedQuestions?: string[]
  /** 本地状态：发送中 / 失败 */
  pending?: boolean
  failed?: boolean
  /** 失败时可重试的原始问题 */
  retryText?: string
}

/** 前端错误分类，用于差异化提示 */
export type AgentErrorKind =
  | 'model_unavailable'
  | 'upstream'
  | 'rate_limited'
  | 'timeout'
  | 'invalid'
  | 'conflict'
  | 'network'
  | 'unknown'

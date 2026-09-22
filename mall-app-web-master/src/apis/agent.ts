import { agentRequest } from '@/utils/agentHttp'
import type {
  AgentChatData,
  AgentChatRequest,
  AgentDeleteSessionData,
  AgentSessionData,
} from '@/types/agent'

/** 发送消息给商品导购智能体 */
export const chatWithAgentAPI = (data: AgentChatRequest) => {
  return agentRequest<AgentChatData>({ method: 'POST', url: '/agent/chat', data })
}

/** 恢复会话（最近 20 条消息与最近一组商品卡片） */
export const getAgentSessionAPI = (sessionId: string) => {
  return agentRequest<AgentSessionData>({ method: 'GET', url: `/agent/session/${sessionId}` })
}

/** 清空会话，幂等 */
export const deleteAgentSessionAPI = (sessionId: string) => {
  return agentRequest<AgentDeleteSessionData>({
    method: 'DELETE',
    url: `/agent/session/${sessionId}`,
  })
}

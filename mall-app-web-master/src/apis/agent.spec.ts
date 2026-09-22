import { beforeEach, describe, expect, it, vi } from 'vitest'
import { chatWithAgentAPI, deleteAgentSessionAPI, getAgentSessionAPI } from './agent'
import { agentRequest } from '@/utils/agentHttp'

// 只验证接口层的请求参数，不发起真实网络请求
vi.mock('@/utils/agentHttp', () => ({ agentRequest: vi.fn() }))

const mockedAgentRequest = vi.mocked(agentRequest)

beforeEach(() => {
  mockedAgentRequest.mockReset()
  mockedAgentRequest.mockResolvedValue({
    code: 200,
    message: '操作成功',
    data: {} as any,
  })
})

describe('智能导购 API', () => {
  it('发送消息使用 POST /agent/chat 并携带 sessionId 与 message', async () => {
    await chatWithAgentAPI({ sessionId: '2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c', message: '有手机吗' })

    expect(mockedAgentRequest).toHaveBeenCalledWith({
      method: 'POST',
      url: '/agent/chat',
      data: { sessionId: '2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c', message: '有手机吗' },
    })
  })

  it('恢复会话使用 GET /agent/session/{sessionId}', async () => {
    await getAgentSessionAPI('2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c')

    expect(mockedAgentRequest).toHaveBeenCalledWith({
      method: 'GET',
      url: '/agent/session/2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c',
    })
  })

  it('清空会话使用 DELETE /agent/session/{sessionId}', async () => {
    await deleteAgentSessionAPI('2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c')

    expect(mockedAgentRequest).toHaveBeenCalledWith({
      method: 'DELETE',
      url: '/agent/session/2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c',
    })
  })

  it('接口失败时向上抛出，不被静默吞掉', async () => {
    mockedAgentRequest.mockRejectedValueOnce({ isAgentError: true, statusCode: 502 })

    await expect(
      chatWithAgentAPI({ sessionId: '2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c', message: 'hi' })
    ).rejects.toMatchObject({ statusCode: 502 })
  })
})

import type { CommonPage } from '@/types/common'
import type {
  CommentQueryParam,
  CommentReplyParam,
  CommentShowStatusParam,
  PmsComment,
  PmsCommentReplay,
} from '@/types/comment'
import http from '@/utils/http'

/**
 * 分页查询商品评价
 */
export function getCommentListAPI(params: CommentQueryParam) {
  return http<CommonPage<PmsComment>>({
    url: '/comment/list',
    method: 'get',
    params: params,
  })
}

/**
 * 修改评价显示状态
 */
export function updateCommentShowStatusAPI(id: number, data: CommentShowStatusParam) {
  return http({
    url: '/comment/update/showStatus/' + id,
    method: 'post',
    data: data,
  })
}

/**
 * 查询评价的回复列表
 */
export function getCommentReplayListAPI(commentId: number) {
  return http<PmsCommentReplay[]>({
    url: '/comment/replay/list/' + commentId,
    method: 'get',
  })
}

/**
 * 回复商品评价
 */
export function createCommentReplayAPI(data: CommentReplyParam) {
  return http({
    url: '/comment/replay/create',
    method: 'post',
    data: data,
  })
}

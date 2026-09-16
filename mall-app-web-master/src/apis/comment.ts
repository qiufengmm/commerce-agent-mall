import { http } from '@/utils/http'
import type { PmsComment, PmsCommentParam } from '@/types/comment'
import type { CommonPage, PageParam } from '@/types/common'

/** 提交商品评价 */
export const addCommentAPI = (data: PmsCommentParam) => {
  return http({
    method: 'POST',
    url: '/comment/add',
    data,
  })
}

/** 分页查询商品评价 */
export const getCommentListAPI = (params: PageParam & { productId: number }) => {
  return http<CommonPage<PmsComment>>({
    method: 'GET',
    url: '/comment/list',
    params,
  })
}

/** 判断订单明细是否已评价 */
export const checkCommentExistsAPI = (params: { orderItemId: number }) => {
  return http<boolean>({
    method: 'GET',
    url: '/comment/exists',
    params,
  })
}

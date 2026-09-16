import type { PageParam } from './common'

/** 商品评价 */
export type PmsComment = {
  /** 主键ID */
  id: number
  /** 商品id */
  productId: number
  /** 评价会员id */
  memberId: number
  /** 所属订单id */
  orderId: number
  /** 订单明细id，一条订单明细只允许评价一次 */
  orderItemId: number
  /** 会员昵称 */
  memberNickName: string
  /** 商品名称 */
  productName: string
  /** 评价星数：0->5 */
  star: number
  /** 评价的ip */
  memberIp: string
  /** 评价时间 */
  createTime: string
  /** 显示状态：0->不显示；1->显示 */
  showStatus: number
  /** 购买时的商品属性 */
  productAttribute: string
  /** 点赞数 */
  collectCouont: number
  /** 阅读数 */
  readCount: number
  /** 上传图片地址，以逗号隔开 */
  pics: string
  /** 评论用户头像 */
  memberIcon: string
  /** 回复数量 */
  replayCount: number
  /** 评价内容 */
  content: string
}

/** 商品评价回复 */
export type PmsCommentReplay = {
  /** 主键ID */
  id: number
  /** 评价id */
  commentId: number
  /** 回复人昵称 */
  memberNickName: string
  /** 回复人头像 */
  memberIcon: string
  /** 回复内容 */
  content: string
  /** 回复时间 */
  createTime: string
  /** 评论人员类型；0->会员；1->管理员 */
  type: number
}

/** 商品评价查询参数 */
export type CommentQueryParam = PageParam & {
  /** 商品名称关键字 */
  productKeyword?: string
  /** 会员昵称 */
  memberNickName?: string
  /** 显示状态：0->不显示；1->显示 */
  showStatus?: number
}

/** 修改评价显示状态参数 */
export type CommentShowStatusParam = {
  /** 显示状态：0->不显示；1->显示 */
  showStatus: number
}

/** 回复商品评价参数 */
export type CommentReplyParam = {
  /** 评价id */
  commentId: number
  /** 回复内容 */
  content: string
}

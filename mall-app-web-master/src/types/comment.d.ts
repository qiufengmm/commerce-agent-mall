/** 商品评价 */
export type PmsComment = {
  id: number
  /** 商品id */
  productId: number
  /** 评价会员id */
  memberId: number
  /** 所属订单id */
  orderId: number
  /** 订单明细id */
  orderItemId: number
  /** 评价会员昵称 */
  memberNickName: string
  /** 商品名称 */
  productName: string
  /** 评价星数：0->5 */
  star: number
  /** 评价的ip */
  memberIp: string
  /** 评价时间 */
  createTime: string
  /** 展示状态 */
  showStatus: number
  /** 购买时的商品属性 */
  productAttribute: string
  collectCouont: number
  readCount: number
  /** 评价内容 */
  content: string
  /** 评价图片地址，以逗号隔开 */
  pics: string
  /** 评价用户头像 */
  memberIcon: string
  replayCount: number
}

/** 提交商品评价参数 */
export type PmsCommentParam = {
  /** 所属订单id */
  orderId: number
  /** 订单明细id，一条订单明细只能评价一次 */
  orderItemId: number
  /** 评价星数：1->5 */
  star: number
  /** 评价内容 */
  content: string
  /** 评价图片地址，以逗号隔开 */
  pics?: string
}

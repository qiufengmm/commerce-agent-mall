/**
 * 支付结果判断（无副作用纯函数，便于单测与页面复用）
 */

/** 服务端返回的「本地订单已支付成功」状态，只有这一个值才代表支付成功 */
export const LOCAL_PAY_SUCCESS = 'TRADE_SUCCESS'

/** 支付宝交易已创建但买家还未付款，属于处理中 */
export const ALIPAY_WAIT_BUYER_PAY = 'WAIT_BUYER_PAY'

/** 支付成功文案 */
export const PAY_TEXT_SUCCESS = '支付成功'
/** 支付处理中文案 */
export const PAY_TEXT_PROCESSING = '支付处理中'
/** 支付失败文案 */
export const PAY_TEXT_FAILED = '支付失败'

/**
 * 根据支付状态串解析结果文案
 *
 * 说明：不能只依据支付宝原始交易状态判断成功。
 * 服务端只有在「订单归属校验通过 + 金额一致 + 本地支付成功」时才会返回 TRADE_SUCCESS，
 * 本地校验失败会返回 PAYMENT_VERIFY_FAILED / PAYMENT_PROCESS_FAILED，
 * 这些值一律按未成功处理，避免订单仍是待付款却显示支付成功。
 *
 * @param tradeStatus 服务端返回的支付状态，可能是 null、undefined 或未知状态
 */
export const resolvePayResultText = (tradeStatus?: string | null): string => {
  if (tradeStatus === LOCAL_PAY_SUCCESS) {
    return PAY_TEXT_SUCCESS
  }
  if (tradeStatus === ALIPAY_WAIT_BUYER_PAY) {
    return PAY_TEXT_PROCESSING
  }
  // 包含 null、TRADE_CLOSED、TRADE_FINISHED、PAYMENT_VERIFY_FAILED、PAYMENT_PROCESS_FAILED
  return PAY_TEXT_FAILED
}

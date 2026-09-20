import { describe, expect, it } from 'vitest'
import {
  ALIPAY_WAIT_BUYER_PAY,
  LOCAL_PAY_SUCCESS,
  PAY_TEXT_FAILED,
  PAY_TEXT_PROCESSING,
  PAY_TEXT_SUCCESS,
  resolvePayResultText,
} from './payment'

describe('resolvePayResultText', () => {
  it('TRADE_SUCCESS 映射为支付成功', () => {
    expect(resolvePayResultText('TRADE_SUCCESS')).toBe(PAY_TEXT_SUCCESS)
    expect(resolvePayResultText('TRADE_SUCCESS')).toBe('支付成功')
  })

  it('WAIT_BUYER_PAY 映射为支付处理中', () => {
    expect(resolvePayResultText(ALIPAY_WAIT_BUYER_PAY)).toBe(PAY_TEXT_PROCESSING)
    expect(resolvePayResultText('WAIT_BUYER_PAY')).toBe('支付处理中')
  })

  it('其他交易状态一律映射为支付失败，不能显示成功', () => {
    expect(resolvePayResultText('TRADE_CLOSED')).toBe(PAY_TEXT_FAILED)
    expect(resolvePayResultText('TRADE_FINISHED')).toBe(PAY_TEXT_FAILED)
    expect(resolvePayResultText('PAYMENT_VERIFY_FAILED')).toBe(PAY_TEXT_FAILED)
    expect(resolvePayResultText('PAYMENT_PROCESS_FAILED')).toBe(PAY_TEXT_FAILED)
    expect(resolvePayResultText('UNKNOWN_STATUS')).toBe(PAY_TEXT_FAILED)
  })

  it('空值与空串映射为支付失败，避免订单待付款却显示成功', () => {
    expect(resolvePayResultText(null)).toBe(PAY_TEXT_FAILED)
    expect(resolvePayResultText(undefined)).toBe(PAY_TEXT_FAILED)
    expect(resolvePayResultText('')).toBe(PAY_TEXT_FAILED)
    expect(resolvePayResultText()).toBe(PAY_TEXT_FAILED)
  })

  it('只有大小写与值完全匹配才算成功，避免误判', () => {
    expect(resolvePayResultText('trade_success')).toBe(PAY_TEXT_FAILED)
    expect(resolvePayResultText(` ${LOCAL_PAY_SUCCESS} `)).toBe(PAY_TEXT_FAILED)
  })
})

import { describe, expect, it } from 'vitest'
import { removeCartItemsById } from './cart'

describe('removeCartItemsById', () => {
  it('删除接口成功后应移除数值和字符串形式的已选商品 ID', () => {
    const cartItems = [{ id: 12 }, { id: '13' }, { id: 14 }]

    expect(removeCartItemsById(cartItems, '12,13')).toEqual([{ id: 14 }])
  })
})

type CartItemWithId = {
  id: string | number
}

/**
 * 按后端返回的逗号分隔 ID，从本地购物车列表移除对应商品。
 * 接口 ID 以字符串传递，而列表 ID 可能是数值，因此统一转为字符串比较。
 */
export const removeCartItemsById = <T extends CartItemWithId>(items: T[], ids: string) => {
  const deletedIds = new Set(ids.split(','))

  return items.filter((item) => !deletedIds.has(String(item.id)))
}

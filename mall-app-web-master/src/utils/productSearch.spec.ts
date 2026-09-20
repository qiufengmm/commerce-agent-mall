import { describe, expect, it } from 'vitest'
import {
  SORT_DEFAULT,
  SORT_PRICE_ASC,
  SORT_PRICE_DESC,
  SORT_SALE,
  canStartSearchLoad,
  resolveSearchPageState,
  resolveSearchSort,
} from './productSearch'

describe('resolveSearchSort', () => {
  it('综合排序使用 sort=0', () => {
    expect(resolveSearchSort({ filterIndex: 0, priceOrder: 0 })).toBe(SORT_DEFAULT)
  })

  it('销量优先使用 sort=2', () => {
    expect(resolveSearchSort({ filterIndex: 1, priceOrder: 0 })).toBe(SORT_SALE)
  })

  it('价格从低到高使用 sort=3', () => {
    expect(resolveSearchSort({ filterIndex: 2, priceOrder: 1 })).toBe(SORT_PRICE_ASC)
  })

  it('价格从高到低使用 sort=4', () => {
    expect(resolveSearchSort({ filterIndex: 2, priceOrder: 2 })).toBe(SORT_PRICE_DESC)
  })
})

describe('resolveSearchPageState', () => {
  it('返回空列表时标记没有更多，并回退页码', () => {
    const state = resolveSearchPageState({ listLength: 0, pageSize: 6, pageNum: 3 })

    expect(state.loadingType).toBe('nomore')
    expect(state.hasMore).toBe(false)
    // 空页时必须回退，否则下一次触底会跳过一页
    expect(state.nextPageNum).toBe(2)
  })

  it('返回条数不足一页时标记没有更多，并回退页码', () => {
    const state = resolveSearchPageState({ listLength: 4, pageSize: 6, pageNum: 2 })

    expect(state.loadingType).toBe('nomore')
    expect(state.hasMore).toBe(false)
    expect(state.nextPageNum).toBe(1)
  })

  it('返回满一页时保持可加载，页码不变', () => {
    const state = resolveSearchPageState({ listLength: 6, pageSize: 6, pageNum: 2 })

    expect(state.loadingType).toBe('more')
    expect(state.hasMore).toBe(true)
    expect(state.nextPageNum).toBe(2)
  })

  it('第一页就为空时不产生负数页码的额外特殊分支，保持与原逻辑一致', () => {
    const state = resolveSearchPageState({ listLength: 0, pageSize: 6, pageNum: 1 })

    expect(state.loadingType).toBe('nomore')
    expect(state.nextPageNum).toBe(0)
  })
})

describe('canStartSearchLoad', () => {
  it('没有更多数据时下拉刷新仍可发起请求', () => {
    expect(canStartSearchLoad('refresh', 'nomore')).toBe(true)
  })

  it('没有更多数据时上拉加载不再发起请求', () => {
    expect(canStartSearchLoad('add', 'nomore')).toBe(false)
  })

  it('还有更多数据时刷新与上拉都可以发起请求', () => {
    expect(canStartSearchLoad('add', 'more')).toBe(true)
    expect(canStartSearchLoad('refresh', 'more')).toBe(true)
    expect(canStartSearchLoad('add', 'loading')).toBe(true)
  })
})

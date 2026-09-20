/**
 * 商品搜索列表的排序与分页状态计算（无副作用纯函数，便于单测与页面复用）
 */

/** 搜索排序方式：与后端 sort 入参保持一致 */
export const SORT_DEFAULT = 0
/** 按销量排序 */
export const SORT_SALE = 2
/** 价格从低到高 */
export const SORT_PRICE_ASC = 3
/** 价格从高到低 */
export const SORT_PRICE_DESC = 4

/** 页面加载状态：more-可加载，loading-加载中，nomore-没有更多 */
export type SearchLoadingType = 'more' | 'loading' | 'nomore'

/** 搜索排序入参 */
export type SearchSortParam = {
  /** 筛选索引：0-综合排序，1-销量，2-价格 */
  filterIndex: number
  /** 价格排序：0-无，1-从低到高，2-从高到低 */
  priceOrder: number
}

/** 分页状态计算结果 */
export type SearchPageState = {
  /** 列表底部展示的加载状态 */
  loadingType: SearchLoadingType
  /** 是否还有下一页 */
  hasMore: boolean
  /** 本次加载结束后应回退到的页码 */
  nextPageNum: number
}

/**
 * 根据筛选索引与价格排序计算后端 sort 参数
 */
export const resolveSearchSort = ({ filterIndex, priceOrder }: SearchSortParam): number => {
  if (filterIndex === 1) {
    return SORT_SALE
  }
  if (filterIndex === 2) {
    return priceOrder === 1 ? SORT_PRICE_ASC : SORT_PRICE_DESC
  }
  return SORT_DEFAULT
}

/**
 * 根据本次返回条数判定加载状态与下一页页码
 *
 * - 返回 0 条：没有更多数据
 * - 返回条数不足每页数量：没有更多数据
 * - 返回满一页：仍可继续上拉加载
 */
export const resolveSearchPageState = (params: {
  /** 本次接口返回的数据条数 */
  listLength: number
  /** 每页数量 */
  pageSize: number
  /** 本次请求使用的页码，从1开始 */
  pageNum: number
}): SearchPageState => {
  const { listLength, pageSize, pageNum } = params
  const hasMore = listLength > 0 && listLength >= pageSize

  if (!hasMore) {
    // 空结果或不满一页时回退页码，保证下一次触底仍请求当前页而不是跳页
    return { loadingType: 'nomore', hasMore: false, nextPageNum: pageNum - 1 }
  }
  return { loadingType: 'more', hasMore: true, nextPageNum: pageNum }
}

/**
 * 判断本次加载是否应该发起请求
 * 已经没有更多数据时，只允许下拉刷新，不允许继续上拉加载
 */
export const canStartSearchLoad = (
  type: 'refresh' | 'add',
  loadingType: SearchLoadingType,
): boolean => !(type === 'add' && loadingType === 'nomore')

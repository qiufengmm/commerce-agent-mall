import { ref } from 'vue'
import type { Ref } from 'vue'

/** 加载类型：refresh-下拉刷新 add-上拉加载更多 */
export type LoadType = 'refresh' | 'add'

/** 列表底部加载状态 */
export type LoadingType = 'more' | 'loading' | 'nomore'

/** 分页请求参数 */
export interface PagingFetchParams {
  pageNum: number
  pageSize: number
}

/** 分页请求结果 */
export interface PagingFetchResult<T> {
  list: T[]
  total: number
}

export interface UseCommentPagingOptions<T> {
  /** 每页数量，默认 10 */
  pageSize?: number
  /** 数据请求实现，由页面传入具体接口 */
  fetcher: (params: PagingFetchParams) => Promise<PagingFetchResult<T>>
  /** 是否允许发起请求，用于商品 id 缺失、未登录等前置判断 */
  canLoad?: () => boolean
  /**
   * 本次加载终止时的统一回调，一次 load 调用内恰好触发一次
   * @param type 本次加载类型
   * @param isLatest 是否为当前最新请求。被更新的请求抢占后旧请求为 false，
   * 页面据此避免用旧请求结束最新一次下拉刷新动画
   */
  onFinish?: (type: LoadType, isLatest: boolean) => void
  /** 请求失败回调 */
  onError?: (error: unknown, type: LoadType) => void
}

export interface UseCommentPagingReturn<T> {
  /** 列表数据 */
  list: Ref<T[]>
  /** 数据总数 */
  total: Ref<number>
  /** 下一页页码 */
  pageNum: Ref<number>
  /** 每页数量 */
  pageSize: Ref<number>
  /** 是否首次加载中 */
  loading: Ref<boolean>
  /** 底部加载状态 */
  loadingType: Ref<LoadingType>
  /** 是否有请求正在执行 */
  requesting: Ref<boolean>
  /** 加载数据，默认 add */
  load: (type?: LoadType) => Promise<void>
  /** 重置为初始状态，并让进行中的旧响应失效 */
  reset: () => void
}

/**
 * 评价列表分页控制器
 *
 * 收敛「全部评价」与「我的评价」两个页面重复的分页时序逻辑，保证：
 * - 同一时间的上拉加载互斥，连续触底不会重复请求同一页；
 * - 下拉刷新可以抢占进行中的请求，旧响应按请求序号丢弃，避免覆盖新数据；
 * - 页码只在请求成功后推进，失败后保持原页码，可重试；
 * - 刷新成功后整体替换数据，分页成功后追加数据；
 * - 请求锁在成功、失败、提前返回时都会释放，不会永久锁死；
 * - 一次 load 调用无论走哪条终止路径，onFinish 都恰好触发一次；
 * - onFinish 的 isLatest 标识本次请求是否仍是最新请求，被抢占的旧请求为 false，
 *   页面据此只用最新一次的 refresh 结束下拉刷新动画。
 */
export const useCommentPaging = <T>(
  options: UseCommentPagingOptions<T>,
): UseCommentPagingReturn<T> => {
  const list = ref<T[]>([]) as Ref<T[]>
  const total = ref(0)
  const pageNum = ref(1)
  const pageSize = ref(options.pageSize ?? 10)
  const loading = ref(true)
  const loadingType = ref<LoadingType>('more')
  const requesting = ref(false)

  // 请求序号，用于丢弃过期响应，只有最新请求可以修改数据并释放锁
  let requestSeq = 0

  const load = async (type: LoadType = 'add') => {
    // 本次调用的收尾标记，保证 onFinish 不会被重复触发
    let finished = false
    // isLatest 为 false 表示本次请求已被更新的请求抢占，页面不能据此结束最新一次下拉刷新动画
    const finish = (isLatest: boolean) => {
      if (finished) return
      finished = true
      options.onFinish?.(type, isLatest)
    }

    // 前置守卫：商品 id 缺失、未登录等场景不发起请求
    if (options.canLoad && !options.canLoad()) {
      loading.value = false
      finish(true)
      return
    }
    // 上拉加载保持互斥，避免连续触底重复请求同一页；下拉刷新不受限制，可以抢占进行中的请求
    if (requesting.value && type !== 'refresh') {
      finish(true)
      return
    }
    // 已经没有更多数据时，不再请求下一页
    if (type === 'add' && loadingType.value === 'nomore') {
      finish(true)
      return
    }

    // 本次请求查询的页码：刷新固定第一页，加载更多沿用当前页码
    const currentPage = type === 'refresh' ? 1 : pageNum.value
    const currentSeq = ++requestSeq

    requesting.value = true
    loadingType.value = 'loading'

    try {
      const res = await options.fetcher({ pageNum: currentPage, pageSize: pageSize.value })
      // 期间已发起过新请求，本次响应已过期，直接丢弃
      if (currentSeq !== requestSeq) return

      const dataList = res?.list || []
      // 刷新成功后替换数据，加载更多成功后追加数据
      list.value = type === 'refresh' ? dataList : list.value.concat(dataList)
      total.value = res?.total || 0
      // 页码只在请求成功后推进，失败时不跳过页码
      pageNum.value = currentPage + 1
      loadingType.value = dataList.length < pageSize.value ? 'nomore' : 'more'
    } catch (error) {
      // 过期请求的失败结果同样丢弃，不影响最新一次加载的状态
      if (currentSeq !== requestSeq) return
      options.onError?.(error, type)
      // 失败后保持页码不变，允许再次触底重新请求同一页
      loadingType.value = 'more'
    } finally {
      // 只有最新请求负责释放锁，避免过期请求提前释放锁
      const isLatest = currentSeq === requestSeq
      if (isLatest) {
        requesting.value = false
        loading.value = false
      }
      // 过期请求仍会收尾一次保证语义一致，但 isLatest 为 false，不会结束最新一次下拉刷新动画
      finish(isLatest)
    }
  }

  const reset = () => {
    // 递增序号让进行中的旧响应失效
    requestSeq += 1
    list.value = []
    total.value = 0
    pageNum.value = 1
    loading.value = true
    loadingType.value = 'more'
    requesting.value = false
  }

  return {
    list,
    total,
    pageNum,
    pageSize,
    loading,
    loadingType,
    requesting,
    load,
    reset,
  }
}

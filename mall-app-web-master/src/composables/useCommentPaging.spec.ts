import { describe, expect, it, vi } from 'vitest'
import { useCommentPaging } from './useCommentPaging'
import type { PagingFetchParams, PagingFetchResult } from './useCommentPaging'

type Item = { id: number }

/** 构造一页数据，默认总数等于本页条数 */
const page = (ids: number[], total = ids.length): PagingFetchResult<Item> => ({
  list: ids.map((id) => ({ id })),
  total,
})

const idsOf = (list: Item[]) => list.map((item) => item.id)

/**
 * 可控的假接口：
 * 每次调用记录入参并返回一个可手动 settle 的 Promise，方便精确模拟并发时序
 */
const createFetcher = () => {
  const calls: PagingFetchParams[] = []
  const pendings: Array<{
    resolve: (value: PagingFetchResult<Item>) => void
    reject: (reason?: unknown) => void
  }> = []

  const fetcher = (params: PagingFetchParams) => {
    calls.push({ ...params })
    return new Promise<PagingFetchResult<Item>>((resolve, reject) => {
      pendings.push({ resolve, reject })
    })
  }

  return {
    fetcher: vi.fn(fetcher),
    calls,
    resolve: (index: number, result: PagingFetchResult<Item>) => pendings[index].resolve(result),
    reject: (index: number, reason?: unknown) => pendings[index].reject(reason),
  }
}

/** 冲刷微任务队列，让 await 之后的逻辑执行完 */
const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

const createPaging = (options: { pageSize?: number; canLoad?: () => boolean } = {}) => {
  const { fetcher, calls, resolve, reject } = createFetcher()
  const onFinish = vi.fn()
  const onError = vi.fn()
  // 模拟页面侧收尾：只有最新请求的 refresh 才会调用 uni.stopPullDownRefresh
  const stopPullDownRefresh = vi.fn()
  const paging = useCommentPaging<Item>({
    pageSize: options.pageSize ?? 2,
    fetcher,
    canLoad: options.canLoad,
    onFinish: (type, isLatest) => {
      onFinish(type, isLatest)
      if (type === 'refresh' && isLatest) {
        stopPullDownRefresh()
      }
    },
    onError,
  })
  return { paging, fetcher, calls, resolve, reject, onFinish, onError, stopPullDownRefresh }
}

describe('useCommentPaging', () => {
  it('首次加载成功后写入数据，并结束首屏 loading', async () => {
    const { paging, fetcher, calls, resolve } = createPaging()

    const task = paging.load('refresh')
    await flush()

    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(calls[0]).toEqual({ pageNum: 1, pageSize: 2 })
    expect(paging.requesting.value).toBe(true)
    expect(paging.loadingType.value).toBe('loading')

    resolve(0, page([1, 2], 5))
    await task

    expect(idsOf(paging.list.value)).toEqual([1, 2])
    expect(paging.total.value).toBe(5)
    expect(paging.loading.value).toBe(false)
    expect(paging.requesting.value).toBe(false)
    expect(paging.pageNum.value).toBe(2)
    expect(paging.loadingType.value).toBe('more')
  })

  it('首次加载失败后页码不变，可以再次请求同一页', async () => {
    const { paging, fetcher, calls, resolve, reject, onError } = createPaging()

    const task = paging.load('refresh')
    await flush()
    reject(0, new Error('网络异常'))
    await task

    expect(paging.list.value).toEqual([])
    expect(paging.pageNum.value).toBe(1)
    expect(paging.loadingType.value).toBe('more')
    expect(paging.loading.value).toBe(false)
    expect(paging.requesting.value).toBe(false)
    expect(onError).toHaveBeenCalledTimes(1)

    const retry = paging.load('add')
    await flush()
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(calls[1]).toEqual({ pageNum: 1, pageSize: 2 })
    resolve(1, page([1, 2], 2))
    await retry

    expect(idsOf(paging.list.value)).toEqual([1, 2])
    expect(paging.pageNum.value).toBe(2)
  })

  it('请求进行中再次上拉不会重复请求同一页', async () => {
    const { paging, fetcher, resolve, onFinish } = createPaging()

    const first = paging.load('add')
    await flush()
    const second = paging.load('add')
    await second

    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(paging.requesting.value).toBe(true)

    resolve(0, page([1, 2], 2))
    await first

    expect(paging.requesting.value).toBe(false)
    // 两次 load 调用各自收尾一次
    expect(onFinish).toHaveBeenCalledTimes(2)
  })

  it('没有更多数据后再次上拉不会发起请求', async () => {
    const { paging, fetcher, resolve } = createPaging()

    const first = paging.load('refresh')
    await flush()
    // 返回条数小于 pageSize，进入 nomore
    resolve(0, page([1], 1))
    await first

    expect(paging.loadingType.value).toBe('nomore')

    await paging.load('add')

    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('下拉刷新可以抢占进行中的上拉请求，旧响应被丢弃', async () => {
    const { paging, fetcher, calls, resolve } = createPaging()

    const first = paging.load('refresh')
    await flush()
    resolve(0, page([1, 2], 10))
    await first
    expect(paging.pageNum.value).toBe(2)

    const addTask = paging.load('add')
    await flush()
    expect(calls[1]).toEqual({ pageNum: 2, pageSize: 2 })

    const refreshTask = paging.load('refresh')
    await flush()
    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(calls[2]).toEqual({ pageNum: 1, pageSize: 2 })

    // 旧的上拉响应迟到，必须被丢弃，不能追加也不能释放锁
    resolve(1, page([3, 4], 10))
    await addTask
    expect(idsOf(paging.list.value)).toEqual([1, 2])
    expect(paging.requesting.value).toBe(true)

    resolve(2, page([9, 10], 10))
    await refreshTask

    expect(idsOf(paging.list.value)).toEqual([9, 10])
    expect(paging.total.value).toBe(10)
    expect(paging.pageNum.value).toBe(2)
    expect(paging.requesting.value).toBe(false)
  })

  it('上拉加载成功后追加数据，不满一页时标记没有更多', async () => {
    const { paging, fetcher, calls, resolve } = createPaging()

    const first = paging.load('refresh')
    await flush()
    resolve(0, page([1, 2], 3))
    await first

    const second = paging.load('add')
    await flush()
    expect(calls[1]).toEqual({ pageNum: 2, pageSize: 2 })
    resolve(1, page([3], 3))
    await second

    expect(idsOf(paging.list.value)).toEqual([1, 2, 3])
    expect(paging.loadingType.value).toBe('nomore')
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('下拉刷新成功后整体替换旧数据，页码回到第二页', async () => {
    const { paging, resolve } = createPaging()

    const first = paging.load('refresh')
    await flush()
    resolve(0, page([1, 2], 100))
    await first

    const second = paging.load('add')
    await flush()
    resolve(1, page([3, 4], 100))
    await second
    expect(idsOf(paging.list.value)).toEqual([1, 2, 3, 4])
    expect(paging.pageNum.value).toBe(3)

    const third = paging.load('refresh')
    await flush()
    resolve(2, page([7, 8], 100))
    await third

    expect(idsOf(paging.list.value)).toEqual([7, 8])
    expect(paging.pageNum.value).toBe(2)
    expect(paging.loadingType.value).toBe('more')
  })

  it('请求同步失败时锁仍会释放，页面可以继续加载', async () => {
    const failure = vi.fn(() => Promise.reject(new Error('boom')))
    const paging = useCommentPaging<Item>({ pageSize: 2, fetcher: failure })

    await paging.load('add')
    expect(paging.requesting.value).toBe(false)
    expect(paging.loadingType.value).toBe('more')

    await paging.load('add')
    expect(failure).toHaveBeenCalledTimes(2)
    expect(paging.requesting.value).toBe(false)
  })

  it('canLoad 返回 false 时不发起请求，也不会推进页码', async () => {
    const { paging, fetcher, onFinish, stopPullDownRefresh } = createPaging({
      canLoad: () => false,
    })

    await paging.load('refresh')
    await paging.load('add')

    expect(fetcher).not.toHaveBeenCalled()
    expect(paging.list.value).toEqual([])
    expect(paging.pageNum.value).toBe(1)
    expect(paging.loading.value).toBe(false)
    expect(onFinish).toHaveBeenNthCalledWith(1, 'refresh', true)
    expect(onFinish).toHaveBeenNthCalledWith(2, 'add', true)
    // 前置拦截时也必须结束下拉刷新动画，否则动画会一直转
    expect(stopPullDownRefresh).toHaveBeenCalledTimes(1)
  })

  it('reset 会清空数据并让进行中的旧响应失效', async () => {
    const { paging, resolve } = createPaging()

    const task = paging.load('refresh')
    await flush()
    resolve(0, page([1, 2], 2))
    await task

    paging.reset()
    expect(paging.list.value).toEqual([])
    expect(paging.total.value).toBe(0)
    expect(paging.pageNum.value).toBe(1)
    expect(paging.loading.value).toBe(true)
    expect(paging.loadingType.value).toBe('more')
    expect(paging.requesting.value).toBe(false)

    // 旧请求即便迟到也不能写回数据
    const stale = paging.load('add')
    await flush()
    resolve(1, page([1, 2], 2))
    paging.reset()
    await stale
    expect(paging.list.value).toEqual([])
  })

  it('连续多次触底只会请求一次同一页', async () => {
    const { paging, fetcher, resolve, onFinish } = createPaging()

    const task = paging.load('add')
    await flush()
    await paging.load('add')
    await paging.load('add')

    expect(fetcher).toHaveBeenCalledTimes(1)

    resolve(0, page([1, 2], 10))
    await task

    expect(idsOf(paging.list.value)).toEqual([1, 2])
    // 三次触底各自收尾一次，但网络请求只发出一次
    expect(onFinish).toHaveBeenCalledTimes(3)
    expect(onFinish).toHaveBeenCalledWith('add', true)
  })

  it('下拉刷新失败时同样收尾，页码不变且不会丢掉旧数据', async () => {
    const { paging, calls, resolve, reject, onFinish, onError, stopPullDownRefresh } =
      createPaging()

    const first = paging.load('refresh')
    await flush()
    resolve(0, page([1, 2], 10))
    await first
    expect(paging.pageNum.value).toBe(2)

    const second = paging.load('refresh')
    await flush()
    reject(1, new Error('网络异常'))
    await second

    // 刷新失败也必须收尾，保证下拉刷新动画一定会停止
    expect(onFinish).toHaveBeenLastCalledWith('refresh', true)
    expect(stopPullDownRefresh).toHaveBeenCalledTimes(2)
    expect(onError).toHaveBeenCalledTimes(1)
    expect(paging.pageNum.value).toBe(2)
    expect(idsOf(paging.list.value)).toEqual([1, 2])
    expect(paging.requesting.value).toBe(false)

    const third = paging.load('refresh')
    await flush()
    expect(calls[2]).toEqual({ pageNum: 1, pageSize: 2 })
    resolve(2, page([5, 6], 10))
    await third

    expect(idsOf(paging.list.value)).toEqual([5, 6])
  })

  it('旧请求的失败响应不会触发 onError，也不会改动最新加载状态', async () => {
    const { paging, resolve, reject, onError } = createPaging()

    const addTask = paging.load('add')
    await flush()
    const refreshTask = paging.load('refresh')
    await flush()

    // 已被抢占的旧请求失败，属于过期结果
    reject(0, new Error('旧请求失败'))
    await addTask

    expect(onError).not.toHaveBeenCalled()
    expect(paging.requesting.value).toBe(true)
    expect(paging.loadingType.value).toBe('loading')

    resolve(1, page([1, 2], 10))
    await refreshTask

    expect(idsOf(paging.list.value)).toEqual([1, 2])
    expect(paging.requesting.value).toBe(false)
    expect(paging.loadingType.value).toBe('more')
  })

  it('快速连续两次下拉刷新，旧响应被丢弃且只有最新请求结束刷新动画', async () => {
    const { paging, fetcher, calls, resolve, onFinish, stopPullDownRefresh } = createPaging()

    const first = paging.load('refresh')
    await flush()
    const second = paging.load('refresh')
    await flush()

    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(calls[0]).toEqual({ pageNum: 1, pageSize: 2 })
    expect(calls[1]).toEqual({ pageNum: 1, pageSize: 2 })

    // 旧刷新响应后到，不能覆盖最新结果，也不能提前释放锁
    resolve(0, page([1, 2], 10))
    await first
    expect(idsOf(paging.list.value)).toEqual([])
    expect(paging.requesting.value).toBe(true)

    // 旧请求已经不是最新请求，不能触发页面层刷新收尾，否则会提前关闭第二次刷新动画
    expect(stopPullDownRefresh).not.toHaveBeenCalled()
    expect(onFinish).toHaveBeenCalledTimes(1)
    expect(onFinish).toHaveBeenNthCalledWith(1, 'refresh', false)

    resolve(1, page([7, 8], 10))
    await second

    expect(idsOf(paging.list.value)).toEqual([7, 8])
    expect(paging.requesting.value).toBe(false)
    // 两次刷新各自收尾一次，但只有最新请求结束刷新动画，且恰好一次
    expect(onFinish).toHaveBeenCalledTimes(2)
    expect(onFinish).toHaveBeenNthCalledWith(2, 'refresh', true)
    expect(stopPullDownRefresh).toHaveBeenCalledTimes(1)
  })

  it('刷新抢占上拉请求后，只有最新的刷新请求会结束刷新动画', async () => {
    const { paging, resolve, onFinish, stopPullDownRefresh } = createPaging()

    const addTask = paging.load('add')
    await flush()
    const refreshTask = paging.load('refresh')
    await flush()

    // 被抢占的上拉请求先返回，它不是最新请求，不能结束本次下拉刷新动画
    resolve(0, page([1, 2], 10))
    await addTask

    expect(idsOf(paging.list.value)).toEqual([])
    expect(paging.requesting.value).toBe(true)
    expect(stopPullDownRefresh).not.toHaveBeenCalled()
    expect(onFinish).toHaveBeenNthCalledWith(1, 'add', false)

    resolve(1, page([9, 10], 10))
    await refreshTask

    expect(idsOf(paging.list.value)).toEqual([9, 10])
    expect(paging.requesting.value).toBe(false)
    expect(onFinish).toHaveBeenNthCalledWith(2, 'refresh', true)
    expect(stopPullDownRefresh).toHaveBeenCalledTimes(1)
  })

  it('旧刷新请求失败时不会结束最新一次刷新动画', async () => {
    const { paging, resolve, reject, stopPullDownRefresh } = createPaging()

    const first = paging.load('refresh')
    await flush()
    const second = paging.load('refresh')
    await flush()

    // 旧刷新请求失败，属于过期结果，不能结束第二次刷新动画
    reject(0, new Error('旧刷新失败'))
    await first
    expect(stopPullDownRefresh).not.toHaveBeenCalled()
    expect(paging.requesting.value).toBe(true)

    // 最新请求本身失败也必须收尾，保证下拉刷新动画一定会停止
    reject(1, new Error('最新刷新失败'))
    await second
    expect(stopPullDownRefresh).toHaveBeenCalledTimes(1)
    expect(paging.requesting.value).toBe(false)
  })

  it('连续上拉加载严格按页码递增请求，不会跳页或重复', async () => {
    const { paging, calls, resolve } = createPaging()

    const first = paging.load('refresh')
    await flush()
    resolve(0, page([1, 2], 100))
    await first

    const second = paging.load('add')
    await flush()
    resolve(1, page([3, 4], 100))
    await second

    const third = paging.load('add')
    await flush()
    resolve(2, page([5, 6], 100))
    await third

    expect(calls.map((call) => call.pageNum)).toEqual([1, 2, 3])
    expect(idsOf(paging.list.value)).toEqual([1, 2, 3, 4, 5, 6])
    expect(paging.pageNum.value).toBe(4)
  })
})

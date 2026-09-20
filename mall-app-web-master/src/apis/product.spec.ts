import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getCategoryTreeAPI, getProductDetailAPI, searchProductListAPI } from './product'
import { http } from '@/utils/http'
import type { ProductListParam } from '@/types/product'

// 只验证接口层的请求参数，不发起真实网络请求
vi.mock('@/utils/http', () => ({
  http: vi.fn(),
}))

const mockedHttp = vi.mocked(http)

beforeEach(() => {
  mockedHttp.mockReset()
  mockedHttp.mockResolvedValue({ code: 200, message: '操作成功', data: { list: [] } as any })
})

describe('商品搜索 API', () => {
  it('请求路径为 /product/search 且使用 GET', async () => {
    await searchProductListAPI({ pageNum: 1, pageSize: 6, sort: 0 })

    expect(mockedHttp).toHaveBeenCalledWith({
      method: 'GET',
      url: '/product/search',
      params: { pageNum: 1, pageSize: 6, sort: 0 },
    })
  })

  it('关键字、分类、页码与排序参数原样透传，页码保持1-based', async () => {
    const params: ProductListParam = {
      keyword: '手机',
      productCategoryId: 12,
      pageNum: 1,
      pageSize: 6,
      sort: 3,
    }
    await searchProductListAPI(params)

    expect(mockedHttp).toHaveBeenCalledWith({
      method: 'GET',
      url: '/product/search',
      params,
    })
  })

  it('翻页请求使用递增页码，不使用0-based偏移', async () => {
    await searchProductListAPI({ pageNum: 2, pageSize: 6, sort: 0 })
    await searchProductListAPI({ pageNum: 3, pageSize: 6, sort: 0 })

    const calls = mockedHttp.mock.calls.map((call) => call[0].params)
    expect(calls).toEqual([
      { pageNum: 2, pageSize: 6, sort: 0 },
      { pageNum: 3, pageSize: 6, sort: 0 },
    ])
  })

  it('分类树与商品详情接口路径正确', async () => {
    await getCategoryTreeAPI()
    await getProductDetailAPI(11)

    expect(mockedHttp).toHaveBeenNthCalledWith(1, {
      method: 'GET',
      url: '/product/categoryTreeList',
    })
    expect(mockedHttp).toHaveBeenNthCalledWith(2, {
      method: 'GET',
      url: '/product/detail/11',
    })
  })

  it('接口失败时向上抛出，不被静默吞掉', async () => {
    mockedHttp.mockRejectedValue(new Error('网络异常'))

    await expect(searchProductListAPI({ pageNum: 1, pageSize: 6, sort: 0 })).rejects.toThrow(
      '网络异常',
    )
  })
})

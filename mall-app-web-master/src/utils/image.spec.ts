import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resolveImageUrl, resolveImageUrlInHtml, resolveImageUrls } from './image'

/** 测试使用的局域网公开地址（占位值，非真实环境配置） */
const LAN_ENDPOINT = 'http://192.0.2.10:9000'

describe('resolveImageUrl', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_MINIO_PUBLIC_ENDPOINT', LAN_ENDPOINT)
  })

  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('替换 localhost:9000 内部地址', () => {
    expect(resolveImageUrl('http://localhost:9000/mall/a.png')).toBe(
      'http://192.0.2.10:9000/mall/a.png',
    )
  })

  it('替换 127.0.0.1:9000 内部地址', () => {
    expect(resolveImageUrl('http://127.0.0.1:9000/mall/a.png')).toBe(
      'http://192.0.2.10:9000/mall/a.png',
    )
  })

  it('替换 minio:9000 容器内部地址', () => {
    expect(resolveImageUrl('http://minio:9000/mall/a.png')).toBe(
      'http://192.0.2.10:9000/mall/a.png',
    )
  })

  it('保留 bucket、object path、query 和 hash', () => {
    expect(
      resolveImageUrl('http://localhost:9000/mall/images/20190519/a.png?x=1&y=2#top'),
    ).toBe('http://192.0.2.10:9000/mall/images/20190519/a.png?x=1&y=2#top')
  })

  it('已经是局域网公开地址时不重复替换', () => {
    const url = 'http://192.0.2.10:9000/mall/a.png'
    expect(resolveImageUrl(url)).toBe(url)
  })

  it('外部 HTTPS 地址保持不变', () => {
    const url = 'https://cdn.example.com/images/a.png'
    expect(resolveImageUrl(url)).toBe(url)
  })

  it('外部 HTTP 地址保持不变', () => {
    const url = 'http://img.example.com/images/a.png'
    expect(resolveImageUrl(url)).toBe(url)
  })

  it('本地 /static 资源保持不变', () => {
    expect(resolveImageUrl('/static/a.png')).toBe('/static/a.png')
    expect(resolveImageUrl('/static/errorImage.jpg')).toBe('/static/errorImage.jpg')
  })

  it('空值返回空字符串', () => {
    expect(resolveImageUrl()).toBe('')
    expect(resolveImageUrl(null)).toBe('')
    expect(resolveImageUrl('')).toBe('')
    expect(resolveImageUrl('   ')).toBe('')
  })

  it('data: 与 blob: 地址保持不变', () => {
    const dataUrl = 'data:image/png;base64,iVBORw0KGgo='
    expect(resolveImageUrl(dataUrl)).toBe(dataUrl)
    const blobUrl = 'blob:http://localhost/1234'
    expect(resolveImageUrl(blobUrl)).toBe(blobUrl)
  })

  it('公开地址尾部带 / 时不产生双斜杠', () => {
    vi.stubEnv('VITE_MINIO_PUBLIC_ENDPOINT', 'http://192.0.2.10:9000/')
    expect(resolveImageUrl('http://localhost:9000/mall/a.png')).toBe(
      'http://192.0.2.10:9000/mall/a.png',
    )
  })

  it('未配置公开地址时不破坏原 URL', () => {
    vi.stubEnv('VITE_MINIO_PUBLIC_ENDPOINT', '')
    expect(resolveImageUrl('http://localhost:9000/mall/a.png')).toBe(
      'http://localhost:9000/mall/a.png',
    )
  })

  it('公开地址配置非法时不破坏原 URL', () => {
    vi.stubEnv('VITE_MINIO_PUBLIC_ENDPOINT', '192.0.2.10:9000')
    expect(resolveImageUrl('http://localhost:9000/mall/a.png')).toBe(
      'http://localhost:9000/mall/a.png',
    )
  })

  it('协议相对的内部地址也能替换', () => {
    expect(resolveImageUrl('//localhost:9000/mall/a.png')).toBe(
      'http://192.0.2.10:9000/mall/a.png',
    )
  })
})

describe('resolveImageUrlInHtml', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_MINIO_PUBLIC_ENDPOINT', LAN_ENDPOINT)
  })

  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('替换富文本中的内部图片地址', () => {
    const html =
      '<p>说明</p><img src="http://localhost:9000/mall/a.png" /><img src="http://minio:9000/mall/b.png" />'
    expect(resolveImageUrlInHtml(html)).toBe(
      '<p>说明</p><img src="http://192.0.2.10:9000/mall/a.png" /><img src="http://192.0.2.10:9000/mall/b.png" />',
    )
  })

  it('不修改第三方图片地址', () => {
    const html = '<img src="https://cdn.example.com/a.png" /><img src="/static/b.png" />'
    expect(resolveImageUrlInHtml(html)).toBe(html)
  })

  it('空值返回空字符串', () => {
    expect(resolveImageUrlInHtml()).toBe('')
    expect(resolveImageUrlInHtml(null)).toBe('')
    expect(resolveImageUrlInHtml('')).toBe('')
  })

  it('未配置公开地址时原样返回', () => {
    vi.stubEnv('VITE_MINIO_PUBLIC_ENDPOINT', '')
    const html = '<img src="http://localhost:9000/mall/a.png" />'
    expect(resolveImageUrlInHtml(html)).toBe(html)
  })
})

describe('resolveImageUrls', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_MINIO_PUBLIC_ENDPOINT', LAN_ENDPOINT)
  })

  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('批量转换并过滤空地址', () => {
    expect(
      resolveImageUrls([
        'http://localhost:9000/mall/a.png',
        null,
        '/static/b.png',
        '',
        'https://cdn.example.com/c.png',
      ]),
    ).toEqual(['http://192.0.2.10:9000/mall/a.png', '/static/b.png', 'https://cdn.example.com/c.png'])
  })

  it('非数组输入返回空数组', () => {
    expect(resolveImageUrls()).toEqual([])
    expect(resolveImageUrls(null)).toEqual([])
  })
})

/**
 * 图片地址兼容处理
 *
 * 背景：
 * 1. 后端 MinIO 内部地址（localhost/127.0.0.1/minio）只有服务端和浏览器所在机器能访问；
 * 2. 手机或微信开发者工具会把 localhost 解析成设备自身，导致历史图片打不开；
 * 3. 历史数据库图片字段可能仍然保存着内部地址，不能通过改库解决。
 *
 * 处理方式：
 * 只重写“已知内部 MinIO 地址”的 host，替换为环境变量 VITE_MINIO_PUBLIC_ENDPOINT
 * 配置的局域网公开地址，保留 bucket、object path、query 和 hash。
 * 外部 CDN、第三方图片、本地 /static 资源、data:/blob: 地址一律保持不变。
 */

/** 已知的内部 MinIO 地址（host 小写比较） */
const INTERNAL_MINIO_HOSTS = new Set(['localhost:9000', '127.0.0.1:9000', 'minio:9000'])

/** 匹配带 host 的地址：http(s)://host/rest 或协议相对 //host/rest */
const URL_WITH_HOST = /^(https?:)?\/\/([^/?#]+)([/?#][\s\S]*)?$/i

/** 允许的公开地址形态：http://host 或 https://host（可带端口/路径前缀） */
const isValidPublicEndpoint = (endpoint: string): boolean =>
  /^https?:\/\/[^\s/?#]+/i.test(endpoint)

/** 去掉公开地址末尾多余的 /，避免出现 //mall 双斜杠 */
const normalizePublicEndpoint = (endpoint?: string | null): string =>
  (endpoint ?? '').trim().replace(/\/+$/, '')

/**
 * 将后端返回的图片地址转换为客户端可访问的地址
 *
 * @param url 后端返回的图片地址，允许为空
 * @returns 可直接绑定到 image/src 的地址，空值返回空字符串
 */
export const resolveImageUrl = (url?: string | null): string => {
  if (typeof url !== 'string') {
    return ''
  }

  const raw = url.trim()
  if (!raw) {
    return ''
  }

  // 特殊协议（data:、blob:）不做处理
  if (raw.startsWith('data:') || raw.startsWith('blob:')) {
    return raw
  }

  // 本地静态资源（/static/...）不做处理；协议相对地址（//host/...）继续走下面的 host 判断
  if (raw.startsWith('/') && !raw.startsWith('//')) {
    return raw
  }

  const matched = URL_WITH_HOST.exec(raw)
  if (!matched) {
    return raw
  }

  const host = matched[2].toLowerCase()
  // 外部地址（含已经是局域网公开地址的情况）保持不变，避免重复替换或误伤 CDN
  if (!INTERNAL_MINIO_HOSTS.has(host)) {
    return raw
  }

  const publicEndpoint = normalizePublicEndpoint(import.meta.env.VITE_MINIO_PUBLIC_ENDPOINT)
  // 未配置或配置非法时不要破坏原地址
  if (!publicEndpoint || !isValidPublicEndpoint(publicEndpoint)) {
    return raw
  }

  // matched[3] 为 path + query + hash，可能为空
  return `${publicEndpoint}${matched[3] ?? ''}`
}

/** 富文本中可能出现的内部 MinIO 地址前缀 */
const INTERNAL_MINIO_PREFIX = /(https?:)?\/\/(?:localhost|127\.0\.0\.1|minio):9000/gi

/**
 * 转换富文本 HTML 中的内部 MinIO 图片地址（商品图文详情等）
 *
 * 只替换已知内部 MinIO 地址前缀，不会命中第三方图片地址。
 *
 * @param html 富文本 HTML
 * @returns 转换后的 HTML，空值返回空字符串
 */
export const resolveImageUrlInHtml = (html?: string | null): string => {
  if (typeof html !== 'string' || html === '') {
    return ''
  }

  const publicEndpoint = normalizePublicEndpoint(import.meta.env.VITE_MINIO_PUBLIC_ENDPOINT)
  if (!publicEndpoint || !isValidPublicEndpoint(publicEndpoint)) {
    return html
  }

  // 命中的是 http(s)://host:9000 或 //host:9000 前缀，整体替换为公开地址
  return html.replace(INTERNAL_MINIO_PREFIX, () => publicEndpoint)
}

/**
 * 批量转换图片地址（评论图片数组、相册图数组等）
 *
 * @param urls 图片地址数组
 * @returns 转换后的新数组，空值过滤掉
 */
export const resolveImageUrls = (urls?: Array<string | null | undefined> | null): string[] => {
  if (!Array.isArray(urls)) {
    return []
  }
  return urls.map((item) => resolveImageUrl(item)).filter((item) => item !== '')
}

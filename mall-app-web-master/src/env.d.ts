/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 是否使用支付宝支付（H5端启用） */
  readonly VITE_USE_ALIPAY: string
  /** 接口基础地址 */
  readonly VITE_API_BASE_URL?: string
  /** MinIO 公开访问地址，用于重写后端返回的内部图片地址 */
  readonly VITE_MINIO_PUBLIC_ENDPOINT?: string
  /**
   * 商品导购智能体基础地址
   * H5 开发：http://localhost:8086；Nginx 环境：/agent-api
   */
  readonly VITE_AGENT_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import { DefineComponent } from 'vue'
  // eslint-disable-next-line @typescript-eslint/no-explicit-any, @typescript-eslint/ban-types
  const component: DefineComponent<{}, {}, any>
  export default component
}

// uni-app page lifecycle hooks (used in Vue3 script setup)
declare function onNavigationBarSearchInputClicked(callback: () => void): void
declare function onNavigationBarButtonTap(callback: (e: { index: number }) => void): void

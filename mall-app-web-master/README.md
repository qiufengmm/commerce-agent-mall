# mall-app-web

## 项目定位

`mall-app-web` 是电商系统的前台商城端，配合 `mall-portal` 后端接口使用。当前版本用于简历项目展示，重点呈现首页、分类、商品详情、购物车、下单、支付、订单和会员中心等移动端商城流程。

## 技术栈

| 技术 | 说明 |
| --- | --- |
| uni-app | 多端应用框架 |
| Vue 3 | 前端框架 |
| TypeScript | 类型约束 |
| Pinia | 状态管理 |
| Vite | 构建工具 |
| Sass | 样式预处理 |

## 目录结构

```text
src
├── apis        前台接口封装
├── components  通用组件
├── pages       页面
├── static      静态资源
├── stores      Pinia 状态管理
├── types       类型定义
└── utils       请求和日期等工具函数
```

## 本地运行

```bash
npm install
npm run dev:h5
```

默认通过 `.env.development` 中的 `VITE_API_BASE_URL` 连接本地前台接口。请先确认 `mall-portal` 后端服务已经启动。

### 环境变量

复制 `.env.example` 为 `.env.*` 后按本机环境填写，注意 `.env.` 开头的本机文件不要提交：

| 变量 | 说明 |
| --- | --- |
| `VITE_APP_TITLE` | 页面标题 |
| `VITE_API_BASE_URL` | 接口基础地址，H5 开发用 `/api`（Vite 代理），真机 / 微信开发者工具用 `http://<LAN_IP>:8085` 或 Nginx 的 `http://<LAN_IP>:8088/portal-api` |
| `VITE_MINIO_PUBLIC_ENDPOINT` | MinIO 局域网公开地址，用于把后端返回的内部图片地址（`localhost:9000` / `127.0.0.1:9000` / `minio:9000`）重写为手机可访问地址 |
| `VITE_USE_ALIPAY` | H5 端是否启用支付宝支付 |

### 局域网 / 真机联调

```bash
npm run dev:h5 -- --host 0.0.0.0
```

所有来自接口的远程图片统一经过 `src/utils/image.ts` 的 `resolveImageUrl` 处理：只重写已知内部 MinIO 地址，
本地 `/static` 资源、`data:` / `blob:` 地址和外部 CDN 地址保持不变；未配置 `VITE_MINIO_PUBLIC_ENDPOINT` 时原样返回。
详细的后端绑定地址与公开地址配置见 `document/docker/local-startup.md` 第 9.6 节。

## 核心流程

- 首页浏览商品推荐、品牌、秒杀和广告内容。
- 分类和搜索进入商品列表。
- 商品详情加入购物车或收藏。
- 购物车选择商品生成确认单。
- 提交订单、模拟支付成功、查看订单状态。
- 会员中心管理地址、收藏、关注和浏览记录。

## 改造方向

- 清理原项目宣传入口，保留完整商城业务链路。
- 优化注册、登录和本地演示流程。
- 后续接入智能导购入口，支持自然语言找商品、解释优惠和生成购物建议。

## 开源许可说明

本项目基于 Apache License 2.0 开源项目进行学习和二次开发。原始许可证与版权声明保留在 `LICENSE.txt` 文件中。
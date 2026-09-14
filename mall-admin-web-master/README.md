# mall-admin-web

## 项目定位

`mall-admin-web` 是电商系统的后台管理端，配合 `mall-admin` 后端接口使用。当前版本用于简历项目展示，重点呈现商品管理、订单管理、营销管理、权限管理和运营看板等后台能力。

## 技术栈

| 技术 | 说明 |
| --- | --- |
| Vue 3 | 前端框架 |
| TypeScript | 类型约束 |
| Vite | 构建工具 |
| Element Plus | 后台 UI 组件库 |
| Vue Router | 路由管理 |
| Pinia | 状态管理 |
| Axios | HTTP 请求 |
| ECharts / vue-echarts | 数据可视化 |
| TinyMCE | 富文本编辑器 |

## 目录结构

```text
src
├── apis        后台接口封装
├── assets      静态资源
├── components  通用组件
├── icons       SVG 图标
├── router      路由配置
├── stores      Pinia 状态管理
├── styles      全局样式
├── types       类型定义
├── utils       工具函数
└── views       页面
    ├── home    首页看板
    ├── oms     订单管理
    ├── pms     商品管理
    ├── sms     营销管理
    └── ums     权限管理
```

## 本地运行

```bash
npm install
npm run dev
```

默认通过 `.env.development` 中的 `VITE_BASE_SERVER_URL` 连接本地后台接口。请先确认 `mall-admin` 后端服务已经启动。

## 改造方向

- 清理原项目宣传入口，保留后台业务能力。
- 优化首页为项目概览和运营数据看板。
- 后续补充智能运营助手入口，例如订单异常分析、商品运营建议和营销活动建议。

## 开源许可说明

本项目基于 Apache License 2.0 开源项目进行学习和二次开发。原始许可证与版权声明保留在 `LICENSE` 文件中。
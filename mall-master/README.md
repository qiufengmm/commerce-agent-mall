# mall 电商项目

## 项目定位

这是一个面向简历展示和二次开发的前后端分离电商系统。当前阶段以本地部署的开源 mall 项目为基础，保留商品、订单、营销、权限、搜索等核心电商能力，并逐步加入个人改造内容。后续计划接入智能导购、运营助手和订单分析等智能体能力。

## 项目组成

```text
mall-master
├── mall-common   通用工具、统一返回、分页、异常处理
├── mall-mbg      MyBatis Generator 生成的实体和 Mapper
├── mall-security Spring Security、JWT、动态权限封装
├── mall-admin    后台管理系统接口
├── mall-portal   前台商城接口
├── mall-search   Elasticsearch 商品搜索接口
└── mall-demo     示例模块
```

配套前端项目：

- `mall-admin-web-master`：后台管理端，Vue 3 + Element Plus。
- `mall-app-web-master`：前台商城端，uni-app + Vue 3。

## 核心业务

- 商品管理：品牌、分类、属性、SKU、库存、上下架。
- 订单链路：购物车、确认单、下单、支付回调、发货、收货、退货。
- 营销能力：优惠券、秒杀、新品推荐、人气推荐、广告位。
- 用户体系：会员、收货地址、收藏、关注、浏览记录。
- 后台权限：管理员、角色、菜单、资源和动态接口权限。
- 搜索服务：基于 Elasticsearch 的商品检索。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端框架 | Spring Boot 3.5, JDK 17 |
| 安全认证 | Spring Security, JWT, Redis |
| 数据访问 | MyBatis, MyBatis Generator, PageHelper |
| 数据存储 | MySQL, Redis, MongoDB |
| 消息队列 | RabbitMQ |
| 搜索 | Elasticsearch |
| 文档 | SpringDoc OpenAPI |
| 对象存储 | MinIO / OSS |

## 本地运行

后端按需启动：

- 只运行后台管理接口：启动 `mall-admin`，通常需要 MySQL 和 Redis。
- 运行前台商城接口：启动 `mall-portal`，订单超时取消等能力还会依赖 RabbitMQ。
- 运行商品搜索：启动 `mall-search`，需要 Elasticsearch。

常用命令：

```bash
mvn clean package -DskipTests
```

也可以在 IDEA 中分别运行：

- `MallAdminApplication`
- `MallPortalApplication`
- `MallSearchApplication`

## 商品搜索（Elasticsearch）

商品搜索由独立服务 `mall-search`（默认 `8081`）提供，门户 `mall-portal` 内部转发，移动端仍只访问 `/product/search`：

```text
移动端 /product/search → mall-portal → mall-search /esProduct/search → Elasticsearch pms 索引
```

启动顺序与验证步骤（ES 启动、IK 插件、`/esProduct/importAll` 初始化索引、分页与同步的 curl 验证清单、内部令牌配置方式）见：

- [Elasticsearch 商品搜索运行手册](document/es-search/elasticsearch-runbook.md)
- [示例环境变量](document/es-search/es-search-env.example)

要点：

- `MALL_SEARCH_INTERNAL_TOKEN` 需要在 mall-admin 与 mall-search 配置成同一个值，仓库内不保存真实令牌；
- 三个服务的 `application.yml` 都不含数据库连接信息，本地启动请通过启动参数或环境变量注入；
- mall-search 的六条 ES 写路径（`importAll`、`create/{id}`、`delete/{id}`、
  `delete/batch`、`sync/{id}`、`sync/batch`）统一要求请求头 `X-Internal-Token`；
  未配置服务端令牌时返回 503，令牌缺失或错误时返回 401，不会静默放行。
- 只读搜索接口（`search`、`search/simple`、`search/relate`、`recommend/{id}`）保持匿名访问，
  不需要该内部令牌。

## 后续改造计划

1. 梳理订单状态流转，补充更清晰的业务注释和异常边界。
2. 增加后台运营看板和关键指标接口。
3. 接入智能导购和运营助手，让前台和后台具备智能问答能力。
4. 为核心订单链路补充测试和接口文档。

## 开源许可说明

本项目基于 Apache License 2.0 开源项目进行学习和二次开发。原始许可证与版权声明保留在 `LICENSE` 文件中；个人改造部分会在后续提交中持续补充说明。

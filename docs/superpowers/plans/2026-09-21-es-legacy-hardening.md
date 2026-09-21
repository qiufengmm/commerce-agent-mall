# Elasticsearch 遗留项治理实施计划

> 日期：2026-09-21
> 目标：补齐商品状态与 ES 索引的一致性边界，清理全量导入遗留文档，保护所有 ES 写入接口，并提供受控的 MySQL 降级搜索。

## 范围与约束

- 允许修改 `mall-admin`、`mall-search`、`mall-portal` 的相关生产代码、测试和 ES 运行文档；不修改数据库表结构。
- 不执行任何真实 MySQL DML/DDL、数据迁移或权限修改；不在本次验证中调用会改变现有 ES 数据的 import/delete/create/sync 接口。
- 不把 Token、密码或密钥写入代码、测试、文档、日志或回执。
- 保持移动端公开接口 `/product/search` 不变，保持门户到搜索服务的 1-based 分页契约不变。

## 现状证据与目标文件

### 商品状态事务

- `mall-master/mall-admin/src/main/java/com/macro/mall/service/PmsProductService.java`
- `mall-master/mall-admin/src/main/java/com/macro/mall/service/impl/PmsProductServiceImpl.java`
- `mall-master/mall-admin/src/main/java/com/macro/mall/event/ProductSyncEventListener.java`

`create`/`update` 已声明事务，但上下架、推荐、新品和删除状态方法没有事务声明；它们发布的 `ProductSyncEvent` 可能走 `fallbackExecution`，需要把会改变索引可见性或索引字段的批量更新纳入事务并在提交后同步。

### 全量导入与旧文档清理

- `mall-master/mall-search/src/main/java/com/macro/mall/search/service/impl/EsProductServiceImpl.java`
- `mall-master/mall-search/src/main/java/com/macro/mall/search/repository/EsProductRepository.java`
- `mall-master/mall-search/src/main/resources/dao/EsProductDao.xml`

当前 `importAll` 只保存 MySQL 中可上架商品，不删除 ES 中已经下架、删除或历史遗留的文档。目标是让全量导入后的 `pms` 文档集合与 `delete_status = 0 and publish_status = 1` 的商品集合一致，同时避免因 MySQL 读取异常而误删整个索引。

### 写接口保护

- `mall-master/mall-search/src/main/java/com/macro/mall/search/config/InternalTokenAuthFilter.java`
- `mall-master/mall-search/src/main/java/com/macro/mall/search/config/SearchSyncSecurityConfig.java`
- `mall-master/mall-search/src/main/java/com/macro/mall/search/controller/EsProductController.java`

当前内部 Token 只保护 `/esProduct/sync/**`，而 `importAll`、`create/**`、`delete/**` 也会改变 ES 数据。目标是用同一内部 Token 保护全部 ES 写入/维护接口，普通 `/esProduct/search/**`、`/search/relate` 等只读接口继续无需 Token。

### MySQL 降级搜索

- `mall-master/mall-portal/src/main/java/com/macro/mall/portal/service/impl/PmsPortalProductServiceImpl.java`
- `mall-master/mall-portal/src/main/java/com/macro/mall/portal/config/MallSearchClientProperties.java`
- `mall-master/mall-portal/src/main/resources/application.yml`

原 MySQL 搜索实现仍在 `searchByMySql`，但主链路只调用 ES，搜索服务不可用时直接报错。目标是增加显式配置开关；建议默认关闭，避免静默掩盖 ES 故障和让用户误以为 ES 排序仍生效。开启后只在搜索服务调用失败时降级，并返回完整 1-based 分页元数据。

## 实施步骤

### 1. 加固商品状态事务

- 为 `updatePublishStatus`、`updateRecommendStatus`、`updateNewStatus`、`updateDeleteStatus` 增加明确的 Spring 事务边界，优先保持现有接口级事务风格；必要时在实现类补充以避免代理/实现方法注解歧义。
- 保证数据库更新和事件发布在同一事务中；提交前不得调用 `EsProductSyncService`，回滚不得触发同步。
- 保持事件监听器的 `AFTER_COMMIT` 语义；同步 HTTP 失败只记录受控 warn，不回滚已经提交的商品事务。
- 增加反射/代理级别和事件时序测试，至少覆盖上架、下架、删除状态以及回滚不触发同步。

### 2. 让全量导入清理陈旧文档

- 读取可上架商品并按商品 ID 去重；保存当前应存在的文档。
- 枚举现有 `pms` 索引文档 ID，计算差集并删除陈旧 ID；实现必须能处理分页/滚动读取，不能只假设索引少于一页。
- MySQL 查询抛错时直接失败并保留现有 ES 文档，不能把异常误判为空数据后清空索引。
- 对空数据集要有明确测试：只有在查询成功返回空集合时才清理全部历史文档。
- 明确并发窗口和返回值语义：返回成功写入/保留的有效商品数量，运行文档说明全量导入是维护操作。
- 增加 Mockito 单测覆盖：保存当前商品、删除 stale 文档、重复导入幂等、数据源异常不误删。

### 3. 保护 ES 写入接口

- 扩展 Token 过滤器匹配：`POST /esProduct/importAll`、`/esProduct/create/**`、`/esProduct/delete/**`、`/esProduct/sync/**`。
- 校验 context path、精确路径和前缀边界，避免通过相似路径绕过；继续使用常量时间 Token 比较。
- 未配置服务端 Token 返回 503；缺失或错误 Token 返回 401；只读搜索接口仍可匿名访问。
- 更新过滤器、注册配置和控制器单测，覆盖四类写接口、缺失/错误/正确 Token、普通搜索放行。
- 更新 `elasticsearch-runbook.md` 和 Docker 启动文档中的调用示例，明确所有写接口都需要内部 Token；文档不得出现真实 Token。

### 4. 实现受控 MySQL 降级

- 在 `MallSearchClientProperties` 增加布尔配置，例如 `mysql-fallback-enabled`，默认 `false`，支持环境变量示例但不写真实值。
- `PmsPortalProductServiceImpl.search` 只捕获搜索客户端失败，开关开启时调用既有 `searchByMySql`，用 `CommonPage.restPage` 返回正确的 `pageNum/pageSize/totalPage/total`；开关关闭时保持现有异常语义。
- 保持过滤条件、上架/删除条件和排序语义；不要把库存、订单、支付或写操作引入降级路径。
- 增加服务层测试：ES 正常不访问 MySQL、ES 失败且开关关闭抛出异常、开关开启返回 H2/Mockito 分页结果、页码非法值仍归一化为 1-based。
- 文档说明降级结果的能力差异和启用方式，避免把降级结果描述成 ES 相关度排序。

### 5. 验证与报告

- 运行相关 `mall-admin`、`mall-search`、`mall-portal` 单元测试和编译；读取每个模块的 Surefire 统计。
- 运行 `git diff --check` 和敏感文件扫描。
- 只读检查现有 Docker 服务健康、ES `_cluster/health`、`pms` `_count` 和搜索接口；不得调用 import/delete/create/sync 写接口。
- 生成 `F:\code\mall\.worktrees\es-legacy-hardening\.codebuddy\reports\es-legacy-hardening-report.md`，包含改动、API 契约、事务说明、配置开关、测试结果、未做的在线写验证和 Git 状态。
- 工作树不 commit、push 或 merge。

## 完成标准

- 商品上下架/删除等状态提交后才触发索引同步，回滚不触发。
- 全量导入能清理陈旧文档且对数据源异常安全。
- 所有 ES 写入维护接口均受内部 Token 保护，搜索读接口不受影响。
- MySQL 降级可配置、默认不隐式启用，开启时分页和过滤契约正确。

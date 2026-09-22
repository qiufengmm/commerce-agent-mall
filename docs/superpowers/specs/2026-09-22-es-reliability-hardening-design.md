# Elasticsearch 可靠性治理设计

> 日期：2026-09-22  
> 状态：已获用户确认，进入实现阶段

## 1. 目标与边界

本次处理四项收尾工作：

1. 清理旧 ES 功能分支产生的 `stash@{0}` 与 `feature/es-legacy-hardening`；
2. 启动并验证可观测组件；
3. 在不执行 MySQL 写操作的前提下，真实验证 ES 写路径的鉴权和 `importAll`；
4. 将 `importAll` 从单实例互斥升级为跨实例互斥，并对 Elasticsearch bulk 部分失败增加一次失败项补偿重试。

不修改数据库表结构，不执行 MySQL DML/DDL，不改变移动端 `/product/search` 契约，不把真实密码或令牌写入代码、文档、日志或报告。

## 2. 现状证据

- `EsProductServiceImpl` 当前使用 `ReentrantLock`，只能阻止同一 JVM 内的并发 `importAll`。
- `mall-search` 当前没有直接声明 Redis starter，Compose 中也没有让 `mall-search` 等待 Redis 健康。
- 当前 `saveAll` 依赖 Spring Data Elasticsearch bulk；bulk 可能部分成功，现有逻辑失败后不删除陈旧文档，但没有针对已知失败文档的重试。
- Logstash 上一次状态为 `ExitCode=137`、`OOMKilled=false`，日志显示收到 `SIGTERM` 后退出，优先按“已停止组件”重新启动验证，不先修改内存参数。

## 3. 推荐架构

### 3.1 Redis 分布式锁

新增 `ImportAllLockService` 抽象和 Redis 实现：

```text
本地 ReentrantLock.tryLock
  → Redis SET key owner NX EX lease
  → 成功后执行 importAll
  → 定时 Lua 校验 owner 并续租
  → 完成后 Lua 校验 owner 并删除
```

锁配置使用环境变量占位符：

- `MALL_SEARCH_IMPORT_LOCK_ENABLED`：Compose 默认为 `true`；
- `MALL_SEARCH_IMPORT_LOCK_KEY`：默认 `mall:search:import-all`；
- `MALL_SEARCH_IMPORT_LOCK_LEASE_SECONDS`：默认 1800；
- `MALL_SEARCH_IMPORT_LOCK_RENEW_INTERVAL_SECONDS`：默认 30。

Redis 不可用、抢锁失败、续租失败均按失败关闭处理，不退化成无锁跨实例执行。续租失败后，流程可以完成当前商品保存，但在删除陈旧文档前必须再次确认锁仍由当前 owner 持有；确认失败则终止清理。

### 3.2 bulk 部分失败补偿

保留当前安全顺序：

```text
读取 MySQL 有效商品
  → bulk 保存
  → bulk 抛出 BulkFailureException 时提取失败商品 id
  → 失败商品最多重试一次
  → 重试全部成功后才扫描并删除陈旧文档
```

已成功写入的商品不回滚；失败项重试仍失败时继续向上抛出，并保证不调用陈旧文档删除。异常类型不是可识别的 bulk 失败、或失败 id 无法映射到本批商品时，也不猜测结果，不删除陈旧文档。

### 3.3 Compose 与配置

`mall-search` 增加 Redis 连接环境变量并依赖 Redis 健康状态；默认本地 Compose 使用 `redis:6379`。应用配置只包含环境变量引用和非敏感默认值。单元测试使用 Mockito 替身，不连接真实 Redis。

### 3.4 可观测组件

不把 Logstash 退出码 137 直接判定为 OOM。启动 observability profile 后验证：

- Logstash health API `/_node/stats` 返回 200；
- Kibana `/api/status` 返回 200；
- Logstash 日志显示主 pipeline 和监控 pipeline 均运行；
- ES `_cluster/health` 保持可用。

## 4. 错误与接口行为

- `importAll` 抢锁失败继续返回已有的“执行中，请稍后重试”业务失败结果，避免改变调用方契约。
- Redis 不可用返回受控失败，不执行 ES 清理。
- 正常只读搜索接口和门户 `/product/search` 不变。
- 六条 ES 写路径仍由 `X-Internal-Token` 保护；真实验证只使用不输出的本地环境变量。

## 5. 验收标准

- `stash@{0}` 和 `feature/es-legacy-hardening` 被明确清理，`stash@{1}` 保留。
- Logstash、Kibana 启动并通过健康检查；失败时记录真实原因，不伪造成功。
- 未带令牌访问六条写路径均为 401；匿名只读搜索保持 200。
- 带真实令牌执行 `POST /esProduct/importAll` 成功，`pms` 文档数量、映射和搜索链路可读验证。
- 单元测试覆盖：Redis 抢锁/释放/续租/不可用、锁丢失阻止 stale delete、bulk 失败项重试成功、重试失败不删除。
- `mall-search` 与全项目测试通过，`git diff --check` 通过，无真实敏感信息。

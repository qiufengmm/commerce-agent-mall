# Elasticsearch 可靠性治理实施计划

> 本计划在 feature/es-reliability-hardening worktree 内执行；工作树阶段禁止 commit、push、merge。

目标：为 importAll 增加 Redis 跨实例锁和 bulk 失败项补偿，并完成可观测组件、真实 ES 写路径验证和旧 stash/分支清理。

架构：mall-search 保留本地 ReentrantLock，再通过 Redis SET NX EX 获取跨实例锁；后台定时续租，Lua 脚本按 owner 安全续租/释放，锁丢失时阻止 stale delete。商品 bulk 保存出现可识别的部分失败时只重试失败商品一次，重试不完整则不清理陈旧文档。Compose 为 mall-search 注入 Redis 并等待健康状态；运行验证只对 ES 执行明确授权的 importAll 写操作，不执行 MySQL 写操作。

技术栈：Java 17、Spring Boot 3.5、Spring Data Redis、Spring Data Elasticsearch、JUnit 5、Mockito、Docker Compose、PowerShell。

设计文档：docs/superpowers/specs/2026-09-22-es-reliability-hardening-design.md

## 全局约束

- 不执行任何 MySQL INSERT、UPDATE、DELETE、DDL、权限修改或数据迁移。
- 不把密码、Token、密钥或 .env 内容写入代码、文档、日志、测试输出或回执。
- mall-search 六条写路径仍要求 X-Internal-Token；只读搜索接口保持匿名。
- importAll 只有在有效商品全部保存成功且当前实例仍持有分布式锁时，才允许删除陈旧 ES 文档。
- 工作树内不执行 git commit、git push、git merge；由主 Agent 在审查后处理。

---

### Task 1：增加可测试的 importAll 锁抽象

文件：

- 新增 mall-master/mall-search/src/main/java/com/macro/mall/search/lock/ImportAllLockService.java
- 新增 mall-master/mall-search/src/main/java/com/macro/mall/search/lock/ImportAllLock.java
- 新增 mall-master/mall-search/src/main/java/com/macro/mall/search/lock/ImportAllLockException.java
- 新增 mall-master/mall-search/src/main/java/com/macro/mall/search/config/ImportAllLockProperties.java
- 新增 mall-master/mall-search/src/test/java/com/macro/mall/search/lock/ImportAllLockServiceTest.java

- [x] 写失败测试：首次抢锁成功；已有 owner 时拒绝；Redis 异常转为受控锁异常；owner 匹配时释放/续租成功；续租失败把句柄标记为 lost；关闭句柄取消续租任务并只释放自己的锁。
- [x] 运行 focused test，确认初始实现因锁类型不存在而失败。
- [x] 实现 StringRedisTemplate 抢锁、Lua owner 校验续租/释放、daemon ScheduledExecutorService 定时续租；enabled=false 时返回不访问 Redis 的本地句柄。
- [x] 重跑 focused test，确认全部通过且不连接真实 Redis。

### Task 2：将分布式锁接入 importAll

文件：

- 修改 mall-master/mall-search/src/main/java/com/macro/mall/search/service/impl/EsProductServiceImpl.java
- 必要时修改 mall-master/mall-search/src/main/java/com/macro/mall/search/controller/EsProductController.java
- 修改 mall-master/mall-search/src/main/resources/application.yml
- 修改 mall-master/mall-search/src/test/java/com/macro/mall/search/service/impl/EsProductServiceImplImportAllTest.java
- 新增 mall-master/mall-search/src/test/java/com/macro/mall/search/service/impl/EsProductServiceImplDistributedLockTest.java

- [x] 写失败测试：抢锁失败时不查询 MySQL、不保存、不扫描 ES；锁已丢失时不执行 deleteAllById；正常持锁时继续现有流程；finally 一定关闭句柄。
- [x] 运行 focused test，确认现有实现因缺少锁字段而失败。
- [x] 在读取 MySQL 前获取分布式句柄；在保存、扫描 ES 前、删除 stale 前再次检查 isHeld；锁失败抛受控异常，保留 controller 的业务失败契约；保留本地 ReentrantLock。
- [x] 运行分布式锁与 importAll focused tests。

### Task 3：增加 bulk 部分失败补偿

文件：

- 修改 mall-master/mall-search/src/main/java/com/macro/mall/search/service/impl/EsProductServiceImpl.java
- 修改 mall-master/mall-search/src/test/java/com/macro/mall/search/service/impl/EsProductServiceImplImportAllTest.java

- [x] 写失败测试：BulkFailureException 报告商品 2 失败时第二次只重试商品 2；重试成功后允许清理 stale；重试再次失败时不调用 deleteAllById；失败 id 无法映射时不猜测、不清理。
- [x] 运行现有 importAll focused test，确认新补偿断言因当前代码不重试而失败。
- [x] 捕获 BulkFailureException，按 getFailedDocuments 的 id 映射本批有效商品，只重试可识别失败商品一次；无法识别或重试失败时抛错并跳过 stale 清理。
- [x] 重跑 importAll、分布式锁和 search_after 回归测试。

### Task 4：Compose、配置和文档接线

文件：

- 修改 mall-master/mall-search/pom.xml
- 修改 mall-master/mall-search/src/main/resources/application.yml
- 修改 .env.example
- 修改 docker-compose.yml
- 修改 mall-master/document/es-search/elasticsearch-runbook.md
- 修改 document/docker/local-startup.md

- [x] mall-search 直接声明 spring-boot-starter-data-redis；配置只使用 MALL_SEARCH_IMPORT_LOCK_* 和 SPRING_DATA_REDIS_* 环境变量引用。
- [x] Compose 给 mall-search 注入 Redis 主机、端口和锁开关，并增加 redis: service_healthy 依赖。
- [x] 文档说明 Redis 是 importAll 跨实例锁前置依赖、锁丢失时不清理 stale、bulk 失败项最多重试一次和 Logstash/Kibana 验证命令。
- [x] 运行两种 Compose config 校验和 git diff --check。

### Task 5：运行时验收与收尾清理

- [x] 运行 mvn test -DskipTests=false，确认全 reactor 通过且未使用 maven.test.failure.ignore。
- [x] 启动 observability profile，验证 Logstash 健康状态、Kibana /api/status、两条 pipeline 和 ES 健康状态。
- [x] 从本地环境读取但不打印令牌；六条写路径缺少令牌均为 401；带令牌只执行 POST /esProduct/importAll，随后只读验证 _count、_mapping、mall-search 搜索和 mall-portal 搜索。
- [ ] 在主工作树确认目标仍为 stash@{0} 和 feature/es-legacy-hardening 后，删除这两个对象，保留 stash@{1}；当前因安全审批要求更明确的销毁授权而暂缓。
- [x] 生成 .codebuddy/reports/es-reliability-hardening-report.md，记录修改、测试、Docker/ES 结果、stash 清理、未验证项和 Git 状态。

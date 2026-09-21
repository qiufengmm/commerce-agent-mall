# 测试基线修复实施计划

> 日期：2026-09-21
> 目标：消除默认 Maven 测试对本机 MySQL、Elasticsearch 和完整开发环境的隐式依赖，并保留必要的外部联调入口。

## 范围与约束

- 只修改测试代码、测试资源和测试范围内的构建配置；不得为了让测试通过而修改生产 `application-dev.yml`、生产数据库配置或业务逻辑。
- H2 只能使用内存库；不得连接当前 Docker/MySQL，也不得执行真实 MySQL 的 DML、DDL、权限修改或数据迁移。
- 默认 `mvn test` 必须不依赖外部服务；需要外部服务的测试必须明确标识并通过显式命令运行。
- 不记录或输出任何真实密码、Token、密钥或本机环境变量。

## 现状证据

1. `mall-master/mall-search/src/test/java/com/macro/mall/search/MallSearchApplicationTests.java` 使用 `@SpringBootTest`，直接加载开发 profile，同时访问 MySQL 和 Elasticsearch；当前 Surefire 失败根因是开发数据源连接配置触发 MySQL 公钥获取错误，历史摘要中的“无法确定 JDBC URL”已不是当前最准确的根因。
2. `mall-master/mall-portal/src/test/java/com/macro/mall/portal/PortalProductDaoTests.java` 同样使用完整 `@SpringBootTest`，测试 SQL 依赖 `PMS_PRODUCT` 及关联表；历史报告曾记录 H2 缺表，但当前 target 报告已出现通过记录，必须先重新复现并以当前结果为准。
3. `mall-portal` 已有手工装配 H2/MyBatis 的测试模式，可复用 `PortalOrderDaoH2Test` 的方式加载生产 DAO XML；`mall-search` 当前没有 H2 测试依赖，需要补充 test scope 依赖或采用同等隔离方式。

## 实施步骤

### 1. 先复现并记录当前基线

- 分别执行 `MallSearchApplicationTests` 和 `PortalProductDaoTests`，记录每个测试类的测试数、失败/错误数和首个根因。
- 不把历史报告中的失败直接当作现状；如果某个类当前已经通过，仍检查它是否偷偷连接了本机服务，不能仅凭绿色结果判定隔离完成。

### 2. 改造 mall-search 测试

- 将旧的完整外部环境测试拆为默认可运行的隔离测试：
  - DAO SQL 使用 H2/MySQL 兼容模式和最小必要表结构、种子数据；
  - 映射/分页/服务行为使用纯单元测试或 Mockito；
  - ElasticsearchTemplate 的真实映射操作不得在默认测试中访问在线 ES。
- 如仍保留外部集成测试，重命名或放入明确的 integration 范围，并提供显式运行方式；默认 `mvn test` 不得加载它。
- 不通过把真实开发密码复制到 `src/test/resources` 的方式修复。

### 3. 改造 mall-portal 商品 DAO 测试

- 用 H2 内存库直接加载 `dao/PortalProductDao.xml`，注册所需的 MBG mapper/resultMap。
- 建立 `pms_product`、`pms_sku_stock`、`pms_product_ladder`、`pms_product_full_reduction` 等查询所需的最小字段，并插入测试数据。
- 覆盖促销商品列表的正常返回、空 ID 列表或无匹配数据边界，以及一对多关联结果不重复的关键行为。
- 测试名称和报告必须明确“只使用 H2，不连接真实 MySQL”。

### 4. 验证与报告

- 单模块主要依据：
  - `mvn -pl mall-search -am -DskipTests=false -Dtest=MallSearchApplicationTests,EsProductDaoH2Test test`
  - `mvn -pl mall-portal -am -DskipTests=false -Dtest=PortalProductDaoTests,PortalProductDaoH2Test test`
- 再运行两个模块的完整默认测试，分别读取每个模块的 Surefire 报告；不能使用 `-Dmaven.test.failure.ignore=true` 掩盖失败。
- 运行 `git diff --check`，检查 diff 不含密码、Token、`target/`、`node_modules/` 或运行时配置。
- 生成 `F:\code\mall\.worktrees\test-baseline-repair\.codebuddy\reports\test-baseline-repair-report.md`，写明复现结果、改动文件、隔离方式、每个模块的实际统计、遗留的真实基线和 Git 状态。

## 完成标准

- 默认测试不再因本机 MySQL/ES 不可用而失败。
- Portal 商品 DAO 测试在干净环境中可重复运行，且不需要 Docker 或开发数据库。
- 若保留外部集成测试，默认测试与集成测试边界、命令和失败责任清晰可见。
- 工作树不 commit、push 或 merge。

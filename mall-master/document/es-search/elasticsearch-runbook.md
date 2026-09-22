# Elasticsearch 商品搜索运行手册

本文档说明商品搜索链路的启动、索引初始化和验证方式。链路如下：

```text
移动端 /product/search
  → mall-portal /product/search（MallSearchClient）
    → mall-search /esProduct/search
      → Elasticsearch pms 索引
```

后台商品变更后通过事件异步同步：

```text
mall-admin 商品写操作（新增/编辑/上下架/推荐/新品/删除，均在 Spring 事务内）
  → ProductSyncEvent（事务提交后发布；没有事务时不触发同步）
    → EsProductSyncServiceImpl（带 X-Internal-Token）
      → mall-search /esProduct/sync/{id} 或 /esProduct/sync/batch
```

---

## 1. 前置条件

| 依赖 | 默认地址 | 说明 |
| --- | --- | --- |
| Elasticsearch | `localhost:9200` | 建议 8.x，需要安装 `analysis-ik` 分词插件 |
| MySQL | `localhost:3306/mall` | 搜索服务从 MySQL 读取已上架商品 |
| Redis | `localhost:6379` | mall-search 的 importAll 分布式锁，以及 mall-admin / mall-portal |
| RabbitMQ | `localhost:5672` | 仅 mall-portal 订单场景需要 |

服务端口：

| 服务 | 端口 |
| --- | --- |
| mall-admin | 8080 |
| mall-search | 8081 |
| mall-portal | 8085 |

## 2. 启动 Elasticsearch

示例（Docker）：

```bash
docker run -d --name mall-es \
  -p 9200:9200 -p 9300:9300 \
  -e "discovery.type=single-node" \
  -e "ES_JAVA_OPTS=-Xms512m -Xmx512m" \
  docker.elastic.co/elasticsearch/elasticsearch:8.15.0
```

安装 IK 分词插件（商品名称、副标题、关键字使用 `ik_max_word`）：

```bash
docker exec -it mall-es ./bin/elasticsearch-plugin install https://get.infini.cloud/elasticsearch/analysis-ik/8.15.0
docker restart mall-es
```

检查：

```bash
curl http://localhost:9200
curl http://localhost:9200/_cat/plugins?v
```

## 3. 配置说明（不要把密钥写进仓库）

三个服务的 `application.yml` 只保留占位符，真实值通过环境变量注入：

| 环境变量 | 作用 | 默认值 |
| --- | --- | --- |
| `MALL_ES_URIS` | mall-search 连接 Elasticsearch 的地址 | `localhost:9200` |
| `MALL_SEARCH_BASE_URL` | mall-admin / mall-portal 访问 mall-search 的地址 | `http://localhost:8081` |
| `MALL_SEARCH_INTERNAL_TOKEN` | 内部写接口令牌，mall-admin 与 mall-search 必须一致 | 空 |
| `MALL_SEARCH_IMPORT_LOCK_ENABLED` | importAll 跨实例 Redis 互斥锁 | `true` |
| `MALL_SEARCH_IMPORT_LOCK_KEY` | Redis 锁 key | `mall:search:import-all` |
| `MALL_SEARCH_IMPORT_LOCK_LEASE_SECONDS` | 锁租约时长 | `1800` |
| `MALL_SEARCH_IMPORT_LOCK_RENEW_INTERVAL_SECONDS` | 锁续租间隔 | `30` |
| `MALL_SEARCH_MYSQL_FALLBACK_ENABLED` | 门户 MySQL 降级搜索开关 | `false` |

### 3.1 mall-search 写接口与内部令牌

所有 mall-search 写接口都要求请求头 `X-Internal-Token`，以下六个写路径必须携带令牌：

| 写接口 | 说明 |
| --- | --- |
| `POST /esProduct/importAll` | 全量导入并清理陈旧文档 |
| `POST /esProduct/create/{id}` | 导入单个商品 |
| `GET /esProduct/delete/{id}` | 删除单个商品文档 |
| `POST /esProduct/delete/batch` | 批量删除商品文档 |
| `POST /esProduct/sync/{id}` | 同步单个商品 |
| `POST /esProduct/sync/batch` | 批量同步商品 |

只读接口保持匿名可访问，不需要令牌：`GET /esProduct/search`、`GET /esProduct/search/simple`、`GET /esProduct/search/relate`、`GET /esProduct/recommend/{id}`。

令牌未配置或校验失败时的行为：

- 服务端未配置令牌：写接口返回 503，并记录 error 日志，不会静默放行；日志中不会输出令牌值；
- 请求缺失令牌或令牌不匹配：写接口返回 401，错误响应不回显令牌；
- mall-admin：令牌缺失时跳过同步并记录 warn 日志，不影响商品主流程。

令牌保护范围：过滤器注册在 `/esProduct/*` 上，按路径段匹配上表中的写路径，命中写动作段（`importAll`、`create`、`delete`、`sync`）即要求鉴权。带尾斜杠（`/esProduct/importAll/`）、带 context path、带分号矩阵参数（`/esProduct/importAll;x=1`）、id 段为 `0`、负数或非数字（`/esProduct/create/0`、`/esProduct/delete/-1`、`/esProduct/sync/abc`）、以及编码路径穿越（`/esProduct/search/../delete/26`、`/esProduct/create%2F26`）都必须通过令牌校验；`/esProduct/importAllExtra`、`/esProduct/sync-other` 这类相似路径不会被放行到写处理器，也不会误伤只读搜索接口。

### 3.2 MySQL 降级搜索开关

`MALL_SEARCH_MYSQL_FALLBACK_ENABLED` 默认必须为 `false`：

| 开关 | mall-search 正常 | mall-search 调用失败 |
| --- | --- | --- |
| `false`（默认） | 只走 ES | 原样暴露搜索服务异常，**不静默查询 MySQL** |
| `true` | 只走 ES，不查询 MySQL | 复用既有 `searchByMySql` 做商品读查询 |

降级搜索的边界：

- 只有 mall-search 客户端调用失败才允许降级，不捕获其它异常；MySQL 查询自身的异常不会被降级逻辑吞掉；
- 降级只做商品读查询，不改变筛选条件、不改变 1-based 分页契约；
- 降级结果保持完整的 `total`、`totalPage`、`pageNum`、`pageSize`；
- 降级**不具备 ES 相关度排序能力**，`sort=0`、空值或非法值时使用稳定的 `id desc` 兜底排序，只保证分页结果稳定。

数据源配置不在仓库内。`mall-search`、`mall-admin`、`mall-portal` 的 `application.yml` 都不包含数据库连接信息，启动时需要自行注入，任选一种：

```bash
# 方式一：启动参数
--spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false
--spring.datasource.username=你的账号
--spring.datasource.password=你的密码

# 方式二：环境变量（Spring Boot 松弛绑定）
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false
SPRING_DATASOURCE_USERNAME=你的账号
SPRING_DATASOURCE_PASSWORD=你的密码
```

建议同时设置 `spring.datasource.druid.max-wait`（例如 10000 毫秒）。Druid 的 `maxWait` 默认为 -1，数据库连不上时获取连接会无限等待，表现为接口无响应而不是快速报错。

可复制 `document/es-search/es-search-env.example` 后填值，不要提交填写后的文件。

## 4. 启动 mall-search

```bash
cd mall-master
mvn -pl mall-search -am compile -DskipTests

export MALL_SEARCH_INTERNAL_TOKEN=你的令牌
export MALL_ES_URIS=http://localhost:9200

mvn -pl mall-search spring-boot:run
```

启动日志出现 Elasticsearch 客户端初始化成功、且没有 `Failed to determine suitable jdbc url` 即表示配置正确。

## 5. 初始化 pms 索引

`importAll` 属于写接口，必须携带内部令牌：

```bash
curl -X POST http://localhost:8081/esProduct/importAll \
  -H "X-Internal-Token: ${MALL_SEARCH_INTERNAL_TOKEN}"
```

### 5.1 返回值含义

- 返回 `data` 是**本次保留/导入的有效商品数量**（MySQL 有效商品按商品 id 去重后的数量）；
- 被剔除的陈旧文档数量**不通过该返回值透出**，只会写入 mall-search 日志，日志中不输出商品明细；
- 重复执行返回相同数量，`pms` 索引文档总数不变化；
- 同一进程内已有导入正在执行时返回失败提示「商品索引全量导入正在执行中，请稍后重试」，不会并发执行两个导入。

### 5.2 有效商品集合与陈旧文档清理规则

MySQL 当前有效集合明确为 `delete_status = 0` 且 `publish_status = 1` 的商品，按商品 id 去重后写入 ES。

导入成功后按 `search_after` 游标分批遍历 `pms` 索引现有文档（每批 500 条、始终是第 0 页，只读取 `_source` 中的 `id` 字段，按 `id` 升序推进游标），删除以下四类陈旧文档：

| 文档类型 | 是否删除 |
| --- | --- |
| 已下架商品（`publish_status != 1`） | 删除 |
| 已删除商品（`delete_status != 0`） | 删除 |
| MySQL 中已不存在的商品 | 删除 |
| ES 中存在但不属于本次有效集合的文档 | 删除 |

### 5.3 安全语义

执行顺序固定为「读 MySQL → 保存当前商品 → 清理陈旧文档」，安全语义如下：

- MySQL 查询异常或返回 null：**直接失败**，不保存、不删除任何 ES 文档，**不会清空 ES**；
- 保存当前商品失败：**不继续清理陈旧文档**；
- 只有 MySQL 明确成功返回空集合时，才允许清理全部陈旧文档；
- 固定顺序保证任何一步失败都不会把索引清成空。

保存失败的准确语义（不要扩大描述）：

- 保存调用底层是 Elasticsearch bulk，**可能部分文档已经写入成功**；对明确出错的商品最多重试一次，
  不做已成功文档的回滚补偿，因此保存抛异常时 **不能保证 ES index 完全没有变化**；
- 无法解析失败商品、或失败商品重试仍失败时，**不继续删除任何陈旧文档**；
- 已写入的部分由再次执行 `importAll` 收敛（该操作幂等），必要时配合只读 `pms/_count` 观察文档总量。

### 5.4 importAll 跨实例互斥与失锁保护

`importAll` 同时使用进程内锁和 Redis 锁。Redis 锁使用 `SET NX` 加租约，持有实例以 owner
校验续租和释放，默认 key 为 `mall:search:import-all`，租约 1800 秒、每 30 秒续租一次。

- Redis 不可用、锁已被其它实例持有或无法启动续租时，导入在读取 MySQL 前拒绝执行；
- 运行过程中续租失败会进入 fail-closed 状态：允许已发生的写入保持原状，但不会继续扫描或删除陈旧 ES 文档；
- `MALL_SEARCH_IMPORT_LOCK_ENABLED=false` 仅用于明确的本地单实例诊断，生产和多实例部署必须保持 `true`；
- Compose 中 `mall-search` 等待 Redis 健康后才启动。Redis 锁只保护 `importAll`，不替代六条写接口的
  `X-Internal-Token` 鉴权，也不改变搜索接口的 1-based 分页契约。

遍历方式说明：清理阶段使用 `search_after` 游标分批（每批 500 条、始终 `from = 0`），
不构造 `from + size` 深层分页请求，因此**不受 `index.max_result_window`（默认 10000）限制**，
可以完整枚举索引中的历史文档，也不会一次性把全部文档拉回应用。

检查索引：

```bash
curl http://localhost:9200/_cat/indices?v
curl http://localhost:9200/pms/_count
```

只读排查：`_count` 明显大于 MySQL 在售商品数量时，说明索引存在陈旧文档，可再次执行
`importAll` 重新收敛（写操作，只在允许写入的环境执行）。

## 6. 启动 mall-portal 与 mall-admin

```bash
export MALL_SEARCH_BASE_URL=http://localhost:8081
export MALL_SEARCH_INTERNAL_TOKEN=你的令牌   # 与 mall-search 一致

# 可选：仅用于验证降级搜索，默认必须保持 false
export MALL_SEARCH_MYSQL_FALLBACK_ENABLED=false

mvn -pl mall-portal spring-boot:run
mvn -pl mall-admin  spring-boot:run
```

`MALL_SEARCH_MYSQL_FALLBACK_ENABLED` 详见第 3.2 节。开启后搜索高峰期可能把读压力转移到 MySQL，不要把开启状态作为常态配置。

## 7. 验证清单

### 7.1 搜索分页（页码 1-based）

```bash
curl "http://localhost:8085/product/search?keyword=小米&pageNum=1&pageSize=5"
curl "http://localhost:8085/product/search?keyword=小米&pageNum=2&pageSize=5"
curl "http://localhost:8085/product/search?keyword=小米&pageNum=3&pageSize=5"
```

期望：三页数据不重复，响应中的 `pageNum` 与请求一致（1-based），`totalPage` 与 `total` 自洽（深分页返回 `total=0` 的空页属于例外，见下方边界表）。

边界行为：

| 请求 | 期望 |
| --- | --- |
| `pageNum=0` 或 `pageNum=-1` | 归一化为第 1 页 |
| `pageNum=9999`（深分页，超出 ES 结果窗口） | 返回空列表，`total=0`，`totalPage=0`（见下方说明） |
| `pageSize=9999` | 按上限 100 处理，响应中的 `pageSize` 回显上限值 100 |
| `pageSize=0` 或负数 | 按默认值 5 处理 |
| 无匹配关键字 | 返回空列表，不报错 |

深分页说明（当前行为，不涉及改造）：本节只针对**搜索接口**的 `from/size` 分页。
Elasticsearch 的 `index.max_result_window` 默认为 10000，当 `from + size` 超过该值时 ES 会返回 `search_phase_execution_exception`。为避免把该异常抛给调用方，`SearchPageUtils.isBeyondMaxResultWindow()` 会提前判断，命中时直接由 `SearchPageUtils.emptyPage()` 返回快速空页：

> 注意：`importAll` 的陈旧文档清理**不使用** `from/size`，已改为 `search_after` 游标分批（第 5.3 节），
> 因此不受 `index.max_result_window` 限制；本节的窗口限制只影响搜索接口的分页深度。

- `list` 为空数组；
- `total=0`；
- `totalPage=0`；
- **不再访问 Elasticsearch**，因此不会触发 `max_result_window` 异常。

也就是说，深分页的空结果是一个「不携带总数」的快速空页，与「无匹配关键字」的查询不到结果不同：不要依赖深分页响应里的 `total` 判断商品总数，需要总数时请使用正常范围内的页码查询。如果需要真正的深分页，需要另行评估 `search_after` 或调大 `index.max_result_window`，这属于改造项，不在本阶段范围内。

### 7.2 写接口与内部鉴权

所有写接口使用同一个令牌头，下列示例都应放回显 200：

```bash
# 同步单个商品
curl -X POST http://localhost:8081/esProduct/sync/26 -H "X-Internal-Token: ${MALL_SEARCH_INTERNAL_TOKEN}"
# 导入单个商品
curl -X POST http://localhost:8081/esProduct/create/26 -H "X-Internal-Token: ${MALL_SEARCH_INTERNAL_TOKEN}"
# 删除单个商品文档
curl -X GET http://localhost:8081/esProduct/delete/26 -H "X-Internal-Token: ${MALL_SEARCH_INTERNAL_TOKEN}"
# 批量删除
curl -X POST http://localhost:8081/esProduct/delete/batch \
  -H "X-Internal-Token: ${MALL_SEARCH_INTERNAL_TOKEN}" \
  -d "ids=26&ids=27"
# 全量导入
curl -X POST http://localhost:8081/esProduct/importAll -H "X-Internal-Token: ${MALL_SEARCH_INTERNAL_TOKEN}"
```

鉴权校验：

```bash
# 缺少令牌 → 401
curl -i -X POST http://localhost:8081/esProduct/sync/26

# 令牌不匹配 → 401
curl -i -X POST http://localhost:8081/esProduct/sync/26 -H "X-Internal-Token: wrong-token"

# 带尾斜杠的写路径 → 同样返回 401
curl -i -X POST http://localhost:8081/esProduct/importAll/

# 带分号矩阵参数的写路径 → 同样返回 401
curl -i -X POST "http://localhost:8081/esProduct/importAll;x=1"

# id 段为0、负数或非数字 → 同样返回 401
curl -i -X POST http://localhost:8081/esProduct/create/0
curl -i -X POST http://localhost:8081/esProduct/delete/-1
curl -i -X POST http://localhost:8081/esProduct/sync/not-a-number

# 服务端未配置 MALL_SEARCH_INTERNAL_TOKEN → 503
# 只读搜索接口不需要令牌 → 200
curl "http://localhost:8081/esProduct/search?keyword=小米&pageNum=1&pageSize=5"
curl "http://localhost:8081/esProduct/recommend/26"
```

### 7.3 批量同步

```bash
# 正常批量 → 200
curl -X POST http://localhost:8081/esProduct/sync/batch \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: ${MALL_SEARCH_INTERNAL_TOKEN}" \
  -d '[26,27,28]'

# 空数组 → 400
curl -i -X POST http://localhost:8081/esProduct/sync/batch \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: ${MALL_SEARCH_INTERNAL_TOKEN}" \
  -d '[]'

# 超过 100 条 → 400
```

mall-admin 侧不受此限制：`EsProductSyncServiceImpl.syncBatch` 会按 `mall.search.max-sync-batch-size` 自动拆分为多个请求，因此后台批量上下架不会因超限被拒绝。

### 7.4 幂等

对同一商品 id 重复调用同步，或直接重复执行 `importAll`，`pms` 索引的文档总数不应变化：

```bash
curl http://localhost:9200/pms/_count
```

### 7.5 商品状态变更的事务边界

`mall-admin` 中所有会发布 `ProductSyncEvent` 的生产方法都带 Spring 事务边界：

- `create`（新增商品）
- `update`（编辑商品）
- `updatePublishStatus`（上下架）
- `updateRecommendStatus`（推荐）
- `updateNewStatus`（新品）
- `updateDeleteStatus`（删除）

语义如下：

1. 数据库更新完成后才通过 `ProductSyncEvent` 发布事件，事务提交前不调用 ES；
2. 监听器使用 `AFTER_COMMIT`，且**没有 `fallbackExecution` 回退**：
   提交成功后同步一次，回滚后不同步，**没有事务时事件不触发任何同步**；
3. 每次状态变更只发布一个事件，不重复注册同步事件；
4. 同步 HTTP 失败只记录受控日志，不会影响已经提交的商品事务。

**当前不提供自动 outbox / MQ 补偿**：后台修改商品状态但 ES 同步失败时，索引可能停留在旧状态，需要重新执行同步或 `importAll` 修正。outbox 表、MQ 重试等补偿机制属于后续单独治理项，不在本阶段范围内。

## 8. 测试数据与权限提示

- 本项目的验证只能使用只读数据库查询，不要为了造数据执行 MySQL 写操作。
- 需要验证上架/下架/删除同步时，用只读查询取出不同状态的商品 id，再调用同步接口，对比同步前后 ES 文档是否存在。
- 令牌、数据库密码等不要写进仓库、文档或聊天记录；文档中的命令一律使用占位符，例如 `${MALL_SEARCH_INTERNAL_TOKEN}`。
- 核心测试清单只使用 Mockito 测试替身，不调用真实 ES 写接口（importAll / create / delete / sync）。

## 9. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| 启动报 `Failed to determine suitable jdbc url` | 没有注入数据源配置，按第 3 节传入参数或环境变量 |
| 接口长时间无响应后一直转圈 | Druid 获取连接无限等待，请设置 `spring.datasource.druid.max-wait` 并核对账号密码 |
| `pms` 索引为空 | 先调用 `/esProduct/importAll`；确认数据库中存在 `delete_status = 0 and publish_status = 1` 的商品 |
| 搜索报分词错误 | Elasticsearch 未安装 `analysis-ik`，按第 2 节安装后重建索引 |
| 同步返回 503 | mall-search 未配置 `MALL_SEARCH_INTERNAL_TOKEN`（importAll / create / delete / sync 同样适用） |
| 同步返回 401 | 请求头 `X-Internal-Token` 缺失或与服务端不一致 |
| `importAll` 返回 401 | 该接口已纳入写接口令牌保护，命令中必须带 `X-Internal-Token` |
| `importAll` 提示正在执行中 | 同一进程已有导入在执行，请等待完成后重试 |
| 门户搜索返回 500 `商品搜索服务暂时不可用` | mall-search 未启动、未建索引，或 `MALL_SEARCH_BASE_URL` 指向错误地址；`MALL_SEARCH_MYSQL_FALLBACK_ENABLED=false`（默认）时不会静默降级 |
| 开启降级后排序结果不符合预期 | 降级查询使用 `id desc` 稳定排序，不具备 ES 相关度排序能力 |
| 批量同步返回 400 | id 列表为空或超过 100 条 |

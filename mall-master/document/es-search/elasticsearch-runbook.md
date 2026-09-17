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
mall-admin 商品写操作
  → ProductSyncEvent（事务提交后发布）
    → EsProductSyncServiceImpl（带 X-Internal-Token）
      → mall-search /esProduct/sync/{id} 或 /esProduct/sync/batch
```

---

## 1. 前置条件

| 依赖 | 默认地址 | 说明 |
| --- | --- | --- |
| Elasticsearch | `localhost:9200` | 建议 8.x，需要安装 `analysis-ik` 分词插件 |
| MySQL | `localhost:3306/mall` | 搜索服务从 MySQL 读取已上架商品 |
| Redis | `localhost:6379` | 仅 mall-admin / mall-portal 需要 |
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
| `MALL_SEARCH_INTERNAL_TOKEN` | 内部同步接口令牌，mall-admin 与 mall-search 必须一致 | 空 |

令牌未配置时的行为：

- mall-search：`/esProduct/sync/**` 返回 503，并记录 error 日志，不会静默放行；
- mall-admin：跳过同步并记录 warn 日志，不影响商品主流程。

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

```bash
curl -X POST http://localhost:8081/esProduct/importAll
```

返回 `data` 为导入的商品数量。检查索引：

```bash
curl http://localhost:9200/_cat/indices?v
curl http://localhost:9200/pms/_count
```

`pms` 索引只包含在售商品：导入 SQL 过滤了 `delete_status = 0 and publish_status = 1`，因此下架和已删除商品不会进入索引。

## 6. 启动 mall-portal 与 mall-admin

```bash
export MALL_SEARCH_BASE_URL=http://localhost:8081
export MALL_SEARCH_INTERNAL_TOKEN=你的令牌   # 与 mall-search 一致

mvn -pl mall-portal spring-boot:run
mvn -pl mall-admin  spring-boot:run
```

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

深分页说明（当前行为，不涉及改造）：Elasticsearch 的 `index.max_result_window` 默认为 10000，当 `from + size` 超过该值时 ES 会返回 `search_phase_execution_exception`。为避免把该异常抛给调用方，`SearchPageUtils.isBeyondMaxResultWindow()` 会提前判断，命中时直接由 `SearchPageUtils.emptyPage()` 返回快速空页：

- `list` 为空数组；
- `total=0`；
- `totalPage=0`；
- **不再访问 Elasticsearch**，因此不会触发 `max_result_window` 异常。

也就是说，深分页的空结果是一个「不携带总数」的快速空页，与「无匹配关键字」的查询不到结果不同：不要依赖深分页响应里的 `total` 判断商品总数，需要总数时请使用正常范围内的页码查询。如果需要真正的深分页，需要另行评估 `search_after` 或调大 `index.max_result_window`，这属于改造项，不在本阶段范围内。

### 7.2 同步接口与内部鉴权

```bash
# 正确令牌
curl -X POST http://localhost:8081/esProduct/sync/26 -H "X-Internal-Token: ${MALL_SEARCH_INTERNAL_TOKEN}"

# 缺少令牌 → 401
curl -i -X POST http://localhost:8081/esProduct/sync/26

# 令牌不匹配 → 401
curl -i -X POST http://localhost:8081/esProduct/sync/26 -H "X-Internal-Token: wrong-token"

# 服务端未配置令牌 → 503
# 普通搜索接口不需要令牌 → 200
curl "http://localhost:8081/esProduct/search?keyword=小米&pageNum=1&pageSize=5"
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

## 8. 测试数据与权限提示

- 本项目的验证只能使用只读数据库查询，不要为了造数据执行 MySQL 写操作。
- 需要验证上架/下架/删除同步时，用只读查询取出不同状态的商品 id，再调用同步接口，对比同步前后 ES 文档是否存在。
- 令牌、数据库密码等不要写进仓库、文档或聊天记录。

## 9. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| 启动报 `Failed to determine suitable jdbc url` | 没有注入数据源配置，按第 3 节传入参数或环境变量 |
| 接口长时间无响应后一直转圈 | Druid 获取连接无限等待，请设置 `spring.datasource.druid.max-wait` 并核对账号密码 |
| `pms` 索引为空 | 先调用 `/esProduct/importAll`；确认数据库中存在 `delete_status = 0 and publish_status = 1` 的商品 |
| 搜索报分词错误 | Elasticsearch 未安装 `analysis-ik`，按第 2 节安装后重建索引 |
| 同步返回 503 | mall-search 未配置 `MALL_SEARCH_INTERNAL_TOKEN` |
| 同步返回 401 | 请求头 `X-Internal-Token` 缺失或与服务端不一致 |
| 门户搜索返回 500 `商品搜索服务暂时不可用` | mall-search 未启动，或 `MALL_SEARCH_BASE_URL` 指向错误地址 |
| 批量同步返回 400 | id 列表为空或超过 100 条 |

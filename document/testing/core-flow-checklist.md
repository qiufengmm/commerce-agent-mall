# Mall 核心流程冒烟检查清单

> 本文只记录**可复现的只读验证命令**、前置条件、预期结果与失败排查。
> 不写入任何密码、AccessKey、内部令牌或真实账号；需要凭据时用占位符 `<...>` 提示从本机环境变量注入。

## 1. 前置条件

1. Docker 环境已按 `document/docker/local-startup.md` 启动，基础设施 `Up (healthy)`；
2. `mall-admin`(8080)、`mall-search`(8081)、`mall-portal`(8085) 三个应用已启动且健康检查通过；
3. 如经过 Nginx(8088)，`nginx` 容器也已启动；
4. 本机已安装 `curl.exe`（Windows 10 1803+ 自带）与 Docker CLI。

确认容器状态（只读）：

```powershell
docker compose --env-file .env ps
```

预期：`mysql / redis / rabbitmq / mongo / elasticsearch / minio` 为 `Up (healthy)`，
`minio-init` 为 `Exited (0)`，`mall-admin / mall-search / mall-portal` 为 `Up`。

## 2. 禁止在当前环境执行的操作

- 任何 SQL 的 `INSERT / UPDATE / DELETE / DDL`、权限修改、数据迁移；
- 注册新账号、创建订单、支付、取消订单、确认收货、上传或删除文件；
- 调用 `POST /esProduct/importAll`、`POST /esProduct/sync/**` 等会改变现有 ES 数据的接口；
- MinIO 的上传、删除、策略修改；
- 任何写入型接口压测。

需要验证写操作链路时，应在独立的临时环境中进行，并在验证后恢复环境。

## 3. 服务健康检查

```powershell
curl.exe http://localhost:8080/actuator/health
curl.exe http://localhost:8085/actuator/health
curl.exe http://localhost:8081/actuator/health
```

预期：三条均返回 `{"status":"UP"}`（HTTP 200）。

失败排查：

- 返回 `000` / 连接被拒绝 → 服务未启动或端口未映射，先 `docker compose logs mall-local-mall-admin-1`；
- 返回 `{"status":"DOWN"}` → 查看返回体中的 `components`，定位 MySQL / Redis / Elasticsearch 子项；
- 应用 `start_period` 为 120 秒，刚重启时 `starting` 属正常，稍后重试。

## 4. Nginx 代理检查

```powershell
curl.exe http://localhost:8088/admin-api/actuator/health
curl.exe http://localhost:8088/portal-api/actuator/health
curl.exe http://localhost:8088/es-api/actuator/health
```

预期：三条均返回 `{"status":"UP"}`；说明 `/admin-api`、`/portal-api`、`/es-api` 三个前缀分别正确转发到 8080 / 8085 / 8081。

失败排查：

- 404 → Nginx 配置未加载或前缀写错，检查 `document/docker/nginx/conf.d/default.conf` 的 `location` 块；
- 502 → 后端未启动，先回到第 3 节确认直连端口；
- 连接被拒绝 → `nginx` 容器未启动，检查 `NGINX_BIND_ADDR` 与端口 8088。

## 5. 门户商品搜索（只读）

```powershell
curl.exe "http://localhost:8088/portal-api/product/search?pageNum=1&pageSize=5"
```

预期：

- HTTP 200；
- `code` 为 200；
- `data.pageNum` 为 `1`，即对外保持 1-based 页码；
- `data.list` 为数组（可以为空，但必须是数组而不是 `null`）。

也可以直连门户服务验证同一链路：

```powershell
curl.exe "http://localhost:8085/product/search?pageNum=1&pageSize=5"
```

失败排查：

- `data.list` 为 `null` → 搜索服务异常，继续第 6 节确认 ES 状态；
- 报 `Connection refused` 到 8081 → `MALL_SEARCH_BASE_URL` 配置错误；
- 返回 500 → 查看 `docker compose logs mall-local-mall-search-1`。

## 6. Elasticsearch 只读检查

```powershell
curl.exe http://localhost:9200/_cat/health?v
curl.exe http://localhost:9200/_cat/indices?v
curl.exe http://localhost:9200/pms/_count
curl.exe "http://localhost:9200/pms/_search?size=1"
```

预期：

- 集群 `status` 为 `green` 或 `yellow`；
- 索引列表中存在 `pms`；
- `pms/_count` 返回 `count` 大于 0（说明已完成过索引初始化）；
- `pms/_search?size=1` 返回一条样例文档，不产生任何写入。

搜索服务直连验证（`X-Internal-Token` 只用于 `/esProduct/sync/**` 同步接口，搜索接口不需要）：

```powershell
curl.exe "http://localhost:8081/esProduct/search?keyword=&pageNum=1&pageSize=5"
curl.exe "http://localhost:8088/es-api/esProduct/search?pageNum=1&pageSize=5"
```

失败排查：

- ES 未响应 → 检查 `elasticsearch` 容器与 9200 端口；
- `pms` 索引缺失或 `count` 为 0 → 索引尚未初始化，**不要在本环境执行 `importAll`**，应在临时环境初始化后再回来验证；
- 搜索接口 500 → 查看 `mall-search` 日志，确认 `SPRING_ELASTICSEARCH_URIS` 指向容器内地址而非 `localhost`。

## 7. MinIO 只读检查

```powershell
curl.exe http://localhost:9000/minio/health/live
curl.exe -o $null -w "%{http_code}" "http://localhost:9000/mall/<桶内已知对象名>"
```

预期：

- 健康检查返回 HTTP 200；
- 匿名读取已存在对象返回 200；返回 403 说明匿名只读策略未生效，返回 404 说明对象名不存在。

对象名可以从搜索结果或商品详情接口的图片字段中读取，不要硬编码任何密钥。

失败排查：

- 健康检查失败 → 检查 `minio` 容器与 `MINIO_BIND_ADDR`；
- 403 → `minio-init` 匿名策略未生效，查看 `docker compose logs mall-local-minio-init-1`；
- 使用宿主机直连时确认 `MINIO_PUBLIC_ENDPOINT` 指向的地址可访问。

## 8. 自动化测试命令

后端（三个模块一起执行，包含已有测试与本次新增测试）：

```powershell
Set-Location F:\code\mall\.worktrees\core-flow-tests\mall-master
mvn -pl mall-admin,mall-search,mall-portal -am test -DskipTests=false "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.failure.ignore=true"
```

说明：

- `maven.test.failure.ignore=true` **只是**为了让 Maven reactor 在模块出现测试失败时继续往下执行，从而一次性收集三个模块的完整结果；
- 它**不代表**失败测试通过，`BUILD SUCCESS` 也不能作为验收结论；
- 命令执行后必须打开各模块的 Surefire 报告（`mall-admin/target/surefire-reports`、`mall-search/target/surefire-reports`、`mall-portal/target/surefire-reports`）单独统计通过数、失败数和环境型 error；
- 本次新增测试的验收以「只运行本次新增的测试类」中的单模块命令为主要依据。

只运行本次新增的测试类（主要验收依据）：

```powershell
mvn -pl mall-portal test -DskipTests=false "-Dtest=UmsMemberControllerTest,OmsCartItemControllerTest,OmsPortalOrderControllerTest,PmsPortalCommentControllerTest,UmsMemberServiceImplTest,OmsCartItemServiceImplTest,PmsPortalCommentServiceImplTest" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn -pl mall-search test -DskipTests=false "-Dtest=EsProductControllerSearchTest,EsProductServiceImplSearchTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

前端：

```powershell
Set-Location F:\code\mall\.worktrees\core-flow-tests\mall-app-web-master
npm test
npm run tsc
```

## 9. 已知环境限制与失败基线

- `mall-search` 存在依赖开发 profile 数据源的 Spring 上下文测试，
  在未配置开发数据源的机器上会因数据源连接失败而报错。
  这类失败属于**环境问题，不是业务回归**，需要在报告中显式列出，
  不允许通过静默跳过或断言放宽把它伪装成通过。
- 三个模块的 `*ApplicationTests` 上下文测试同样依赖外部中间件，
  离线环境下需要与纯单元测试区分统计。
- 涉及 SQL 的测试只允许使用独立 H2 内存库或 Mockito，
  禁止连接当前 Docker MySQL，也禁止把本机数据库快照复制到测试资源目录。

# Mall 本地 Docker Compose 启动文档

本文档说明如何用一套可重复使用的 Docker Compose 配置拉起 Mall 项目的全部本地依赖，
以及各 profile 的启动、验证、停止与排查方式。

## 按顺序操作清单（第一次启动请先执行这里）

推荐第一次严格按以下顺序执行：进入仓库根目录 → 创建 `.env` → 校验配置 → 拉取镜像 → 启动基础设施 →（确认后）初始化数据库 → 构建 Java 应用 → 构建前端并启动 Nginx → 检查 MinIO bucket/匿名策略并导入 ES → 停止服务。

全栈 `app + edge` 模式包含 10 个常驻服务，另有一次性 `minio-init`；后者成功状态是 `Exited (0)`，不是常驻 `healthy`。

详细解释和故障排查见后续章节；本文档不会替你执行任何 SQL。

### 1. 进入仓库根目录

```powershell
Set-Location F:\code\mall
Test-Path .\docker-compose.yml
docker info
docker compose version
```

作用：确认当前目录确实是包含 `docker-compose.yml` 的仓库根目录，并确认 Docker Desktop 已启动。

### 2. 创建并填写 `.env`

```powershell
if (-not (Test-Path -LiteralPath '.\.env')) {
    Copy-Item -LiteralPath '.\.env.example' -Destination '.\.env'
}
notepad .env
```

作用：`.env.example` 只有占位值，`.env` 才是 Compose 实际读取的本机配置。至少替换 `MYSQL_ROOT_PASSWORD`、`RABBITMQ_PASSWORD`、`MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD` 和 `MALL_SEARCH_INTERNAL_TOKEN`。

本机访问时保持：

```ini
MINIO_IMAGE=quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z
MINIO_MC_IMAGE=quay.io/minio/mc:RELEASE.2025-05-21T01-59-54Z
MINIO_PUBLIC_ENDPOINT=http://localhost:9000
MINIO_BIND_ADDR=127.0.0.1
NGINX_BIND_ADDR=127.0.0.1
```

作用：MinIO 服务和初始化客户端从 Quay 拉取；公开地址给浏览器使用；两个绑定地址确保默认只允许本机访问。

### 3. 校验配置

```powershell
powershell -ExecutionPolicy Bypass -File document\docker\check-env.ps1
if ($LASTEXITCODE -ne 0) { throw '凭据校验失败，请修改 .env 后重试' }
docker compose --env-file .env config --quiet
docker compose --env-file .env --profile app --profile edge --profile observability config --quiet
docker compose --env-file .env config --images
```

作用：启动容器前发现占位密码、重复变量、YAML 错误和错误镜像地址。最后一条命令必须显示 `quay.io/minio/minio:...` 和 `quay.io/minio/mc:...`。

### 4. 拉取镜像

```powershell
docker pull quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z
docker pull quay.io/minio/mc:RELEASE.2025-05-21T01-59-54Z
docker compose --env-file .env pull mysql redis rabbitmq mongo elasticsearch minio minio-init
```

作用：先区分镜像仓库/代理不可达和容器启动失败。MinIO 使用 Quay；如果 MySQL、Redis 等 Docker Hub 镜像失败，检查 Docker Desktop `Settings → Resources → Proxies`。

### 5. 启动基础设施

```powershell
docker compose --env-file .env up -d
docker compose --env-file .env ps -a
docker compose --env-file .env logs --tail=100 minio-init
```

作用：启动 MySQL、Redis、RabbitMQ、MongoDB、Elasticsearch、MinIO，并运行一次性 `minio-init`。预期基础设施为 `Up (healthy)`，`minio-init` 为 `Exited (0)`。

### 6. 初始化数据库前停下来确认

要启动 Java 应用并访问真实商品数据，需要向全新的本地 MySQL 数据卷导入经过审查的数据库快照；只验证基础设施和 MinIO 可以跳过。仓库中的 `mall.sql` 是历史初始化脚本，包含建表、初始化数据以及可能影响已有表的语句，不能替代当前数据库快照，也不能导入已有业务库。执行任何 SQL 前必须确认影响范围，并在本对话明确回复：`确认执行这份 SQL`。本文档不会自动挂载或执行 SQL。

### 7. 构建并启动 Java 应用

```powershell
docker compose --env-file .env --profile app build
docker compose --env-file .env --profile app up -d
docker compose --env-file .env --profile app ps -a
```

作用：构建并启动 `mall-search`、`mall-admin`、`mall-portal`。`mall-admin` 还会等待 `minio-init` 成功退出；失败时先看 `docker compose --env-file .env logs --tail=200 mall-search mall-admin mall-portal`。

### 8. 构建前端并启动 Nginx

```powershell
Set-Location F:\code\mall\mall-admin-web-master
npm install
$env:VITE_BASE_SERVER_URL = '/admin-api'
npm run build
Set-Location F:\code\mall\mall-app-web-master
npm install
$env:VITE_API_BASE_URL = '/portal-api'
npm run build:h5
Set-Location F:\code\mall
docker compose --env-file .env --profile app --profile edge up -d
```

作用：生成 Nginx 的前端静态文件并启动反向代理。管理后台地址是 `http://localhost:8088/`；只启动 `edge` 而没有启动 `app` 时 API 返回 502 是预期行为。

### 9. 检查 MinIO bucket/匿名策略并导入 ES

```powershell
curl.exe http://localhost:9000/minio/health/live
docker compose --env-file .env run --rm --no-deps minio-init `
  'mc alias set localminio http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" > /dev/null && mc ls --recursive --json localminio/mall && mc anonymous get localminio/mall'
curl.exe -X POST http://localhost:8081/esProduct/importAll
curl.exe http://localhost:9200/_cat/indices?v
curl.exe http://localhost:9200/pms/_count
```

作用：确认 MinIO 健康、`mall` bucket 存在且配置为匿名下载，并把 MySQL 在售商品导入 ES 的 `pms` 索引。该命令本身不上传文件；真实上传应在管理后台新增/编辑商品并上传图片，再用返回的匿名 URL 验证文件可以直接打开，URL 应以 `http://localhost:9000/mall/` 开头。

### 10. 停止服务

```powershell
docker compose --env-file .env --profile app --profile edge --profile observability down
```

作用：删除容器和网络但保留命名数据卷。不要执行 `down -v`，除非明确要删除本地 MySQL、ES、MinIO 图片等全部数据。

相关文件：

```text
.env.example                                 环境变量模板（只含占位符）
docker-compose.yml                           编排主文件
.dockerignore                                构建上下文忽略规则
document/docker/Dockerfile.app               三应用通用多阶段构建文件
document/docker/check-env.ps1                启动前凭据校验（Windows PowerShell，首选）
document/docker/check-env.sh                 启动前凭据校验（Bash / Git Bash / WSL，可选）
document/docker/nginx/conf.d/default.conf    Nginx 站点与反向代理配置
document/docker/logstash/pipeline/logstash.conf  Logstash 管道配置
```

> **当前交付边界（必读）**
>
> 本文件是可复用的启动与排查说明，不记录某一台机器的动态容器状态、数据库行数或 MinIO 对象数量。
> MinIO 的两个 Quay 镜像标签已在本地完成拉取验证；其他镜像是否能拉取，取决于目标机器的
> Docker Desktop 网络与代理配置。执行完整运行验证必须按本文档顺序在目标机器实际启动，并以
> `docker compose ps`、健康检查和 HTTP 检查结果为准。

---

## 1. 环境要求

| 项目 | 要求 |
| --- | --- |
| Docker Desktop | 已安装并启动，Docker Engine 版本需支持 Compose Spec（本机实测 29.5.3 / Compose v5.1.4） |
| 可用内存 | 建议 ≥ 8 GB；最低 4 GB（Elasticsearch 默认堆 1 GB，Logstash 512 MB，三个 Java 应用各约 1 GB） |
| 磁盘 | 建议 ≥ 20 GB 可用空间（命名数据卷 + 镜像层） |
| 构建工具（仅构建应用镜像时需要） | Maven 由构建容器内提供，宿主机不强制要求；宿主机运行 Java 服务时需要 JDK 17 + Maven |

### 1.1 端口检查

默认会占用以下宿主机端口，启动前先确认没有冲突：

```powershell
$ports = 3306, 6379, 5672, 15672, 27017, 9200, 9000, 9001, 8080, 8081, 8085, 8088, 5601, 4560, 4561, 4562, 4563
foreach ($p in $ports) {
    $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if ($c) { Write-Output "PORT $p 已被占用 -> PID $($c.OwningProcess)" }
}
```

端口用途：

| 端口 | 服务 | 说明 |
| --- | --- | --- |
| 3306 | MySQL | 默认基础设施 |
| 6379 | Redis | 默认基础设施 |
| 5672 / 15672 | RabbitMQ / 管理台 | 默认基础设施 |
| 27017 | MongoDB | 默认基础设施 |
| 9200 | Elasticsearch | 默认基础设施 |
| 9000 / 9001 | MinIO API / 控制台 | 默认基础设施 |
| 8080 / 8081 / 8085 | mall-admin / mall-search / mall-portal | `app` profile |
| 8088 | Nginx | `edge` profile |
| 5601 | Kibana | `observability` profile |
| 4560-4563 | Logstash TCP 输入 | `observability` profile |

### 1.2 端口绑定与网络暴露约定

**默认访问地址全部是 `localhost` / `127.0.0.1`。** Compose 中每一条宿主机端口映射都显式
带了 `127.0.0.1` 前缀，默认不暴露到局域网；已用解析后的配置核对过
（`docker compose config --format json`，共 17 条发布端口，全部 `host_ip = 127.0.0.1`）。

只有两个服务提供**按服务 opt-in** 的绑定地址变量，用于手机 / 微信开发者工具联调：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `MINIO_BIND_ADDR` | `127.0.0.1` | 改成 `0.0.0.0` 后 MinIO API(9000) 与控制台(9001) 暴露到局域网 |
| `NGINX_BIND_ADDR` | `127.0.0.1` | 改成 `0.0.0.0` 后 Nginx(8088) 暴露到局域网 |

### 1.3 局域网 / 真机联调（正确的启动命令）

> **注意**：只执行 `docker compose --profile edge up -d` **不会**启动
> `mall-admin` / `mall-search` / `mall-portal`，此时访问 `/admin-api/`、`/portal-api/`
> 一定返回 502。需要 Java 应用时必须同时带上 `--profile app`。

推荐做法：把 `MINIO_BIND_ADDR` / `NGINX_BIND_ADDR` 写进 `.env`（而不是临时环境变量，
避免下次启动忘记改回来），再按需要启动：

```powershell
# .env 中（写完记得执行 check-env.ps1，脚本会打印 0.0.0.0 告警）
MINIO_BIND_ADDR=0.0.0.0
NGINX_BIND_ADDR=0.0.0.0
# 手机要能下载 MinIO 里的图片，公开地址必须写成宿主机局域网 IP
MINIO_PUBLIC_ENDPOINT=http://192.168.1.20:9000
```

```powershell
# 需要 Java 后端 + Nginx 代理（真机联调的常规组合）
docker compose --profile app --profile edge up -d

# 还需要 Logstash / Kibana 时
docker compose --profile app --profile edge --profile observability up -d

# 只验证 Nginx 静态站点与反向代理容器本身（后端未启动，API 会 502）
docker compose --profile edge up -d
```

联调结束后把两个 `*_BIND_ADDR` 改回 `127.0.0.1`，并 `docker compose --profile app --profile edge up -d` 重建容器使绑定生效。

`MINIO_PUBLIC_ENDPOINT` 的取值规则见第 9 节。

> **安全警告**
>
> - Redis、MongoDB、Elasticsearch 的本地配置**没有开启任何认证**
>   （无密码、无 xpack、无鉴权）；因此配置文件中**不存在**把它们整体暴露到
>   `0.0.0.0` 的开关，这是刻意设计，请不要手工改掉端口映射的 `127.0.0.1` 前缀。
> - 把它们暴露到不可信网络等同于把无认证的数据库与搜索引擎直接开放，
>   任何能访问该网段的人都可以读写数据。
> - 局域网联调只开放必要的 MinIO 与 Nginx，并且只在可信网络下进行。
> - 凭据校验脚本会在 `MINIO_BIND_ADDR` / `NGINX_BIND_ADDR` 为 `0.0.0.0` 时打印告警。

---

## 2. 镜像标签清单与离线导入

镜像标签都支持在 `.env` 中覆盖（`MYSQL_IMAGE`、`REDIS_IMAGE`、`ES_IMAGE` 等）。
当前默认值：

| 服务 | 默认镜像 |
| --- | --- |
| MySQL | `mysql:5.7` |
| Redis | `redis:7` |
| RabbitMQ | `rabbitmq:3.13-management-alpine` |
| MongoDB | `mongo:7` |
| Elasticsearch | `docker.elastic.co/elasticsearch/elasticsearch:8.18.8` |
| MinIO | `quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z` |
| MinIO 初始化 | `quay.io/minio/mc:RELEASE.2025-05-21T01-59-54Z` |
| 应用构建期 | `maven:3.9-eclipse-temurin-17`（固定，不支持覆盖） |
| 应用运行期 | `eclipse-temurin:17-jre`（固定，不支持覆盖） |
| Nginx | `nginx:1.27-alpine` |
| Logstash | `docker.elastic.co/logstash/logstash:8.18.3` |
| Kibana | `docker.elastic.co/kibana/kibana:8.18.8` |

### 2.1 镜像标签拉取验证状态

- MinIO 服务与初始化客户端统一从官方 Quay 仓库拉取：`quay.io/minio/minio` 与
  `quay.io/minio/mc`；固定标签来自同一时期的 GitHub Release，**互相兼容**。
- 不再依赖 Docker Hub 上的 `minio/minio` 与 `minio/mc` 仓库；
  如果当前网络无法访问 Quay，请按本节的镜像加速器或离线导入方案处理。
  两个固定 Quay 标签已单独执行 `docker pull` 验证；其他 Docker Hub 和 `docker.elastic.co`
  镜像仍需在你的网络环境中按第 4 节和第 5 节验证。
- 因此本文档不把单独的镜像拉取成功等同于完整 Compose 已运行成功；实际结果以你的网络环境为准。

### 2.2 应用基础镜像的固定原则（不要改成 Alpine）

`document/docker/Dockerfile.app` 的两阶段基础镜像**已固定，不支持通过变量覆盖**：

- 构建阶段：`maven:3.9-eclipse-temurin-17`
- 运行阶段：`eclipse-temurin:17-jre`（Debian/Ubuntu 系列，提供 `apt-get`）

原因：Compose 中三个 Java 服务的健康检查使用 `curl` 访问 `/actuator/health`，
Dockerfile 在运行阶段执行 `apt-get install -y curl` 来提供该命令。
如果换成 Alpine 等不含 `apt-get` 的运行时镜像，会出现两类问题：

1. 构建阶段 `apt-get` 不存在 → 镜像直接构建失败；
2. 运行阶段没有 `curl` → 健康检查失败，容器被误判为 `unhealthy`，
   进而导致依赖它的 `mall-admin` / `mall-portal` 永远不启动。

因此**不再提供 `RUNTIME_IMAGE` / `BUILDER_IMAGE` 覆盖能力**。如必须使用内网镜像仓库，
请在本地把镜像打成同名标签后再构建：

```powershell
docker pull <内网仓库>/library/eclipse-temurin:17-jre
docker tag  <内网仓库>/library/eclipse-temurin:17-jre eclipse-temurin:17-jre

docker pull <内网仓库>/library/maven:3.9-eclipse-temurin-17
docker tag  <内网仓库>/library/maven:3.9-eclipse-temurin-17 maven:3.9-eclipse-temurin-17
```

### 2.3 镜像仓库不可达时的处理方式

按顺序尝试下面三种方案：

**方案一：配置镜像加速器（Docker Desktop）**

Settings → Docker Engine，在配置中增加 `registry-mirrors`（使用你所在网络可用的
加速器地址），Apply & Restart 后重试：

```json
{
  "registry-mirrors": ["https://<你的加速器地址>"]
}
```

**方案二：覆盖镜像变量（只适用于有镜像变量的服务）**

在 `.env` 中把镜像指向可达的仓库，例如：

```text
MYSQL_IMAGE=mirror.example.com/library/mysql:5.7
REDIS_IMAGE=mirror.example.com/library/redis:7
RABBITMQ_IMAGE=mirror.example.com/library/rabbitmq:3.13-management-alpine
MONGO_IMAGE=mirror.example.com/library/mongo:7
NGINX_IMAGE=mirror.example.com/library/nginx:1.27-alpine
MINIO_IMAGE=mirror.example.com/minio/minio:RELEASE.2025-04-22T22-12-26Z
MINIO_MC_IMAGE=mirror.example.com/minio/mc:RELEASE.2025-05-21T01-59-54Z
ES_IMAGE=mirror.example.com/elasticsearch/elasticsearch:8.18.8
KIBANA_IMAGE=mirror.example.com/kibana/kibana:8.18.8
LOGSTASH_IMAGE=mirror.example.com/logstash/logstash:8.18.3
```

应用基础镜像没有覆盖变量，请按 2.2 用 `docker tag` 方式处理。

**方案三：离线导入（`docker image load`）**

在能联网的机器上拉取并打包，拷回本机导入（导入后用 `docker tag` 对齐本文的标签）：

```powershell
# 联网机器
docker pull quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z
docker pull quay.io/minio/mc:RELEASE.2025-05-21T01-59-54Z
docker save quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z quay.io/minio/mc:RELEASE.2025-05-21T01-59-54Z -o minio.tar

# 本机
docker image load -i minio.tar
```

**Maven 仓库不可达时**：构建阶段在容器内下载依赖，可在项目的 Maven `settings.xml`
或镜像加速器侧配置仓库镜像；也可以在 `.dockerignore` 之外准备本地仓库缓存目录
并挂载到构建容器（需要自行修改 Dockerfile）。

### 2.4 Elasticsearch 版本说明

- 选 `8.18.8` 是为了与本地 Maven 依赖 `co.elastic.clients:elasticsearch-java:8.18.8`
  对齐（Spring Boot 3.5.14 管理的客户端版本），不使用历史配置中的 `7.17.3`；
- 本地开发关闭了 xpack 安全认证（`xpack.security.enabled=false`），
  避免把 ES 初始密码写入仓库，也无需配置账号即可访问 `http://localhost:9200`；
- 如果后续需要安装 `analysis-ik` 分词插件，插件版本必须与镜像版本一致：

```powershell
docker compose exec elasticsearch ./bin/elasticsearch-plugin install https://get.infini.cloud/elasticsearch/analysis-ik/8.18.8
docker compose restart elasticsearch
```

---

## 3. 准备 `.env` 与启动前环境校验（必须）

### 3.1 生成 `.env`

`.env.example` 只含变量名与安全占位符，复制后替换其中的 `<...>` 占位符：

```powershell
Copy-Item .env.example .env
notepad .env
```

必须替换的占位符：

| 变量 | 说明 |
| --- | --- |
| `MYSQL_ROOT_PASSWORD` | 本地 MySQL root 密码 |
| `RABBITMQ_PASSWORD` | 本地 RabbitMQ 应用账号密码 |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | MinIO root 账号与密码 |
| `MALL_SEARCH_INTERNAL_TOKEN` | mall-admin 与 mall-search 共用的内部同步令牌 |

令牌生成示例（任选一种）：

```powershell
# PowerShell
-join ((1..32) | ForEach-Object { '{0:x2}' -f (Get-Random -Maximum 256) })
```

```bash
# Bash / Git Bash
openssl rand -hex 32
```

### 3.2 必须先执行凭据校验，再启动

复制模板后**不允许**不修改占位值就直接启动。先执行：

```powershell
powershell -ExecutionPolicy Bypass -File document\docker\check-env.ps1
```

Bash / Git Bash / WSL 环境可选：

```bash
bash document/docker/check-env.sh
```

校验脚本会检查以下变量是否仍为占位值、是否缺失、长度是否达标：

| 变量 | 额外要求 |
| --- | --- |
| `MYSQL_ROOT_PASSWORD` | 非空、非占位值 |
| `RABBITMQ_PASSWORD` | 非空、非占位值 |
| `MINIO_ROOT_USER` | 非空、非占位值，长度 ≥ 3 |
| `MINIO_ROOT_PASSWORD` | 非空、非占位值，长度 ≥ 8（MinIO 服务端强制要求） |
| `MALL_SEARCH_INTERNAL_TOKEN` | 非空、非占位值，长度 ≥ 16（避免弱令牌） |

被判为"占位值"的特征包括：包含 `<` 或 `>`、包含 `change-me` / `changeme` /
`change_me` / `please-change` / `example` / `placeholder` / `your-` / `todo` / `fixme`
等关键字（大小写不敏感）、以及包含"请填写 / 请设置 / 请生成 / 请替换"等说明文字。

**重复定义检测**：上面 5 个必检变量在 `.env` 中出现**两次及以上**时，脚本直接判定为失败。

- 原因：Compose 对重复变量使用**最后一个值**，而只取第一个值的解析方式可能得到
  完全不同的结果（例如第一个值是合规密码、第二个值是占位符时，只取首值会"看起来通过"）；
- 脚本只报告变量名与"重复定义"，**不输出任何变量值**；
- `check-env.ps1` 与 `check-env.sh` 的重复变量行为保持一致；
- 处理办法：删除多余的重复行，每个变量只保留一行。

退出码：

| 退出码 | 含义 |
| --- | --- |
| `0` | 校验通过（可能附带告警） |
| `1` | 存在未通过的校验项，必须修改 `.env` 后重试 |
| `2` | 找不到 `.env` 文件 |

安全设计：

- 脚本**只输出变量名与失败原因，绝不输出变量值**，日志可以安全粘贴到聊天或报告；
- 脚本不启动、不修改任何服务，只是本地文本检查。

> **为什么不能只用 `docker compose config --quiet`**
>
> `docker compose config --quiet` 只能验证 **YAML 语法与变量插值/渲染**，
> 只要变量存在且非空就能通过，**完全不判断值是否安全**。
> 也就是说，`.env` 里写着 `MYSQL_ROOT_PASSWORD=<请填写本地 MySQL root 密码>` 时，
> `config --quiet` 依然返回 0。
> 因此**必须**先跑 `check-env.ps1`，通过后再执行 Compose 启动与 `config` 校验。

安全补充约定：

- `.env` 已被 `.gitignore` 忽略，**不要提交**填写后的文件；
- 禁止把真实密码、Token、密钥、支付宝配置或生产地址写入 `.env.example` 或本文档；
- 带 `:?` 约束的变量（如 `MYSQL_ROOT_PASSWORD`、`MALL_SEARCH_INTERNAL_TOKEN`）缺失时，
  `docker compose` 会直接报错并退出，不会静默使用空密码启动；
- `MALL_SEARCH_INTERNAL_TOKEN` 在 mall-admin 与 mall-search 之间必须一致，
  否则 `/esProduct/sync/**` 会返回 401/503，mall-admin 侧会跳过同步并打印 warn 日志。

---

## 4. profile 关系与启动方式

### 4.1 profile 划分

| profile | 是否默认 | 包含服务 |
| --- | --- | --- |
| `infra` | **默认分组，不需要 `--profile`** | `mysql`、`redis`、`rabbitmq`、`mongo`、`elasticsearch`、`minio`、`minio-init` |
| `app` | 需 `--profile app` | `mall-admin`、`mall-search`、`mall-portal` |
| `edge` | 需 `--profile edge` | `nginx` |
| `observability` | 需 `--profile observability` | `logstash`、`kibana` |

要点：

- `infra` 不是显式 profile，而是"不带任何 profile 也会启动"的默认分组；
- 三个可选 profile **互不影响**，可以任意组合，也可以单独启动；
- `app` 依赖 `infra` 的健康状态，但启动命令里不需要额外写 `infra`：
  执行 `docker compose --profile app up -d` 会同时把 `infra` 拉起；
- `observability` 不是业务启动依赖，不启动不影响任何业务功能。

### 4.2 各模式命令

| 模式 | 启动命令 | 启动内容 | 适用场景 |
| --- | --- | --- | --- |
| 开发模式（推荐日常开发） | `docker compose up -d` | 仅基础设施 | Java 服务在 IDE / 宿主机运行，改代码免重建镜像，调试最方便 |
| 全栈模式 | `docker compose --profile app --profile edge up -d` | 基础设施 + 三个 Java 应用 + Nginx | 验证容器化部署、前端联调、整体链路 |
| 可选观测模式 | `docker compose --profile observability up -d` | 基础设施 + Logstash + Kibana | 需要采集与检索应用日志时按需开启 |
| 全量 | `docker compose --profile app --profile edge --profile observability up -d` | 全部 | 一次性拉起所有组件 |

### 4.3 基础设施（默认）

```powershell
docker compose up -d
```

默认启动 6 个基础设施服务 + 1 个一次性初始化任务：

| 服务 | 说明 |
| --- | --- |
| `mysql` | MySQL 5.7，自动创建 `MYSQL_DATABASE` 指定的库（默认 `mall`） |
| `redis` | Redis 7，开启 AOF |
| `rabbitmq` | RabbitMQ management 版，自动创建 vhost `/mall` 与 `mall` 用户并授予权限 |
| `mongo` | MongoDB，供 mall-portal 的浏览历史 / 收藏 / 关注使用 |
| `elasticsearch` | ES 8.18.8 单节点，关闭安全认证 |
| `minio` | MinIO，root 账号与密码来自环境变量 |
| `minio-init` | 一次性任务，创建 `MINIO_BUCKET_NAME`（默认 `mall`）bucket 并设置为匿名只读下载，执行完即退出；`mall-admin` 会等待它**成功退出**后才启动，失败则阻止 `mall-admin` 启动（见 4.4） |

首次执行会拉取镜像，耗时较长。启动后等待所有服务健康：

```powershell
docker compose ps
```

### 4.4 应用（`app` profile）

```powershell
docker compose --profile app build          # 首次需要构建，时间较长
docker compose --profile app up -d
```

端口与依赖（本次返工重点）：

| 服务 | 容器端口 | 宿主机映射 | 说明 |
| --- | --- | --- | --- |
| `mall-admin` | 8080 | `127.0.0.1:8080:8080` | 显式设置 `SERVER_PORT=8080` |
| `mall-search` | 8081 | `127.0.0.1:8081:8081` | 显式设置 `SERVER_PORT=8081` |
| `mall-portal` | 8085 | `127.0.0.1:8085:8085` | 显式设置 `SERVER_PORT=8085` |

- `mall-portal` 的 `application.yml` **没有** `server.port`，且本工作树中没有
  `application-dev.yml`，因此 Compose 通过环境变量 `SERVER_PORT=8085` 显式声明端口；
  三个服务都做了同样处理，不依赖模块默认配置。
- `mall-admin` 与 `mall-portal` 都调用 `mall-search`，两者都在 `depends_on` 中声明了
  `mall-search: condition: service_healthy`，**必须等 mall-search 健康后才启动**。
- `mall-admin` 额外声明 `minio-init: condition: service_completed_successfully`：
  **必须等 `minio-init` 成功退出（退出码 0）之后才启动**。bucket 创建与匿名只读策略统一由
  `minio-init` 负责，因此应用启动时 bucket 必定已存在、策略必定已生效，不会在策略生效前接受上传；
  **`minio-init` 失败（退出码非 0）会阻止 `mall-admin` 启动**，不会"应用起来了但策略没生效"。
  `mall-portal` 不上传文件，因此不依赖 `minio-init`。
- `mall-admin` 同时保留 `minio: condition: service_healthy`：这一条只保证 MinIO 服务本身可连通
  （SDK 连接需要），与上一条目标不同，两条都必须保留。
- `minio-init` 自身仍是 `minio: condition: service_healthy`、`restart: "no"`；
  `service_completed_successfully` 要求被依赖容器能正常结束，因此 `restart` 不能改成常驻策略。
- `mall-search` 只依赖 `mysql` 与 `elasticsearch` 的健康状态，不反向依赖
  admin/portal；`minio-init` 只依赖 `minio`，`minio` 不依赖任何服务，
  因此**不存在循环依赖**。
- 使用 `document/docker/Dockerfile.app` 从本地源码多阶段构建，三个应用复用同一个
  Dockerfile，通过 `MODULE` / `JAR_FILE` 参数区分。
- 不依赖任何预构建的 `mall/*:1.0-SNAPSHOT` 镜像，构建出的本地镜像名为
  `mall-local/mall-admin:local` 等（前缀可用 `APP_IMAGE_PREFIX` 覆盖）。
- 容器内通过服务名访问基础设施（`mysql`、`redis`、`rabbitmq`、`mongo`、
  `elasticsearch`、`minio`、`mall-search`），全部连接信息由环境变量注入，
  **未修改任何 `application*.yml`**。
- 三个服务都映射了宿主机端口，可直接用 `http://localhost:8080` 等方式调试。

启动顺序示意：

```text
mysql / redis / rabbitmq / mongo / elasticsearch / minio (health OK)
                └──> mall-search (8081, health OK)
minio (health OK)
                └──> minio-init (退出码 0，bucket + 匿名只读策略就绪)

mall-admin  (8080)  <- mall-search(health OK) + mysql + redis + minio + minio-init(退出码 0)
mall-portal (8085)  <- mall-search(health OK) + mysql + redis + mongo + rabbitmq
```

### 4.5 Nginx（`edge` profile）

```powershell
docker compose --profile edge up -d
```

- 管理后台静态资源：`http://localhost:8088/`
- H5 静态资源：`http://localhost:8088/h5/`
- 反向代理：`/admin-api/` → mall-admin 8080、`/portal-api/` → mall-portal 8085、
  `/es-api/` → mall-search 8081

**前端必须先构建**，否则目录为空、页面会 404，这时不要认为"全栈已可访问"：

```powershell
# 管理后台
cd mall-admin-web-master
npm install
npm run build                 # 产物：dist/

# H5 / 移动端
cd mall-app-web-master
npm install
npm run build:h5              # 产物：dist/build/h5/
```

**API 基地址适配（不改前端代码）**：两个前端的 API 基地址都是构建期环境变量，
Vite 会用进程级 `VITE_*` 变量覆盖 `.env` 文件。**走 Nginx 时必须使用
`/admin-api` 与 `/portal-api` 前缀**，不能用绝对地址，否则请求不会打到 Nginx：

```powershell
# 管理后台：默认 .env 是绝对地址 http://localhost:8080，走 Nginx 需改为相对前缀
cd mall-admin-web-master
$env:VITE_BASE_SERVER_URL = "/admin-api"
npm run build

# H5：默认 .env.production 是 http://localhost:8085，走 Nginx 需改为相对前缀
cd mall-app-web-master
$env:VITE_API_BASE_URL = "/portal-api"
npm run build:h5
```

不注入这两个变量也可以：保持前端现有行为，直接访问后端端口（`8080` / `8085`），
只是不经过 Nginx。**本文档不修改任何前端业务代码。**

其他限制：

- 微信小程序与原生 App 默认直连 `mall-portal`（`8085`），**不需要也不要求经过 Nginx**；
- 若后端服务未启动，Nginx 仍可正常启动，对应接口返回 502（配置使用 Docker 内置 DNS
  做运行时解析，不会因 `host not found in upstream` 退出）；
- **单独执行 `docker compose --profile edge up -d` 只验证静态代理容器本身，不代表后端可用**；
  需要 `/admin-api/`、`/portal-api/` 返回正常时，必须同时加 `--profile app`，见 1.3；
- 商品图片 URL 来自数据库（MinIO 地址），与 Nginx 无关，需要单独处理域名或端口。

### 4.6 观测（`observability` profile）

```powershell
docker compose --profile observability up -d
```

- Kibana：`http://localhost:5601`
- Logstash TCP 输入：`4560`(debug) / `4561`(error) / `4562`(business) / `4563`(record)

管道配置复制自 `mall-master/document/elk/logstash.conf`，仅把输出地址从
`localhost:9200` 改为容器内的 `http://elasticsearch:9200`。

**开发配置默认关闭 Logstash 输出**（`logstash.enableInnerLog=false`）。需要采集时：

1. 在 `.env` 中设置 `LOGSTASH_ENABLEINNERLOG=true`、`LOGSTASH_HOST=logstash`；
2. 重启应用：`docker compose --profile app up -d --force-recreate`；
3. 宿主机运行的 Java 服务则设置环境变量
   `$env:LOGSTASH_HOST = "localhost"` 与 `$env:LOGSTASH_ENABLEINNERLOG = "true"`。

日志文件不含密码、Token 或密钥；Logstash / Kibana 配置中同样不写入任何凭据。

---

## 5. 配置校验

**先跑第 3.2 节的凭据校验脚本**，再做 Compose 配置校验。

不启动容器，只校验 Compose 文件语法与变量插值：

```powershell
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example --profile app --profile edge --profile observability config --quiet
```

使用自己的 `.env` 时：

```powershell
docker compose config --quiet
docker compose --profile app --profile edge --profile observability config --quiet
```

查看插值后的完整配置（排查变量问题时很有用）：

```powershell
docker compose --env-file .env.example --profile app config
```

核对所有发布端口是否都绑定 `127.0.0.1`：

```powershell
$cfg = docker compose --env-file .env.example --profile app --profile edge --profile observability config --format json | ConvertFrom-Json
foreach ($s in $cfg.services.PSObject.Properties) {
    foreach ($p in $s.Value.ports) {
        "{0,-14} host_ip={1,-9} published={2}" -f $s.Name, $p.host_ip, $p.published
    }
}
```

---

## 6. 查看状态、健康状态与日志

```powershell
# 服务状态
docker compose ps
docker compose ps --format table

# 实时日志
docker compose logs -f
docker compose logs -f mysql elasticsearch
docker compose logs --tail=200 mall-admin

# 单个容器的健康检查详情
docker inspect --format "{{json .State.Health}}" mall-local-mysql-1
docker inspect --format "{{.State.Health.Status}}" mall-local-mall-admin-1

# 资源占用
docker stats
```

健康状态取值：`starting` → `healthy` / `unhealthy`。
应用服务的 `start_period` 为 120 秒，启动期间显示 `starting` 属正常。

---

## 7. Java 服务在宿主机运行（开发模式）

只启动基础设施，Java 服务用 Maven 或 IDE 运行，改代码无需重建镜像。

```powershell
# 1. 只启动基础设施
docker compose up -d

# 2. 设置环境变量（示例：mall-search）
$env:MALL_SEARCH_INTERNAL_TOKEN = "<与 mall-admin 一致的本机令牌>"
$env:SPRING_ELASTICSEARCH_URIS  = "http://localhost:9200"
$env:SPRING_DATASOURCE_URL      = "jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
$env:SPRING_DATASOURCE_USERNAME = "root"
$env:SPRING_DATASOURCE_PASSWORD = "<本机 MySQL root 密码>"
$env:SPRING_DATASOURCE_DRUID_MAX_WAIT = "10000"

# 3. 启动
cd mall-master
mvn -pl mall-search -am compile -DskipTests
mvn -pl mall-search spring-boot:run
```

另外两个服务：

```powershell
# mall-admin（需要 MinIO 与搜索服务地址）
$env:MINIO_ENDPOINT            = "http://localhost:9000"
$env:MINIO_ACCESSKEY           = "<MinIO root 用户>"
$env:MINIO_SECRETKEY           = "<MinIO root 密码>"
$env:MINIO_BUCKETNAME          = "mall"
$env:MALL_SEARCH_BASE_URL      = "http://localhost:8081"
# 可选：宿主机模式下不设置也行，公开地址会自动回退到 MINIO_ENDPOINT
$env:MINIO_PUBLIC_ENDPOINT     = "http://localhost:9000"
mvn -pl mall-admin spring-boot:run

# mall-portal（需要 MongoDB 与 RabbitMQ）
$env:SPRING_DATA_MONGODB_HOST       = "localhost"
$env:SPRING_DATA_MONGODB_PORT       = "27017"
$env:SPRING_DATA_MONGODB_DATABASE   = "mall-port"
$env:SPRING_RABBITMQ_HOST           = "localhost"
$env:SPRING_RABBITMQ_PORT           = "5672"
$env:SPRING_RABBITMQ_VIRTUAL_HOST   = "/mall"
$env:SPRING_RABBITMQ_USERNAME       = "mall"
$env:SPRING_RABBITMQ_PASSWORD       = "<RabbitMQ 密码>"
$env:MALL_SEARCH_BASE_URL           = "http://localhost:8081"
mvn -pl mall-portal spring-boot:run
```

`mall-portal` 的搜索链路保持不变：移动端访问 `/product/search`，由 mall-portal
转发到 mall-search 的 `/esProduct/search`，**不要改成直接访问 8081**。

---

## 8. 数据库初始化（务必先读警告）

> **警告**
>
> `mall-master/document/sql/mall.sql` 含有 `DROP TABLE`、`CREATE TABLE` 与初始化数据语句，
> **只能用于全新的本地空数据库**。对已有业务数据的库执行会造成数据丢失，且不可撤销。
> 严禁在任何真实/生产环境执行。

### 8.1 自动执行机制与当前策略

MySQL 官方镜像的 `/docker-entrypoint-initdb.d` 机制有一个关键特性：
**脚本只在数据目录为空（即数据卷首次创建）时执行一次**；已存在数据的数据卷再次启动时
不会执行，也不会覆盖已有表。

即便如此，`docker-compose.yml` 中对应的挂载行**默认是注释掉的**，不会自动执行任何 SQL：

```yaml
      # - ./mall-master/document/sql:/docker-entrypoint-initdb.d:ro
```

原因是 `mall.sql` 具有破坏性，自动执行风险高于收益。推荐按 8.2 手工按需导入。

> **不要为了让容器启动而取消这行注释。** 容器起不来通常是因为密码、端口或镜像问题，
> 不是缺 SQL；取消注释反而会在下一次新建数据卷时静默执行 `DROP TABLE`。

### 8.2 前提：必须使用全新空数据卷

任何初始化都只能在**全新的空数据卷**上进行：

```powershell
# 确认是全新环境（没有 mall-local_* 数据卷）
docker volume ls | Select-String mall-local

# 如需重建：先停止并删除数据卷（会丢失全部本地数据）
docker compose down -v
```

### 8.3 手工导入步骤

按依赖顺序执行，每执行一个脚本前先确认影响范围：

```powershell
$pw = "<本机 MySQL root 密码>"

# 1) 基础库表与初始化数据（危险：含 DROP TABLE，仅用于全新本地库）
Get-Content -Raw .\mall-master\document\sql\mall.sql |
  docker compose exec -T mysql mysql -uroot -p"$pw" mall

# 2) 商品评价表结构调整
Get-Content -Raw .\mall-master\document\sql\alter_pms_comment_20260915.sql |
  docker compose exec -T mysql mysql -uroot -p"$pw" mall

# 3) 后台评价管理权限数据
Get-Content -Raw .\mall-master\document\sql\comment-admin-permission.sql |
  docker compose exec -T mysql mysql -uroot -p"$pw" mall
```

约束：

- 只针对新的本地 MySQL 数据卷；
- **执行前必须先说明数据库、表、SQL、影响范围与回滚方式，并得到明确回复
  "确认执行这份 SQL" 后才能执行**；
- 不要挂载或执行 `.codebuddy/reports/` 下的优惠券历史订正 SQL；
- 不要对任何已有业务数据的库执行 `mall.sql`；
- 回滚方式：删除数据卷后重建（见第 12 节），或在导入前先做逻辑备份。

导入前先备份（可选）：

```powershell
docker compose exec -T mysql mysqldump -uroot -p"$pw" --databases mall |
  Out-File -Encoding utf8 .\mall-backup-$(Get-Date -Format yyyyMMddHHmmss).sql
```

> **本次返工没有执行上述任何一条 SQL**，数据库保持未初始化状态。

---

## 9. MinIO 使用：内部地址、公开地址与只读策略

### 9.1 两个地址的分工（重要）

容器内 MinIO SDK 与浏览器访问需要的是**两个不同地址**，混用会导致图片打不开：

| 变量 | 值（默认） | 用途 |
| --- | --- | --- |
| `MINIO_ENDPOINT` | `http://minio:9000`（Compose 固定注入） | MinIO SDK 连接地址，只在容器网络内使用 |
| `MINIO_PUBLIC_ENDPOINT` | `http://localhost:9000`（`.env` 可覆盖） | 后台上传接口**返回给前端的 URL 前缀**，必须是浏览器可解析的地址 |

- `MINIO_ENDPOINT` 使用 Docker 服务名 `minio`，**只能被容器解析**，浏览器与手机都不认识；
- `MINIO_PUBLIC_ENDPOINT` **不能**写成 `http://minio:9000`，否则返回给浏览器的 URL 依然无法访问；
- 后台 `MinioController` 上传时用 `MINIO_ENDPOINT` 连接 MinIO，返回 URL 时用
  `MINIO_PUBLIC_ENDPOINT`；未配置公开地址时自动回退到内部地址，且不会生成双斜杠；
- 只有 `mall-admin` 需要公开地址（`mall-portal` / `mall-search` 不上传文件），
  因此该变量只注入给 `mall-admin`；
- 环境变量名支持两种写法：`MINIO_PUBLIC_ENDPOINT`（本文档使用，推荐）与
  `MINIO_PUBLICENDPOINT`（Spring Boot 宽松绑定把 `minio.publicEndpoint` 折叠后的形式）。
  `MinioController` 用 `${minio.publicEndpoint:${MINIO_PUBLIC_ENDPOINT:}}` 同时兼容两者，
  已用本地测试验证（见 13 节）。

### 9.2 bucket 策略：匿名只读下载

`minio-init` 在基础设施启动时完成两件事（执行完即退出）：

```bash
mc mb --ignore-existing "mallminio/$MINIO_BUCKET_NAME"
mc anonymous set download "mallminio/$MINIO_BUCKET_NAME"
```

- `mc anonymous set download` 等价于只授予 `s3:GetObject`（匿名**只读下载**）；
- **不开放**匿名上传、覆盖、删除或列举权限（不使用 `upload`、`public`）；
- 后台 `MinioController` 原先只在 bucket 不存在时才设置只读策略，预创建的 bucket 会保持私有，
  现在由 `minio-init` 统一处理，两侧策略一致；
- `minio-init` 是 bucket 与只读策略的**唯一负责方**：`mall-admin` 通过
  `depends_on: minio-init: condition: service_completed_successfully` 等待其成功退出后才启动，
  因此应用运行的整个生命周期内策略都已生效；`minio-init` 失败时 `mall-admin` 不会被创建；
- 上传与删除仍然需要 `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` 凭据。

> 这是**公开读取**策略：知道对象 URL 的人都可以下载该 bucket 内的文件。
> 本地开发可接受，请勿在公网或不可信网络使用同一套配置。

### 9.3 访问地址与手动检查

- 控制台：`http://localhost:9001`，使用 `.env` 中的 `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` 登录；
- API：`http://localhost:9000`；
- bucket `mall` 由 `minio-init` 自动创建（已存在则跳过）。

手动重跑初始化：

```powershell
docker compose run --rm minio-init
```

用 `mc` 检查（`docker compose run` 是临时容器，需先在该容器内创建 alias）：

```powershell
docker compose --env-file .env run --rm --no-deps minio-init `
  'mc alias set localminio http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" > /dev/null && mc ls --recursive --json localminio/mall && mc stat localminio/mall && mc anonymous get localminio/mall'
```

### 9.4 上传后图片在浏览器打不开的排查

| 现象 | 原因与处理 |
| --- | --- |
| 返回 URL 是 `http://minio:9000/...` | `MINIO_PUBLIC_ENDPOINT` 未配置或写成了 Docker 服务名，见 9.1 |
| 本机浏览器打不开，手机可以 / 反之 | 公开地址与访问端不在同一网络，见 9.5 |
| 返回 403 | bucket 策略未生效，重跑 `docker compose run --rm minio-init` 再用 `mc anonymous get` 确认 |
| 返回 404 | `MINIO_PUBLIC_ENDPOINT` 端口与 `MINIO_API_PORT` 不一致 |

### 9.5 手机 / 微信开发者工具联调

手机访问需要用宿主机局域网地址，同时放开 MinIO 的绑定地址：

```text
# .env
MINIO_PUBLIC_ENDPOINT=http://192.168.1.20:9000
MINIO_BIND_ADDR=0.0.0.0
NGINX_BIND_ADDR=0.0.0.0
```

然后用**带 Java 应用**的命令启动（只启动 `edge` 不会产生可用的 API）：

```powershell
docker compose --profile app --profile edge up -d
```

- `192.168.1.20` 要换成宿主机在局域网中的真实 IP（`ipconfig` 查看）；
- 只改 `MINIO_PUBLIC_ENDPOINT` 而不改 `MINIO_BIND_ADDR` 时，手机仍然连不上 9000 端口；
- `MINIO_BIND_ADDR=0.0.0.0` 只影响 MinIO，**不要**照此把 Redis、MongoDB、Elasticsearch
  暴露到局域网（见 1.2 安全警告）。

---

## 10. Elasticsearch 商品索引初始化（手工执行）

商品索引 `pms` **不会自动创建**，需要在 `mall-search` 健康之后手工调用现有接口
（`POST /esProduct/importAll`，不需要 Token）：

```powershell
# 1) 确认 mall-search 已健康
docker compose ps mall-search
curl.exe http://localhost:8081/actuator/health

# 2) 导入索引
curl.exe -X POST http://localhost:8081/esProduct/importAll

# 3) 检查索引
curl.exe http://localhost:9200/_cat/indices?v
curl.exe http://localhost:9200/pms/_count
```

补充：

- 只导入 `delete_status = 0 and publish_status = 1` 的在售商品；
- 没有执行 importAll 之前搜索结果为空，这是预期行为；
- 后台商品新增 / 编辑 / 上下架 / 删除会通过事件同步到 ES，
  同步接口 `/esProduct/sync/**` 需要请求头 `X-Internal-Token`，
  值来自 `MALL_SEARCH_INTERNAL_TOKEN`，mall-admin 与 mall-search 必须一致；
- 搜索验证（1-based 页码）：

```powershell
curl.exe "http://localhost:8085/product/search?keyword=%E5%B0%8F%E7%B1%B3&pageNum=1&pageSize=5"
```

- 更完整的搜索链路说明见 `mall-master/document/es-search/elasticsearch-runbook.md`。

---

## 11. 停止服务与数据卷清理

### 11.1 停止（保留数据卷，推荐）

```powershell
docker compose down
docker compose --profile app --profile edge --profile observability down

# 只停止不删除容器
docker compose stop
docker compose --profile app --profile edge --profile observability stop
```

`down` 会删除容器与网络，但**不会**删除命名数据卷，数据仍然保留。

### 11.2 清理数据卷（危险）

```powershell
# 查看本项目的数据卷
docker volume ls | Select-String mall-local

# 删除全部数据卷：所有 MySQL / Redis / RabbitMQ / MongoDB / ES / MinIO 数据不可恢复
docker compose down -v

# 只删除某一个数据卷
docker volume rm mall-local_es-data
```

风险与恢复说明：

- **删除数据卷等于删除全部本地数据，没有快照、没有回收站，不可恢复**；
- 执行前确认：是否需要保留数据库、上传的图片、ES 索引；
- 恢复方式只有重新初始化：重建数据卷 → 按第 8 节导入 SQL（需重新确认）→
  按第 10 节重建 ES 索引 → 重新上传 MinIO 文件；
- 需要保留数据时先做逻辑备份（`mysqldump`、`mongodump`、MinIO `mc mirror`）。

---

## 12. 故障排查

### 12.1 端口冲突

现象：`Error starting userland proxy: listen tcp4 127.0.0.1:3306: bind: address already in use`

处理：

1. 用 1.1 节的命令找出占用进程；
2. 停止本机同名服务（如本地已安装的 MySQL / Redis），或修改 `.env` 中对应端口变量
   （`MYSQL_PORT`、`REDIS_PORT`、`ADMIN_PORT` 等）；
3. 修改后重新 `docker compose up -d`。

### 12.2 健康检查失败

现象：`docker compose ps` 中状态长期为 `unhealthy` 或一直 `starting`

处理：

```powershell
docker compose ps
docker inspect --format "{{json .State.Health}}" mall-local-mysql-1
docker compose logs --tail=200 mysql
```

常见原因：

| 服务 | 原因 |
| --- | --- |
| mysql | root 密码与 `.env` 不一致（健康检查用 `MYSQL_ROOT_PASSWORD` 登录）；首次初始化未完成（等待 `start_period` 60 秒） |
| rabbitmq | 首次启动生成数据较慢（等待 60 秒）；`RABBITMQ_DEFAULT_VHOST` 格式错误（必须以 `/` 开头） |
| mongo | `mongosh` 不可用（镜像版本过低，请使用 `mongo:7` 及以上） |
| elasticsearch | 内存不足或 `vm.max_map_count` 过低，见 12.3 |
| minio | root 用户或密码长度不足（密码至少 8 位，见 3.2） |
| 应用 | 数据库连接失败、ES 未就绪、端口被占用；若容器内缺少 `curl`，说明基础镜像被替换成了非 Debian 系镜像，见 2.2 |

启动顺序已通过 `depends_on` 串联，以下都是预期行为，不是"卡住"：

- `mall-search` 未健康时，`mall-admin` / `mall-portal` 不会启动（`service_healthy`）；
- `minio-init` 未成功退出时，`mall-admin` 不会启动（`service_completed_successfully`）。

`minio-init` 的排查命令：

```powershell
docker compose ps -a minio-init          # 关注 Exited 的退出码，0 为成功
docker compose logs --tail=200 minio-init
```

### 12.3 Elasticsearch 内存不足

现象：ES 容器反复重启，日志出现 `OutOfMemoryError` 或
`max virtual memory areas vm.max_map_count [65530] is too low`

处理：

1. 调小堆内存，在 `.env` 中设置 `ES_JAVA_OPTS=-Xms512m -Xmx512m`，然后
   `docker compose up -d elasticsearch`；
2. 增大 Docker Desktop 的内存分配（Settings → Resources → Memory，建议 ≥ 8 GB）；
3. Windows / WSL2 下调整 `vm.max_map_count`：

```powershell
wsl -d docker-desktop sysctl -w vm.max_map_count=262144
```

4. 只做开发调试时可以暂时不启动 ES（但 mall-search 会因依赖无法启动，
   需要改用开发模式单独运行 Java 服务）。

### 12.4 数据库初始化失败

现象：导入 SQL 报错，或应用启动报 `Failed to determine suitable jdbc url` /
`Table 'mall.xxx' doesn't exist`

处理：

1. 确认数据卷是新建的空卷：`docker volume ls | Select-String mall-local`；
2. 确认库已创建：`docker compose exec mysql mysql -uroot -p"$pw" -e "SHOW DATABASES;"`；
3. 确认导入顺序：`mall.sql` → `alter_pms_comment_20260915.sql` → `comment-admin-permission.sql`；
4. 确认没有对已有业务数据的库执行 `mall.sql`；
5. 应用侧确认数据源环境变量正确，并设置 `SPRING_DATASOURCE_DRUID_MAX_WAIT=10000`
   避免连接不上时无限等待；
6. MySQL 5.7 与 `mysql-connector-j 9.3.0` 组合如遇认证插件问题，
   可在 `.env` 中把 `MYSQL_IMAGE` 覆盖为 `mysql:8.0` 后重建数据卷。

### 12.5 镜像拉取与构建失败

| 现象 | 处理 |
| --- | --- |
| `pull access denied` / `request canceled` / 连接超时 | Docker Hub 不可达，见 2.3（加速器 / 镜像变量 / `docker image load`） |
| 构建阶段 `apt-get: not found` | 运行阶段基础镜像被换成了非 Debian 系镜像，见 2.2，恢复为 `eclipse-temurin:17-jre` |
| 构建阶段 Maven 依赖下载失败 | Maven 仓库不可达，见 2.3 末尾 |
| `docker compose build` 报找不到 Dockerfile 或上下文过大 | 确认在仓库根目录执行；确认根目录 `.dockerignore` 存在并排除了 `node_modules` / `target` / `.git` |
| 首次构建很慢 | 需要下载全部 Maven 依赖，属正常现象；后续构建会复用缓存 |

### 12.6 其他

| 现象 | 处理 |
| --- | --- |
| `minio-init` 显示 `Exited (0)` | 一次性任务成功执行完即退出，属正常；用 `docker compose logs minio-init` 确认 bucket 与只读策略均已完成 |
| `minio-init` 显示 `Exited (1)`（或非 0）且 `mall-admin` 未启动 | `minio-init` 失败会阻止 `mall-admin` 启动（`depends_on: service_completed_successfully`），这是预期保护行为；用 `docker compose logs minio-init` 定位原因（凭据、bucket 名、网络），修复后重新 `docker compose up -d` |
| Nginx 页面 404 | 前端产物未构建，或路径与 `ADMIN_WEB_DIST_DIR` / `H5_WEB_DIST_DIR` 不一致 |
| `/admin-api` 返回 502 | `app` profile 未启动，或 mall-admin 未健康 |
| 前端请求打到 8080/8085 但走的是 Nginx | 构建时未注入 `VITE_BASE_SERVER_URL=/admin-api` 与 `VITE_API_BASE_URL=/portal-api`，见 4.5 |
| 搜索结果为空 | 未执行 `/esProduct/importAll`，见第 10 节 |

---

## 13. 本次交付的验证边界

已完成（配置级）：

- `docker compose --env-file .env.example config --quiet` 通过；
- `docker compose --env-file .env.example --profile app --profile edge --profile observability config --quiet` 通过；
- 解析后的配置中共有 **12 个服务带 ports、17 条发布端口**，默认 `host_ip` 全部为 `127.0.0.1`；
- 显式设置 `MINIO_BIND_ADDR=0.0.0.0` 与 `NGINX_BIND_ADDR=0.0.0.0` 后，只有
  MinIO 9000 / 9001 与 Nginx 8088 变成 `0.0.0.0`，其余 14 条仍为 `127.0.0.1`；
- `mall-admin` 解析后的环境变量中 `MINIO_ENDPOINT=http://minio:9000`、
  `MINIO_PUBLIC_ENDPOINT=http://localhost:9000`，两者已分离；
- `check-env.ps1` / `check-env.sh` 三种路径行为一致：占位值 → 1，已替换值 → 0，
  重复定义必检变量 → 1；两个脚本均不输出变量值；Bash 版对带 BOM 的 `.env` 仍可正确解析；
- `MinioControllerUrlTest`：6 个用例全部通过（`mvn -o -pl mall-admin -Dtest=MinioControllerUrlTest test`，BUILD SUCCESS）；
- 公开地址占位符 `${minio.publicEndpoint:${MINIO_PUBLIC_ENDPOINT:}}` 在
  仅 `MINIO_PUBLIC_ENDPOINT`、仅 `MINIO_PUBLICENDPOINT`、两者都有、两者都没有
  四种场景下均已实测解析正确；
- 静态核对：`minio-init` 使用 `mc anonymous set download`（无 `upload` / `public`），
  Java 侧策略为 `Action("s3:GetObject")`；
- 依赖断言（对 `docker compose config --format json` 的解析结果做校验）：
  `mall-admin.depends_on["minio-init"].condition == service_completed_successfully`；
  `mall-admin.depends_on.minio.condition == service_healthy`；
  `mall-admin.depends_on["mall-search"].condition == service_healthy`；
  `minio-init.depends_on.minio.condition == service_healthy`；
  `minio-init.restart == "no"`；
  对全部 `depends_on` 边做深度优先遍历，**未发现依赖环**；
- `document/docker/Dockerfile.app` 中不含任何递归删除命令；
- `git diff --check` 无空白错误。

动态验收边界：本文件不替代目标机器的运行报告。镜像拉取、应用构建、健康检查、`depends_on`
顺序、Nginx 代理、ES 索引导入和数据库初始化，都必须在实际目标环境中按本文档逐项记录结果。

---

## 14. 容器化带来的配置影响小结

- 未修改任何前端业务代码、数据库表结构和现有 API；
- Java 侧唯一的改动是 `mall-admin` 的 `MinioController`：新增可选配置
  `minio.publicEndpoint`（环境变量 `MINIO_PUBLIC_ENDPOINT`），
  上传仍用 `minio.endpoint` 连接 MinIO，**只把返回给前端的 URL 前缀改为公开地址**，
  未配置时回退内部地址；接口路径 `/minio/upload`、请求参数与响应结构、删除接口行为均未改动；
  同时新增 `MinioControllerUrlTest` 覆盖该行为；
- 未修改 `application-dev.yml` / `application-prod.yml`，全部连接信息通过
  Spring Boot 环境变量优先级覆盖（`SERVER_PORT`、`SPRING_DATASOURCE_*`、
  `SPRING_DATA_REDIS_*`、`SPRING_RABBITMQ_*`、`SPRING_DATA_MONGODB_*`、
  `SPRING_ELASTICSEARCH_URIS`、`MINIO_*`、`MALL_SEARCH_BASE_URL`、
  `MALL_SEARCH_INTERNAL_TOKEN`、`LOGSTASH_*`）；
- 新增文件只有编排配置、校验脚本与文档，历史配置
  `mall-master/document/docker/docker-compose-*.yml` 保持原样，
  未被引用也未被修改。

# Mall 项目新对话交接摘要

> 更新时间：2026-09-28
>
> 本文件只用于新对话恢复项目上下文，不替代 `AGENTS.md`、正式计划、代码审查和实际验证。

## 当前基线

- 项目根目录：`F:\code\mall`
- 当前分支：`main`
- **2026-09-28 实测基线（当前有效）**：主仓库 `main` 与 `origin/main` **一致**，均为 `ed5140aeee81d2668300964093c30ff0ad616d89`（`记录商品导购智能体推送状态`）；`main` 工作区干净，**无待推送提交**。本条**取代**此前 2026-09-27 的 `56810c2` 基线记录。
- 历史基线（已被上条取代，仅作记录）：本文件曾记录 `main` = `origin/main` = `56810c2805e099ac45f0422a881096dd532de760`（`Merge branch 'codex/deepseek-base-url'`）；更早的 2026-09-25 记录（本地 `main`=`b75533b`、`origin/main`=`b8c46b1`、领先 5 个提交）一并保留为历史。
- Java agent 功能工作树 `codex/java-agent-migration`：**已合并入 `main` 历史**（合并提交 `18b5408`，功能提交 `96bafb7`、`5182cea` 均在 `main` 历史中），该 worktree **工作区干净**。旧记录「HEAD `f7b822b`，仍有预期未提交文件、尚未合并」**已过期**，保留为历史。
- **工作树清理候选（实际删除必须由用户手动执行，项目规则禁止批量目录删除）**：`codex/deepseek-base-url`（`73202de`）与 `codex/java-agent-migration`（`5182cea`）worktree 均干净、分支提交已在 `main` 历史中，可列为待清理。
- **待用户决定（禁止清理或丢弃）**：`C:\Users\qiufengm\.codex\worktrees\agent-live-acceptance\mall`（分支 `codex/agent-live-acceptance`，HEAD `56810c2`）含 **6 个未提交的 Python 源/测试改动**，且**无对应这些改动的报告**；主 Agent 已只读审查，详见下文 2026-09-28 节。
- 已合并的 Elasticsearch 可靠性工作树、`feature/es-reliability-hardening` 分支、旧 ES `stash@{0}` 和 `feature/es-legacy-hardening` 均已清理；当前剩余 `stash@{0}`（`On main: 集成 Docker 本地运行修复前的主工作区备份`）**应保留**。商品导购实现工作树已清理。
- `.env`、本地数据库快照、Docker 数据卷和运行时凭据均不纳入 Git。
- 主工作区应保持干净；运行时 `.env`、数据库快照、Docker 数据卷和凭据不纳入 Git。

## 已完成模块

1. MinIO 图片迁移与本地上传链路：数据库图片地址、MinIO bucket、公开地址和匿名只读策略已完成；历史图片是否完整取决于实际对象是否存在。
2. 商品评价：真实评价、查看全部、我的评价、后台审核/回复、登录回跳、分页并发和刷新动画已完成。
3. Elasticsearch：搜索闭环、1-based 分页、深分页保护、商品同步、内部 Token、批量上限和运行文档已完成。
   测试基线已隔离为默认可重复测试；商品状态事务边界、提交后同步、全量导入陈旧文档清理、Redis 跨实例锁、bulk 失败项重试和受控 MySQL 降级搜索已完成。
4. 订单/支付/库存一致性：状态条件更新、支付归属/金额校验、库存保护、优惠券绑定和历史数据订正已完成。
5. Docker 本地全栈环境：MySQL、Redis、RabbitMQ、MongoDB、Elasticsearch、MinIO、三个 Java 服务和 Nginx 已编排；MinIO 使用固定 Quay 镜像，应用构建跳过旧 Fabric8 Docker 插件，Nginx 代理与健康检查已修复。
6. 移动端 MinIO 地址适配：统一图片 URL 转换、富文本图片转换、H5/微信开发者工具/真机环境示例和局域网联调文档已合并；H5 经 Nginx 的 `/static/**` 映射、通知图片文件名兼容和真实 MinIO 对象访问均已验证；夸克手机最终复测正常，微信开发者工具图片验收也已完成。正式 HTTPS 合法域名仍留待后续环境配置。
7. Python 商品导购智能体：独立 FastAPI 服务、OpenAI 兼容客户端、Redis 短期会话、只读商品/库存/优惠券工具、安全围栏、移动端聊天页、Docker Compose/Nginx 接入和 Stub 离线评测已合并；游客搜索/详情/库存的容器化只读链路已验证。真实模型、共享 Nginx `/agent-api/` 运行时代理和微信真机仍待环境具备后复测。
   〔**2026-09-28 更新（当前有效）**：真实模型主栈 + 会员优惠券验收**已完成**（见本文「2026-09-28 主栈真实模型与会员优惠券验收（当前有效结论）」）；**微信真机 / 正式域名仍未完成**。本条之下 2026-09-25 / 2026-09-27 各段均为**历史时点**记录，其中「**尚未合并到 `main`**」「真实会员 Token / 会员券未测」等状态**已被取代**——Java agent 已由合并提交 `18b5408` 并入 `main`。〕
   **2026-09-25 更新（`java-agent-migration` 工作树，尚未合并到 `main`）**：商品导购智能体已用 Java 17 的 `mall-master/mall-agent` 重写。该工作树的 `docker-compose.yml` 已把 `mall-shopping-agent` 的构建切换为仓库根 context `.` + `document/docker/Dockerfile.app`（`MODULE=mall-agent`、`JAR_FILE=mall-agent-1.0-SNAPSHOT.jar`、`APP_PORT=8086`，运行镜像 `eclipse-temurin:17-jre`），healthcheck 改为镜像内 `curl -fsS http://127.0.0.1:8086/health/live`，并注入 `AgentProperties` 全部 22 个 `MALL_AGENT_*` 白名单变量（外部凭据只有 `MALL_AGENT_OPENAI_API_KEY`，允许为空）〔**2026-09-27 更正**：安全加固新增 `MALL_AGENT_TRUSTED_PROXY_IP`，Compose 白名单**现为 23 个** `MALL_AGENT_*` 变量 + `TZ`；上列 22 个为该轮迁移初期清单，保留为历史〕。对外边界不变：service key `mall-shopping-agent`、`app` profile、容器端口 `8086`、宿主机绑定 `127.0.0.1`、`depends_on` 为 Redis 与健康的 `mall-portal`、Nginx `/agent-api/` → `mall-shopping-agent:8086`。Python `mall-shopping-agent/` 源码、测试与 `document/docker/Dockerfile.agent` 保留为参考，Compose 不再构建它们。Java 镜像构建与容器运行验收**未执行**（本机 Docker daemon 不可用），回滚方式见 `document/docker/local-startup.md` 第 4.7 节。

   **2026-09-27 更新（`java-agent-migration` 工作树，仍未合并到 `main`）**：本轮完成 Java agent 的 **E2E 验收**，Task 12 Step 4 勾选为 `[x]`。Docker daemon **29.5.3 已恢复**；本轮开始前与清理后原 Compose **13 个服务均 healthy**，原 Compose `mall-shopping-agent` 容器 ID 短前缀 **`2077801e5c74`**、镜像 SHA `sha256:aa9fb0606788935444270bad4ead7708659003cf16c79802fc40fb48decd745e`（标签指向本 Java worktree，**本轮未被替换**）；直连与主 Nginx `8088` 的 `/health/live`、`/health/ready` 均 **200**，主 Nginx `nginx -t` 成功。`mvn -o -f mall-master/pom.xml -pl mall-agent -am -DskipTests package` **exit 0**；尝试构建独立 tag `mall-local/mall-shopping-agent:e2e-20260927` 时 `Dockerfile.app` 的 Maven 步骤约 3 分钟无新输出，由主 Agent **Ctrl+C 中止**（**不是** Maven 编译失败，**未**查明网络原因）。随后以 `eclipse-temurin:17-jre` 临时容器只读挂载当前 JAR、独立临时 Redis（DB15、无持久化）与临时 Nginx（只读挂载项目 `document/docker/nginx/conf.d/default.conf`）完成 E2E：stub 模式下 `/agent-api/agent/chat` 对真实门户公开搜索 **HTTP 200**、**5 张门户商品卡**（字段均非空），session GET 2 条消息 / 5 张卡、DELETE `deleted=true`，游客个人券问题返回 `requiresLogin=true` 且无卡片；经 env 白名单另起临时 live one-off 容器加做一条「推荐一款手机」真实模型公开查询 **HTTP 200**、答案非空。**实测路径是临时隔离 app/Nginx，不是主 Nginx chat 调用**。全量测试 `mvn -o -q -f mall-master/pom.xml test`（`MALL_AGENT_TEST_REDIS_URL` 指向独立临时 Redis）Surefire **72 份报告合计 Tests=1243 / failures=0 / errors=0 / skipped=0**，**8 个真实 Redis 门控用例确实运行**；测试 Redis DB15 结束 `dbsize=0`、容器移除；`git diff --check` exit 0。临时容器/网络与测试端口 `18086`/`18087`/`18088`/`18089`、Redis `16380` 均已清理（现无监听），E2E **只写被删除的隔离 Redis**。**遗留与加固后状态**：上述公开聊天 E2E 与单条真实模型公开查询是**最新安全加固前**的历史运行证据（2026-09-27 上午，路径为**临时隔离 app/Nginx**，非主栈 chat）。**最初识别的 4 项部署边界 P2 均已在当前 Java 工作树代码中处理（代码/静态与单测层；最新运行时仍待验收）**：（1）`Dockerfile.app` runtime 以固定 UID/GID `10001:10001`（`USER mallapp`）**非 root** 运行；（2）Compose 中 Agent 只接入 `agent-proxy-net` 与 `agent-backend-net`，**不再接入 `mall-net`**，看不到数据库等无关服务；（3）`ClientIpResolver` 仅在请求对端 `remoteAddr` 与 `MALL_AGENT_TRUSTED_PROXY_IP`（**默认空 = 不信任任何代理**）按字节相等时才采信 `X-Real-IP`，且不读 `X-Forwarded-For`；（4）CORS 默认空并拒绝通配 `*`（fail-closed，`docker-compose.yml`/`.env.example` 去掉了 `:-*` 兜底）。**这些结论均为代码/静态层，最新镜像/网络/Nginx 源 IP/Redis 门控/主栈 chat 的实际运行行为尚未复验。** 当前最终静态/测试证据见 `.codebuddy/reports/java-agent-hardening-review-report.md`：全 reactor 9 模块 **984 tests / 0 failure / 0 errors / 8 skipped**（8 项 Redis 集成测试在无测试 Redis 时跳过；〔**2026-09-27 更正**：此「984」实为**仅 `mall-agent` 单模块**的用例数被误写为全 reactor 总数；接独立临时 Redis 后最新全 reactor 总数为 **1320**，见下方「独立运行验收轮」〕）、Python Compose 静态测试 **152 passed**〔**2026-09-27 更新**：新增 3 条 Nginx 日志脱敏用例后为 **155 passed**〕、默认与全 profile `compose config` **exit 0**、`git diff --check` **exit 0**。**用户个人券真实会员 Token 未测**、**微信真机未测**；**Task 12 Step 4 保持 `[x]`（依据为临时隔离 app/Nginx 路径）**；**Task 13 仍未完成**（不因静态检查通过而标完成）；**Java 分支仍未合并**。

   **2026-09-27 更新（独立运行验收轮，`java-agent-migration` 工作树，仍未合并到 `main`）**：本轮对**最新安全加固后的源码**完成**独立运行时验收**（临时隔离环境，**不动主栈服务**），并纠正全量测试口径。要点：

   - **最新镜像（专用临时 tag，验收后已删除）**：以正式 `document/docker/Dockerfile.app` 执行 `docker build --network=host` **exit 0 / Maven BUILD SUCCESS**，镜像 ID **`sha256:284512f81cdcbc6d192e893194d94c9894ae256861649d176b32ea09f903e584`**。**只在独立临时网络 / 独立临时容器运行**，**未重建、未替换主 Compose 的 13 个服务**（清理后复查主 13 容器仍 healthy）。
   - **非 root 运行（运行层实测）**：新 Agent 镜像 `Config.User=mallapp`，容器内 `id` 输出 **uid/gid 10001**——非 root 已在**真实镜像运行层**生效，不再是仅静态断言。
   - **分网拓扑（运行层实测）**：临时 Nginx 位于代理网 `.2`，Agent 位于代理网 `.3` 并接入后端网，Redis 在后端网。
   - **网络边界的准确口径（重要，不得外推）**：本轮**只是分网隔离（network segmentation）**，**不是严格出站隔离**。实证：Agent 容器 DNS **无法解析**主栈 ES 容器名；但经 Docker Desktop `host.docker.internal` 可**真实连通**主栈宿主机发布端口——**TCP `13306` 可连**、**HTTP 可达 Mongo `27018` 与 ES `9200`**。共享的**无认证 Redis 仍与主商城共用同一实例**，Agent 拥有同实例连接权限。**用户已明确决定「先按分网隔离，记录风险并继续」**；因此**不得**写成「看不到数据库」或「网络层完全隔离」。
   - **接口与安全实测（最新镜像 + 临时 Nginx + 独立无持久化 Redis）**：`GET /agent-api/health/ready` **200**；stub 游客「推荐一款手机」`POST /agent-api/agent/chat` **200** 且 **5 张真实门户商品卡**；`GET /agent/session/{sessionId}` 返回 **2 条消息**；`DELETE` 返回 `deleted=true`；游客个人券返回 `requiresLogin=true`；**直接来源伪造不同 `X-Real-IP` 且 IP 配额为 1 时第二次请求 `429`**；**未受信任 Origin 的 `OPTIONS` `403`**。
   - **Nginx `/agent-api/` 日志脱敏（运行层实测）**：旧配置实测会把**随机 `sessionId`** 写入 `mall-access.log`；新配置临时 `nginx -t` **exit 0** 并 `reload` 后，`GET /agent/session/<sessionId>?<query>` **HTTP 200**，新写入的日志**不含 `sessionId` 与 query**，但**保留 `method` / `status` / `time`**。详见 `.codebuddy/reports/java-agent-nginx-log-redaction-report.md`。
   - **清理与零写**：临时 **4 个容器、2 个网络、专用镜像 tag** 已逐一删除并复查**零残留**，主栈 **13 容器仍 healthy**；本轮**无任何 SQL**、**无主 Redis 写**（只写被删除的隔离 Redis），**未 commit / 未 merge / 未 push** Java 分支。
   - **本轮未跑（不得视为已完成）**：最新镜像下**未跑真实模型**、**未跑真实会员 Token / 会员券**、**未跑微信真机**、**未跑主栈 Nginx `8088` 的 chat**、**未跑可信代理双客户端正路径**，也**未启动新版非 root 的 `mall-admin` / `mall-search` / `mall-portal` 镜像**。
   - **测试计数更正（重要）**：全量 `mvn -o -q -f mall-master/pom.xml test` 接**独立临时 Redis**，各模块 **demo 2 / admin 63 / search 97 / portal 174 / agent 984**，**总计 1320 tests / failures 0 / errors 0 / skipped 0**。**此前文档写的「全 reactor 984」是把「仅 agent 模块」的用例数误当成全 reactor 总数，特此明确纠正**；`agent 984` 只是其中一个模块，**全 reactor 总数为 1320**。历史各轮计数（1243 / 1229 / 1189 等）保留其执行时点口径，**不得与 1320 相加**。
   - **静态与配置证据（本轮实测）**：Python Compose 静态 `pytest`（含新增 3 条 Nginx 日志脱敏用例）**155 passed**；Compose 默认与全 profile `config --quiet` **均 exit 0**；`git diff --check` **exit 0**。
   - **剩余风险（列为后续评估，未擅自宣称已修复）**：（1）`session` 的 `GET` / `DELETE` 已在身份解析前按可信客户端 IP 消耗一次现有 IP 桶；不消耗会话桶，跨实例互斥仍仅为进程内 guard；（2）`/health/ready` **匿名可访问**，可被用于放大读取依赖状态；（3）Nginx `/agent-api/` 的 access log 保留脱敏诊断字段，location 级 error log 写入 `/dev/null`（会丢弃该路由 Nginx 错误诊断），其它 location 继承 server 日志配置。
   - **用户明确结论**：真实会员券 / 微信真机**暂缓**，**Task 13 保持未完成**；正式 HTTPS / 合法域名**继续暂缓**。

   - **2026-09-27 最新更正（收窄共享 `Dockerfile.app` 运行身份后，仍未合并）**：用户明确批准收窄共享 Dockerfile 的运行身份——runtime 新增 `ARG APP_RUN_USER=root` 与 `ARG APP_HOME=/root`；`mall-admin` / `mall-search` / `mall-portal` **不传参，保持迁移前的 root 身份与 `HOME=/root`**（**不得**把这「旧三服务」写成非 root）；只有 `mall-shopping-agent` 在 Compose `build.args` 传 `APP_RUN_USER=mallapp` + `APP_HOME=/app`，并保留 `user: "10001:10001"`。故 Agent 运行身份现为**镜像 build args + Compose user 双重约束**，不再是「仅由 Compose 覆盖」。静态证据：TDD 红绿与 156 条静态用例、再加 HOME 返工后 **158** 条静态用例见 `.codebuddy/reports/java-agent-only-nonroot-report.md`；主 Agent 实测全 `pytest` **158 passed**、Compose 默认与全 profile `config --quiet` **exit 0**、`git diff --check` **exit 0**。**重要（不得误述）**：旧镜像 `sha256:284512f8…` 是本轮 ARG/HOME 变更**之前**构建，**不能**称其证明**当前最终 `Dockerfile.app` 镜像**生效；最终镜像新构建**两次均未成功**（第一次 `apt` 阶段 Docker BuildKit EOF；第二次 Docker Desktop Linux Engine `_ping` **500**），**尚未生成新镜像**，其后 daemon 只读 `docker version` / `docker ps` 卡住已中止等待。因此当前最新 `Dockerfile.app` **仅静态/测试层通过**，真实镜像 `User` / `HOME` 待 Docker 恢复后重验；旧临时 E2E（隔离环境）仍是**旧版本**的有效运行证据，Java 业务代码未变。网络风险口径不变（**仅分网隔离**、非严格出站隔离；`host.docker.internal` 可达主栈已发布 ES/Mongo/MySQL，共享无认证 Redis 同实例），用户已接受先记风险。主 Agent 正请求用户决定是否允许**重启 Docker Desktop**（**重启会影响主 Compose，未批准前不得自行重启**）。

   - **2026-09-27 最新更正（Docker Desktop 重启后：最终镜像构建复验与聊天未完成，仍未合并）**（追加，不改上文历史）：用户**明确允许重启 Docker Desktop**。**第一次 `docker desktop restart` exit 0**，Docker Linux Engine **恢复**。
     - **最终 `Dockerfile.app` 显式传参镜像（Agent 身份）**：以**当前最终** `Dockerfile.app` 在**独立标签 `mall-local/mall-agent-user-scope-accept:20260927`** 构建 **exit 0**，镜像 **`sha256:77907c5d625af1c2230ca2c3b45733f62568cf4b2455edf29ee14b09e7deff34`**；**`Config.User=mallapp`、`HOME=/app`**，`docker run` 容器内 `id` = **`10001:10001`**，`/app/app.jar` **可读**、`/app` **可写**。→ **取代**「最终镜像尚未产出、`User`/`HOME` 待复验」的当轮当时结论。
     - **同一最终 `Dockerfile.app` 默认镜像（不传 `APP_RUN_USER`/`APP_HOME`）**：独立构建 **exit 0**，镜像 **`sha256:01af63388dc31fd4692ddf6ba89acb88bee5b98885b96bddda28dea9ad7d071f`**；**`Config.User=root`**，容器内 `id` = **`0:0`**、`HOME=/root`。→ 证明**默认身份确为 root / `HOME=/root`**，Agent 非 root 身份**来自显式传参**。
     - **新 Agent 就绪（运行层实测）**：启动**临时独立 Redis + Agent 容器**，新 Agent **直连** `GET /health/live` **200**、`GET /health/ready` **200**。
     - **聊天未通过（重要）**：本轮**聊天请求未获得响应**，**最终镜像 `/agent/chat` / `/agent-api/agent/chat` 不计为本轮 E2E 通过**；**旧版镜像的隔离聊天 E2E 不能替代本轮最终镜像的聊天验收**。
     - **Docker API 再次卡住 + 资源证据**：其后 Docker API **再次卡住**；同期 **WSL `free`** 显示**总 7.8G / 已用 6.7G / available 约 354.9M / swap 4.0G 用满**。**口径谨慎**：本轮**仅将资源耗尽记为「高度相关证据」，不绝对断言唯一根因**；聊天未达响应原因**未查明**。
     - **第二次重启与精确清理**：**第二次 `docker desktop restart` exit 0**；随后**精确删除本轮 2 个临时容器、1 个空临时网络、2 个独立镜像标签**，**未清理**主栈容器 / 网络 / 卷；**主栈恢复中，其当前健康状态由主 Agent 另行复核，本记录轮不作断言**。
     - **勾选与边界**：**Task 12 Step 4 保持 `[x]`**；**Task 13 Step 2 保持 `[ ]`、Task 13 仍未完成**（**镜像身份 / HOME 已验证、就绪已验证，但最终镜像聊天未完成**）。本轮**未执行任何 Git 写操作**（无 commit / push / merge / reset），未执行 SQL，未改代码 / 配置 / 测试；`codex/java-agent-migration` HEAD 仍为 `f7b822b`，**未提交、未合并**；`main` = `origin/main` = `56810c28…`，`main` 工作区干净。详见 `.codebuddy/reports/java-agent-isolated-runtime-acceptance-report.md` 第 13 节。

   - **2026-09-27 最终更正（最终独立镜像隔离 Nginx 路径 E2E 完成；当前有效结论，追加，不改上文历史）**：用户**授权暂时停止主栈 Kibana / Logstash**，释放约 **0.8 GiB**；在资源上限内以**独立 `Dockerfile.app` 镜像**（标签 `mall-local/mall-agent-final-e2e:20260927`）构建**成功**（exit 0），使用**独立 Redis** 与**临时 Nginx**（`default.conf` 当前为**只读挂载**）完成**最终镜像隔离 Nginx 路径 E2E**：`/agent-api/health/live` 与 `/agent-api/health/ready` **200**，`chat` **200** 且 **5 张真实门户商品卡**，会话 `GET` **2 条消息**、`DELETE` **成功**，游客个人券 `requiresLogin=true` 且 **0 卡**，Nginx 访问日志**无 UUID / query**但有 **method / status**。**第一次 400 仅因测试请求漏必填 `sessionId`**，补齐后成功。临时**容器 / 网络 / 镜像标签均逐一删除**，Kibana / Logstash **已恢复**；主 Agent **第三次重启 Docker Desktop** 后有一次主 Compose **13/13 healthy** 快照，但**随后 Docker API 仍间歇卡住**，**不能宣称持续健康**。测试最新 `mvn` **full reactor 1320 / 0 failure / 0 errors / 8 skipped（无独立测试 Redis）**、Python Compose **158 passed**、全 profile `config` **exit 0**；只读审查最近部署文件**无代码阻断**，先前「最终镜像聊天未验收」**缺口已补**。**仍未验收**：**主栈 `8088` chat**、**真实会员券 / 微信真机**、**正式域名**；**Task 13 Step 2 保持 `[ ]`**。Java 分支**未 commit / merge / push**，`main` 与 `origin/main` 仍为 **`56810c2`**。→ **当前有效结论：最终镜像隔离 Nginx 路径 E2E 已通过；「最终镜像聊天未完成」只属历史第一次失败轮，不代表现状。**

## Docker 验证基线

> **历史日志说明**：本节按时间点记录历次运行 / 验证基线，**每一条只代表其执行时点**，不同轮的计数、勾选与基线口径**不得跨轮相加或混用**。本节末段 2026-09-27 各条的待办中「`main` = `origin/main` = `56810c2`」「Task 13 Step 2 保持 `[ ]`」「真实会员券 / 微信真机未验收」等状态，**均已被下方「2026-09-28 主栈真实模型与会员优惠券验收（当前有效结论）」取代**，以该节为准。

- 10 个常驻服务健康，`minio-init` 成功退出 `Exited (0)`。
- Nginx、三个 API 入口、MinIO 健康检查和 ES 检查均已返回成功；ES 集群为 green。
- 三个 Java 应用镜像已成功构建。
- Compose 默认配置和全 profile 配置校验均通过。
- 2026-09-20 重新启动全 profile 后完成只读冒烟：Admin 实际宿主机端口为 `.env` 中的 `ADMIN_PORT=18080`，直连与 Nginx 代理均返回 200/UP；门户和 ES 搜索均返回 `code=200`、`pageNum=1`、5 条结果；ES `pms` 实时 `_count` 为 20；MinIO 健康检查和已知对象读取均返回 200。
- 本次冒烟未执行 SQL、注册、下单、支付、同步/导入、上传或删除；历史对象是否完整仍需以实际对象清单为准。
- Elasticsearch 可靠性治理已完成并在本地 Compose 环境验证：Redis 锁、Logstash/Kibana 健康、六条写路径令牌拒绝、带令牌 `importAll`、`pms` 索引数量/映射和门户搜索均已验证；未执行真实 create/delete/sync 写请求。
- 2026-09-22 已在隔离数据上完成真实注册、登录、收货地址、优惠券领取、直购下单和本地支付成功链路验证；首次支付正确扣减 SKU 库存并释放锁定库存，重复支付保持幂等。验收后已恢复 SKU 原值并删除测试会员、地址、订单、订单明细、优惠券及缓存，所有测试记录只读复核为 0。
- 2026-09-22 商品导购智能体合并后复验：Python `344 passed, 6 skipped`，Ruff、Stub 评测 `10/10`，移动端 `95 passed`、TypeScript、H5/微信小程序构建和 Compose 两种 profile 配置均通过；本机无安全模型凭据，未执行 live 模型评测。
- 2026-09-25（`java-agent-migration` 工作树）Task 12 只完成**离线静态验收**：Compose 静态断言测试全绿、`docker compose --env-file .env.example config --quiet` 与全 profile 渲染通过；**未**执行 `docker build`、`docker compose up`、Nginx 运行时代理和真实接口请求，原因是本机 Docker daemon 不可连接（`docker ps` 报 npipe 管道不存在），故 Java 镜像构建与容器运行仍待具备 Docker 的环境复验。
- 2026-09-27（`java-agent-migration` 工作树，独立运行验收轮）：以正式 `document/docker/Dockerfile.app` 重建最新镜像（专用临时 tag，验收后已删除；`docker build --network=host` **exit 0**、镜像 `sha256:284512f8…`），**只在独立临时网络 / 容器**运行，**未重建未替换**主 Compose 13 服务（清理后主 13 容器仍 healthy）。最新镜像 `Config.User=mallapp`、容器 `id` = uid/gid `10001`（**非 root 运行层实测**）；临时 Nginx 代理网 `.2`、Agent `.3` + 后端网、Redis 后端网。实测 `/agent-api/health/ready` `200`、stub 游客 chat `200` + 5 张门户卡、session GET 2 消息 / DELETE `deleted=true`、游客券 `requiresLogin=true`、伪造 `X-Real-IP` + IP 配额 1 时第二次 `429`、未受信任 Origin `OPTIONS` `403`；Nginx 新配置日志不含 `sessionId`/query 但保留 `method`/`status`/`time`。**边界**：仅**分网隔离**，**非严格出站隔离**（`host.docker.internal` 可达主栈宿主发布端口 `13306` 及 Mongo `27018`、ES `9200`；共享无认证 Redis 仍同实例），用户决定按分网隔离继续并记录风险。全量测试接独立临时 Redis：**1320 tests / 0 failure / 0 errors / 0 skipped**（demo 2 / admin 63 / search 97 / portal 174 / agent 984；**纠正旧「全 reactor 984」实为 agent 单模块**）；Python Compose 静态 `pytest` **155 passed**、`compose config` **exit 0**、`git diff --check` **exit 0**。临时 4 容器 / 2 网络 / 镜像 tag 已删除**零残留**，**无 SQL、无主 Redis 写、无 commit/merge/push**。
- 2026-09-27（`java-agent-migration` 工作树，收窄共享 `Dockerfile.app` 运行身份后）：仅**静态/测试层通过**——runtime 新增 `ARG APP_RUN_USER=root` / `ARG APP_HOME=/root`（默认 root / `HOME=/root`），`mall-admin` / `mall-search` / `mall-portal` 保持 root 与 `HOME=/root`，仅 `mall-shopping-agent` 传 `APP_RUN_USER=mallapp` + `APP_HOME=/app` 并保留 `user: "10001:10001"`；全 `pytest` **158 passed**、Compose 默认与全 profile `config --quiet` **exit 0**、`git diff --check` **exit 0**。**最终镜像新构建两次均未成功**（第一次 apt 阶段 BuildKit EOF；第二次 Docker Desktop Linux Engine `_ping` 500），**未生成新镜像**，真实镜像 `User` / `HOME` 待 Docker 恢复后重验；**旧镜像 `sha256:284512f8…` 属于本轮变更前构建，不能作为当前最终 Dockerfile 已生效的证据**。
- 2026-09-27（`java-agent-migration` 工作树，Docker Desktop 重启后最终镜像复验）：用户**明确允许重启 Docker Desktop**；**第一次 `docker desktop restart` exit 0**，Engine 恢复。以**当前最终** `document/docker/Dockerfile.app` 在**独立标签 `mall-local/mall-agent-user-scope-accept:20260927`** 构建 **exit 0**，镜像 **`sha256:77907c5d…deff34`**，**`Config.User=mallapp`、`HOME=/app`**，容器 `id` = **`10001:10001`**、`/app/app.jar` 可读、`/app` 可写；**同一最终 Dockerfile 不传 `APP_RUN_USER`/`APP_HOME`** 独立构建 **exit 0**，镜像 **`sha256:01af6338…d071f`**，**`Config.User=root`**，容器 `id` = **`0:0`**、`HOME=/root`。启动**临时独立 Redis + Agent 容器**，新 Agent **直连** `GET /health/live` **200**、`GET /health/ready` **200**。**但聊天请求未获得响应，本轮最终镜像聊天 E2E 不计为通过**（**旧版镜像隔离聊天 E2E 不能替代本轮最终镜像聊天验收**）。其后 Docker API **再次卡住**，**WSL `free`** 显示**总 7.8G / 已用 6.7G / available 约 354.9M / swap 4.0G 用满**（**仅记为高度相关证据，不绝对断言唯一根因**）。**第二次 `docker desktop restart` exit 0** 后，**精确删除本轮 2 个临时容器、1 个空临时网络、2 个独立镜像标签**，**未清理**主栈容器 / 网络 / 卷；**主栈恢复中，当前健康状态由主 Agent 另行复核**。
- 2026-09-27（`java-agent-migration` 工作树，**最终独立镜像隔离 Nginx 路径 E2E —— 当前有效结论**）：用户**授权暂时停止主栈 Kibana / Logstash**（释放约 **0.8 GiB**）；在资源上限内以**独立 `Dockerfile.app` 镜像**（标签 `mall-local/mall-agent-final-e2e:20260927`）构建**成功**（exit 0），用**独立 Redis + 临时 Nginx**（`default.conf` 只读挂载）完成 E2E：`/agent-api/health/live`、`/agent-api/health/ready` **200**；`chat` **200** + **5 张真实门户商品卡**；会话 `GET` **2 条消息** / `DELETE` **成功**；游客个人券 `requiresLogin=true` + **0 卡**；Nginx 访问日志**无 UUID / query**、保留 **method / status**。**首次 400 仅因测试请求漏必填 `sessionId`，补齐即成功**。临时**容器 / 网络 / 镜像标签逐一删除**，Kibana / Logstash **已恢复**。**主栈健康**：**第三次重启 Docker Desktop** 后有**一次 13/13 healthy 快照**，但**其后 Docker API 仍间歇卡住**，**不作持续健康断言**。测试：`mvn` **full reactor 1320 / 0 / 0 / 8 skipped（无独立测试 Redis）**、Python Compose **158 passed**、`config` 全 profile **exit 0**；只读审查**无代码阻断**。**仍未验收**：主栈 `8088` chat、真实会员券 / 微信真机、正式域名。**Task 13 Step 2 保持 `[ ]`**；Java 分支**未 commit / merge / push**，`main` = `origin/main` = **`56810c2`**。
- 启动步骤和 SQL 安全边界见 `document/docker/local-startup.md`；历史 `mall.sql` 不作为已有数据库的直接覆盖脚本。

**2026-09-27 Java 导购迁移本地合并完成**：补齐详情 SKU `spData` 透传与会话 GET/DELETE 的可信客户端 IP 限流；Nginx `/agent-api/` 请求体上限 32 KB、读超时 60 秒、location 错误日志写入 `/dev/null`（此路由的 Nginx error details 会丢弃，access log 保留脱敏诊断字段）。中文功能提交为 `96bafb7`（实现 Java 商品导购智能体并完善部署安全）和 `5182cea`（记录 Java 智能体迁移提交信息）；本地非快进合并提交为 `18b5408cb7076f98b3da38c61fec0e9a82fa067b`。合并后在 `main` 重验：Maven 全 reactor **1333 项，0 失败 / 0 错误 / 8 跳过**（真实 Redis 门控用例）；Python Compose/Nginx **163 passed**；Compose 默认与全 profile 配置均通过；合并 diff 检查通过。未 push，`origin/main` 仍为 `56810c2`；真实会员券、微信真机、正式域名仍暂缓。

## 2026-09-28 主栈真实模型与会员优惠券验收（当前有效结论）

> 本节为**当前有效的实测结论**（追加，不删改上文历史）。上文 2026-09-27 及更早各轮中「真实模型 / 真实会员券未验收」「主栈 `8088` chat 未验收」等状态，凡与本节冲突者，**以本节为准**。

- **基线**：主仓库 `main` = `origin/main` = `ed5140a`，`main` 工作区干净。
- **主 Compose 运行模式**：对主 Compose `mall-shopping-agent` 做只读 `docker inspect` 并**仅筛选** `MALL_AGENT_MODEL_MODE`，确认运行模式为 **`openai`（真实模型）**。**未读取、未记录任何 API Key / Token / 会话 UUID。**
- **主 Nginx H5 页面**：`http://localhost:8088`。
- **登录态真实模型优惠券解释（H5）**：实际展示的优惠券解释与「已领取券」页**匹配**——**优惠金额、无门槛、指定商品范围、有效期**均正确。**未记录会员 ID 或任何凭据。**
- **真实模型商品详情查询（H5）**：查询 iPhone 14 返回**售价 5999 元**，详情 SKU **合计可售库存 2394**；回答**主动披露**「列表页库存与详情页库存不一致」，并**以详情页为准**。
- **真实模型预算筛选（H5）**：按「手机售价不超过 4000 元」筛选，本次搜索返回 **5 件**，其中 **3 件在预算内**：红米 5A（649 元 / 库存 414）、小米 8（2699 元 / 库存 410）、华为 P20（3788 元 / 库存 1985）；两台超预算 iPhone **未进入推荐**；随后多轮追问**正确比较出华为 P20 库存最高**。（以上均为**查询时点数据**。）
- **游客真实模型聊天（主 Nginx，无 Authorization）**：`POST /agent-api/agent/chat` 返回 **HTTP 200**、统一响应 `code=200`、`requiresLogin=false`、**答案非空**、**1 张商品卡**；本轮所用临时会话 `DELETE` 返回 `deleted=true`。
- **诚实备注（不得编造）**：首次探测脚本的 **PowerShell 多行输出解析有误**，**不能**把其中 `-1` 记为接口失败；**首次会话清理的 DELETE 回执未成功解析**，其清理结果**不确定**，仅能说明服务会话存在 TTL 兜底；**不臆断**该会话已立即删除。
- **副作用边界**：本轮**未执行任何 SQL、无代码改动**；H5 登录态对话产生了新增查询内容，留存在**短期会话**中。
- **验收结论**：**真实模型主栈与会员优惠券验收已完成。**
- **仍未完成**：**微信开发者工具 / 真机的商品导购端到端验收**（**需用户扫码配合**）；**正式 HTTPS / 小程序合法域名**按用户决定**暂缓**；`GET` / `DELETE` **跨实例互斥**仍为**可选技术遗留**（单实例已按当前目标验证）。

## 2026-09-28 工作树只读审查（`agent-live-acceptance`，待用户决定）

> 本节为**只读审查**记录（追加，不改动该工作树）。**禁止清理或丢弃该工作树**，结论为**待用户决定修复 / 归档**。

- **对象**：`C:\Users\qiufengm\.codex\worktrees\agent-live-acceptance\mall`，分支 `codex/agent-live-acceptance`，HEAD `56810c2`；含 **6 个未提交的 Python 源/测试改动**，且**无对应这些改动的报告**。
- **定向 pytest 结果**：因**缺少 `redis` 包**，`api/test_chat.py` 在**收集（collection）阶段即失败**，**该模块测试未执行**。
- **不涉及 API 的两文件测试**（`safety` + `orchestrator`）：**共 61 项，59 通过、2 失败**：
  1. 样例「我这张券能用在这款商品上吗」**未命中**个人券意图；
  2. 样例「帮我领优惠券」被**错误命中**个人券意图。
- **处置**：该工作树及改动**保持原样**，标记为**待用户决定修复 / 归档**；**不得**清理、丢弃或提交。
- **其他工作树**：`codex/deepseek-base-url`、`codex/java-agent-migration` 均干净，分支提交已在 `main` 历史中，列为**待清理候选**（实际删除须由用户手动执行）。`stash@{0}`（`On main: 集成 Docker 本地运行修复前的主工作区备份`）**应保留**。

## 当前剩余任务

> 说明：Java 导购智能体代码迁移、提交、审查、本地合并、`git push`、主栈 stub 游客验收，以及**真实模型主栈 + 会员优惠券适用性验收**均已**完成**（历史与结论见上文「已完成模块 #7」「2026-09-28 主栈真实模型与会员优惠券验收（当前有效结论）」），不再列为剩余任务。以下仅列**当前仍未完成 / 待用户决定**的事项。

1. **微信开发者工具 / 真机**商品导购端到端验收：**未完成**，**需用户用开发者工具 / 真机扫码配合**。
2. **正式 HTTPS / 小程序合法域名**：按用户决定**暂缓**，待后续环境配置。
3. **可选技术遗留 / 风险记录**：`GET` / `DELETE` 会话**跨实例互斥**仍为可选技术遗留（单实例已按当前目标验证）；网络为**分网隔离，非严格出站隔离**（`host.docker.internal` 仍可达主栈宿主发布端口；共享无认证 Redis 同实例），用户已决定先记风险并继续。ES 多实例 `importAll` 压力测试与 outbox/MQ 补偿机制**未执行**，不得视为已完成。
4. **`codex/agent-live-acceptance` 脏工作树（待用户决定，禁止清理 / 丢弃 / stash / 提交 / 擅自修复）**：`C:\Users\qiufengm\.codex\worktrees\agent-live-acceptance\mall`，分支 `codex/agent-live-acceptance`，HEAD `56810c2`，含 **6 个未提交的 Python 源 / 测试改动**，且**无对应报告**（详见「2026-09-28 工作树只读审查」）。
5. **两个干净 worktree 手动清理候选（实际删除须由用户手动执行，项目规则禁止批量目录删除）**：`codex/deepseek-base-url`（`73202de`）、`codex/java-agent-migration`（`5182cea`），均干净、分支提交已在 `main` 历史中。

**2026-09-28 主栈补测更正（覆盖上方旧记录中“主栈 `8088` chat 未验收”的状态；历史记录，保留）**：〔**2026-09-28 后续更新**：主 Compose 现经只读 `docker inspect` 确认为 **`openai`（真实模型）**运行模式，且**主栈真实模型 + 会员券验收已完成**，见上文「2026-09-28 主栈真实模型与会员优惠券验收（当前有效结论）」；本段 `stub` 结论为**该时点**记录，仅作历史。〕Compose 主栈 `mall-shopping-agent` 运行模式为 `stub`；经 `127.0.0.1:8088/agent-api/`，live/ready 均 200，游客“推荐一款手机”聊天返回 200 和 5 张商品卡；会话 GET 为 2 条消息，DELETE 返回 `deleted=true`。随机测试会话已清理。此测试使用 stub，不代表真实模型、会员 Token/优惠券、微信真机或正式域名验收；无 SQL/MySQL 写入，商品链路为门户只读调用。请求后单次状态快照显示长期运行服务 healthy，`minio-init` 为预期退出码 0。Task 13 Step 2 的报告记录现已补全并勾选；人工及真实环境待办仍保留。

## 新对话必须遵守

1. 先读取 `F:\code\mall\AGENTS.md`、最新计划和相关报告。
2. 先检查 `git status`、`git log` 和 `git worktree list`，不要相信历史摘要代替实际代码。
3. 先分析架构、文件边界、API、数据库影响和验收标准，再生成 CodeBuddy 工作树提示词。
4. 用户手动把提示词发送给对应 worktree；工作树不得自行 commit、push、merge。
5. CodeBuddy 必须生成 `.codebuddy/reports/<task-slug>-report.md`，聊天只返回简短回执。
6. 主 Agent 必须检查报告、真实 diff、敏感文件、只读审查和测试结果。
7. MySQL 的 DML、DDL、权限修改和数据迁移必须先取得明确确认：`确认执行这份 SQL`。
8. 使用中文提交信息；本地合并后重新验证；只有用户明确确认才 push。
9. 不要在任何文档、提示词、日志或回复中输出密码、Token 或密钥。

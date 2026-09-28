# 商品导购智能体 Java 化迁移实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task. Follow the Mall manual worktree workflow; do not dispatch coding agents. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 将现有 FastAPI 商品导购运行服务迁移到独立 Java 17 / Spring Boot `mall-agent` 模块，保持移动端 HTTP/JSON 契约、只读业务边界、Redis 会话身份隔离和运行配置兼容。

**架构：** 在 `mall-master` 新增独立 Spring Boot 服务；用 `RestClient` 调用 OpenAI 兼容模型及固定的 mall-portal GET 白名单，用 Redis 保存短期会话和限流状态。先通过 Java Stub、MockMvc、HTTP mock 与离线评测建立功能和安全等价，再将 Compose 中同名服务切换到 Java 镜像；Python 源码保留但不再部署。

**技术栈：** Java 17、Spring Boot 3.5.14、Spring MVC、Spring Data Redis、Spring `RestClient`、Jakarta Validation、JUnit 5、Mockito、MockMvc、Maven。

**设计：** `docs/superpowers/specs/2026-09-23-java-product-shopping-agent-design.md`

## 全局约束

- 保持独立服务模块名 `mall-agent`、Compose 服务键 `mall-shopping-agent`、容器端口 `8086` 和 Nginx `/agent-api/` 入口。
- 保持 `POST /agent/chat`、`GET/DELETE /agent/session/{sessionId}`、`GET /health/live`、`GET /health/ready` 及 `code/message/data` JSON 契约。
- 保留所有 `MALL_AGENT_*` 环境变量名与语义；模型 Base URL 原样保留供应商路径，只拼接 `/chat/completions`。
- 服务只允许五条 mall-portal GET 路径：`/product/search`、`/product/detail/{id}`、`/sso/info`、`/member/coupon/listHistory?useStatus=0`、`/member/coupon/listByProduct/{productId}`；不得使用 SQL 或商城数据库/消息队列/ES 客户端。
- 游客与会员 Redis 会话命名空间不得互通；会员 ID 只从 `/sso/info` 服务端解析；Token 仅透传至身份和个人优惠券接口，永不写入日志、Redis 或响应。
- 商品卡片事实必须来自本轮服务端工具结果；库存为 `max(stock-lockStock,0)`；模型不能提供名称、价格、图片、库存或详情路径。
- 游客个人优惠券询问必须确定性要求登录，且在命中时零模型调用、零会员优惠券接口调用；公开优惠券问法不得误判；领券/加购/下单/支付/订单和库存/ES 写操作必须零工具调用并拒绝。
- 不改移动端业务页面、不新增或执行 SQL、不改商城表、不改真实 `.env`；不触碰 `codex/agent-live-acceptance` 的 6 个未提交文件。
- Python 服务源码、测试与 `document/docker/Dockerfile.agent` 保留；只从 Compose 运行链路退役，不批量删除。
- 不提交、不推送、不合并、不重置分支；由主 Agent 在审查通过后按项目流程处理 Git。
- 如果需要任何子 Agent，只允许 `gpt-6-luna`，显式设置 `max` 推理强度；本计划不自动分派编码子任务。
- 禁止输出或提交真实 API Key、Token、密码；所有日志只输出安全错误分类和追踪信息。
- 全量 Maven 测试不得使用 `maven.test.failure.ignore`；真实模型、Redis 容器、Docker/Nginx、微信真机结果必须分别如实记录。
- 本计划当前工作树为 `F:\code\mall\.worktrees\java-agent-migration`；除明确带 `-f mall-master/pom.xml` 的基线命令外，所有 Maven 命令均从该工作树的 `mall-master/` 运行；Python、Compose、Nginx 与 Git 命令从该工作树根目录运行。不得从 `F:\code\mall` 主工作区执行本分支实现命令。
- Java 配置必须兼容现有全部变量（上列 22 个为**迁移初期清单**，予以保留作历史说明）：`MALL_AGENT_HOST`、`MALL_AGENT_PORT`、`MALL_AGENT_LOG_LEVEL`、`MALL_AGENT_CORS_ALLOW_ORIGINS`、`MALL_AGENT_REQUEST_TIMEOUT_SECONDS`、`MALL_AGENT_MODEL_MODE`、`MALL_AGENT_OPENAI_BASE_URL`、`MALL_AGENT_OPENAI_API_KEY`、`MALL_AGENT_OPENAI_MODEL`、`MALL_AGENT_OPENAI_TIMEOUT_SECONDS`、`MALL_AGENT_MAX_TOOL_ROUNDS`、`MALL_AGENT_PORTAL_BASE_URL`、`MALL_AGENT_PORTAL_TIMEOUT_SECONDS`、`MALL_AGENT_REDIS_URL`、`MALL_AGENT_SESSION_TTL_SECONDS`、`MALL_AGENT_SESSION_MAX_MESSAGES`、`MALL_AGENT_RATE_LIMIT_SESSION_LIMIT`、`MALL_AGENT_RATE_LIMIT_SESSION_WINDOW_SECONDS`、`MALL_AGENT_RATE_LIMIT_IP_LIMIT`、`MALL_AGENT_RATE_LIMIT_IP_WINDOW_SECONDS`、`MALL_AGENT_TOOL_RESULT_MAX_CHARS`、`MALL_AGENT_CONTEXT_MAX_CHARS`；`MALL_AGENT_TEST_REDIS_URL` 仅供显式集成测试使用。**2026-09-27 安全加固新增** `MALL_AGENT_TRUSTED_PROXY_IP`（可信反向代理地址，**默认空 = 不信任任何代理**；仅当请求对端 `remoteAddr` 与该地址按字节相等时才采信 `X-Real-IP`）；因此 Compose 白名单现为 **23** 个 `MALL_AGENT_*` 变量（外加 `TZ`，见 `.env.example` 第 127 行）。

## 文件边界与职责

| 路径 | 计划职责 |
| --- | --- |
| `mall-master/pom.xml` | 注册 `mall-agent` Maven reactor 模块。 |
| `mall-master/mall-agent/pom.xml` | 声明 Web、Redis、Validation、测试依赖；继承现有 Java 17 / Spring Boot 版本。 |
| `mall-master/mall-agent/src/main/java/com/macro/mall/agent/` | 新 Java 服务；按 `api`、`config`、`model`、`storefront`、`session`、`safety`、`tools`、`agent`、`presentation` 分责。 |
| `mall-master/mall-agent/src/main/resources/application.yml` | 8086 监听、`MALL_AGENT_*` 参数映射、Redis URL、超时/TTL/轮数默认值；不含凭据。 |
| `mall-master/mall-agent/src/test/java/com/macro/mall/agent/` | 契约、安全、领域、HTTP mock、Redis 和离线评测测试。 |
| `mall-master/mall-agent/src/test/resources/` | 从 Python fixtures 转换的无凭据门户响应和模型对话样例。 |
| `mall-master/pom.xml` | 仅在模块接入任务中修改，不顺带改其他模块依赖。 |
| `docker-compose.yml` | 保留 `mall-shopping-agent` 服务名、依赖、网络、端口与白名单变量，切换 build context/Dockerfile 为 Java。 |
| `document/docker/Dockerfile.app` | 复用已有 Java 17 Maven 多阶段镜像，通过 `MODULE`/`JAR_FILE` 构建 agent，不新增重复 Dockerfile。 |
| `.env.example`、`document/docker/check-agent-env.ps1`、`document/docker/check-agent-env.sh` | 只在 Java 运行所需变量与现有检查不一致时做最小修正；禁止打印变量值。 |
| `document/docker/nginx/conf.d/default.conf` | 保持 `/agent-api/` 到 `mall-shopping-agent:8086` 代理不变；除非测试证明有必要，不改代理逻辑。 |
| `document/docker/local-startup.md`、`mall-shopping-agent/README.md`、`document/agent/product-shopping-agent.md`、`docs/context/project-handoff.md` | 更新实际运行实现为 Java，并说明 Python 源码仅留作参考、未删除。 |
| `mall-app-web-master/` | 不修改；通过请求/响应契约证明现有页面可继续使用。 |

## 接口约定（任务之间共享）

- `ApiEnvelope<T>(int code, String message, T data)`；健康、聊天、会话均使用既有 JSON 包装。
- `ChatRequest(String sessionId, String message)`；`sessionId` 按 Python `str.strip()` 去首尾空白并转小写后必须为规范 UUID v4（接受大写及首尾空白并规范化），`message` 按 Python `str.strip()` 处理后长度为 1..1000 个 Unicode 码点；不得从请求体接受身份、Token、URL 或工具控制参数。
- `ChatData(String sessionId, String messageId, String answer, List<ProductCard> products, boolean requiresLogin, List<String> suggestedQuestions)`。
- `SessionData(String sessionId, List<SessionMessage> messages, List<ProductCard> products, boolean requiresLogin)`；`DeleteSessionData(String sessionId, boolean deleted, boolean requiresLogin)`。
- `ProductCard(long id, String name, String pic, String price, String subtitle, String stockStatus, int availableStock, String detailPath)`。
- 规范模型接口：`ModelResponse complete(ModelRequest request)`；`ModelRequest` 包含消息、四个注册工具定义、`toolChoice` 与温度；工具调用携带 `id/name/arguments`。
- 门户接口只暴露 `searchProducts(SearchParams)`、`getProductDetail(long)`、`resolveMember(String authorization)`、`listUnusedCouponHistory(String authorization)`、`listProductCoupons(long,String authorization)`，不提供任意 URL/方法参数。
- 会话接口：`SessionSnapshot load(String key)`、`void save(String key, SessionSnapshot snapshot)`、`boolean delete(String key)`、`void copy(String sourceKey,String targetKey)`。

---

### Task 1：恢复 Maven 基线并记录迁移对照

**文件：**
- 检查：`mall-master/pom.xml`
- 检查：`mall-shopping-agent/src/mall_shopping_agent/api/schemas.py`
- 检查：`mall-shopping-agent/src/mall_shopping_agent/session/identity.py`
- 检查：`mall-shopping-agent/src/mall_shopping_agent/session/repository.py`
- 检查：`mall-shopping-agent/src/mall_shopping_agent/model/schemas.py`
- 检查：`mall-shopping-agent/evals/cases.json`
- 报告：`.codebuddy/reports/java-agent-baseline-report.md`

**接口：** 后续实现以本计划“接口约定”为唯一 Java 命名来源；Python JSON、Redis 键与 10 个评测用例作为行为对照，不把旧分支未提交代码合入。

- [ ] **Step 1：在依赖可用环境跑当前基线**

运行：`mvn -f mall-master/pom.xml test`

预期：当前 `main@56810c2` 全 reactor BUILD SUCCESS。若失败原因是下载 Spring Boot parent 或依赖被沙箱网络拒绝，先在具备 Maven 缓存/网络权限的环境解决依赖取得，再用同一命令重跑；不得修改 parent 版本、跳过测试或把依赖解析错误记作源码失败。

已知现状：本计划编写前已在该 worktree 尝试 Maven 基线，但 Maven 解析 `spring-boot-starter-parent:3.5.14` 时因沙箱网络 `Permission denied: getsockopt` 失败，未进入编译/测试。因此本任务仍是首个执行门槛，不能把该次结果记为通过或源码失败。

- [ ] **Step 2：运行与迁移相关的 Python 对照测试**

运行：`python -m pytest mall-shopping-agent/tests/unit/api mall-shopping-agent/tests/unit/session mall-shopping-agent/tests/unit/safety mall-shopping-agent/tests/unit/evals -q`

预期：退出码 0；若本机没有 Python 测试依赖，则记录可复现的缺失依赖并使用已有报告与 fixture，不安装或改写项目依赖来绕过 Java Maven 门槛。

- [ ] **Step 3：整理迁移对照矩阵并生成基线报告**

在报告中记录 10 条离线评测各自的输入、身份、允许/禁止工具、requiresLogin、工具调用上限和事实断言；仅从当前分支已跟踪的 Python 源码与测试取证，不读取、不应用 `codex/agent-live-acceptance` 的未提交 diff。检查本分支已有的设计稿与实施计划并保持不变；本任务不修改 Maven/Python/Compose/前端生产代码，也不修改这两份文档。

预期：基线报告 `.codebuddy/reports/java-agent-baseline-report.md` 说明测试命令、退出码/失败根因、Python 对照行为矩阵和未验证项；Task 1 不改动任何项目源码或既有设计/计划文档。Java fixtures 的创建归 Task 2。

### Task 2：新增 Maven 模块与最小可运行服务

**文件：**
- 修改：`mall-master/pom.xml`
- 创建：`mall-master/mall-agent/pom.xml`
- 创建：`mall-master/mall-agent/src/main/java/com/macro/mall/agent/MallAgentApplication.java`
- 创建：`mall-master/mall-agent/src/main/java/com/macro/mall/agent/config/AgentProperties.java`
- 创建：`mall-master/mall-agent/src/main/resources/application.yml`
- 创建：`mall-master/mall-agent/src/test/resources/portal/product-search.json`
- 创建：`mall-master/mall-agent/src/test/resources/portal/product-detail.json`
- 创建：`mall-master/mall-agent/src/test/resources/portal/sso-info.json`
- 创建：`mall-master/mall-agent/src/test/resources/portal/coupon-history.json`
- 创建：`mall-master/mall-agent/src/test/resources/portal/coupon-by-product.json`
- 创建：`mall-master/mall-agent/src/test/resources/evals/cases.json`
- 测试：`mall-master/mall-agent/src/test/java/com/macro/mall/agent/config/AgentPropertiesTest.java`
- 测试：`mall-master/mall-agent/src/test/java/com/macro/mall/agent/api/HealthControllerTest.java`

**接口：** `AgentProperties` 暴露 `host`、`port`、`logLevel`、`corsAllowOrigins`、`requestTimeoutSeconds`、`modelMode`、`openaiBaseUrl`、`openaiApiKey`、`openaiModel`、`openaiTimeoutSeconds`、`maxToolRounds`、`portalBaseUrl`、`portalTimeoutSeconds`、`redisUrl`、`sessionTtlSeconds`、`sessionMaxMessages`、`rateLimitSessionLimit`、`rateLimitSessionWindowSeconds`、`rateLimitIpLimit`、`rateLimitIpWindowSeconds`、`toolResultMaxChars`、`contextMaxChars`；`openaiBaseUrl` 保留服务商 path 并拼接 `/chat/completions`；`modelAvailable` 对 `stub` 恒为 true，`openai` 仅在模型名和 API Key 均非空/非占位时为 true。环境变量必须通过精确 `MALL_AGENT_*` 名读取/映射，不能把下划线映射错误地当成多层嵌套配置。

- [ ] **Step 1：从已跟踪的 Python fixtures 建立脱敏 JSON 样例**

从 `mall-shopping-agent/tests/fixtures/portal/` 复制五种接口结构到上列 Java test resources，并将 `mall-shopping-agent/evals/cases.json` 复制为 `evals/cases.json`。保留 fixture 业务字段供映射测试使用；删除 Authorization、Token、密码、Key 等任何凭据字段，不从 `.env` 读取数据。

运行：`python -c "import json,pathlib; root=pathlib.Path('mall-master/mall-agent/src/test/resources'); [json.loads(p.read_text(encoding='utf-8')) for p in root.rglob('*.json')]; print('JSON_FIXTURES_OK')"`

预期：打印 `JSON_FIXTURES_OK`；扫描 Java test resources 不出现凭据值；10 条评测用例结构与来源一致。

- [ ] **Step 2：先注册模块及 starter，添加默认值与边界测试**

测试至少断言 `host=0.0.0.0`、`port=8086`、`corsAllowOrigins=`（**默认空，fail-closed；通配 `*` 在配置阶段被拒绝**）、`modelMode=openai`、`openaiBaseUrl=https://api.openai.com/v1`、`openaiTimeoutSeconds=30`、`requestTimeoutSeconds=35`、`maxToolRounds=4`、`portalBaseUrl=http://localhost:8085`、`portalTimeoutSeconds=10`、`redisUrl=redis://localhost:6379/0`、`sessionTtlSeconds=86400`、`sessionMaxMessages=20`、限流默认 20/300 秒与 60/300 秒、`toolResultMaxChars=4000`、`contextMaxChars=16000`；逐项从同名 `MALL_AGENT_*` 注入覆盖并验证，不只验证默认值；参数非法时上下文启动失败。只添加 `spring-boot-starter-web`、`spring-boot-starter-data-redis`、`spring-boot-starter-validation` 和现有父 POM 提供的测试依赖；不添加商城 DAO/数据库/搜索/消息队列依赖。

运行：`mvn -pl mall-agent -am -Dtest=AgentPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：新增测试在实现前失败；完成配置映射后 PASS，且 `mvn -pl mall-agent dependency:tree` 中无 `mall-mbg`、MySQL、MongoDB、RabbitMQ、Elasticsearch client。

- [ ] **Step 3：实现 Spring Boot 入口与最小健康端点**

测试 `GET /health/live` 返回 HTTP 200、`{"code":200,"message":"操作成功","data":{"status":"UP"}}`；ready 探针成功时 200，任一依赖失败时 503，响应不包含 URL、Key 或异常正文。Ready 的 portal 探针调用 `GET /product/search?pageNum=1&pageSize=1` 并检查 `total >= 0`，Redis 探针调用 `PING`；此阶段通过可注入的 `HealthProbe` 测试替身验证，不建立外部连接。

运行：`mvn -pl mall-agent -am -Dtest=HealthControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：两个健康测试 PASS；`mvn -pl mall-agent -am test` BUILD SUCCESS。

### Task 3：锁定 HTTP DTO、输入校验和统一错误响应

**文件：**
- 创建：`api/ApiEnvelope.java`、`api/ChatRequest.java`、`api/ChatData.java`、`api/SessionData.java`、`api/DeleteSessionData.java`、`api/SessionMessage.java`、`api/ProductCard.java`
- 创建：`api/AgentController.java`、`api/ApiExceptionHandler.java`
- 创建：`config/AgentCorsConfiguration.java`
- 测试：`api/AgentContractTest.java`、`api/AgentValidationTest.java`

**接口：** DTO 精确匹配本计划“接口约定”；控制器路径固定 `/agent/chat` 与 `/agent/session/{sessionId}`，成功响应保持 code/message/data；统一异常体仍使用同一外层结构。

- [ ] **Step 1：写 MockMvc 契约测试**

覆盖 camelCase 字段、空产品列表、`requiresLogin`、消息 ID、session GET/DELETE 数据字段；拒绝缺失 sessionId/message、按 Python 空白规则 trim 后为空的消息、超过 1000 个 Unicode 码点的消息、非规范 UUID、UUID v1，以及请求体中的 `memberId`/`url`/`token` 等越权字段；验证补充平面字符按 Unicode 码点而非 UTF-16 单元计数，并覆盖大写/首尾空白 UUID 在请求体及可编码路径中的规范化。断言错误 HTTP 状态与既有客户端需要的 envelope。另测试 CORS：Origins 采用 `MALL_AGENT_CORS_ALLOW_ORIGINS` 的逗号分隔**精确来源**列表（**不允许通配 `*`；默认空即不注册任何跨域许可，fail-closed**），只允许 `GET,POST,DELETE,OPTIONS`，允许请求头 `*`，`allowCredentials=false`。

运行：`mvn -pl mall-agent -am -Dtest=AgentContractTest,AgentValidationTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：尚无 DTO/路由时测试失败；实现后所有契约断言 PASS；序列化结果不能出现 `session_id` 或 snake_case 字段。

- [ ] **Step 2：实现 Bean Validation、规范 UUID v4 检查和 API 异常映射**

消息按 Python `str.strip()` 语义 trim 后，按 Unicode 码点计数并校验长度 1..1000；sessionId 按 Python `str.strip()` 去首尾空白并转小写后必须等于规范 UUID v4 文本（大写及带空白形式接受并规范化，与 Python `validate_session_id` 一致）；未知请求属性拒绝而不是忽略。`AgentCorsConfiguration` 将逗号列表 trim 后配置到 Spring MVC，**拒绝含通配 `*` 的来源；默认空时不注册任何跨域许可（fail-closed）**；methods 仅 `GET/POST/DELETE/OPTIONS`、headers `*`、credentials false。定义具有固定 code/message 的应用异常，禁止把异常堆栈、上游响应正文返回给客户端。

运行：`mvn -pl mall-agent -am -Dtest=AgentContractTest,AgentValidationTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；`git diff --check` 无输出。

### Task 4：实现 OpenAI 兼容模型客户端与离线 Stub

**文件：**
- 创建：`model/ModelClient.java`、`model/ModelRequest.java`、`model/ModelMessage.java`、`model/ModelToolCall.java`、`model/ModelResponse.java`、`model/ModelException.java`
- 创建：`model/OpenAiCompatibleClient.java`、`model/StubModelClient.java`
- 测试：`model/OpenAiCompatibleClientTest.java`、`model/StubModelClientTest.java`

**接口：** `ModelResponse complete(ModelRequest request)`；兼容端点为 `{baseUrl去尾斜杠}/chat/completions`；非流式 JSON；工具调用解析为稳定的 `id/name/arguments`；模型异常只携带安全错误分类。

- [ ] **Step 1：测试 URL 拼接、鉴权头和工具协议 JSON**

用 `MockRestServiceServer` 对 DeepSeek 根地址 `https://api.deepseek.com` 和带 `/v1` 前缀的地址分别断言只追加 `/chat/completions`；请求体包含配置模型名、messages、tools、`tool_choice=auto`、`stream=false`；`Authorization: Bearer` 只在发向模型的 HTTP 请求中出现。

运行：`mvn -pl mall-agent -am -Dtest=OpenAiCompatibleClientTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：客户端未实现时失败；实现后 URL、请求字段、工具调用 JSON 全部匹配；测试输出不得打印测试 key。

- [ ] **Step 2：测试模型错误分类与脱敏**

覆盖 401/403/404 映射不可用或配置错误、429/5xx/连接失败映射上游失败、超时映射超时、无 choices/空内容/非法 tool_calls 映射协议失败。构造含测试 Key 与上游敏感文本的错误响应，断言异常 message、日志捕获和 MockMvc 响应均不含这些字符串。

运行：`mvn -pl mall-agent -am -Dtest=OpenAiCompatibleClientTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；只对明确可重试状态/连接失败执行至多一次重试，不对 4xx 重试。

- [ ] **Step 3：实现确定性 Stub client**

Stub client 仅用于 `MALL_AGENT_MODEL_MODE=stub`，从测试/离线评测指定对话返回预定工具调用和回答；不得联网、读取 API Key 或生成测试样例中没有的商品事实。断言同一输入得到同一结果。

运行：`mvn -pl mall-agent -am -Dtest=StubModelClientTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；openai 模式遇到空/占位 Key 时 chat 映射 503，而 health live/ready 仍按服务依赖状态工作。

### Task 5：建立固定只读 MallPortalClient

**文件：**
- 创建：`storefront/MallPortalClient.java`、`storefront/PortalException.java`
- 创建：`storefront/dto/ProductSearchResponse.java`、`ProductDetailResponse.java`、`MemberInfoResponse.java`、`CouponHistoryResponse.java`、`ProductCouponResponse.java`
- 测试：`storefront/MallPortalClientTest.java`

**接口：** 仅实现本计划“接口约定”的五个 GET 方法；调用路径为固定常量，分页大小固定 5；`Authorization` 仅出现在 `/sso/info` 和两条会员券路径。

- [ ] **Step 1：为五个 GET 路径写请求断言**

用 `MockRestServiceServer` 覆盖搜索查询参数、详情 ID 编码、身份、未使用券 `useStatus=0`、指定商品优惠券；对每次请求断言 method 为 GET、host 为配置的 portal host、path 属于五条白名单。让服务端字段 fixture 驱动响应 DTO 解析。

运行：`mvn -pl mall-agent -am -Dtest=MallPortalClientTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：初始失败；完成后五条路径与 fixture 字段解析通过，任意未经允许的 URL/method 在 API/工具层无可达入口。

- [ ] **Step 2：断言 Token 传递边界和错误隔离**

会员 Token 对 `/sso/info`、`listHistory`、`listByProduct` 原样传递；商品搜索和详情不得带 Token。覆盖 401、404、超时和非法 JSON，异常不保留 Authorization、上游响应正文或完整 URL 查询数据。

运行：`mvn -pl mall-agent -am -Dtest=MallPortalClientTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；删除任何“传入任意 path / method / header”的通用调用函数。

### Task 6：实现身份命名空间、Redis 会话兼容与限流

**文件：**
- 创建：`session/AgentIdentity.java`、`session/IdentityResolver.java`、`session/SessionSnapshot.java`、`session/SessionRepository.java`、`session/RedisSessionRepository.java`、`session/RateLimiter.java`、`session/RedisRateLimiter.java`、`session/InFlightGuard.java`
- 修改：`config/AgentProperties.java`（仅补 Redis/session/limit 参数）
- 测试：`session/AgentIdentityTest.java`、`session/RedisSessionRepositoryTest.java`、`session/RedisRateLimiterTest.java`、`session/RedisSessionIntegrationTest.java`

**接口：** 使用共享接口约定；会话键保持 `mall:agent:session:guest:{sessionId}` 和 `mall:agent:session:member:{memberId}:{sessionId}`；限流键保持 `mall:agent:rate:{session|ip}:{sha256前32位}:{windowBucket}`；默认 TTL 86400 秒、保留最近 20 条消息、会话 300 秒 20 次、IP 300 秒 60 次。

- [x] **Step 1：先测试身份与跨身份隔离**

断言 UUID v4 规范化、memberId 必须正整数、客户端请求体不能指定 memberId；同一 sessionId 的 guest/member session key 不同；fingerprint 为 SHA-256 前 32 位。测试访客会话迁移仅在成功解析有效 token 后发生，会员目标非空时不覆盖。

运行：`mvn -pl mall-agent -am -Dtest=AgentIdentityTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：实现前失败；实现后 PASS；游客 key 永不作为会员读取 fallback。

- [x] **Step 2：测试 JSON 结构、TTL、GET 续期、修复损坏值**

基于 Python Pydantic `SessionSnapshot` JSON 样例断言 Java 可读取 `messages:[{role,content}]` 和 `products`，写回 JSON 字段兼容；只保存必要消息和最多 5 张卡片，不保存 Token、模型原始响应、门户原始响应。测试 load 续 TTL，save 设置 TTL，坏 JSON 被删除并变为空快照，repository copy 在目标键为空时复制并删除 source；目标已有内容时由上层迁移服务阻止覆盖，且保留 guest key。

运行：`mvn -pl mall-agent -am -Dtest=RedisSessionRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；若 JSON 兼容无法做到无损，使用空会话安全降级并在 API/文档说明，不跨身份读取。

- [x] **Step 3：测试固定窗口限流和重复请求防护**

使用固定 Clock 与 mock Redis，验证同会话 20/21 次、IP 60/61 次边界、TTL 只在首次计数设置、429 带 Retry-After。安全返工后 `check` 之外另提供分阶段入口：`checkIp` 只消耗 IP 桶、`checkSession` 只消耗会话桶（各一次），`check` 保留「先会话后 IP、会话超限不再消耗 IP」的兼容语义；chat 主流程使用分阶段入口，因此「会话超限的请求不消耗 IP」在正常请求上不可同时维持（IP 已在身份解析前消耗），该边界差异在服务实现与返工报告中记录。并发防护改为按规范 sessionId 占用整个 chat 生命周期：同一 sessionId 的第二个并发请求返回 409，不同 sessionId 互不影响，结束后释放 in-flight 锁。

运行：`mvn -pl mall-agent -am -Dtest=RedisRateLimiterTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；Redis 操作使用原子 INCR/TTL 或 Lua，测试覆盖不会因并发丢计数。

- [x] **Step 4：提供显式可选 Redis 集成测试**

`RedisSessionIntegrationTest` 仅在 `MALL_AGENT_TEST_REDIS_URL` 非空且合法时启用；只使用指定测试 DB/index 和 `mall:agent:test:` 前缀，并在单条明确 key 范围内清理，绝不 flushdb/flushall。

运行：`mvn -pl mall-agent -am -Dtest=RedisSessionIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：未配置时测试明确 skip；配置本地 Redis 时 round-trip、TTL、身份隔离通过。

**Task 6 状态（2026-09-24，DeepSeek Flash）**：身份命名空间、Redis 会话兼容与固定窗口限流实现完成；`RedisRateLimiterTest` 23 项全绿、`RedisSessionIntegrationTest` 11 项（5 项真实 Redis 用例在未配置 `MALL_AGENT_TEST_REDIS_URL` 时明确 skip）。本轮定向返工补齐两处缺陷：429 业务文案逐字符对齐 Python（无句号）、`RedisRateCounter` 在 Redis 未返回计数时失败关闭（不再按 0 放行），并新增可选真实 Redis 限流 Lua 集成用例。**尚未提交**：工作树 `codex/java-agent-migration` 的 `mall-agent/` 目录仍为 untracked，等待主 Agent 按流程审查后提交。报告：`.codebuddy/reports/java-agent-session-redis-report.md`。

### Task 7：迁移确定性安全策略与不可信数据围栏

**文件：**
- 创建：`safety/ShoppingIntentClassifier.java`、`safety/RefusalPolicy.java`、`safety/UntrustedTextFence.java`
- 测试：`safety/ShoppingIntentClassifierTest.java`、`safety/RefusalPolicyTest.java`、`safety/UntrustedTextFenceTest.java`

**接口：** `classifyPersonalCoupon(String): boolean`；`detectWriteRefusal(String): Optional<Refusal>`；规则优先级为写操作拒绝 > 个人券身份门槛 > 普通模型对话。

- [x] **Step 1：先测试个人券问法分类边界**

正例包括“我的优惠券能用在哪个商品上”“我这张券能用在这款商品上吗”“我领过的券”；反例包括“商城有哪些公开优惠券”“这个商品支持什么优惠活动”“我不问个人券，只查商品折扣”。领券命令如“帮我领优惠券”必须落到写拒绝而不是登录提示。

运行：`mvn -pl mall-agent -am -Dtest=ShoppingIntentClassifierTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：实现前失败；完成后每个正反例均匹配规定分类。

- [x] **Step 2：测试全部写操作拒绝且零工具调用**

覆盖领券、加购、下单、支付、取消订单、确认收货、改库存、ES 导入/同步/删除；断言拒绝文本有引导且 `ModelClient` 和 `MallPortalClient` 调用次数均为 0。

运行：`mvn -pl mall-agent -am -Dtest=RefusalPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；未知或普通查询不误判成写操作。

- [x] **Step 3：测试商品文本围栏、截断与注入清理**

把商品名、简介和模型工具数据视为不可信文本；覆盖 `<|im_start|>`、`[system]`、控制字符、超长文本和注入指令，确保固定系统规则与用户输入角色不被重写，工具结果按长度裁剪并加清晰 data fence。

运行：`mvn -pl mall-agent -am -Dtest=UntrustedTextFenceTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；原始商品文本不得被复制到系统提示词位置。

**Task 7 状态（2026-09-24，DeepSeek Flash；含两轮分类器兼容性返工）**：确定性安全策略与不可信数据围栏实现完成；`ShoppingIntentClassifierTest` 54 项、`RefusalPolicyTest` 69 项、`UntrustedTextFenceTest` 33 项全绿（合计 156 项），全模块 `mvn -o -pl mall-agent -am test` 为 601 项 / 0 失败 / 0 错误 / 5 skip。三者为个人券分类、8 类写拒绝与不可信文本围栏的纯逻辑组件，无可变状态与外部客户端依赖。计划中 Task 7 只有 Step 1–3 三个步骤，已全部勾选（用户提示词中的“四个勾选”按“Task 7 全部勾选”处理）。**分类器兼容性返工（两轮）**：第一轮按审查要求补齐 Python 支持的“本人(账户)?的”“个人的”及“这张/这几张/账户里的/账户中的/已领取的/刚领的/领到的/可用的/未使用的”修饰表达正例，**删除无 Python 依据的英文分类规则**（`My Coupons` 等英文问法不再判定为个人券），否定关键词收敛为 Python 的 9 项；第二轮按最终只读审查**移除无 Python 依据的状态动词“拥有”**（`我拥有优惠券` 现为 `false`，与 Python 一致；`我持有优惠券` 仍为 `true`）。与参考 Python 的 49 条对照矩阵（26 正 + 18 反 + 5 英文）中 42 条一致，7 条为有授权的刻意差异：3 条领券纠错（`帮我领优惠券`/`帮我领取这张优惠券`/`帮我领一张券` → classifier `false`，由 `RefusalPolicy` 拒绝）与 1 条正例纠错（`我这张券能用在这款商品上吗` → `true`）为用户批准的两处纠错，另 3 条为保留的用户确认中文前缀/账户修饰正例；`RefusalPolicy` 未改动。**偏差（如实记录）**：Step 2 原本要求的 `ModelClient`/`MallPortalClient` 运行时调用次数为 0 的断言，因本任务尚无 Orchestrator/工具调用链而无法诚实验证，本任务只证明策略无外部客户端依赖，端到端调用计数断言留给 Task 9/10；另 Java 相对 Python 强化了字面 `[system]` 等方括号角色伪造标记的中和。**尚未提交**：工作树 `codex/java-agent-migration` 的 `mall-agent/` 目录仍为 untracked，等待主 Agent 按流程审查后提交。报告：`.codebuddy/reports/java-agent-safety-policy-report.md`。

### Task 8：实现白名单工具、商品事实和优惠券解释

**文件：**
- 创建：`tools/AgentTool.java`、`tools/ToolRegistry.java`、`tools/ToolContext.java`、`tools/ToolResult.java`、`tools/SearchProductsTool.java`、`tools/GetProductDetailTool.java`、`tools/CompareProductsTool.java`、`tools/GetMemberCouponsForProductTool.java`
- 创建：`presentation/ProductFact.java`、`presentation/ProductFactCollector.java`、`presentation/ProductCardBuilder.java`
- 测试：`tools/ToolRegistryTest.java`、`tools/ProductToolsTest.java`、`tools/CouponToolTest.java`、`presentation/ProductCardBuilderTest.java`

**接口：** 注册名称严格为 `searchProducts`、`getProductDetail`、`compareProducts`、`getMemberCouponsForProduct`；每个参数 DTO 拒绝未知字段并限制分页 1..20、排序 0..4、商品 ID 正数、比较 ID 2..3 且互不重复。

- [x] **Step 1：测试注册表与严格参数校验**

断言只注册四个给定工具名；未知工具、任意 URL/HTTP method/header 字段、非法分页/排序/ID 返回结构化拒绝且无门户调用。

运行：`mvn -pl mall-agent -am -Dtest=ToolRegistryTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；工具执行接口没有接受 URI、method 或 headers 的参数。

- [x] **Step 2：测试搜索、详情、比较数据与错误**

搜索页大小固定 5；详情库存 SKU 按 `max(stock-lockStock,0)` 汇总；比较只处理 2..3 个商品，缺失/下架/上游失败分别标记，门户错误不能伪装成功。

运行：`mvn -pl mall-agent -am -Dtest=ProductToolsTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；测试事实全部来自 fixture，不连接真实商城。

- [x] **Step 3：测试会员券交集与本人身份**

会员券结果为本人 `useStatus=0` 历史券 ID 与指定商品可用券 ID 的交集；对失效 token 返回登录要求。游客直接执行工具时零会员 API 调用；计算金额、门槛、有效期、适用范围和可用状态，最多返回 5 张并优先展示可用券。

运行：`mvn -pl mall-agent -am -Dtest=CouponToolTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；不调用任何领券接口。

- [x] **Step 4：测试卡片事实只能来自本轮服务端工具**

覆盖搜索/详情/比较事实优先级 `detail > compare > search`、重复 ID 去重、最多 5 卡、详情路径 `/pages/product/product?id={id}`；模型提出本轮未出现的商品 ID 时不得输出卡片。卡片 JSON 字段必须为 camelCase。

运行：`mvn -pl mall-agent -am -Dtest=ProductCardBuilderTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；修改模型回答中的价格/库存不改变服务端卡片事实。

**Task 8 状态（2026-09-24，DeepSeek Flash；含审查返工：D1/D2 修复与 D3 报告措辞更正）**：白名单工具、商品事实与服务端卡片实现完成；`ToolRegistryTest` 26 项、`ProductToolsTest` 63 项、`CouponToolTest` 25 项、`ProductCardBuilderTest` 23 项全绿（合计 137 项），全模块 `mvn -o -pl mall-agent -am test` 为 738 项 / 0 失败 / 0 错误 / 5 skip（Task 7 基线 601 + 本轮 137）。默认注册表精确注册四个工具 `searchProducts`/`getProductDetail`/`compareProducts`/`getMemberCouponsForProduct`；参数严格拒绝额外字段（含 url/method/headers/token/sql）与类型强制，报错前门户调用次数为 0；搜索分页固定 5；详情用门户派生可售库存（最多 10 SKU / 5 公开券）；比较 2..3 个按 ID 顺序标记成功/不存在/异常；会员券只取本人未使用历史券与商品适用券的 `couponId` 交集，游客零会员接口调用；卡片事实优先级 `detail > compare > search`、去重保序、最多 5 张、详情路径固定。**定向返工（只读审查 D1/D2/D3）**：D1 商品 ID 改用新增的 `strictLong`（32 位参数仍用 `strictInt`），三个 long ID schema 显式声明 `minimum=1`、`maximum=Long.MAX_VALUE`，`2147483648` 在三处被接受并原样传入门户、超 `Long.MAX_VALUE` 被拒且零调用；D2 覆写 `ToolContext.toString()` 为脱敏形式，不输出 backend 文本表示、完整 Token 或 Bearer 内容（guest/member 可安全区分）；D3 更正报告措辞——`spData` 缺失仅属 detail 的 SKU 条目（`storefront.dto.SkuStock` 未建模），Python `compareProducts` 本就不输出 SKU 条目，不涉及 compare。**已知接口不足（未越界改接口）**：同上 `spData`，如需该字段须单独授权扩展 DTO。**流程偏差（如实记录）**：Step 1/2/4 与返工的红灯为「目标类缺失的编译失败」或行为失败并已留档；Step 3 的编译红灯输出未单独留存，报告只记录其后实测到的 2 处测试自身缺陷与 GREEN，不伪造红灯日志。**尚未提交**：工作树 `codex/java-agent-migration` 的 `mall-agent/` 目录仍为 untracked，等待主 Agent 按流程审查后提交。报告：`.codebuddy/reports/java-agent-tools-report.md`。

### Task 9：实现受限工具循环和 Stub 对话评测

**文件：**
- 创建：`agent/AgentOrchestrator.java`、`agent/ConversationBuilder.java`、`agent/AgentTurnResult.java`
- 创建（支持类型）：`agent/AgentTurnRequest.java`、`agent/AgentLimits.java`、`agent/ToolCallRecord.java`、`agent/ToolRoundLimitException.java`
- 创建：`evals/EvaluationCase.java`、`evals/StubEvaluationRunner.java`
- 创建：`src/test/resources/evals/cases.json`（Task 1 的对照副本）
- 创建：`src/test/resources/evals/stub_conversations.json`（Stub 确定性对话脚本；前 9 段为 Python fixture 语义对照副本，后 2 段为 Java 边界评测新增）
- 测试：`agent/AgentOrchestratorTest.java`、`evals/StubEvaluationTest.java`

**接口：** `AgentOrchestrator.run(AgentTurnRequest): AgentTurnResult`；最多 4 轮工具调用；每个工具调用先经 `ToolRegistry`；工具错误作为结构化结果返回模型但不得成为成功事实；卡片由 ProductCardBuilder 生成。

- [x] **Step 1：先测试工具调用循环和上限**

使用 FakeModelClient：无工具时直接回答；一次与多次工具调用按消息顺序执行；循环上限为配置值 4；第 5 轮产生固定 `TOOL_ROUND_LIMIT`，总工具调用不超过上限；未知工具和畸形参数不会发门户请求。

运行：`mvn -pl mall-agent -am -Dtest=AgentOrchestratorTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；模型消息中工具调用 ID/name/arguments 与规范协议对应。

- [x] **Step 2：测试事实回填、回答裁剪与异常**

模型回答只决定候选 ID 标记；从本轮工具事实生成卡片；超过 1500 字的回答被安全裁剪；模型/门户错误分别映射固定领域异常；模型不支持工具调用映射 503，上游超时/错误 502，循环超限 422。

运行：`mvn -pl mall-agent -am -Dtest=AgentOrchestratorTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；日志不包含提示词全文、商品完整响应或模型原始响应。

- [x] **Step 3：逐条迁移十项离线评测**

对 `evals/cases.json` 的每条用例断言允许/禁止工具、requiresLogin、最大工具调用次数、必需/禁止事实及卡片数。增加“领券拒绝零调用”“这张券能用吗游客要求登录”“公开优惠券继续公开搜索”三个确定性边界用例，不改变原十条语义。

运行：`mvn -pl mall-agent -am -Dtest=StubEvaluationTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：所有原 10 项及 3 个新增边界用例通过；测试结果展示逐例 ID，Stub 不访问模型供应商。

**Task 9 状态（2026-09-24，DeepSeek Flash；含审查返工：Report.allPassed 汇总自洽修复）**：受限工具循环与 Stub 对话评测实现完成，Step 1、2、3 全部勾选验收。

- **测试规模**：`AgentOrchestratorTest` 24 项、`StubEvaluationTest` 15 项全绿（合计 39 项）。
  - 全模块 `mvn -o -f mall-master/pom.xml -pl mall-agent -am test`（JDK 21.0.10）为 22 个测试类 **777 项 / 0 失败 / 0 错误 / 5 skip**；全部 XML mtime 落在本轮 2026-09-24 20:25:24–20:25:29；5 条 skip 为 `RedisSessionIntegrationTest` 真实 Redis 环境假设门控。
- **完成行为（实现要点）**：
  - 模型请求携带固定 system + 最近历史 + 本轮 user 与四个注册工具 schema（`tool_choice=auto`）；`assistant(tool_calls)` 与对应 `tool` 消息严格按 ID/name/arguments/顺序追加。
  - 最多执行 `AgentLimits.maxToolRounds()`（默认 4）轮，第 5 轮仍返回工具调用抛固定 422 `TOOL_ROUND_LIMIT`（`ToolRoundLimitException`）。
  - 未知工具/畸形参数以围栏 `REJECTED` 结果反馈模型且零门户请求；工具失败只反馈模型，其失败结果虽传入 `ProductFactCollector.add` 但被内部 `!ok` 过滤，不成为成功事实、不出卡片。
  - 写操作拒绝先于一切模型/门户调用；游客会员券工具 `LOGIN_REQUIRED` 置 `requiresLogin` 并停止后续模型轮。
  - 卡片事实只由本轮成功工具经 `ProductCardBuilder` 构造、模型仅提交候选 ID；回答按 1500 Unicode 码点裁剪（不切开代理对）并在清空后回退固定文案；`buildSessionUpdate` 只落用户/助手消息与最近卡片。
- **离线评测（13 条）**：`evals/cases.json` 13 条（Python 原十条语义 + `edge-coupon-claim-refusal` / `edge-guest-personal-coupon-requires-login` / `edge-public-coupon-public-search` 三条边界），逐例断言允许/禁止工具、`requiresLogin`、调用次数上限、必需/禁止事实与卡片数。
  - `PortalOperationAudit` 由 Mockito 门户替身的默认 `Answer` 在每个真实门户方法（含 `resolveMember`）调用时写入并在每条用例前 `reset()`，据 Python `_expected_backend_operations` 复核真实门户操作（拒绝与游客个人券零门户、会员券恰为 `listUnusedCouponHistory→listProductCoupons`、搜索/详情/比较各自对应）。
  - loader 校验对齐 Pydantic `extra="forbid"` 并更严格（显式要求 `maxToolCalls`/`productCardCount`、拒未知字段与重复 id、拒未注册工具/工具冲突/会员要求登录）。
- **TDD 证据（如实记录）**：
  - Task 9 首次 MCP 编码轮已超时且未返回完整回执，**初始编码轮没有可引用的单独红灯日志**，本状态不伪造该轮红灯。
  - 本轮 MCP 执行回执报告过审查返工 `Report.allPassed` 的红灯 **15 run / 1 failure / exit 1**，但**失败日志与报告文件未留存，不能称为独立可复核红灯**（当前工作树仅有绿灯 XML，修复后行为由代码与双断言可复核）。
  - 收紧 `allPassed()` 为「无失败且 `passed==total` 且 `outcomes.size()==total`」后绿灯 15 run / 0 failure / exit 0，并新增「不一致汇总 `Report(3,3,[],[])` 为 false」与「合法正例 `Report(1,1,[],单例)` 为 true」双断言。
- **审查结论**：无 P1/P2；P3-2 已修复。
- **P3-1（记录，交 Task 10 兜底）**：现有 Python 实现没有服务层个人券确定性预检（Python `orchestrator` 只在模型调用 `getMemberCouponsForProduct` 返回 `LOGIN_REQUIRED` 时才置位 `requiresLogin`），Task 9 编排器保持 Python 兼容，成功搜索与个人券请求同批次时可能同时返回商品卡。
  - 设计稿要求的游客个人券 0 模型/0 门户调用预检来源于未提交分支 `codex/agent-live-acceptance`，Task 10 的 `AgentChatService` 须在服务层实现并验证无商品卡，属相对现有 Python 的有意行为增强。
- **P3-3（记录）**：`buildSessionUpdate` 对空 `message` 的验证由 `SessionDocumentCodec` 下游执行，HTTP 路径下 `ChatRequest` 会先校验。
- **低风险差异**：1500 Unicode 码点回答上限为计划要求（Python 原运行时实际为 4000、且该常量未被使用）、Java loader 比 Pydantic 更严格、Stub fixture 复用 `ProductToolsTest` fixtures。
- **未运行**：真实 Redis、真实模型供应商、真实 mall-portal、Docker、Nginx、微信真机；`git diff --check` 退出码 0（仅设计稿 LF→CRLF 提示，无 whitespace error）。
- **尚未提交**：工作树 `codex/java-agent-migration`（HEAD `f7b822b`）下 `mall-agent/` 目录与计划文件仍为 untracked，**无 commit hash**，未提交/推送/合并，未执行任何 SQL 或真实外部写调用，等待主 Agent 按流程审查后提交。报告：`.codebuddy/reports/java-agent-orchestration-report.md`。

### Task 10：接通 API、身份解析和会话生命周期

**文件：**
- 修改：`api/AgentController.java`、`api/ApiExceptionHandler.java`
- 创建：`api/AgentChatService.java`
- 创建：`session/IdentityResolver.java`（如 Task 6 已建则仅实现）
- 测试：`api/AgentChatControllerTest.java`、`api/SessionControllerTest.java`

**接口：** controller 仅解析 HTTP/验证，再调用 AgentChatService；服务负责 token/身份、限流、会话 load、拒绝策略、编排和 save；Authorization 不从 request body 读取。

- [x] **Step 1：测试游客聊天、模型未配置、错误映射和限流**

MockMvc 覆盖成功聊天 envelope、模型无凭据 503、门户故障 502、限流 429 + Retry-After、并发 409、工具轮数超限 422；每个错误体不得包含测试 Key、上游响应文本或堆栈。

**固定顺序（安全返工后）：** 模型未配置 503 → 按规范 sessionId 占用并发键（同 sessionId 第二个请求 409）→ 预认证按真实客户端 IP 消耗一次 IP 桶（失效 Token 超 IP 配额时 429 且不触达门户 `/sso/info` 与会话）→ 身份解析（失效 Token 记入游客会话，只占 IP 配额）→ 身份确定后按 member/guest 会话键消耗一次会话桶 → 拒绝/编排。一次正常请求恰好各消耗一次 IP 与会话预算，不重复计 IP。

运行：`mvn -pl mall-agent -am -Dtest=AgentChatControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；限流或拒绝在模型调用之前执行。

- [x] **Step 2：测试游客个人券严格登录门槛**

对个人券正例，游客请求返回 HTTP 200 且 `requiresLogin=true`、无商品卡；断言 model mock 调用 0 次、coupon history/product endpoints 0 次、普通商品 search/detail 也不调用。将问题存入游客会话用于登录后恢复。

运行：`mvn -pl mall-agent -am -Dtest=AgentChatControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；公开优惠券查询仍允许普通公开工具流程；“帮我领优惠券”匹配写拒绝优先级，不返回个人券登录流程。

**Task 10 Step 2 状态（2026-09-24，DeepSeek Flash）**：游客个人券确定性登录门槛与写拒绝优先级在服务层 `DefaultAgentChatService` 实现完成，Step 2 勾选（Step 1/3 未动）。`AgentChatControllerTest` 由 15 项增至 22 项并全绿；全模块 799 项 / 0 失败 / 0 错误 / 5 skip，全 reactor BUILD SUCCESS。TDD 红灯（仅加测试、未改生产代码）为 **22 run / 5 failures / exit 1**（5 项新行为断言失败：3 项 `requiresLogin` 期望 true 实得 false、1 项期望 200 实得 500、2 项拒绝文案断言失败）。实现后红灯全部转绿，未使用 skip/failure.ignore 掩盖。**尚未提交**：工作树 `codex/java-agent-migration` 的 `mall-agent/` 目录仍为 untracked。**未验证**真实模型/真实 Redis/真实 mall-portal/Docker/Nginx/微信真机/真实会员券。报告见 `.codebuddy/reports/java-agent-task10-step1-test-fixture-report.md` 的“Task 10 Step 2 追加记录”一节（Task 10 整体报告待 Step 3 完成后统一生成）。

- [x] **Step 3：测试会员身份、会话恢复/迁移/清空**

用 `/sso/info` mock 将 Authorization 解析为 memberId；会员 session key 含服务端 memberId；登录后只在会员目标为空时把访客摘要迁移并删除 guest key；非空目标不覆盖；DELETE 幂等，失效 Token 按 requiresLogin 返回。GET/DELETE 不调用模型。

运行：`mvn -pl mall-agent -am -Dtest=SessionControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；改变客户端 body 的任何 memberId 不改变服务端身份。

**Task 10 Step 3 状态（2026-09-24，DeepSeek Flash）**：会员身份、会话恢复/迁移/清空在服务层 `DefaultAgentChatService` 实现完成，Step 3 勾选。新测试 `SessionControllerTest` 21 项全绿（用真实 `InMemorySessionRepository` 薄包装 + mock `MallPortalClient` 的 MockMvc 集成测试，验证真实仓储语义）；`SessionControllerTest`+`AgentChatControllerTest`+`AgentContractTest`+`AgentValidationTest` 合计 110 项全绿；mall-agent 模块 820 项 / 0 失败 / 0 错误 / 5 skip（仅 `RedisSessionIntegrationTest` 真实 Redis 门控），全 reactor BUILD SUCCESS。TDD 红灯（仅加测试、未改生产代码）为 **21 run / 18 failures / exit 1**（3 项既有守卫行为在实现前即通过），实现后全绿，未使用 skip/failure.ignore 掩盖。`Authorization` 与 Python `_authorization` 一致：`IdentityResolver.normalizeAuthorization` 提升为 `public static`，去首尾空白后同时用于身份解析与会员工具 `AgentTurnRequest`。**尚未提交**：工作树 `codex/java-agent-migration` 的 `mall-agent/` 目录仍为 untracked。**未验证**真实模型/真实 Redis/真实 mall-portal/Docker/Nginx/微信真机/真实会员券。**P2 遗留（提交/Task 12/13 前需关闭或明确决策）**：Python `asyncio.timeout(MALL_AGENT_REQUEST_TIMEOUT_SECONDS)` 的 35 秒整体请求超时在 Java 仍无执行时消费点，本 Step3 未实现，禁止用未经设计的 Future/后台任务补丁。报告见 `.codebuddy/reports/java-agent-task10-step1-test-fixture-report.md` 的“Task 10 Step 3 追加记录”一节（Task 10 整体报告待后续统一生成；Task 11–13 仍未完成）。**⚑ 更正（2026-09-25 最终收尾轮）**：本段“**P2 遗留**……35 秒整体请求超时在 Java 仍**无执行时消费点**”已过时——该 P2 已由请求级 deadline 实现关闭（`DefaultAgentChatService.runOrchestratedTurn` 以 `RequestDeadline.start(properties.getRequestTimeoutSeconds())` + `RequestDeadlineContext.callWith(...)` 同步包住 `orchestrator.run`，`MALL_AGENT_REQUEST_TIMEOUT_SECONDS` 有真实执行时消费点），仍未验证的是**真实模型慢响应 E2E**，详见下方“Task 10 最终收尾状态”。

**Task 10 审查返工状态（2026-09-24，DeepSeek Flash）**：收尾部分完成的 Task 10 审查返工，Step 1 勾选。请求级 deadline 已落地：新增 `deadline/` 包（`RequestDeadline` 以单调 `nanoTime`/可注入 ticker 记录总预算、`RequestDeadlineContext` 以 `ThreadLocal` + `finally` 移除、`RequestDeadlineExceededException` 无消息无 cause、`DeadlineClientHttpRequestFactory` 走 JDK `HttpClient` + Spring `JdkClientHttpRequestFactory` 的单请求整体超时），`DefaultAgentChatService.runOrchestratedTurn` 以 `callWith` **只包住 `orchestrator.run`**，超时不落库、`inflight` 在 `finally` 释放、固定 502 `AGENT_TIMEOUT`。**本轮修复两处缺陷**：（1）**P1 累计标记污染**——`RequestDeadline` 原以累计 `deadlineClamped` 记录「被 deadline 截断」，使「先被总预算截断但成功」的请求污染「后续未被总预算截断的 per-client 超时」，被误判为 `AGENT_TIMEOUT`；改为**请求级** `lastRequestClamped`，`DeadlineClientHttpRequestFactory.createRequest` 每次先 `beginRequest()` 重置再按本次 `min(configured, remaining)` 设置，`deadlineExceeded` 只读最近一次请求的标记。（2）**P2 身份解析错误码**——`getSession/deleteSession` 原把非 401 门户错误统一映射 502，与 Python `_resolve_identity` 的 generic 语义不符；改为原样传播并由 `ApiExceptionHandler` 收敛为固定内部 500，编排期商品/券门户错误仍保持 502。Javadoc 已明确区分「身份解析 `/sso/info` → generic 500」与「编排期 `/product/**`、`/member/coupon/**` → 502」。验证（离线、`JAVA_HOME=jdk-21.0.10`）：聚焦 `DeadlineTest`+`DeadlineHttpRequestFactoryTest`+`AgentChatControllerTest`+`SessionControllerTest` = **64 run / 0 failures / exit 0**（返工前基线 62 run / 4 failures / exit 1）；mall-agent 模块 **841 run / 0 failures / 0 errors / 5 skip / exit 0**（820 + deadline 包 14 + AgentChatControllerTest 净增 6 + SessionControllerTest 净增 1，计数自洽）；5 skip 唯一来源仍为 `RedisSessionIntegrationTest`（真实 Redis 门控）。loopback 真实 socket 用例通过：预算内终止且异常链含 `HttpTimeoutException`，`clampedRequestDoesNotPolluteLaterPerCallTimeout` 证明先截断成功请求不污染后续 per-client 超时。**未跑全 reactor**（上次全验收已耗尽 10 分钟窗口）。**未提交**：`mall-agent/` 目录与计划文件仍为 untracked（HEAD `f7b822b`），无 commit hash；未执行任何 SQL 或真实外部写调用。报告见 `.codebuddy/reports/java-agent-task10-step1-test-fixture-report.md` 的“Task 10 审查返工追加记录”一节。

> **⚠️ 计数与 P2 更正（2026-09-25 最终收尾轮实测）**：上方返工段中的聚焦 `64 run`、模块 `841 run` 及“`AgentChatControllerTest` 净增 6（22→28）”为**陈旧/错误**计数，重跑同一命令的实测为：聚焦 **66 run / 0 failures / exit 0**、模块 **843 run / 0 failures / 0 errors / 5 skip / exit 0**、`AgentChatControllerTest` 为 **30**（`22→30` 净增 8）。本段“未跑全 reactor”仍成立。**旧 P2 已关闭**（`MALL_AGENT_REQUEST_TIMEOUT_SECONDS` 有真实执行时消费点），仍未验证的是真实模型慢响应 E2E。

**Task 10 最终收尾状态（2026-09-25，DeepSeek Flash）**：本轮仅处理既有代码、报告与本节记录，未进入 Task 11、未碰部署。**本轮真实修复（3 处）**：（1）`DeadlineHttpRequestFactoryTest.clampedRequestDoesNotPolluteLaterPerCallTimeout` 第二次请求的截断标记采集由 `catch (RuntimeException)` 改为 **`finally`** 绝对执行（保留异常断言与 `false` 断言，未弱化）；（2）`DeadlineClientHttpRequestFactory` Javadoc 删除无法在环境证实的“已对照 spring-web 6.2.18 源码核实”及 `completeOnTimeout`/`cancel(true)`/超时信号 future 等过细内部机制，改为仅描述 API 层准确行为；（3）`AgentChatControllerTest` 类 Javadoc 去掉重复“编排/编排”，改为“编排阶段（商品/会员券门户读取）”。**本轮实测（JDK 21.0.10 / `mvn -o`，两条命令退出码均 0）**：聚焦四类 **66 run / 0 failures / 0 errors / 0 skip**（`AgentChatControllerTest` **30** / `SessionControllerTest` 22 / `DeadlineHttpRequestFactoryTest` 7 / `DeadlineTest` 7）；mall-agent 模块 **843 run / 0 failures / 0 errors / 5 skip**（逐 XML 聚合，唯一 skip 来源 `TEST-...RedisSessionIntegrationTest.xml` 的 5 条真实 Redis 门控）；证据 mtime 落在本轮 2026-09-25 11:05:13–11:05:22。**陈旧计数更正**：旧 `64`/`841`/`AgentChatControllerTest 28` → `66`/`843`/`30`（模块算式 820 + 14 + 净增 8 + 1 = 843）。**旧 P2（“无执行时消费点”）已关闭**：`DefaultAgentChatService.runOrchestratedTurn` 以 `RequestDeadline.start(properties.getRequestTimeoutSeconds())` + `RequestDeadlineContext.callWith(...)` **同步**包住 `orchestrator.run`，剩余预算耗尽在发网前抛 `RequestDeadlineExceededException`，编排返回后越过 deadline 亦不落库。**R5 不污染证据归属更正**：请求级不污染的真实网络证据归属 loopback 用例 `DeadlineHttpRequestFactoryTest.clampedRequestDoesNotPolluteLaterPerCallTimeout`；`AgentChatControllerTest.clampedRequestDoesNotPolluteLaterPortalTimeoutMapping` 只调用 `createRequest` 不发网，不作真实网络证据。“成功网络请求”表述更正为“该次请求超时被收紧到剩余预算（clamped）但因 `/fast` 立即响应而正常完成”。历史红灯文字按原文保留；经盘点 `target/surefire-reports/` 无保存的历史红灯日志，**未独立保存的红灯已明确标注本轮无法复核**。**仍未验证**：真实模型慢响应 E2E、真实 Redis、真实 mall-portal、真实会员优惠券、Docker、Nginx、微信真机；本轮未跑全 reactor。**未提交**：`mall-agent/` 目录与计划文件仍为 untracked（HEAD `f7b822b`），无 commit hash；未执行任何 SQL 或真实外部写调用。报告见 `.codebuddy/reports/java-agent-task10-step1-test-fixture-report.md` 的“Task 10 最终收尾追加记录”一节。**Task 11–13 仍未完成**，本状态不代表整项完成。

### Task 11：Java Stub 全链路、Redis 与应用集成测试

**文件：**
- 创建/修改：`mall-agent/src/test/java/com/macro/mall/agent/MallAgentApplicationTest.java`
- 创建：`MallAgentRedisIntegrationTest.java`
- 修改：`mall-agent/src/test/resources/application-test.yml`

**接口：** Spring 上下文可启动且无 MySQL/MongoDB/RabbitMQ/Elasticsearch 自动配置或外部建连；测试通过 FakeModelClient、MockRestServiceServer 和 mock Redis 独立运行。

- [x] **Step 1：用 `@SpringBootTest` 验证服务模块隔离**

断言上下文启动、端口配置生效、Bean 图不包含 `DataSource`/MyBatis/Elasticsearch client/Mongo client/Rabbit client；Redis 与门户连接均替换为测试替身。避免通过 `@Disabled` 或空测试满足绿灯。

运行：`mvn -pl mall-agent -am -Dtest=MallAgentApplicationTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：PASS；日志无外部依赖连接拒绝。

- [x] **Step 2：运行显式 Redis 集成用例**

仅当 `MALL_AGENT_TEST_REDIS_URL` 指向用户明确提供的本地测试 Redis DB 时启用；key 使用 `mall:agent:test:{UUID}`，测试结束逐 key 删除，不 flush DB。

运行：`mvn -pl mall-agent -am -Dtest=MallAgentRedisIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

预期：无地址时明确 skip；给出地址时保存、读取、TTL 和 guest/member 隔离通过。

- [x] **Step 3：运行模块和全 reactor 测试**

运行：`mvn -pl mall-agent -am test`，再运行 `mvn -f mall-master/pom.xml test`。

预期：全部模块 BUILD SUCCESS；不带 `maven.test.failure.ignore`，日志没有 “Tests are skipped”；出现依赖网络失败时标为环境阻塞并重试可访问 Maven 仓库的环境，不调整测试。

**Task 11 状态（2026-09-25 最终收尾，DeepSeek Flash）**：Step 1 / Step 2 / Step 3 全部勾选，交付如下。**文件**（仅这些）：`src/test/java/com/macro/mall/agent/MallAgentApplicationTest.java`、`src/test/resources/application-test.yml`、`src/test/java/com/macro/mall/agent/session/MallAgentRedisIntegrationTest.java`，以及 `src/test/java/com/macro/mall/agent/session/RedisSessionIntegrationTest.java`（Task 6 既有文件的 gate 与 10 秒 TTL 调整）。**Step 1**：`MallAgentApplicationTest` 5 项，以真实 `MallAgentApplication` 启动 MOCK Web 上下文（**不绑真实端口**），test profile 隔离 Redis 自动配置，模型 / mall-portal / `StringRedisTemplate` 均用 Mockito 替身；AgentProperties 的全部 22 个 `MALL_AGENT_*` 变量以安全测试值逐一钉住，API Key 置空，`server.port` 只由 `application.yml` 的 `MALL_AGENT_PORT` 占位符解析（非自证式断言）。**TDD 红灯（范围与证据）**：临时移除 Redis 排除后同一类 **5 run / 1 failure**，精确断言 `RedisConnectionFactory` 不存在却被创建，该轮日志**无 connect / 6379 连接指纹**；恢复后 **5/5 通过**且配置字节恢复一致。**Step 2（⚠️ 遵守门控、未实测）**：系统**未设置** `MALL_AGENT_TEST_REDIS_URL`，故 `MallAgentRedisIntegrationTest` 的 3 项真实仓储用例（保存读取 TTL、TTL 续期、guest/member 隔离）**全部 skip**，`RedisSessionIntegrationTest` 的 5 项真实用例同样 skip——**真实 Redis 未经实测，不宣称验证通过**；两类清理只对本次登记的唯一 `mall:agent:test:{runId}:` UUID 键逐个 DELETE，绝不 FLUSH。**Redis gate 修复**：显式非法/remote 地址此前会静默 skip，已修为 `Target.resolve` 仅在**缺失/空白**时返回 empty，显式错误值 / 非 loopback / 0 号库抛**固定且不含配置值**的异常，仅本机 loopback + 非 0 库接受；门控纯函数红灯（旧静默 skip 行为）**4 项失败** → 绿灯**8 项解析通过**；真实网络用例的连接建立推迟到门控之后。**TTL 抖动修复**：两处"压小 TTL"由 3 秒硬编码改为 **10 秒安全余量**。**Step 3 实测（JDK 21.0.10 / `mvn -o`）**：新类聚焦 `MallAgentApplicationTest` **5/5**；门控聚焦 `RedisSessionIntegrationTest,MallAgentRedisIntegrationTest` **16 run / 8 pass / 8 skip**；mall-agent 模块 **853 / 0 failure / 0 error / 8 skip**；最终全 reactor `mvn -o -f mall-master/pom.xml test` **退出码 0 / BUILD SUCCESS**，8 模块全 SUCCESS，合计 **1189 run / 0 failure / 0 error / 8 skip**（mall-demo 2、mall-admin 63、mall-search 97、mall-portal 174、mall-agent 853；`mall-common`/`mall-mbg`/`mall-security` 为 `No tests to run`＝无测试源码，不是 skip），64 个 XML mtime 落在 2026-09-25 12:13:21.556–12:14:09.658。**8 项 skip 确切来源**：`RedisSessionIntegrationTest` 5 条 + `MallAgentRedisIntegrationTest` 3 条，均因 `MALL_AGENT_TEST_REDIS_URL` 未配置。**独立只读审查**：此前 4 项审查问题均已关闭，无 P1/P2，低 TTL 抖动已修。**业务/生产 API 无变化，配置仅新增 test profile，无数据库影响，未执行任何 SQL 或真实外部服务。未提交/未推送/未合并**（HEAD `f7b822b`，`mall-agent/` 与计划仍 untracked）。**未验证**：真实 Redis、真实模型、真实 mall-portal/会员券、Docker、Nginx、微信真机。报告：`.codebuddy/reports/java-agent-task11-report.md`。

**Task 11 补充实测（2026-09-27）：独立临时 Redis 实例上的真实 Redis 集成测试**：本条为 **2026-09-27** 在**独立临时 Redis 实例**上对 Task 11 真实 Redis 用例的补充实测，**不改动**上方 2026-09-25 的 Task 11 状态与其余历史记录。它**取代**的仅是「真实 Redis 未实测 / 8 项门控用例因未配置 `MALL_AGENT_TEST_REDIS_URL` 而 skip」这一**当轮当时**结论，**不取代**任何其他结论。

- **测试环境（独立临时实例，非共享服务）**：主 Agent 新建了一个**独立的临时 Redis 8.2.3 实例**（Windows 版，二进制与 Surefire XML 记录的 `PATH` 中 `D:\redis\Redis-8.2.3-Windows-x64-msys2-with-Service` 一致），监听 **`127.0.0.1:16380`**、**关闭持久化**、使用 **DB15**；它**不是**既有 Redis Windows 服务（该服务仍在 `127.0.0.1:6379` 运行），也**不是**任何共享容器。
- **门控开启方式**：仅在当次 Maven 进程上设置**进程级** `MALL_AGENT_TEST_REDIS_URL`（指向该临时实例的 loopback + 非 0 库）；未写入任何配置文件、未修改 `.env`。
- **执行命令与退出码**：

```bash
mvn -o -q -f mall-master/pom.xml -pl mall-agent -am \
  '-Dtest=RedisSessionIntegrationTest,MallAgentRedisIntegrationTest' \
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

  退出码 **0**。
- **Surefire XML 实测**（`mall-master/mall-agent/target/surefire-reports/`，文件 mtime 2026-09-27 10:05:21）：

| 测试类 | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- |
| `RedisSessionIntegrationTest` | 13 | 0 | 0 | 0 |
| `MallAgentRedisIntegrationTest` | 3 | 0 | 0 | 0 |
| **合计（本次聚焦选择）** | **16** | **0** | **0** | **0** |

  其中 `RedisSessionIntegrationTest` 的 13 项 = **8 项门控纯函数用例**（`productionRedisUrlIsNeverUsedAsFallback`、`redissLoopbackTargetEnablesTlsAndParsesCredentials`、`illegalConfigurationFailsFastInsteadOfBeingSkipped`、`localhostAndIpv6LoopbackAreAccepted`、`localLoopbackNonZeroDatabaseTargetIsParsed`、`explicitRemoteHostIsRejected`、`rejectionMessageIsFixedAndNeverLeaksTheConfiguredValue`、`missingOrBlankTestRedisUrlDisablesIntegration`）+ **5 项真实用例**（`guestAndMemberNamespacesAreIsolatedInRedis`、`savesAndLoadsSessionAgainstRealRedis`、`rateCounterIncrementsAndKeepsExistingTtlAgainstRealRedis`、`corruptDocumentIsRemovedFromRealRedis`、`loadRenewsTtlAgainstRealRedis`）；`MallAgentRedisIntegrationTest` 的 3 项（`loadRenewsTtlInsteadOfOnlyReading`、`guestAndMemberSessionKeysAreIsolatedInRealRedis`、`savesAndLoadsGuestSessionWithConfiguredTtl`）**全部为真实用例**。故本轮**共 8 个真实 Redis 用例（5 + 3）实际建立连接并执行，0 skip / 0 failure / 0 error**。
- **零残留**：运行结束后该临时实例 **DB15 `dbsize=0`**（用例自行逐 key 删除本次 `mall:agent:test:{runId}:` 前缀键，未 `FLUSH` DB）。
- **实例关闭（仅限已确认的临时实例）**：仅对**已确认的**该临时实例（PID `4324`、loopback `16380`）执行 `redis-cli shutdown nosave`，**未触碰**任何共享 Redis；实测**当前 `127.0.0.1:16380` 已无监听**（端口关闭），**原 Redis Windows 服务仍 `Running`**（`127.0.0.1:6379` 仍监听）。
- **与既有计数口径的关系**：本条聚焦 **16 run / 0 skip** 与上方 Task 11 状态 / Task 13 记录中的**全 reactor 计数（1189 或 1243 等）是不同运行**，**不得相加**；本次**未**重跑全 reactor，**不覆盖**任何全 reactor 历史计数。
- **证据边界（不得外推）**：本条补证**仅**证明「真实 Redis 下的会话仓储 / 限流计数 / guest-member 隔离 / TTL 续期 / 损坏文档清理」这一层，它**不等于**也**不构成**以下任何验收：**共享容器 / 共享 Redis** 运行时验收；**真实模型**（`MALL_AGENT_MODEL_MODE=live` + 真实 Key）响应验收；**真实会员 Token / 会员优惠券**验收；**`/agent/chat` 或经 Nginx 的 `/agent-api/agent/chat` 端到端**链路验收（该端点会写 Redis 会话 / 限流键，本轮**未调用**）。因此**不勾选 Task 12 Step 4、不勾选 Task 13**（两者仍为未完成）。
- **零写与 Git 纪律**：本轮**未**执行任何 SQL、未写共享 Redis、未 commit / push / merge；仅修改 `docs/superpowers/plans/2026-09-23-java-product-shopping-agent.md` 与 `.codebuddy/reports/java-agent-security-rework-report.md`。

### Task 12：切换 Compose / Docker、校验配置与文档

**文件：**
- 修改：`docker-compose.yml`
- 复用：`document/docker/Dockerfile.app`（通过 `MODULE=mall-agent`、对应 JAR 名、`APP_PORT=8086`）
- 按实际差异修改：`.env.example`、`document/docker/check-agent-env.ps1`、`document/docker/check-agent-env.sh`
- 更新：`mall-shopping-agent/README.md`、`document/agent/product-shopping-agent.md`、`document/docker/local-startup.md`、`docs/context/project-handoff.md`
- 测试：`mall-shopping-agent/tests/unit/docker/test_compose_config.py`（保留 Python 历史行为测试，但不因退役而批量删改）

**接口：** Compose 外部 service key 继续是 `mall-shopping-agent`，运行镜像 Java、容器端口 8086、健康探针 `/health/live`、依赖 Redis 与健康的 mall-portal、Nginx upstream 不改；根 `.env` 不挂载，模型 Key 单独注入白名单变量。回滚映射明确为原 Python `context: ./mall-shopping-agent` + `dockerfile: ../document/docker/Dockerfile.agent`；执行回滚前先确认用户现有容器状态，不在验收失败时自行改动共享服务。

- [x] **Step 1：先写 Compose 静态约束断言**

断言 service key、profiles、port host bind、Redis/portal depends_on、MALL_AGENT_* 变量、health endpoint、Nginx upstream/path 保持；build context 指向仓库根，Dockerfile 指向 `document/docker/Dockerfile.app`，build args 指向 `mall-agent` 与 JAR 文件；配置中不引用 `Dockerfile.agent` 作为运行构建。

运行：`python -m pytest mall-shopping-agent/tests/unit/docker/test_compose_config.py -q`

预期：切换实现前新断言失败；修改后 PASS；已有检查器对 `.env.example`、stub 配置、重复键和敏感值不输出的测试均保持通过。

**Step 1 状态（2026-09-25，DeepSeek Flash）**：已在 `mall-shopping-agent/tests/unit/docker/test_compose_config.py` 新增 6 个静态用例（`test_java_env_whitelist_constant_matches_agent_properties_source`、`test_java_build_reuses_app_dockerfile_from_repo_root`、`test_java_build_app_dockerfile_compiles_module_from_root_source`、`test_java_healthcheck_uses_curl_on_liveness_endpoint`、`test_java_env_injects_exact_agent_properties_whitelist`、`test_root_dockerignore_excludes_worktrees_and_secrets`），22 变量白名单以 AgentProperties.java 源码实际读取为准并与测试常量互校。**真实红灯**（仅加测试、未改 Compose）：`-k 'java_build or java_healthcheck or java_env or root_dockerignore'` = **4 failed / 2 passed / exit 1**，失败点分别是旧 Python build context、Python healthcheck、缺 9 个 whitelist 变量、root `.dockerignore` 未排除 `.worktrees/`。实现后同一选择 **6 passed**，全文件 **113 passed / exit 0**（Python 历史 Dockerfile/脚本测试无回归）。

- [x] **Step 2：切换 compose build target 并校验两组 profile**

Compose build 使用已有 Dockerfile.app 的 `MODULE=mall-agent`、`JAR_FILE=mall-agent-1.0-SNAPSHOT.jar`、`APP_PORT=8086`；保留 Python Dockerfile 和源代码但不再参与 `mall-shopping-agent` build。

运行：`docker compose --env-file .env.example config --quiet`；`docker compose --env-file .env.example --profile app --profile edge --profile observability config --quiet`

预期：两命令退出码 0；全部发布端口绑定约定不变；只要配置文件仍引用 Python build context，即本步骤失败。

**Step 2 状态（2026-09-25）**：`docker-compose.yml` 的 `mall-shopping-agent` build 已改为 `context: .` + `dockerfile: document/docker/Dockerfile.app` + args `MODULE=mall-agent` / `JAR_FILE=mall-agent-1.0-SNAPSHOT.jar` / `APP_PORT=8086`，healthcheck 改为镜像内 `curl -fsS http://127.0.0.1:8086/health/live`（不探 `/health/ready`），environment 精确注入 22 个 `MALL_AGENT_*` 白名单变量 + `TZ`（外部凭据仅 `MALL_AGENT_OPENAI_API_KEY`，允许为空，无 `env_file`）；根 `.dockerignore` 新增 `.worktrees/`。service key、`app` profile、`127.0.0.1:${AGENT_PORT:-8086}:8086`、容器端口 8086、`depends_on` 为 Redis 与 mall-portal healthy、Nginx `/agent-api/` 与 `mall-shopping-agent:8086` 均未改（Nginx 配置零改动）。两命令均 **exit 0**，渲染确认 22 个变量与端口绑定 `host_ip: 127.0.0.1`。Python `Dockerfile.agent`、`mall-shopping-agent/.dockerignore` 与源码保留未动。

- [x] **Step 3：构建 Java 镜像和独立容器验收**

运行：`docker compose --env-file .env.example --profile app build mall-shopping-agent`

预期：Java 17 镜像构建成功，运行镜像命令是 `java -jar`；Docker 未运行或基础镜像不可达时仅报告环境阻塞，不切换真实 `.env`、不重建用户现有容器。启动验收前先只读检查现有 Compose 容器状态、目标网络、端口与最终渲染配置；如果会替换当前共享的 `mall-shopping-agent` 容器，先向用户说明将被替换的容器和回滚命令并取得确认。确认后只操作目标 service，不重建依赖或其他应用。若 Java 健康/路由验收失败，先停止验收并报告；经用户确认后可把 Compose build 配置恢复为上述原 Python context/Dockerfile，再构建并启动同名 service 回滚，不能清理 volume 或其他服务。

**Step 3 状态（2026-09-25）**：**阻塞，未执行**。本机 Docker CLI 存在（`Client 29.5.3`）但 daemon 不可连接：`docker ps` 报 `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine ... The system cannot find the file specified`。按计划约定「Docker 未运行时只报告环境阻塞」，本轮**未**执行 `docker build`、`docker compose up`，未改动真实 `.env`，未替换任何现有容器。Java 镜像构建、`java -jar` 启动与容器健康验收均**未验证**。

**Step 3 状态（2026-09-25 追加，Docker daemon 恢复后的只读复测）**：daemon 已恢复，但本轮**未执行 `docker build`**，Step 3 **仍保持未勾选**。只读复测发现：运行中的 `mall-local-mall-shopping-agent-1` 容器的 Compose 标签指向**主仓库**（`com.docker.compose.project.working_dir=F:\code\mall`、`config_files=F:\code\mall\docker-compose.yml`），命令为 `python -m uvicorn mall_shopping_agent.main:app`，镜像 `mall-local/mall-shopping-agent:local`（`sha256:cadfbb02…`）构建于 2026-09-23；镜像清单中**无 Java 镜像**。即该容器是主仓库旧 Python 实现，**不能确认来自本工作树当前 Compose**，其 `healthy` 与端口 200 均不构成 Java 镜像与容器的验收证据。详见报告 `.codebuddy/reports/java-agent-task12-report.md` 第 11 节。

**Step 3 状态（2026-09-25 Compose service replacement 验收轮）**：**勾选完成**。本轮用 `up --build` 一次性完成 Java 镜像构建与目标 service 替换，**不是**单独运行 `docker build`（此前轮次的「缺单独 `docker build` 退出码回执」由本轮 `up --build` 的退出码覆盖，但**不得伪称**本轮另跑过独立 build 命令）。准确事实如下：

- **命令与退出码**：在 `F:\code\mall\.worktrees\java-agent-migration` 执行
  `MALL_AGENT_MODEL_MODE=stub docker compose --env-file .env.example --profile app --profile edge up -d --no-deps --build mall-shopping-agent` → **exit 0**；Compose project = `mall-local`；`--no-deps` 只构建/启动目标 service，不重建依赖。
- **模式值来源（精确）**：命令前在**进程环境**显式设置 `MALL_AGENT_MODEL_MODE=stub`；`.env.example` 第 144 行本身是 `MALL_AGENT_MODEL_MODE=openai`；Compose 变量插值优先级为 **shell 环境 > `--env-file`**，故 stub 来自 **shell 环境**，**不是**由 `.env.example` 提供。不得写成由 `.env.example` 提供 stub；本次未输出其他环境变量。
- **替换前后**：Python 容器 `mall-local-mall-shopping-agent-1`（ID `4907a3026b89…` / ImageID `cadfbb…`）被替换为 Java 容器（ID `b39c8d3193d10005dd6cc1108dfa57be73278f9aa3459f0693bd84ef6a896a49` / ImageID `sha256:a71b6e8ede5abd4619f55a6fa19bf008594b2dfe3e14b9679c3ac0bb0c8258fa`）。
- **Java 运行事实**：Entrypoint `sh -c exec java $JAVA_OPTS -jar /app/app.jar`；端口仍 `127.0.0.1:8086`；新容器 Compose labels 指向**当前工作树** `F:\code\mall\.worktrees\java-agent-migration` 与其 `docker-compose.yml`；health `healthy`；日志为 Spring Boot 3.5.14 / Java 17 / Tomcat 8086。
- **其他服务无扰动**：其余容器 ID / health / 启动时间未变；Redis / mall-portal / Nginx 均 `healthy`。
- **与先前记录的轮次区别**：第 11 节（只读复测）结论「仍是主仓库共享 Python 容器、未替换」与第 12 节（隔离容器轮）结论「唯一 Java 镜像 + 一次性容器、缺 `docker build` 回执、未替换共享 service」均为**当时有效**的历史事实，已被本轮 Compose service replacement 取代。
- **未做**：未调用 `/agent/chat`（该端点会写 Redis session / rate keys）；未做任何 Redis/DB 写入、SQL、git 操作或文件外变更。
- **回滚命令（仅命令，未读取任何 `.env` 值）**：**当前未合并状态下**，Java 迁移改动只在本工作树，主仓库 `F:\code\mall\docker-compose.yml` **仍是原 Python 构建**（`context: ./mall-shopping-agent` + `dockerfile: ../document/docker/Dockerfile.agent`，无 build args），`F:\code\mall\.env`、Python 构建上下文与 `Dockerfile.agent` 均存在，故**无需改任何 Compose**：`Set-Location F:\code\mall` 后执行
  `docker compose --env-file .env --profile app up -d --no-deps --build mall-shopping-agent`（只用主仓 Python Compose + 真实本机 `.env`，`--no-deps` 只重建目标 service，**不输出**任何 `.env` 值）。**不要**改成修改 Java 工作树 Compose 再用 `.env.example` 启动（`.env.example` 含占位值）。**将来 Java 合并入 `main` 后**，主仓 Compose 会转为 Java 构建，届时才需先把 build 段恢复为原 Python `context: ./mall-shopping-agent` + `dockerfile: ../document/docker/Dockerfile.agent`（删除 `MODULE`/`JAR_FILE`/`APP_PORT`，healthcheck 恢复 Python 标准库探测），再执行**同一条只重建目标服务**的命令（仍用真实本机 `.env`，不输出其内容）。
- 详见报告 `.codebuddy/reports/java-agent-task12-report.md` 第 13 节。

**Step 3 / Step 4 环境复查状态（2026-09-26，仅文档，只读）**：本轮对当前环境做独立只读复查，`docker inspect` 因 `npipe:////./pipe/dockerDesktopLinuxEngine` **不存在**而失败，`curl` 访问 `http://127.0.0.1:8086` 与 `http://127.0.0.1:8088` 均返回 **`000`**（无连接）。这是 **2026-09-26 当天环境不可达**，**不能**据此断言 Java 容器当前仍在 running / healthy；它**不推翻** 2026-09-25 Compose service replacement 验收轮的历史实测证据（`up --build` exit 0、Java 容器 healthy、经 Nginx `/agent-api/health/live` 200），故 **Step 3 保持 `[x]`、Step 4 保持 `[ ]`（部分完成）**，无需改回未完成。本轮**仅修改文档，无任何新的容器操作**。详见报告 `.codebuddy/reports/java-agent-task12-report.md` 第 14 节。

**Step 3 / Step 4 后续环境状态（2026-09-26 后续，仅文档，只读）**：上一条记录的「2026-09-26 当天 Docker 环境不可达」是**当轮当时**的历史事实；其后的实际状态已变化，**不得**再据此认为 Docker 当前仍不可用：

- **Docker daemon 已恢复**：2026-09-25 Compose service replacement 轮替换出的 Java 容器 `b39c8d3193d1`（`mall-local-mall-shopping-agent-1`）**仍为 healthy**（依据 2026-09-26 后续只读 `docker inspect`；未读取、未输出其 `Config.Env`）。
- **新源码镜像重建未成功**：按本工作树**当前源码**（`AgentHealthConfiguration` 两个**真实探针** + Redis `connect-timeout` / `timeout` 固定 `2s`）重建镜像的尝试，停在 `document/docker/Dockerfile.app` 构建阶段的 `mvn ... package`，**长时间无进展后中止，命令退出码 1**；**旧容器 `b39c8d3193d1` 未被替换**（仍由 2026-09-25 镜像承载）。
- **新探针就绪结果仍未运行验证**：当前源码「门户真实固定匿名只读探针 + Redis `PING` 均健康时 `/health/ready` 返回 200 UP，否则 503 DOWN」**尚未在任何运行容器上复测**；运行中的容器仍是 2026-09-25 旧镜像（占位探针，ready 恒 503）。因此**不得**据当前源码断言「运行容器 ready 200」，容器 `/health/ready` 与 `/agent/chat` 的实际结果必须以**重建镜像并重新启动后**的 HTTP 探测为准。
- **Task 12 Step 4**：**仍未完成**（**没有新镜像运行、没有聊天端到端`/agent-api/agent/chat`验收**）；**Task 13**：**仍未完成**。
- 本轮**仅修改文档与报告**，未改 Java / 测试 / Compose / `.env`，未 commit / push / merge，未执行任何 SQL。详见报告 `.codebuddy/reports/java-agent-readiness-report.md` 与 `.codebuddy/reports/java-product-shopping-agent-report.md`。
- 〔**2026-09-26 最终更正**：本块「首次重建尝试未成功 / **尚未在任何运行容器上验证** / 旧容器未替换」已被后续轮次取代——改用 **host 网络**重建成功（**exit 0、Maven BUILD SUCCESS**，镜像 `sha256:aa9fb0606788935444270bad4ead7708659003cf16c79802fc40fb48decd745e`），并以 stub + `.env.example` **仅替换目标服务**（新容器 `2077801e5c74…` healthy；Redis / mall-portal / Nginx 容器 **ID 不变且 healthy**）；直连 `8086` 与经 Nginx `8088` 的 `/health/live`、`/health/ready` 均 **200 UP**；`nginx -t` exit 0、Compose 全 profile `config --quiet` exit 0。**新镜像真实就绪探针已在容器运行中验证**。遗留：`/agent/chat`（会写 Redis 会话 / 限流键）未调用、真实模型 / 会员券 / 微信真机未验证；**Task 12 Step 4 保持 `[ ]`（部分完成）**、**Task 13 仍未完成**。见文末「最终状态」块。〕

- [x] **Step 4：Nginx 和 API 代理只读验证（2026-09-27 勾选完成；实测路径为临时隔离 app/Nginx）**

执行 `nginx -t` 或使用 Compose edge 容器 `nginx -t`；通过 `/agent-api/health/live` 验证 200。`/agent-api/health/ready` **在 2026-09-25 旧镜像（两个探针为 `() -> false` 占位实现）下恒 503**，**不能**作为 Redis / portal 状态证据；**当前源码已改为真实探针**（见下方「Step 3 / Step 4 后续环境状态（2026-09-26 后续）」）〔**2026-09-26 最终更正**：新镜像**已重建（host 网络，exit 0）并在容器运行中验证**，直连 `8086` 与经 Nginx `8088` 的 `/health/live`、`/health/ready` 均 **200 UP**，见文末「最终状态」块；**Step 4 仍保持 `[ ]`（部分完成）**，因 `/agent-api/agent/chat` 未验收〕。对 `/agent-api/agent/chat` 使用 stub 模式和公开商品只读查询；不得调用优惠券领券、购物车、订单、支付、库存写或 ES 写接口。

预期：Nginx upstream 仍解析 `mall-shopping-agent:8086`，移动端前端无需改 API 地址；真实模型/真实会员 Token 未配置时明确标为未验收。

**Step 4 状态（2026-09-25）**：**未验证**（随 Step 3 阻塞）。本轮只做了离线静态核对：`document/docker/nginx/conf.d/default.conf` **零改动**（`git status` 无该文件），静态测试中 `location /agent-api/`、`mall-shopping-agent:8086`、`rewrite ^/agent-api/?(.*)$ /$1 break;`、`proxy_read_timeout 40s;` 与 Authorization 透传断言全部通过。`nginx -t`、`/agent-api/health/live` 200 与 `/agent-api/agent/chat` stub 请求**未执行**，真实模型/真实会员 Token 未验收。

**Step 4 状态（2026-09-25 追加，Docker daemon 恢复后的只读复测）**：**仍保持未勾选**。只读探针结果：`docker exec mall-local-nginx-1 nginx -t` **exit 0**（`syntax is ok` / `test is successful`）；`GET http://127.0.0.1:8088/agent-api/health/live` **HTTP 200**，与工作树 Nginx 配置（与主仓库逐字一致）的 `location /agent-api/` → `mall-shopping-agent:8086`、`proxy_read_timeout 40s;` 定义一致。但该 200 的后端是**主仓库 Python 容器**（见 Step 3 追加说明），Step 4 明确**依赖 Step 3** 的 Java 运行时，故「与计划定义匹配」条件不成立，**不勾选**。本轮未请求 `/agent/chat` 或任何写 Redis/DB 接口。详见报告第 11 节。

**Step 4 状态（2026-09-25 Compose service replacement 验收轮）**：**部分完成，保持 `[ ]`**。

- **已通过**：`docker exec mall-local-nginx-1 nginx -t` → **exit 0**（`syntax is ok` / `test is successful`）；直连 `GET http://127.0.0.1:8086/health/live` **200**、经现存 Nginx `GET http://127.0.0.1:8088/agent-api/health/live` **200**（本轮后端已是本工作树 Java 容器）。
- **不能作为依赖证据**：直连与经 Nginx 的 `/health/ready` 均为 **503**；`AgentHealthConfiguration` 的 `portalHealthProbe` / `redisHealthProbe` 当前都是 `() -> false` 占位实现，`ReadinessService` 要求全部探针健康才就绪，故 ready 恒 503 **与 Redis / mall-portal 是否可达无关**，**不构成** Redis 或 mall-portal 的健康证据。〔**2026-09-26 后续更正**：此处「当前」指 **2026-09-25 旧镜像**；其后的源码已把两个探针改为**真实探针**，但**新镜像尚未重建/运行**，运行容器仍是旧镜像，见下方「Step 3 / Step 4 后续环境状态（2026-09-26 后续）」。**2026-09-26 最终更正**：新镜像**已用 host 网络重建（exit 0、Maven BUILD SUCCESS）并替换目标容器**，直连 `8086` 与经 Nginx `8088` 的 `/health/live`、`/health/ready` 均 **200 UP**，见文末「最终状态」块。〕
- **未执行**：`/agent-api/agent/chat` **未请求**；该请求会写 Redis session / rate keys，**不在本次零写约束内**，故明确留作余下验收。
- **余下验收**：在允许写 Redis 会话 / 限流键的环境下，用 stub 模式对 `/agent-api/agent/chat` 做公开商品只读查询，确认经 Nginx 的聊天链路；以及**重建镜像并重启后**验证真实 Redis / portal 探针下的 ready 语义。详见报告第 13 节。〔**2026-09-26 后续更正**：真实 Redis / portal 探针已在**当前源码**中实现（`AgentHealthConfiguration` + `PortalSearchHealthProbe` / `RedisPingHealthProbe`），但**新镜像重建未成功**（停在 `Dockerfile.app` 构建阶段的 `mvn ... package`，命令退出码 1），**旧容器未替换**，故 ready 语义**仍未运行验证**，见下方「Step 3 / Step 4 后续环境状态（2026-09-26 后续）」。**2026-09-26 最终更正**：改用 **host 网络**重建**成功（exit 0、Maven BUILD SUCCESS）**并替换目标容器，ready 语义**已在容器运行中验证（200 UP）**；但 `/agent-api/agent/chat` 仍未验收，**Step 4 保持 `[ ]`（部分完成）**，见文末「最终状态」块。〕



**Step 4 状态（2026-09-27 E2E 验收轮，勾选为 `[x]`）**：本轮以**临时隔离环境**完成计划要求的 stub 公开商品只读聊天链路，并加做真实模型公开查询，故勾选 Step 4。

- **实测路径是「临时隔离 app/Nginx」，不是主 Nginx chat 调用**：app 侧为 `eclipse-temurin:17-jre` 临时容器**只读挂载当前 JAR**；Nginx 侧为**临时容器只读挂载项目** `document/docker/nginx/conf.d/default.conf`；另建**独立临时 Redis（DB15、无持久化）**。因此本轮 `/agent-api/agent/chat` 的 `200` **不得**表述为「主 Nginx `8088` 的 chat 端到端验收」。
- **stub 模式实测**：`/agent-api/health/ready` **200**、`nginx -t` **exit 0**；`POST /agent-api/agent/chat` 对真实 `mall-portal` 公开搜索返回 **HTTP 200** 与 **5 张门户商品卡**（`id`/`name`/`price`/`stockStatus`/`availableStock`/`detailPath` 均非空）；`GET /agent/session/{sessionId}` 返回 **2 条消息 / 5 张卡**；`DELETE /agent/session/{sessionId}` 返回 `deleted=true`；游客个人优惠券问题返回 `requiresLogin=true` 且**无商品卡**。
- **真实模型加做**：`check-agent-env.ps1 -EnvFile F:\code\mall\.env` **exit 0**（仅确认 OpenAI-compatible mode/key/model 已配置与服务域名 `api.deepseek.com`，**不输出** Key、不输出完整 `.env` 或可疑 query）；以 **env 白名单**启动临时 live one-off 容器（只注入声明的 `MALL_AGENT_*`，Redis 覆盖到临时实例、portal 指向 `http://mall-portal:8085`，当前 JAR **只读挂载**），经 `/agent-api/agent/chat` 只发起**一条**「推荐一款手机」公开查询：**HTTP 200、答案非空、5 张卡字段为真实门户值**；session GET **2 messages**、DELETE **true**。
- **清理与零写**：临时容器与测试网络均已删除；测试端口 `18086`/`18087`/`18088`/`18089` 与 Redis `16380` **当前无监听**。该 E2E **只写被删除的隔离 Redis**，未调用共享 Redis，未做 MySQL 写、优惠券领取、购物车、订单、支付、库存修改或 ES 写。
- **仍未验证**：用户个人券的真实会员 Token / 会员券适用性**未测**；微信真机**未测**；主 Nginx `8088` 的 `/agent-api/agent/chat` 仍未在主栈上直接验收。

- [x] **Step 5：更新运行手册和环境校验说明**

README、agent 文档、local-startup 与 handoff 说明 Java 是运行实现、Python 源码被保留但 Compose 不再使用、Java 启动配置、stub/live 区分、Redis 可选集成测试与回滚方式。校验脚本只调整真实需要的变量检查，不显示实际值。

**Step 5 状态（2026-09-25）**：已更新 `mall-shopping-agent/README.md`（顶部状态横幅 + 环境变量/本地运行/Docker Compose 三处标注为 Python v1 参考）、`document/agent/product-shopping-agent.md`（顶部状态备注 + §12 部署段）、`document/docker/local-startup.md`（镜像表 + §4.7 + 回滚步骤）、`docs/context/project-handoff.md`（已完成模块 #7 追加 + Docker 验证基线追加）；`.env.example` 补齐 22 个可调非敏感默认值（无重复键、无 `sk-`/`Bearer` 真实值，真实 Key 仍为 `<请填写模型服务 API Key>` 占位）〔**括注（2026-09-25 返工后）**：此处「22 个可调」是当时的措辞；返工已改为 **A 段 7 个 Compose 可调 + B 段 15 个 Compose 容器固定值**的两段契约，`MALL_AGENT_REQUEST_TIMEOUT_SECONDS=35` 等安全/超时参数归入固定段，详见下方返工段〕；`Dockerfile.app` 注释覆盖 `mall-agent` 并修正「根 POM 默认 skipTests=true」的过时说法（现默认 false，`-DskipTests` 为镜像构建显式跳过）。**校验脚本未改**：`check-agent-env.ps1/.sh` 的必检集合仍是 model mode / base url / model / api key / portal url / redis url / AGENT_PORT / log level 共 8 项，新增变量均为可选，按计划「不是必填就不扩大检查器职责」保持原样；两脚本矩阵、重复定义与脱敏测试继续通过（`.env.example` 带占位 Key 仍返回退出码 1）。

**Task 12 独立审查返工状态（2026-09-25，DeepSeek-V4.1-Flash）**：针对独立审查的 5 条发现就地返工，未改 Java 生产逻辑、Python 生产服务或范围外配置。**（1）配置契约（核心）**：`.env.example` 把 22 个 `MALL_AGENT_*` 拆为两段 —— A 类「Compose 可调（宿主 .env 覆盖生效）」7 个（`LOG_LEVEL` / `CORS_ALLOW_ORIGINS` / `MODEL_MODE` / `OPENAI_BASE_URL` / `OPENAI_MODEL` / `OPENAI_API_KEY` / `SESSION_TTL_SECONDS`，Compose 以 `${VAR:-默认值}` 插值）；B 类「Compose 容器固定值（不通过宿主 .env 覆盖；仅供独立运行 / 本机 Java 参考）」15 个（含 `MALL_AGENT_REQUEST_TIMEOUT_SECONDS=35`，必须低于 Nginx 40 秒）。`docker-compose.yml` 在同处补契约注释、行为不变。**（2）`document/docker/local-startup.md`** 计数与表述按 compose 实测修正：常驻服务 10→11、「三个 Java 应用」→四个 Java 服务、端口统计 12 服务/17 条→13 服务/18 条（`0.0.0.0` 时其余 14→15 条）、健康检查「三项 actuator」改为「三个业务服务探 `/actuator/health` + `mall-shopping-agent` 探 `/health/live`」，并补全 `/agent-api/` 代理、edge-only 502 说明与启动顺序图。**（3）根 `.dockerignore`** 新增精确忽略 `.git` 文件（Windows linked-worktree 下 `.git` 是文件而非目录），保留 `.git/`。**（4）回滚说明** 按旧 Python 配置核实：明确删除 3 个 build args、恢复 Python 标准库 healthcheck（Python 镜像无 curl）、environment 可保留 22 个或还原 13 个、其余字段新旧一致。**（5）`docs/context/project-handoff.md`** 顶部更新时间 2026-09-22 → 2026-09-25。**TDD 证据**：新增 6 个静态用例（契约分区、插值、固定、35<40、`.env.example` 两段标注、`.git` 精确忽略），实现前 `-k` 选择 **2 failed / 4 passed**（失败点正是 `.env.example` 缺两段小标题与 `.dockerignore` 缺 `.git`），实现后 **6 passed**。

**Task 12 第二轮独立只读复核后的文档修正（2026-09-25，DeepSeek-V4.1-Flash）**：只读复核在 5 条发现的处理上总体确认成立，但发现 **1 个 P2 + 4 个 P3**，本轮**只改文档/注释与 1 个结构性守卫**，未改 Compose 运行行为。**P2**：`local-startup.md` §4.7/验证段与 `docker-compose.yml` healthcheck 注释此前把 `/health/ready` 写成"依赖 Redis 与 mall-portal、就绪返回 200"的运行事实，但 `AgentHealthConfiguration` 的 `portalHealthProbe` / `redisHealthProbe` 当前都是 `() -> false` 占位实现，`ReadinessService` 要求全部探针健康才就绪，**故 `/health/ready` 当前恒 503 + `{"status":"DOWN"}`，与依赖可达性无关**；已改为占位语义并新增"恒 503 不代表依赖故障"的排查提示，`/health/live` 写明恒 200 且是 Compose 唯一探针。Actuator 措辞改为"探针不依赖 Actuator、未实测的 endpoint 不下运行时结论"。**P3**：回滚段"4 类字段"改为"4 类配置 + 1 个执行/验证步骤"；本文件 Step 5 历史原文加括注指向 A/B 契约；报告契约表短名改全名；超时用例取常量一项经评估**接受现状**（同文件已把常量与 Compose 实际值逐字绑定）。**新增测试**：`test_compose_healthcheck_never_probes_not_ready_endpoint`（结构性守卫，断言 healthcheck 不含 `/health/ready`、含 `/health/live`；因本轮 Compose 行为未变，**该守卫实现前即通过、无红灯阶段**，如实记录）。**验证**：全文件 **120 passed / exit 0**（工作树无 `.venv`，用系统 Python 3.13.9），两组合 `docker compose config --quiet` 均 exit 0，`git diff --check` exit 0。**Task 5 / Task 6 的真实 readiness 探针接入属本任务范围之外，未实现**；Step 3 / Step 4 仍未勾选，Task 13 未开始。〔**2026-09-26 后续更正**：本段「真实 readiness 探针…未实现」为**当轮**结论；其后 `AgentHealthConfiguration` 已接入**两个真实探针**（`PortalSearchHealthProbe` 固定匿名只读 `GET /product/search?pageNum=1&pageSize=1` + `RedisPingHealthProbe` 只读 `PING`，Redis `connect-timeout`/`timeout` 固定 `2s`），当前源码 ready 语义为「两者均健康 200 UP，否则 503 DOWN」；但**新镜像尚未重建/运行验证**，运行容器仍是 2026-09-25 旧镜像（占位探针，恒 503），见「Step 3 / Step 4 后续环境状态（2026-09-26 后续）」。**2026-09-26 最终更正**：新镜像**已用 host 网络重建（exit 0）并替换容器**，运行容器现为**新镜像**、ready **200 UP**，见文末「最终状态」块。〕

运行：`rg -n 'Python 3\.11 \+ FastAPI|Dockerfile\.agent|mall-shopping-agent:8086' document\docker\local-startup.md document\agent\product-shopping-agent.md mall-shopping-agent\README.md docker-compose.yml document\docker\nginx\conf.d\default.conf`，逐个检查命中上下文；另运行两个 env checker 的 stub 与占位值用例。

预期：文档不再把 Python 描述为当前 Compose runtime；路径/历史说明可以出现但明确标为 legacy/reference；校验脚本输出不含变量值。

### Task 13：合并前审查证据与迁移报告

**文件：**
- 创建：`.codebuddy/reports/java-product-shopping-agent-report.md`
- 核查：本计划所有生产、测试、部署、文档路径；`git status` 与 `git diff`

**接口：** 报告包含完成概述、文件/接口清单、配置和 Redis 兼容、权限与只读边界、验证命令/结果、Git 状态、未验证的真实模型/真实会员/微信项目、运行回滚步骤和未解决项。

- [x] **Step 1：执行独立安全与敏感信息扫描**

检查 dependency tree 不含禁止客户端、Compose 只暴露既有 host binding、模型 Key 不入任何日志/Redis/响应、Authorization 仅到 portal 授权请求；`rg` 扫描不得把测试占位 Key 当真实泄漏，也不得将真实 key 显示在终端记录中。

运行：`git diff --check`；`git status --short --branch`；`git diff --stat`；`rg -n 'jdbc:|mysql-connector|mongodb|rabbitmq|elasticsearch-java|Authorization|OPENAI_API_KEY' mall-master\mall-agent\pom.xml mall-master\mall-agent\src docker-compose.yml`，仅检查路径/依赖和变量名，不打印 `.env`。

预期：diff 检查干净，受影响范围符合本计划，禁止数据库和写接口依赖为零。

**Step 1 状态（2026-09-25，DeepSeek-V4-Pro High，离线只读审查）**：仅完成 Task 13 Step 1 安全与敏感信息离线扫描，未做任何修复、未生成最终交接报告（Step 2 保持未完成）。证据与结论：

- **仓库状态**：HEAD `f7b822b`（分支 `codex/java-agent-migration`），`git worktree list` 显示主工作区 `main@56810c2` 与另两个旧工作树未受影响。已跟踪改动 11 个文件，未跟踪 2 项（`docs/superpowers/plans/...` 与 `mall-master/mall-agent/` 整模块）；`git diff --check` 退出码 0（仅设计稿 LF→CRLF 提示，无 whitespace error）；改动范围与文件边界一致，无 `mall-portal`/`mall-admin`/`mall-search`/`mall-common`/`mall-mbg`/`mall-security`/`mall-app-web-master` 越界写入。
- **依赖边界**：`mall-master/mall-agent/pom.xml` 仅声明 `spring-boot-starter-web`、`-data-redis`、`-validation`，继承父 POM 的 actuator/aop/hutool/lombok/configuration-processor/test；父 POM `<dependencies>` 无 MySQL/mall-mbg/MongoDB/RabbitMQ/Elasticsearch，`<dependencyManagement>` 中的 mysql/mybatis/mall-mbg 仅为版本管理、未被 mall-agent 声明引入。生产 Java 代码零 `java.sql`/`javax.sql`/`JdbcTemplate`/`DataSource`/`MyBatis`/Mongo/Rabbit/ES import，零 SQL 写入（`INSERT/UPDATE/DELETE/...` 仅命中 Javadoc 中 `DELETE /agent/session` 路由描述）。
- **只读业务边界**：`MallPortalClient` 仅五条固定 GET 路径（search/detail/sso-info/coupon-history/coupon-by-product），无任意 URL/method/header 入口，分页固定 5，baseUrl 拒绝内嵌凭据/查询/片段；`Authorization` 仅透传到 `/sso/info` 与两条会员券路径，搜索/详情绝不携带，非法头字符映射固定 502 文案。
- **凭据与脱敏**：模型 Key 经 `MALL_AGENT_OPENAI_API_KEY` 环境变量读取，占位即拒绝发请求；`AgentProperties.toString()` 对 `openaiApiKey`/`redisUrl` 打码；`OpenAiCompatibleClient`/`MallPortalClient`/`PortalException`/`ModelException` 错误均为固定安全文案，不保留 Key/Authorization/上游正文。`application.yml` 无任何 Key/Token/密码。`.env.example` 的模型 Key 仍为 `<请填写模型服务 API Key>` 占位、模型名为 `<请填写模型名>`。全仓库无 `sk-`/AKIA/PRIVATE KEY/ghp_/xox 等真实凭据指纹；测试中的 `Bearer placeholder-member-token` 等均为占位/合成值。
- **Compose 暴露面**：`docker-compose.yml` 端口绑定 `127.0.0.1:${AGENT_PORT:-8086}:8086` 未变，无新增 host binding；environment 为 22 个 `MALL_AGENT_*` + `TZ`，无 `env_file`、无 DB/MQ/ES 凭据。
- **关键未验证状态（如实记录）**：Task 12 Step 3（Docker 镜像构建）与 Step 4（Nginx runtime）仍受本机 Docker daemon 不可用阻塞、保持未勾选；`MallAgentRedisIntegrationTest`/`RedisSessionIntegrationTest` 的真实 Redis 用例在未配置 `MALL_AGENT_TEST_REDIS_URL` 时 skip；真实模型、真实会员 Token/优惠券、微信真机均未验证。上述未验证项不因本次静态扫描而改变。

- [x] **Step 2：记录完整验收并停止，不自行提交或推送**

报告分别列出：模块测试、全量 Maven 测试、13 项 Stub 评测、环境校验、Compose config、镜像构建、运行时代理/健康、Redis 实测、真实模型、真实会员优惠券、微信真机；未实测项标注“未验证”，不把配置校验写成运行时验证。

预期：生成 `.codebuddy/reports/java-product-shopping-agent-report.md`；聊天只回执报告路径、实现状态、阻塞和 Git 状态。之后由主 Agent 按 AGENTS.md 执行审查、中文提交和本地合并；推送必须另行取得用户明确确认。

> **当前勾选语义（以 2026-09-28 当前有效结论为准）**：本步现为 `[x]`，含义是**完整验收报告与未验证清单已记录**；**不代表**微信真机 / 正式域名已验收——**微信开发者工具 / 真机验收仍需用户扫码配合，当前未完成**。真实模型主栈与会员优惠券验收已由 2026-09-28 单独完成（见文末当前有效结论）。下方 2026-09-25 / 2026-09-26 的状态段为**该时点历史记录**。

**Step 2 状态（2026-09-25）**：**保持未勾选**。完整验收报告 `.codebuddy/reports/java-product-shopping-agent-report.md` **尚未生成**；真实模型 / 真实会员 Token / 微信真机 / `/agent/chat` 运行时链路均未验证，**Task 13 仍未完成**。**原因更新（2026-09-25 Compose service replacement 验收轮）**：本段原写「Task 12 Step 3 / Step 4 仍未关闭（Java 镜像缺 `docker build` 退出码回执、Compose service replacement 未做、Nginx 运行时仍指向旧 Python 容器）」**已过时**——Task 12 **Step 3 已勾选完成**（`up --build` exit 0、Compose service 已替换为 Java 容器），**Step 4 部分完成**（保持 `[ ]`）：`nginx -t` 与经 Nginx `/agent-api/health/live` 200 已通过，但 `/health/ready` 恒 503 不能证明 Redis/portal 状态、`/agent-api/agent/chat` 因会写 Redis 未执行。故 Task 13 Step 2 未完成的当前原因是**整份验收报告未生成与余下未验证项**，而不再是 Step 3 阻塞。

**Step 2 状态（2026-09-26 后续）**：已生成**当前阶段**总验收报告 `.codebuddy/reports/java-product-shopping-agent-report.md`（连同 `java-agent-readiness-report.md`），但 **Step 2 保持 `[ ]`、Task 13 仍未完成**——该报告如实标注未完成项而**不伪称完成**：新源码镜像重建未成功（停在 `Dockerfile.app` 构建阶段的 `mvn ... package`，退出码 1）、**旧容器未替换**、**新探针 ready 200 未运行验证**、**`/agent/chat` 聊天端到端未验收**、真实模型 / 真实会员 Token / 微信真机 / 真实 Redis（`MALL_AGENT_TEST_REDIS_URL` 未配置 → 8 项 skip）仍**未验证**。验证方面本轮实测 `mvn -o -f mall-master/pom.xml test` = **BUILD SUCCESS / 1229 run / 0 failure / 0 error / 8 skip**（mall-agent 893 / 8 skip）。详见两份报告与下方「Step 3 / Step 4 后续环境状态（2026-09-26 后续）」。〔**2026-09-26 最终更正**：本段「新探针 ready 200 未运行验证 / 旧容器未替换」已被后续轮次取代——新镜像已重建（host 网络，exit 0）并替换容器，`/health/ready` **200 UP**（直连与经 Nginx），见文末「最终状态」块。〕

**Step 3 / Step 4 / Task 13 状态（2026-09-26 宿主机 JAR 实测轮，仅文档）**：本轮补入**宿主机 JAR** 的真实运行证据，并更正上一条「重建未成功」在措辞上易被误读为「Maven 编译失败」之处；**Task 12 Step 4、Task 13 仍未完成**，运行容器仍未验收。〔**2026-09-26 最终更正**：运行容器**其后已用 host 网络重建的新镜像替换并验收（ready 200 UP）**，见文末「最终状态」；**Step 4 仍保持 `[ ]`**（缺聊天端到端/真实依赖验收）、**Task 13 仍未完成**。〕

- **打包（实测）**：`mvn -o -f mall-master/pom.xml -pl mall-agent -am -DskipTests package` → **exit 0**（JDK 21 产出运行 JAR）。
- **宿主机 JAR live/ready（实测，2026-09-26）**：以本工作树最新 JAR 在 `127.0.0.1:18086` 临时启动，stub 模式，门户 `MALL_AGENT_PORTAL_BASE_URL=http://127.0.0.1:8085`、Redis `MALL_AGENT_REDIS_URL=redis://127.0.0.1:16379/0`（真实 Redis），**仅请求 `live` / `ready`**：两者均 **HTTP 200 UP**（ready 经**真实 Redis `PING`** 成功）。
- **宿主机 JAR Redis 不可达（实测，2026-09-26）**：再次临时启动**同一 JAR**，**仅**把 Redis 指向 `redis://127.0.0.1:1/0`：`/health/ready` → **HTTP 503 DOWN**（单次请求约 `0.441616` 秒）、`/health/live` → **HTTP 200 UP**。两个临时进程**均已停止**，`127.0.0.1:18086` **不再监听**。
- **运行容器（当时未验收；已被后续轮次取代）**：共享容器 `8086` 仍是旧镜像 `b39c8d3193d1`，其实测 `/health/ready` 为 **503**，**未替换**。Docker daemon 已恢复；但按当前源码重建 Compose 新镜像的**首次**尝试（**默认网络**）**Maven 步骤长时间无新输出**，**主 Agent 主动中止该未完成构建，命令 exit 1**；旧容器**健康且未替换**。〔**2026-09-26 最终更正**：改用 **host 网络**重建成功（exit 0、Maven BUILD SUCCESS）并**已替换目标容器**，见下方「最终状态」。〕
  - **措辞纪律（重要）**：**不得**把「手动中止未完成构建」写成「Maven 编译失败」，**也**不得写成「已查明网络原因」；同时**不得**说新探针**完全没做任何运行验证**——准确表述是「**宿主机 JAR 已实测成功**（200/503 两条路径），**容器未验收**」。
- **真实 Redis 结论更正**：宿主 JAR 的 ready 已通过真实 Redis `PING`；但 **8 项门控的 Redis 集成测试未跑**，容器/聊天未验证。
- **结论**：**Task 12 Step 4 仍未完成**（无新镜像运行、无 `/agent-api/agent/chat` 端到端验收）；**Task 13 仍未完成**（Step 2 保持 `[ ]`）。本轮**仅修改文档与报告**，未改 Java / 测试 / Compose / `.env`，未 commit / push / merge，未执行任何 SQL；详见 `.codebuddy/reports/java-agent-readiness-report.md`、`.codebuddy/reports/java-product-shopping-agent-report.md` 与 `document/docker/local-startup.md`。

**最终状态（2026-09-26，新镜像已重建并在容器运行中验收）**：本条为本计划**当前有效**的运行状态，**取代**上文各轮「首次重建未成功 / 尚未在任何运行容器上验证 / 旧容器未替换」的**当轮当时**记录（后者保留为历史）。

- **镜像构建（成功）**：`docker build --network=host --progress=plain -f document/docker/Dockerfile.app --build-arg MODULE=mall-agent --build-arg JAR_FILE=mall-agent-1.0-SNAPSHOT.jar --build-arg APP_PORT=8086 -t mall-local/mall-shopping-agent:local .` → **exit 0、Maven BUILD SUCCESS**，镜像 **`sha256:aa9fb0606788935444270bad4ead7708659003cf16c79802fc40fb48decd745e`**。
  - **口径纪律**：此前**默认网络**构建在 Maven 阶段长时间无新输出，是**由主 Agent 主动中止的未完成构建（exit 1）**，**不是** Maven 编译失败，也**未**查明为网络原因；第二次 **host 网络**构建即成功。
- **容器替换（仅目标服务）**：以 `MALL_AGENT_MODEL_MODE=stub`（进程环境）配合 `.env.example` 执行 `docker compose --env-file .env.example --profile app --profile edge up -d --no-deps --no-build mall-shopping-agent` → **exit 0**；**仅替换 `mall-shopping-agent` 目标服务**，新容器 **`2077801e5c7487df666aade6ebddc3e48a1b4f32a3a70ff70da27bcf2f925a63`、healthy**；**Redis / mall-portal / Nginx 容器 ID 均不变且 healthy**。**不涉及任何「Compose unhealthy 自动触发重启」**（无此机制参与）。
- **运行验证（实测）**：直连 `8086` `/health/live` **200 UP**、`/health/ready` **200 UP**；经 Nginx `8088` `/agent-api/health/live` **200 UP**、`/agent-api/health/ready` **200 UP**；`nginx -t` **exit 0**；Compose **全 profile `config --quiet` exit 0**。**新镜像的真实就绪探针（门户只读探针 + Redis `PING`）已在容器运行中验证**，**不再**称「未验证」。
- **遗留 / 未完成**：`/agent/chat` 与经 Nginx 的 `/agent-api/agent/chat` **未调用**（会写 Redis 会话 / 限流键，不在本轮零写约束内）；真实模型 / 真实会员 Token / 会员券 / 微信真机仍**未验证**；**Task 12 Step 4 保持 `[ ]`（部分完成，缺聊天端到端与真实依赖验收）**、**Task 13 仍未完成**。
- **零写与 Git**：本轮**无 SQL / Redis 业务写**，**未** commit / push / merge；**仅修改文档**（`document/docker/local-startup.md`、`document/agent/product-shopping-agent.md`、`mall-shopping-agent/README.md` 与本计划文件）。

**最终状态补充（2026-09-27，E2E 验收轮）**：本条为**追加**，**不删改**上文历史。要点：

- **Docker / 运行（A）**：Docker daemon **29.5.3 已恢复**；本轮开始前与清理后，原 Compose 的 **13 个服务均 healthy**。原 Compose `mall-shopping-agent` 容器 ID `2077801e5c7487df666aade6bc…`（可验证短前缀 **`2077801e5c74`**），镜像 SHA `sha256:aa9fb0606788935444270bad4ead7708659003cf16c79802fc40fb48decd745e`，标签指向本 Java worktree；该容器**本轮未被替换**。直连 `/health/live`、`/health/ready` 与主 Nginx `8088` 的 `/agent-api/health/live`、`/agent-api/health/ready` **均 HTTP 200**；主 Nginx `nginx -t` 成功。
- **当前源码包（B）**：`mvn -o -f mall-master/pom.xml -pl mall-agent -am -DskipTests package` **exit 0**，生成当前 JAR。尝试构建独立 tag `mall-local/mall-shopping-agent:e2e-20260927` 时，`document/docker/Dockerfile.app` 的 Maven 步骤**约 3 分钟无新输出**，由主 Agent **Ctrl+C 中止**——**不是** Maven 编译失败，**也**未声称已查明网络原因。随后以 `eclipse-temurin:17-jre` 临时容器**只读挂载当前 JAR**、**独立临时 Redis（DB15、无持久化）**与**临时 Nginx（只读挂载项目 `document/docker/nginx/conf.d/default.conf`）**完成 E2E（结论见上方 Step 4 状态块）；临时容器、测试网络与测试端口已删除，E2E **只写被删除的隔离 Redis**。
- **真实模型（C）**：`check-agent-env.ps1 -EnvFile F:\code\mall\.env` **exit 0**，输出**仅**确认 OpenAI-compatible mode/key/model 已配置与服务域名 `api.deepseek.com`（**不含** Key、完整 `.env` 或可疑 query）；临时 live one-off 容器经 env 白名单只注入声明的 `MALL_AGENT_*`，真实模型只发起**一条**公开查询（**HTTP 200、答案非空、5 张卡为真实门户值**）。**用户个人券真实会员 Token / 会员券适用性未测**，**微信真机未测**；文档不记录 API Key 值、模型完整响应或任何凭据。
- **测试（D）**：`mvn -o -q -f mall-master/pom.xml test`，当次进程 `MALL_AGENT_TEST_REDIS_URL` 指向**独立临时 Redis**，Surefire **72 份报告合计 Tests=1243 / failures=0 / errors=0 / skipped=0**；其中 **8 个真实 Redis 门控用例确实运行**。测试 Redis DB15 结束 `dbsize=0`，容器已移除。`git diff --check` **exit 0**。
- **安全审查（D）**：业务/聊天安全**窄范围**审查**无 P0/P1/P2**，但发现 `RedisRateLimiterTest` 的 Javadoc 旧并发语义已由 CodeBuddy 修正（**纯注释**）。部署边界审查**无 P0/P1**，有 **4 个 P2**（其中 CORS 默认项已于 **2026-09-27 后续返工修复**，余 **3 个当轮待处理**）：（1）`document/docker/Dockerfile.app` runtime **未指定 `USER`**，容器以 root 运行；（2）~~Compose 与 `AgentProperties` 默认 **CORS `*`**~~ **已修复**：`docker-compose.yml` 与 `.env.example` 的 `MALL_AGENT_CORS_ALLOW_ORIGINS` 默认改为**留空**（fail-closed，同源经 Nginx `/agent-api/` 不需 CORS，跨域直连须显式填写精确来源，去掉 `:-*` 通配兜底；`AgentProperties` 本就默认空并在配置阶段拒绝 `*`）；（3）agent 同在 `mall-net`，可连**无认证** ES/Mongo 等基础服务（代码当前**不使用**这些客户端，**不代表**网络层已隔离）；（4）`ClientIpResolver` **信任 `X-Real-IP`**，外部经 Nginx 会被覆盖，但**本机直连 / 同网容器可伪造**分桶。两项**宽范围** CodeBuddy 审查曾**超 240 秒终止、无输出**；后续窄范围复核才返回上述结果，**不得**声称「全面审查全无问题」。
  - 〔**2026-09-27 加固后更正**：上述 4 项部署边界 P2 **均已在当前 Java 工作树代码中处理（代码/静态与单测层；最新运行时仍待验收）**——（1）`Dockerfile.app` 已以固定 UID/GID `10001:10001`（`USER mallapp`）**非 root** 运行；（3）Agent 已只接入 `agent-proxy-net` 与 `agent-backend-net`、**不再接入 `mall-net`**；（4）`ClientIpResolver` 已仅在 `remoteAddr` 与 `MALL_AGENT_TRUSTED_PROXY_IP`（**默认空 = 不信任任何代理**）**按字节相等**时采信 `X-Real-IP`，且不读 `X-Forwarded-For`；（2）CORS 同前。**旧的四项不再需要用户决策**，仅待最新镜像/网络运行时复验。详见 `.codebuddy/reports/java-agent-hardening-review-report.md` 与文末「2026-09-27 加固后当前状态」节。〕
- **计划状态（E）**：**Task 12 Step 4 已勾选 `[x]`**（依据见上方 Step 4 状态块；**实测路径为临时隔离 app/Nginx，不是主 Nginx chat 调用**）；**Task 13 仍未勾选 / 未完成**——因**真实会员 Token / 会员券**、**微信真机**及**主栈 `8088` `/agent-api/agent/chat` 与可信代理双客户端正路径**未完成〔**2026-09-27 更正**：原列「最新加固后的运行时验收未完成」——该**独立运行验收已于临时隔离环境完成**（见下条「最新独立运行验收（2026-09-27）」）；仍**未复验**的是主栈 chat、可信代理正路径与真实模型 / 会员 / 微信，且本轮**仅分网隔离、非严格出站隔离**〕（原 **4 个部署边界 P2 已在代码/静态与单测层处理，非「待用户决策」**，见上条括注与文末「2026-09-27 加固后当前状态」），**不因静态检查通过而标完成**。阻塞与下一步建议见 `.codebuddy/reports/java-product-shopping-agent-report.md` 的 2026-09-27 追加节。

## 计划自审

- **设计覆盖：** 模块边界与依赖（Task 2）、HTTP 契约（Task 3）、OpenAI/Stub（Task 4）、portal GET 白名单（Task 5）、Redis/身份/限流（Task 6）、意图/拒绝/围栏（Task 7）、工具和事实卡片（Task 8）、编排与离线评测（Task 9）、API 与会话迁移（Task 10）、上下文隔离和 Redis 集成（Task 11）、Compose/Nginx/文档（Task 12）、安全审查与报告（Task 13）均有明确验收任务。
- **边界核对：** 不迁移前端，不改数据库/SQL，不操作脏的 `codex/agent-live-acceptance` worktree，不删除 Python 源码；Java 模块无 DB/ES/Mongo/Rabbit 客户端。
- **类型一致性：** 全计划共享 `ApiEnvelope<T>`、Chat/session DTO、`ModelClient.complete(ModelRequest)`、五个定型门户方法、`SessionRepository` 方法签名；任务 4、5、6 的产物被后续任务按同一签名消费。
- **运行配置：** Java Docker 构建复用已存在的 `document/docker/Dockerfile.app`，不重复实现第二个等价 multi-stage Dockerfile；旧 Python Dockerfile保留但从 Compose build 脱链。
- **步骤完整性：** 没有待补充标记或跨任务类推；每项列出具体文件、测试名、执行命令及可观察预期结果。

---

## 2026-09-27 加固后状态（**历史时点**，已被 2026-09-28 当前有效结论取代）

> **历史时点说明（不得当作当前结论）**：本节记录 **2026-09-27 当轮**收尾状态（**追加**，不删改上文历史；上文各轮计数保留其执行时点口径）。本节内「**Task 13 Step 2 保持 `[ ]`、Task 13 仍未完成**」与「`main` = `origin/main` = `56810c2`」均为**该时点**记录：其后 2026-09-28 已将 **Task 13 Step 2 标为 `[x]`**（含义为完整验收报告与未验证清单已记录，**不代表**微信真机 / 正式域名已验收），且 **`main` = `origin/main` = `ed5140a`**。**当前有效 Task 状态与 Git 基线以文末「2026-09-28 主栈真实模型与会员优惠券验收（当前有效结论）」为准。** 引用报告：`.codebuddy/reports/java-agent-hardening-review-report.md`。

- **分支与提交**：工作树 `F:\code\mall\.worktrees\java-agent-migration`、分支 `codex/java-agent-migration`，HEAD 仍为 `f7b822b`；`mall-agent/` 与本源计划文件仍为**未跟踪/未提交**，**尚未合并到 `main`**。主仓库 `main` 已由主 Agent 在既有授权下推送，`main` = `origin/main` = `56810c2805e099ac45f0422a881096dd532de760`（`Merge branch 'codex/deepseek-base-url'`），`main` 工作区干净、**无待推送提交**。
- **部署边界 4 项 P2（代码/静态与单测层已处理；运行层已在临时隔离环境复验，主栈 chat 等仍未验收）**：
  1. **CORS fail-closed**：`AgentProperties.corsAllowOrigins` 默认空并拒绝通配 `*`；`AgentCorsConfiguration` 默认空时**不注册任何跨域许可**；`docker-compose.yml` 与 `.env.example` 的 `MALL_AGENT_CORS_ALLOW_ORIGINS` 默认留空，去掉 `:-*` 通配兜底。
  2. **非 root 运行**：`document/docker/Dockerfile.app` runtime 以固定 UID/GID `10001:10001`（`USER mallapp`）运行，Compose 中 `user: "10001:10001"` 与之一致。〔**2026-09-27 最新更正（收窄运行身份）**：该条已被收窄——`Dockerfile.app` runtime 现为 `ARG APP_RUN_USER=root` / `ARG APP_HOME=/root`（**默认 root / `HOME=/root`**）；`mall-admin` / `mall-search` / `mall-portal` 不传参，**保持 root 与 `HOME=/root`**（**不是**非 root）；**仅 `mall-shopping-agent`** 传 `APP_RUN_USER=mallapp` + `APP_HOME=/app` 并保留 `user: "10001:10001"`。Agent 运行身份现为**镜像 build args + Compose user 双重约束**。详见下方「最新更正（2026-09-27，收窄共享 Dockerfile 运行身份后）」。〕
  3. **网络隔离**：Agent 只接入 `agent-proxy-net`（对 Nginx）与 `agent-backend-net`（对 Redis / mall-portal），**不再接入 `mall-net`**（此为**代码/静态层**的网络分段；**运行层实测仅为分网隔离、非严格出站隔离**，见下条「最新独立运行验收」——`host.docker.internal` 仍可达主栈宿主发布端口，**不得**表述为「看不到数据库」）。
  4. **源 IP 信任边界**：`ClientIpResolver` 仅在请求对端 `remoteAddr` 与 `MALL_AGENT_TRUSTED_PROXY_IP` **按地址字节相等**时才采信 `X-Real-IP`；该变量**默认空 = 不信任任何代理**，且刻意不读取 `X-Forwarded-For`。
  - 以上均为**代码/静态与单元测试层**结论；**2026-09-27 独立运行验收轮已在临时隔离环境复验**：非 root 已在最新镜像运行层生效、分网拓扑已实测、经 Nginx 的源 IP 采信边界（伪造源 IP + IP 配额 1 → 第二次 `429`）与 Redis 门控已实测；**但主栈 chat、可信代理双客户端正路径与真实模型 / 会员 / 微信仍未验收**，且**仅分网隔离、非严格出站隔离**（见下条「最新独立运行验收（2026-09-27）」）。
- **最新独立运行验收（2026-09-27，临时隔离环境；运行层实测）**：
  - **最新镜像**：以正式 `document/docker/Dockerfile.app` 执行 `docker build --network=host` **exit 0 / Maven BUILD SUCCESS**，镜像 ID **`sha256:284512f81cdcbc6d192e893194d94c9894ae256861649d176b32ea09f903e584`**（专用临时 tag，验收后已删除）。**只在独立临时网络 / 独立临时容器运行**，**未重建、未替换主 Compose 的 13 个服务**（清理后主 13 容器仍 healthy）。
  - **非 root 生效（运行层）**：新 Agent 镜像 `Config.User=mallapp`，容器内 `id` 输出 **uid/gid 10001**——上述「非 root」结论现已在**真实镜像运行层**生效，不再是仅静态断言。
  - **分网拓扑（运行层）**：临时 Nginx 位于代理网 `.2`，Agent 位于代理网 `.3` 并接入后端网，Redis 在后端网。
  - **边界口径（重要，不得外推）**：本轮**只是分网隔离（network segmentation）**，**不是严格出站隔离**。实证：Agent 容器 DNS **无法解析**主栈 ES 容器名，但经 Docker Desktop `host.docker.internal` 可**真实连通**主栈宿主机发布端口——**TCP `13306` 可连**、**HTTP 可达 Mongo `27018` 与 ES `9200`**；共享的**无认证 Redis 仍与主商城共用同一实例**，Agent 拥有同实例连接权限。**用户已明确决定「先按分网隔离，记录风险并继续」**；**不得**写成「看不到数据库」或「网络层完全隔离」。
  - **接口与安全实测**：`GET /agent-api/health/ready` **200**；stub 游客「推荐一款手机」`POST /agent-api/agent/chat` **200** 且 **5 张真实门户商品卡**；`GET /agent/session/{sessionId}` **2 条消息**；`DELETE` `deleted=true`；游客个人券 `requiresLogin=true`；**直接来源伪造不同 `X-Real-IP` 且 IP 配额为 1 时第二次 `429`**；**未受信任 Origin `OPTIONS` `403`**。
  - **Nginx `/agent-api/` 日志脱敏（运行层）**：旧配置实测会把**随机 `sessionId`** 写进 `mall-access.log`；新配置临时 `nginx -t` **exit 0** + `reload` 后，带 query 的 `GET /agent/session/<sessionId>` **HTTP 200**，新日志**不含 `sessionId`/query**、**保留 `method`/`status`/`time`**。详见 `.codebuddy/reports/java-agent-nginx-log-redaction-report.md`。
  - **清理与零写**：临时 **4 容器 / 2 网络 / 专用镜像 tag** 已逐一删除并复查**零残留**，主栈 **13 容器仍 healthy**；**无 SQL**、**无主 Redis 写**（只写被删除的隔离 Redis）、**未 commit / 未 merge / 未 push**。
  - **本轮未跑（不得视为已完成）**：最新镜像下**未跑真实模型**、**未跑真实会员 Token / 会员券**、**未跑微信真机**、**未跑主栈 Nginx `8088` chat**、**未跑可信代理双客户端正路径**，也**未启动新版非 root 的 `mall-admin` / `mall-search` / `mall-portal` 镜像**。
  - **剩余风险（列为后续评估，未擅自宣称已修复）**：`session` 的 `GET` / `DELETE` **无 IP 限流**且带 Token 时调用门户身份接口；`/health/ready` **匿名可访问**、可被放大读取依赖；Nginx **仅 `agent` 的 `access_log` 已脱敏**，其它 location 不在本轮。
- **当前最新静态/测试证据（2026-09-27 独立运行验收轮，接独立临时 Redis）**：全 reactor `mvn -o -q -f mall-master/pom.xml test` 各模块 **demo 2 / admin 63 / search 97 / portal 174 / agent 984**，**总计 1320 tests / 0 failure / 0 errors / 0 skipped**；Python Compose 静态测试（含新增 3 条 Nginx 日志脱敏用例）**155 passed**；默认与全 profile `docker compose config --quiet` **exit 0**；`git diff --check` **exit 0**。
- **计数口径更正（重要）**：此前写入的「全 reactor 9 模块 **984 tests / 8 skipped**」（引自 `java-agent-hardening-review-report.md` 第 3.1 节）**实为「仅 `mall-agent` 一个模块」的用例数被误当成全 reactor 总数**；**全 reactor 总数为 1320**，`agent 984` 只是其中一个模块。历史各轮计数（1189 / 1229 / 1243 等）保留其执行时点口径，**不得与 1320 相加**。旧「984 / 8 skipped」属**无测试 Redis 的 agent 单模块**口径，与接独立临时 Redis 的 1320（0 skipped）是**不同运行**。
- **历史运行证据（加固前，保留并标时间）**：2026-09-27 上午以**临时隔离 app/Nginx + 独立临时 Redis**完成公开聊天 E2E（stub 模式：`/agent-api/health/ready` 200、`/agent-api/agent/chat` HTTP 200 与 5 张门户商品卡、session GET 2 条消息/5 张卡、DELETE `deleted=true`、游客个人券 `requiresLogin=true` 且无卡），并经 env 白名单临时 live 容器加做一条真实模型公开查询（HTTP 200、答案非空）。这些属**最新安全加固前**的历史运行证据；**新加固后的主栈 `8088` `/agent-api/agent/chat` 与最新镜像运行时未复验**。〔**2026-09-27 更正**：最新加固后镜像已在**临时隔离环境**完成运行层复验（见上条「最新独立运行验收（2026-09-27）」）；**仍未复验的**是**主栈 `8088` 的 `/agent-api/agent/chat`** 与**可信代理双客户端正路径**。〕
- **仍未完成 / 已暂缓**：**真实会员 Token / 个人券适用性**、**微信开发者工具 / 真机**未验收；正式 HTTPS 与微信小程序合法域名按用户此前决定**暂缓**。跨实例会话互斥**仍仅为进程内 guard**（`InFlightGuard`，无 Redis 锁），只有多副本需求确定后再评估分布式锁。历史可选 Elasticsearch 多实例 `importAll` 压力测试与 outbox/MQ 补偿机制**未执行**，不得视为已完成。
- **计划勾选状态纪律（历史时点口径）**：**Task 12 Step 4 当时保持 `[x]`**（依据为**临时隔离 app/Nginx** 路径，非主栈 chat）；**Task 13 Step 2 当时保持 `[ ]`、Task 13 当时仍未完成**——当轮不因静态检查通过而标完成。〔**2026-09-28 后续更新**：Task 13 Step 2 现为 `[x]`；当前有效勾选状态以文末「2026-09-28 主栈真实模型与会员优惠券验收（当前有效结论）」为准。〕
- **最新更正（2026-09-27，收窄共享 `Dockerfile.app` 运行身份后；仍未合并）**：用户明确批准收窄共享 Dockerfile 的运行身份——runtime 新增 `ARG APP_RUN_USER=root` 与 `ARG APP_HOME=/root`（**默认 root / `HOME=/root`**）；`mall-admin` / `mall-search` / `mall-portal` 的 Compose `build.args` 不传这两个参数，**保持迁移前的 root 身份与 `HOME=/root`**（**不得**写成非 root）；只有 `mall-shopping-agent` 传 `APP_RUN_USER=mallapp` + `APP_HOME=/app`，并保留 `user: "10001:10001"`。故 Agent 运行身份现为**镜像 build args + Compose user 双重约束**（不再是「仅由 Compose 覆盖用户」）。**静态证据**：TDD 红绿与 156 条静态用例、再加 HOME 返工后 **158** 条静态用例见 `.codebuddy/reports/java-agent-only-nonroot-report.md`；主 Agent 实测全 `pytest` **158 passed**、Compose 默认与全 profile `config --quiet` **exit 0**、`git diff --check` **exit 0**（上文「155 passed / 1320 tests」属**更早**的独立运行验收轮口径，保留其执行时点，**不与 158 相混**）。**重要（不得误述）**：旧镜像 `sha256:284512f8…` 是本轮 ARG/HOME 变更**之前**构建，**不能**作为**当前最终 `Dockerfile.app` 镜像**生效的证据；最终镜像新构建**两次均未成功**（第一次 `apt` 阶段 Docker BuildKit EOF；第二次 Docker Desktop Linux Engine `_ping` **500**），**尚未生成新镜像**，其后 daemon 只读 `docker version` / `docker ps` 卡住已中止等待。因此当前最新 `Dockerfile.app` **仅静态/测试层通过**，真实镜像 `User` / `HOME` 待 Docker 恢复后重验；旧临时 E2E（隔离环境）仍是**旧版本**的有效运行证据，Java 业务代码未变。**用户网络风险口径不变**（**仅分网隔离**、非严格出站隔离；`host.docker.internal` 可达主栈已发布 ES/Mongo/MySQL，共享无认证 Redis 同实例），用户已接受先记风险。主 Agent 正请求用户决定是否允许**重启 Docker Desktop**（**重启会影响主 Compose，未批准前不得自行重启**）。
- **Git 纪律**：本节为**纯文档更新**，未执行 commit / push / merge / reset，未改 Java/Python 代码、测试、Compose、Dockerfile 或真实 `.env`，未执行 SQL、未做 Docker build/up/exec。
- **最终镜像构建复验与聊天未完成（2026-09-27，Docker Desktop 重启后；追加，不改上文历史）**：
  - **授权与重启**：用户**明确允许重启 Docker Desktop**；**第一次 `docker desktop restart` exit 0**，Docker Linux Engine **恢复**（该授权消除了第 12 节「未获答复前不重启」的处置边界，属授权后执行）。
  - **最终 `Dockerfile.app` 显式传参镜像**：以**当前最终** `Dockerfile.app` 在**独立标签 `mall-local/mall-agent-user-scope-accept:20260927`** 构建 **exit 0**，镜像 **`sha256:77907c5d625af1c2230ca2c3b45733f62568cf4b2455edf29ee14b09e7deff34`**；**`Config.User=mallapp`、`HOME=/app`**，`docker run` 容器内 `id` = **`10001:10001`**，`/app/app.jar` **可读**、`/app` **可写**。→ **取代**第 11 / 12 节「最终镜像尚未产出、`User`/`HOME` 待复验」的**当轮当时**结论。
  - **同一最终 `Dockerfile.app` 默认镜像（不传 `APP_RUN_USER`/`APP_HOME`）**：独立构建 **exit 0**，镜像 **`sha256:01af63388dc31fd4692ddf6ba89acb88bee5b98885b96bddda28dea9ad7d071f`**；**`Config.User=root`**，`docker run` 容器内 `id` = **`0:0`**、`HOME=/root`。→ 与上条互为对照，证明**默认身份确为 root / `HOME=/root`**，Agent 非 root 身份**来自显式传参**。
  - **新 Agent 就绪（运行层实测）**：启动**临时独立 Redis + Agent 容器**（独立于主栈），新 Agent **直连** `GET /health/live` **200**、`GET /health/ready` **200**。
  - **聊天未通过（重要）**：本轮**聊天请求未获得响应**，**`/agent/chat` / `/agent-api/agent/chat` 不计为本轮 E2E 通过**；**旧版镜像的隔离聊天 E2E 不能替代本轮最终镜像的聊天验收**。
  - **Docker API 再次卡住与资源证据**：其后 Docker API **再次卡住**；同期 **WSL `free`** 显示**总 7.8G / 已用 6.7G / available 约 354.9M / swap 4.0G 用满**。**口径谨慎**：本轮**仅将资源耗尽记为「高度相关证据」，不绝对断言其为唯一根因**；聊天未达响应原因**未查明**。
  - **第二次重启与精确清理**：**第二次 `docker desktop restart` exit 0**；随后**精确删除本轮 2 个临时容器、1 个空临时网络、2 个独立镜像标签**，**未清理**主栈容器 / 网络 / 卷；**主栈恢复中，其当前健康状态由主 Agent 另行复核，本记录轮不作断言**。
  - **计划勾选**：**Task 12 Step 4 保持 `[x]`**；**Task 13 Step 2 保持 `[ ]`、Task 13 仍未完成**——本轮**镜像身份 / HOME 已验证、就绪探针已验证**，但**最终镜像聊天未完成**，**仍不勾选 Step 2**。
  - **Git 边界**：本轮**未执行任何 Git 写操作**（无 commit / push / merge / reset），未执行 SQL，未改代码 / 配置 / 测试；`codex/java-agent-migration` HEAD 仍为 `f7b822b`，**未提交、未合并**；`main` = `origin/main` = `56810c28…`，`main` 工作区干净。详见 `.codebuddy/reports/java-agent-isolated-runtime-acceptance-report.md` 第 13 节。

- **最终更正（2026-09-27，最终独立镜像隔离 Nginx 路径 E2E 完成；追加，不改上文历史）——本计划的「当前有效结论」**：
  - **资源授权与镜像**：用户**授权暂时停止主栈 Kibana / Logstash**，释放约 **0.8 GiB**；在资源上限内以**独立 `Dockerfile.app` 镜像**（标签 `mall-local/mall-agent-final-e2e:20260927`）构建**成功**（exit 0）。
  - **隔离环境**：使用**独立 Redis** 与**临时 Nginx**（当前 `default.conf` 为**只读挂载**），在资源上限下完成**最终镜像 + 隔离 Nginx 路径**的 E2E。
  - **E2E 实测**：`/agent-api/health/live` 与 `/agent-api/health/ready` 均 **200**；`POST /agent-api/agent/chat` **200** 且 **5 张真实门户商品卡**；会话 `GET` **2 条消息**、`DELETE` **成功**；游客个人券 `requiresLogin=true` 且 **0 卡**；Nginx 访问日志**不含 UUID / query**，但**保留 method / status**。
  - **第一次 400 的澄清**：第一次请求 400 **仅因测试请求漏传必填 `sessionId`**，补齐后即成功；**不是**实现或镜像缺陷。
  - **清理与恢复**：临时容器 / 网络 / 镜像标签**逐一删除**；Kibana / Logstash **已恢复**。
  - **主栈健康口径（谨慎）**：主 Agent **第三次重启 Docker Desktop** 后有一次主 Compose **13/13 healthy** 快照，但随后 **Docker API 仍间歇卡住**，故**不能宣称主栈持续健康**（仅为一次快照，非持续结论）。
  - **测试口径**：最新 `mvn` **full reactor 1320 / 0 failure / 0 errors / 8 skipped**（本轮**无独立测试 Redis**，故 8 条门控用例 skip）；Python Compose 静态测试 **158 passed**；Compose 全 profile `config` **exit 0**。
  - **只读审查**：最近部署文件只读审查**无代码阻断**；先前「**最终镜像聊天未验收**」的缺口**已补**。
  - **仍未验收（当前有效结论）**：**主栈 `8088` chat**、**真实会员券 / 微信真机**、**正式域名**。
  - **勾选**：**Task 13 Step 2 保持 `[ ]`**，不勾选。
  - **Git 边界**：Java 分支**未 commit / merge / push**；`main` 与 `origin/main` 仍为 `56810c2`。

## 2026-09-27 本地提交、合并与合并后复核

- **本轮行为修正**：商品详情 SKU 的 `spData` 已从门户响应经 DTO 传到详情工具；会话 GET/DELETE 在身份解析前各按可信客户端 IP 消耗一次现有 IP 桶，超限 429，不消耗会话桶。详见 `.codebuddy/reports/java-agent-final-hardening-report.md`。
- **Nginx 最终配置**：`/agent-api/` 使用 `client_max_body_size 32k`、`proxy_read_timeout 60s` 和 location 级 `error_log /dev/null`；最后一项会丢弃该路由 Nginx 错误诊断，脱敏 access log 仍记录方法、状态与耗时。其他代理保持原 120s 读超时与 server 级 20m body limit。
- **静态/动态验证**：本轮全 reactor Maven 1333 项，失败/错误 0、跳过 8（均为真实 Redis 门控用例，未配置独立测试 Redis）；Python Compose/Nginx 测试 163 passed；Compose 默认与全部 profile 配置均 exit 0；隔离 Nginx 实测 32k 限制（40k 请求 413）、无上游 502、日志不含测试 UUID；所有临时容器已清理，主 Compose 当前容器快照均 healthy。
- **只读审查**：Java 与部署审查均无 Critical/Important；审查建议的 DELETE 可信代理正路径覆盖、嵌套 location 解析器与 Compose 超时注释已补齐。没有提交级阻断。
- **尚未完成**：真实会员优惠券与微信真机、正式 HTTPS/小程序合法域名仍按用户决定暂缓；主栈 `8088` chat 的最新变更后 E2E 未在本轮执行；GET/DELETE 跨实例互斥仍未实现。
- **功能提交**：`96bafb7`（实现 Java 商品导购智能体并完善部署安全）；`5182cea`（记录 Java 智能体迁移提交信息）。
- **本地合并**：`main` 以非快进方式合并，提交 `18b5408cb7076f98b3da38c61fec0e9a82fa067b`（合并 Java 商品导购智能体迁移），无冲突。
- **合并后复验**：在 `main` 新跑全 reactor `mvn -o -q -f mall-master/pom.xml test`，exit 0；本轮 Surefire 新鲜报告合计 1333 项、0 失败、0 错误、8 跳过（真实 Redis 门控用例）；`python -m pytest tests/unit/docker/test_compose_config.py -q` 为 163 passed；Compose 默认配置和 app/edge/observability 全 profile 配置均 exit 0；`git show --check` 无空白错误。
- **推送结果**：用户确认后执行 `git push origin main`，exit 0，远端回执 `56810c2..fecc43a main -> main`；本地 `origin/main` 跟踪引用与 `HEAD=fecc43a` 一致，工作区干净。推送后的独立 `git ls-remote` 复核因 GitHub TLS 握手失败未取得新回执；没有 force push。

## 2026-09-28 主栈游客 E2E 与 Task 13 记录收尾

> 〔**后续更新（当前有效）**：主 Compose 现已确认为 **`openai`（真实模型）**运行模式，且**主栈真实模型 + 会员优惠券验收已完成**；本节 `stub` 结论为**该时点**记录，保留为历史。**当前有效结论见文末「2026-09-28 主栈真实模型与会员优惠券验收（当前有效结论）」**。〕

- **主栈配置**：运行中的 `mall-shopping-agent` 环境变量模式只读取到 `stub`；经主 Nginx `8088` 的 `/agent-api/health/live` 与 `/agent-api/health/ready` 均 HTTP 200。
- **游客聊天**：使用本轮随机会话请求 `POST /agent-api/agent/chat`（“推荐一款手机”），HTTP/统一响应码均为 200，答案非空，返回 5 张商品卡，`requiresLogin=false`；会话 GET 返回 2 条消息；DELETE 返回 `deleted=true`，清理测试会话。未使用真实模型或会员凭据。
- **副作用边界**：仅调用商品门户只读查询；无 SQL/MySQL 写、领券、购物车、订单、支付、库存或 ES 写。限流计数遵循既有 TTL。请求后单次 Compose 快照显示长期运行服务 healthy、`minio-init` 为预期 `Exited (0)`。
- **计划勾选**：Task 13 Step 2 现标为完成，含义是完整验收报告与未验证清单已记录；不代表真实模型、会员优惠券、微信真机或正式域名已验收。主栈当前只完成 stub 游客流程。
- **仍待人工/外部条件**：真实模型主栈调用、真实会员 Token/优惠券适用性、微信真机、正式 HTTPS/小程序合法域名，以及跨实例互斥评估；分网隔离并非严格出站隔离。
- **Git**：功能和合并提交为 `96bafb7`、`5182cea`、`18b5408`；主栈验收记录提交 `fecc43a` 已随 `main` 推送成功。推送命令返回成功，本地跟踪引用已更新；独立远端复核请求遇 TLS 握手失败。未 force push。

---

## 2026-09-28 主栈真实模型与会员优惠券验收（当前有效结论）

> 本节为本计划**当前有效的实测结论**（**追加**，不删改上文历史）。上文 2026-09-27 及更早各轮中「真实模型 / 真实会员券未验收」「主栈 `8088` chat 未验收」「Task 13 未完成」等状态，凡与本节冲突者，**以本节为准**。旧记录保留其执行时点口径。

- **基线**：`main` = `origin/main` = `ed5140aeee81d2668300964093c30ff0ad616d89`（`记录商品导购智能体推送状态`），`main` 工作区干净。上文「`main` = `origin/main` = `56810c2`」为**历史基线**。
- **主 Compose 运行模式**：对主 Compose `mall-shopping-agent` 做只读 `docker inspect` 并**仅筛选** `MALL_AGENT_MODEL_MODE`，确认运行模式为 **`openai`（真实模型）**。**未读取、未记录任何 API Key / Token / 会话 UUID。**
- **主 Nginx H5 页面**：`http://localhost:8088`。
- **登录态真实模型优惠券解释（H5）**：展示的优惠券解释与「已领取券」页**匹配**——**优惠金额、无门槛、指定商品范围、有效期**均正确。**未记录会员 ID 或任何凭据。**
- **真实模型商品详情查询（H5）**：查询 iPhone 14 返回**售价 5999 元**，详情 SKU **合计可售库存 2394**；回答**主动披露**「列表页库存与详情页库存不一致」并**以详情页为准**。
- **真实模型预算筛选（H5）**：按「手机售价不超过 4000 元」筛选，本次搜索返回 **5 件**，其中 **3 件在预算内**：红米 5A（649 元 / 库存 414）、小米 8（2699 元 / 库存 410）、华为 P20（3788 元 / 库存 1985）；两台超预算 iPhone **未进入推荐**；随后多轮追问**正确比较出华为 P20 库存最高**。（以上均为**查询时点数据**。）
- **游客真实模型聊天（主 Nginx，无 Authorization）**：`POST /agent-api/agent/chat` 返回 **HTTP 200**、统一响应 `code=200`、`requiresLogin=false`、**答案非空**、**1 张商品卡**；本轮所用临时会话 `DELETE` 返回 `deleted=true`。
- **诚实备注（不得编造）**：首次探测脚本的 **PowerShell 多行输出解析有误**，**不能**把其中 `-1` 记为接口失败；**首次会话清理的 DELETE 回执未成功解析**，其清理结果**不确定**，仅能说明服务会话存在 TTL 兜底；**不臆断**该会话已立即删除。
- **副作用边界**：本轮**未执行任何 SQL、无代码改动**；H5 登录态对话产生了新增查询内容，留存在**短期会话**中。
- **验收结论**：**真实模型主栈与会员优惠券验收已完成**。
- **仍未完成（当前有效）**：**微信开发者工具 / 真机的商品导购端到端验收**（**需用户扫码配合**）；**正式 HTTPS / 小程序合法域名**按用户决定**暂缓**；`GET` / `DELETE` **跨实例互斥**仍为**可选技术遗留**（单实例已按当前目标验证）。
- **计划勾选状态**：**Task 12 Step 4 保持 `[x]`**；**Task 13 Step 2 保持 `[x]`**（含义为完整验收报告与未验证清单已记录，**不代表**微信真机 / 正式域名已验收）。
- **Git 边界**：本轮**未执行任何 Git 写操作**（无 commit / push / merge / reset），未执行 SQL，未改代码 / 配置 / 测试。`main` 与 `origin/main` 均为 `ed5140a`。

## 2026-09-28 工作树只读审查（`agent-live-acceptance`，历史记录；当时待用户决定）

- **对象**：Codex 管理的 detached worktree，分支 `codex/agent-live-acceptance`，HEAD `56810c2`；含 **6 个未提交的 Python 源/测试改动**，且**无对应这些改动的报告**。
- **定向 pytest**：因**缺少 `redis` 包**，`api/test_chat.py` 在**收集（collection）阶段即失败**，**该模块测试未执行**。
- **不涉及 API 的两文件测试**（`safety` + `orchestrator`）：**共 61 项，59 通过、2 失败**——（1）「我这张券能用在这款商品上吗」**未命中**个人券意图；（2）「帮我领优惠券」被**错误命中**个人券意图。
- **处置（历史）**：当时**禁止清理或丢弃该工作树**，并标记为**待用户决定修复 / 归档**；该标记仅记录当时状态，当前处置见文末状态补记。
- **其他工作树（历史记录）**：`codex/deepseek-base-url`、`codex/java-agent-migration` 的提交当时已在 `main` 历史中，并被列为**待清理候选**；该候选标记只表示当时状态，当前注册情况见文末状态补记（旧 worktree 不再注册，分支 ref 按记录保留）。`stash@{0}`（`On main: 集成 Docker 本地运行修复前的主工作区备份`）**应保留**。
- **本轮边界**：本节为**纯文档更新**，未改 Java / Python / 前端 / Compose / Nginx / 配置 / 数据库，未执行 Git 集成操作，未清理任何 worktree。

## 2026-09-28 退役附记

旧 Python 实现（`mall-shopping-agent/` 目录与其独立 `document/docker/Dockerfile.agent`）已由**用户手动从工作区移除**（当前为未提交的工作区删除项，尚未提交）；Java 17 `mall-master/mall-agent` 是**唯一运行实现**，Compose service key `mall-shopping-agent` 即该 Java service。
本计划正文（含「Python 源码保留为参考」与上文历史 worktree 记录）为**历史记录**，不再代表当前状态；历史内容不重写、不删除。
当前收尾与验收进度见 `docs/superpowers/plans/2026-09-28-retire-python-shopping-agent.md`。

## 2026-09-28 状态补记（只读 Git 快照校准；历史快照）

本节记录按 `main@29e45c6` 得出的 2026-09-28 只读快照，仅用于校准当时的历史状态误读风险，**不是当前快照**；其 ahead 8 和 worktree 注册状态已由后续提交取代，当前 Git 状态见 `docs/context/project-handoff.md` 顶部最新快照。以下内容不修改、不删除原有审查记录或历史数字；所有动态状态仍以实时 `git worktree list` / 分支 ref 为准。

- **旧 worktree 已不再注册**：当前 `git worktree list` 中**没有** `agent-live-acceptance`、`deepseek-base-url`、`java-agent-migration` 对应的已登记工作树。因此上文「待用户决定修复 / 归档」（`agent-live-acceptance`）与旧 worktree「待清理候选」等表述**不再构成当前待办**：**旧 worktree 的注册 / 清理已不再是当前待办事项**。
- **对应本地分支 ref 仍保留，提交已在 `main` 历史中**：这三个本地分支 ref 仍存在，各自 tip 均为 `main@29e45c6` 的**祖先**。逐一对应（`git rev-list --left-right --count main...<branch>`）：`codex/agent-live-acceptance` = `17 0`；`codex/deepseek-base-url` = `19 0`；`codex/java-agent-migration` = `14 0`。因此**无需再清理对应 worktree**；此事实**不代表**应删除这些分支 ref。
- **历史未提交改动的当前状态无法确认**：上文记录的 `agent-live-acceptance` **6 个未提交 Python 源/测试改动**仅是**旧时点观察**；现无已登记工作树 / ref 可核验其是否仍有副本，**不得推断**它们已恢复、已进入 `main` 或已彻底丢失；也**不得仅因该 worktree 不在列表中就宣称相关问题已关闭**。如用户仍需追查 / 恢复这些旧改动，应**另行明确决定**，本补记**不自动处理**。
- **`deepseek-base-url` / `java-agent-migration`**：其旧 worktree 同样**未注册**；分支 ref 保留且提交均在 `main` 历史中（计数见上）。`stash@{0}`（`On main: 集成 Docker 本地运行修复前的主工作区备份`）**保持保留不动**，未读取、未应用、未改写、未删除。
- **远端快照（只读）**：本次快照 `main@29e45c6`、`origin/main@cd38a73`（`origin/main` 为 `main` 的**祖先**），`git rev-list --count origin/main..main` = `8`，即本地 `main` **超前 8 个提交、尚未推送**。此为**只读快照事实**；**推送仍须用户明确确认**，本任务**不推送**。
- **快照边界**：以上事实均取自 2026-09-28 的**只读 Git 快照**（本次基线 `main@29e45c6`、工作区干净；仅只读查询 `git worktree list`、分支 ref 与 `git rev-list`、`git log`、`git stash list`）；未执行任何 Git 写操作，未清理任何 worktree / 分支 / 文件。后续状态一律以实时 `git worktree list` 与分支 ref 为准。

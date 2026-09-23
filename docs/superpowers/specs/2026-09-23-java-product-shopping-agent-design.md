# 商品导购智能体 Java 化迁移设计

> 状态：待用户审阅
> 日期：2026-09-23
> 基线：`main@56810c2`
> 工作分支：`codex/java-agent-migration`

## 1. 目标与范围

将当前独立 Python/FastAPI 商品导购运行服务迁移为 Java 17 / Spring Boot 服务，纳入 `mall-master` Maven 工程；保留移动端现有 API 与页面契约，保持商品查询只读、优惠券身份隔离、会话与安全边界。Java 服务通过验收后成为唯一运行时实现，旧 Python 源码暂时保留为历史参考，不再由 Compose 部署。

本设计只包含智能体后端运行时迁移及其部署/运行文档适配，不重做 uni-app 聊天界面，不修改商城数据库结构，不新增交易写操作。

## 2. 已确认的决策

- 采用独立 Java 服务，不将模型调用和智能体编排并入 `mall-portal`。
- 外部 API、请求/响应字段、`/agent-api/` 入口及移动端页面保持兼容。
- 将游客询问个人优惠券必须先登录的确定性边界迁入 Java，并覆盖公开优惠券问法、否定表达和领券写操作拒绝场景。
- Java 验收后停止部署 Python 服务，但保留 Python 源码；不批量删除文件或目录。
- 若后续使用只读审查子 Agent，仅使用 `gpt-6-luna`，并显式指定 `max` 推理强度。

## 3. 当前项目基线与约束

### 3.1 已有 Python 智能体

当前服务位于 `mall-shopping-agent/`，使用 Python 3.11、FastAPI、Pydantic、HTTPX 和 Redis。它通过 OpenAI 兼容的 `/chat/completions` 执行最多 4 轮受控工具调用，且只通过 HTTP 调用 `mall-portal` 的商品搜索、商品详情、登录身份及优惠券只读接口。Compose 服务名为 `mall-shopping-agent`，容器端口为 8086；Nginx 将 `/agent-api/` 反代到该服务。

对外接口：

| 方法 | 路径 | 目标行为 |
| --- | --- | --- |
| POST | `/agent/chat` | 发送消息并返回回答、商品卡片、登录要求和建议问题 |
| GET | `/agent/session/{sessionId}` | 按当前身份恢复最近会话 |
| DELETE | `/agent/session/{sessionId}` | 幂等清空当前身份的会话 |
| GET | `/health/live` | 进程存活检查 |
| GET | `/health/ready` | Redis 与商城门户就绪检查 |

现有详细业务规则、接口样例和 10 类评测定义见 `document/agent/product-shopping-agent.md`；实施计划和原始验收口径见 `docs/superpowers/plans/2026-09-22-python-product-shopping-agent.md`。

### 3.2 Java 工程

`mall-master/pom.xml` 当前使用 Java 17、Spring Boot 3.5.14，包含 `mall-admin`、`mall-search`、`mall-portal` 等 Maven 模块。新增智能体模块不得依赖 `mall-mbg`、MySQL、MongoDB、RabbitMQ 或 Elasticsearch 客户端；仅依赖 Web、Redis、校验和测试所需组件。不得因为智能体迁移而改动现有商城模块的数据库或业务行为。

### 3.3 Git 与未提交工作

- 本次隔离 worktree 从 `main@56810c2` 创建；主仓库工作区只读检查为干净，`main` 比 `origin/main` 超前 3 个提交。
- `codex/agent-live-acceptance` 仍有 6 个未提交文件（当前统计 269 insertions、12 deletions），涉及个人优惠券意图策略、API 边界和测试。本迁移不得切换、覆盖、合并或清理该工作树；只把已确认的行为要求转换成 Java 侧测试。
- 该工作树报告记录实时模型评测为 4/10，且会员券场景使用测试占位 Token；不得把它表述为真实会员券或完整 Live 验收通过。
- 新 worktree 的 Maven 基线命令因沙箱网络权限无法解析 Spring Boot parent POM 而在项目编译前失败。实现前需在具备依赖缓存/网络权限的环境重新运行基线；这不是已确认的代码失败。

## 4. 推荐架构

```text
uni-app（现有聊天页，不改 API 契约）
        │ /agent-api/**
        ▼
Nginx（现有 location 与超时保持）
        │ mall-shopping-agent:8086
        ▼
独立 mall-agent Java 服务
  ├─ Spring MVC API 与统一错误响应
  ├─ AgentOrchestrator（受限工具循环）
  ├─ OpenAICompatibleClient（Spring RestClient + 固定 DTO）
  ├─ AllowlistedToolRegistry / SafetyPolicy
  ├─ MallPortalClient（固定 GET 白名单）
  ├─ RedisSessionRepository / RateLimiter
  └─ ProductPresentationBuilder（事实卡片由服务端构造）
        ├─ Redis（短期会话与限流）
        └─ mall-portal（只读 HTTP）
```

Java 实现先采用 Spring Boot 自带 HTTP 客户端和明确的 OpenAI 兼容 DTO，不引入 Spring AI 或其他 Agent SDK。这样保留当前服务商切换能力和既有工具协议，不把模型框架耦合进门户服务。Compose 的服务键 `mall-shopping-agent`、容器端口 8086 和 Nginx upstream DNS 名称尽量保持不变，降低切换面。

建议新增 Maven 模块 `mall-master/mall-agent/`，业务代码按 `api`、`agent`、`model`、`storefront`、`session`、`tools`、`safety`、`presentation` 分包。该模块独立启动、独立容器化，不加入商城数据库依赖。

## 5. 兼容契约

### 5.1 HTTP 与 JSON

保持 `/agent/chat`、`/agent/session/{sessionId}`、`/health/live`、`/health/ready` 路径与 HTTP 方法不变。保持现有 `code/message/data` 包装、聊天字段、商品卡片字段、`requiresLogin`、错误状态码及 UUID v4 / 消息长度规则。移动端 `VITE_AGENT_API_BASE_URL`、页面、登录回跳和 `/agent-api/` 反代不需要业务改造。

Java 版必须用契约测试验证字段兼容；商品卡片 `name`、`pic`、`price`、库存与 `detailPath` 必须从本轮门户工具结果服务端生成，不能信任模型返回的展示事实。

### 5.2 配置

保留现有 `MALL_AGENT_*` 环境变量名称与语义，包括模型模式、OpenAI 兼容 Base URL、API Key、模型名、门户 URL、Redis URL、请求超时、会话 TTL、工具轮数和日志级别。DeepSeek 根地址等服务商前缀原样保留，由客户端只追加 `/chat/completions`。API Key 仅存在于环境变量和模型请求头，不进入 Redis、响应或日志。

### 5.3 Redis 会话

保留 `mall:agent:session:*`、`mall:agent:rate:*` 前缀、游客/会员命名空间、24 小时 TTL、每会话最近 20 条消息和双维度固定窗口限流语义。实施时先读取 Python 真实 JSON 结构，再决定 Java 序列化兼容；应优先保证旧会话在 TTL 内可继续读取，若无法无损兼容，必须提供明确的安全降级行为，不得把游客会话交给会员身份读取。

## 6. 业务流程与安全边界

1. 校验 UUID、消息长度、限流和并发重复请求。
2. 无 Token 按游客处理；有 Token 时仅将其透传给 `mall-portal /sso/info` 解析身份，不接受客户端提供的会员 ID。
3. 对个人优惠券意图进行确定性分类：游客立即返回登录提示，且零模型调用、零会员接口调用；公开优惠券问法不误判为个人券。
4. 对领券、加购、下单、支付、取消、确认收货、改库存及 ES 写入等请求，优先按拒绝策略回复，不调用写工具。
5. 模型只能请求白名单工具：`searchProducts`、`getProductDetail`、`compareProducts`、`getMemberCouponsForProduct`。工具参数由 Java DTO 校验，拒绝未知字段、越界分页和非法商品 ID。
6. `MallPortalClient` 只允许现有五条商城 GET 路径；禁止任意 URL、HTTP 方法、SQL、直接数据库连接或 ES 写接口。Token 只发往身份与会员优惠券接口。
7. 商品库存按 `max(stock-lockStock, 0)` 计算；会员券按“本人未使用券”与“商品适用券”求交集后说明。
8. Agent 最多执行 4 轮工具调用；工具异常不能被模型包装成事实成功。商品文本视为不可信数据并在进入模型上下文前清理、裁剪和围栏。
9. Redis 只存裁剪后的必要对话与卡片摘要，不保存 Token、完整模型响应或完整门户响应。

故障语义保持：模型未配置/不支持工具调用 503；模型上游超时/失败 502；门户读取失败 502 且不生成虚假卡片；限流 429；非法输入 400；工具轮数超限 422。模型不可用不得影响 `mall-portal` 或商城其他页面。

## 7. 文件与部署影响

预计需要：

- `mall-master/pom.xml`：新增 `mall-agent` 模块声明。
- `mall-master/mall-agent/`：新增 Java 应用、配置、工具、会话与测试。
- `docker-compose.yml`：将原 `mall-shopping-agent` 运行构建改为 Java 镜像，保持服务键和端口契约。
- `document/docker/`：新增 Java 多阶段 Dockerfile；Compose 健康检查仍访问 `/health/live`。
- `.env.example`、`check-agent-env.ps1`、`check-agent-env.sh`：保留配置校验契约，必要时只改与运行实现相关的说明，不输出敏感值。
- `document/docker/local-startup.md`、`mall-shopping-agent/README.md`、`docs/context/project-handoff.md`：记录 Java 服务启动、Python 源码已退役但保留、配置和验收边界。
- `mall-app-web-master/`：默认不修改；只有契约测试发现确实不兼容时才暂停并说明，不擅自调整接口。

Python 目录及其测试暂保留，但从最终 Compose 运行配置和默认启动路径移除。严禁递归删除或批量删除 Python 文件；如将来要彻底清理，由用户按项目规则手动处理或逐个明确授权。

## 8. 测试与验收

### 8.1 Java 自动化测试

- JUnit 5 / Mockito：模型 DTO 与 OpenAI `tools/tool_calls` 协议、重试/超时/脱敏、工具白名单、参数校验、最多 4 轮、卡片事实回填、拒绝策略。
- MockMvc：现有 API 请求/响应契约、UUID/长度错误、游客与会员身份、模型和门户错误映射、限流状态码。
- HTTP client mock：逐条断言只发出允许的门户 GET 请求，Token 只透传到授权接口，任何失败响应不泄露上游正文。
- Redis 隔离单测及显式 Redis 集成测试：TTL、身份命名空间、游客登录迁移、限流、幂等清空及旧会话兼容策略。
- 迁移原 10 类离线 Stub 评测；每项断言允许工具、禁止工具、登录要求、事实来源和卡片规则。真实模型评测与 Stub 结果分开报告。

### 8.2 工程与运行验收

- `mvn -pl mall-agent -am test` 与 `mvn test`；不使用 `maven.test.failure.ignore`。
- Compose 默认与 app/edge/observability 全 profile `config --quiet`；Nginx `-t`。
- Java 容器健康检查通过；经 `/agent-api/` 验证 chat、session GET/DELETE、访客商品查询和游客个人券登录提示。
- 验证不变量：没有数据库 schema/DML 变化；不触发领券、加购、订单、支付、库存或 ES 写操作；敏感扫描没有 Key/Token 泄漏。
- 实际 OpenAI 兼容模型、真实会员券和微信开发者工具验收单独标记。现有 Python Live 评测 4/10 不得改写为通过；Java 版只有在条件满足且真实运行后才能报告结果。

## 9. 迁移顺序

1. 确认本设计文档；按 `docs/superpowers/plans/` 编写逐项 Java 实施计划。
2. 在 `codex/java-agent-migration` 新 worktree 中新增 Java 模块，先以 Fake Model、内存会话和 HTTP mock 完成安全与契约测试。
3. 完成 Redis 与 `mall-portal` 只读适配，复刻离线评测，再做本地容器构建和独立端口验证。
4. 通过 Java 单测/全量 Maven 测试后，将 Compose 的既有 `mall-shopping-agent` 服务切换到 Java 构建；保持 Nginx 与前端 API 地址不变。
5. 在 Compose/Nginx 环境完成只读端到端验收，更新运行文档和交接摘要。
6. Python 源码保留在仓库但从运行链路退役；不执行批量删除。
7. 主 Agent 审查真实 diff、敏感文件、测试和报告，中文提交并本地合并复验；只有用户明确确认后才 push。

## 10. 方案比较

| 方案 | 优点 | 风险/代价 | 结论 |
| --- | --- | --- | --- |
| 在 `mall-master` 新增独立 Java `mall-agent` 服务 | 故障隔离，复用现有 Java 版本和 Maven 体系，API 可平滑切换 | 增加一个 Spring Boot 容器和迁移测试工作 | **推荐** |
| 将智能体直接并入 `mall-portal` | 少一个服务 | 模型慢请求、依赖和故障进入核心门户，违背当前隔离目标 | 不采用 |
| 在原 Python 目录原地替换成 Java | 表面目录和 Compose 简单 | 迁移中难以并行验证/回退，容易混淆未提交 Python 修复 | 不采用 |

## 11. 验收定义

Java 版达到以下条件才视为迁移完成：

1. 功能、API 与移动端契约保持兼容，前端无需修改业务逻辑。
2. 只读调用白名单、个人优惠券身份边界和交易拒绝策略均有自动化测试。
3. Java 全量测试、Stub 评测、Compose/Nginx 配置及运行时端到端验证通过。
4. Compose 只运行 Java 智能体；Python 源码虽保留，但不再是运行依赖。
5. 无 SQL/数据库写操作、无密钥泄露、无未经确认的 push。
6. 报告如实区分自动化测试、Stub、真实模型、真实会员身份及微信真机的验证状态。

## 12. 审阅清单

- [ ] 服务保持独立，模块拟命名为 `mall-agent`。
- [ ] 保持现有 HTTP API、Redis 会话语义、环境变量与前端不变。
- [ ] 将未提交分支中的个人券意图行为作为规则要求迁移，但不合并或改写该 worktree。
- [ ] Python 源码保留为参考，不再进入最终 Compose 运行链路。
- [ ] 不接 MySQL/MongoDB/RabbitMQ/Elasticsearch 客户端，不新增表，不增加任何商城写操作。

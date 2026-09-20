# Mall 后续阶段实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已合并订单状态一致性和 Elasticsearch 基础闭环的基础上，完成未提交工作树收尾、统一 Docker 启动、关键链路验证，并为商品导购智能体建立可验收的第一版范围。

**Architecture:** 先串行收尾已有 `comment-followup` 与 `es-search-hardening`，再以 Docker Compose 固化基础设施，最后用后端测试、前端测试和真实接口冒烟覆盖核心交易链路。导购智能体作为独立子项目，不与基础设施或订单状态改动混合提交。

**Tech Stack:** Java 17、Spring Boot、MyBatis、MySQL、Redis、RabbitMQ、Elasticsearch、MinIO、Vue 3、uni-app、Vitest、Maven、Docker Compose。

**Spec:** `F:\code\mall\AGENTS.md` 第 2、4、5、6 节及本阶段用户确认的任务顺序。

## Global Constraints

- 新代码必须在独立功能分支和 worktree 中完成，不能直接修改 `main`。
- 工作树实现阶段禁止 `git commit`、`git push`、`git merge`；由主 Agent 审查后统一执行。
- MySQL 的 INSERT、UPDATE、DELETE、DDL 和权限变更必须先说明影响并取得明确确认。
- `.codebuddy/`、环境配置、密码、Token、`node_modules/`、`target/`、`dist/` 不提交。
- 每个工作树必须生成 `.codebuddy/reports/<task-slug>-report.md`，聊天只返回简短回执。
- 每个逻辑单元使用中文提交信息；合并后在 `main` 重新验证，用户确认后再推送。

---

### Task 1: 收尾并合并会员评价分页返工（已完成）

**Files:**
- Worktree: `F:\code\mall\.worktrees\comment-followup`
- Review: `mall-app-web-master/src/pages/comment/list.vue`、`mine.vue`、`composables/useCommentPaging.ts`、对应 spec
- Report: `.codebuddy/reports/comment-followup-report.md`

- [x] 读取报告、`git diff` 和 `git status`，确认只涉及评价分页和刷新锁。
- [x] 运行 `npm test`、`npm run tsc`、`npm run build:h5` 和 `git diff --check`。
- [x] 只读审查登录返回、刷新抢占、失败重试、重复触底和空商品 ID。
- [x] 提交：`fac4fc0 修复会员评价分页并发与登录回跳`。
- [x] 合并到 `main`：`db38c5f`，并在 `main` 重跑前端测试。

---

### Task 2: 收尾并合并 Elasticsearch hardening（已完成）

**Files:**
- Worktree: `F:\code\mall\.worktrees\es-search-hardening`
- Backend: `mall-master/mall-search`、`mall-master/mall-admin`、`mall-master/mall-portal`
- Docs: `mall-master/document/es-search/`
- Report: `.codebuddy/reports/es-search-hardening-report.md`

- [x] 读取报告、实际 diff 和敏感信息检查结果。
- [x] 核对 1-based 分页、深分页空页、批量上限、内部 Token、事务提交后同步和旧索引删除。
- [x] 运行相关单元测试、编译和 `git diff --check`；完整 SpringBoot 集成测试需显式注入本地数据源。
- [x] 未执行 MySQL 写操作；真实商品写入只做代码和测试验证。
- [x] 提交：`2d7d8f2 加固 Elasticsearch 搜索闭环与分页边界`。
- [x] 合并到 `main`：`f6322d0`，并在 `main` 重跑 ES 相关测试。

---

### Task 3: 清理已完成 worktree（已完成）

**Files:**
- `F:\code\mall\.worktrees\comment-admin`
- `F:\code\mall\.worktrees\comment-member`
- `F:\code\mall\.worktrees\es-search-admin`
- `F:\code\mall\.worktrees\es-search-member`

- [x] 分别确认已合并 worktree 无未提交改动且分支已包含在 `main`。
- [x] 保存已合并提交哈希；历史任务报告按原 worktree 记录保留或随 worktree 清理，不作为当前代码基线。
- [x] 经用户确认后逐个执行 `git worktree remove <明确路径>`，不批量删除。
- [x] 保留分支历史，不删除远程分支。

收尾记录：旧的评价与 Elasticsearch worktree 已完成清理；Docker 收尾 worktree `feature/minio-quay-images` 已合并至 `main`，并已移除其工作目录，保留分支历史。后续核心测试 worktree 在合并后另行保留，待用户确认后再清理。

---

### Task 4: Docker Compose 与启动文档（已完成）

**Files:**
- Create: `F:\code\mall\docker-compose.yml`
- Create: `F:\code\mall\document\docker\local-startup.md`
- Modify only when necessary: `mall-master/document/docker/`

- [x] 盘点现有 Docker 文件、端口和环境变量，避免重复定义。
- [x] 编排 MySQL、Redis、RabbitMQ、Elasticsearch、MinIO，并配置持久化卷和健康检查。
- [x] 不把数据库密码、JWT 密钥、MinIO 密码写入仓库，使用 `.env.example` 占位符。
- [x] 文档写明启动顺序、数据库快照边界、RabbitMQ 用户/vhost、MinIO bucket、ES 索引导入和停止命令。
- [x] 使用 `docker compose config` 校验，并完成实际容器、HTTP、MinIO 和 ES 验证。
- [x] 已完成提交：`aa9b1ba`、`c629448`、`ea338b7`、`32c06e6`。

---

### Task 5: 关键接口与端到端验证（自动化测试完成，在线冒烟待执行）

**Files:**
- Tests remain beside existing modules under `mall-master/**/src/test/`。
- Manual checklist: `document/testing/core-flow-checklist.md`。

**本轮测试边界：**

- 后端新增测试默认使用 Mockito、standalone MockMvc 或 H2，不连接开发环境默认数据源，不把本机凭据复制到测试文件。
- 真实 Docker API 冒烟只允许使用当前本地环境中已存在的数据做只读验证；若需要新增/修改业务数据，必须先按 `AGENTS.md` 取得 SQL 执行确认，并优先使用隔离测试数据。
- 当前基线：`mall-admin` 现有测试 49 个通过；`mall-search` 已执行 35 个测试，其中 3 个 Spring 上下文测试因开发 profile 默认数据源连接配置失败，不能据此宣称全套测试通过；`mall-portal` 因 Maven reactor 在 `mall-search` 失败后未执行。

- [x] 后端自动化覆盖会员注册/登录、购物车、订单、评价等核心控制器与服务边界；新增 77 个 portal 用例，全部通过。
- [x] ES 搜索分页和深分页边界已覆盖；新增 14 个 search 用例，全部通过。商品导入、同步和鉴权既有测试仍需结合在线环境清单继续核验。
- [x] 前端 Vitest 覆盖商品搜索、支付结果和相关关键交互；7 个测试文件、43 个用例通过，TypeScript 检查通过。
- [ ] 启动 Docker 基础设施后，按清单完成一次真实 API 冒烟；不得把测试数据写入生产库。
- [x] 已记录基线失败和本轮失败，未用“测试通过”掩盖环境错误：`MallSearchApplicationTests` 3 个环境配置错误、`PortalProductDaoTests` 1 个 H2 表缺失错误，均为改动前基线。
- [x] 独立提交：`085197b 补充核心接口与端到端测试`；已合并到 `main`，未 push。

---

### Task 6: 移动端图片与真机联调

**Files:**
- `mall-app-web-master/src/` 中涉及图片基础地址的配置文件
- `document/docker/local-startup.md`

- [ ] 为 H5、微信开发者工具和局域网真机分别定义图片 API 地址。
- [ ] 将手机访问地址从 `localhost:9000` 改为可访问的局域网 IP 或环境变量。
- [ ] 验证首页、分类、商品详情、评价、购物车和订单图片均返回 200。
- [ ] 验证字体、MinIO 公开读、跨域和微信开发者工具安全域名配置。
- [ ] 独立提交：`完善移动端局域网资源访问配置`。

---

### Task 7: 第一版商品导购智能体

**Files:**
- New module/design only after Tasks 1–6 are stable。
- Design doc: `document/agent/product-shopping-agent.md`

- [ ] 先定义只读能力：商品搜索、筛选、详情问答、库存和优惠券解释。
- [ ] 明确工具边界：智能体不能直接改订单、库存、支付或数据库；下单必须回到现有前端确认流程。
- [ ] 设计商品搜索工具调用协议，复用 `/product/search`，不绕过门户服务。
- [ ] 为敏感操作设计二次确认和审计日志，不在提示词中放密钥。
- [ ] 先做离线评测集和 10 条典型对话，再决定模型和接口实现。
- [ ] 独立提交：`设计第一版商品导购智能体`。

---

## 当前执行顺序

1. 用户确认后清理已合并的 `core-flow-tests` worktree。
2. 按只读清单完成 Docker API 在线冒烟，并记录真实响应。
3. 手机/微信真机图片联调。
4. Elasticsearch 遗留项专项处理。
5. 商品导购智能体设计与实现。

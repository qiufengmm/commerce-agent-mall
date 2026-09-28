# 退役旧 Python 商品导购智能体设计稿

- 日期：2026-09-28
- 状态：**已批准并已实施**（用户已批准范围并**手动删除**两个目标；本设计稿保留为设计记录）。**后续主 Agent 独立复核 / 提交 / 合并 / push 尚未完成。**（详见第 11 节「实施与复核更正」）
- 工作树：`F:\code\mall\.worktrees\retire-python-agent`（分支 `codex/retire-python-agent`）
- 起始基线（本任务开始时实测）：`main` = `origin/main` = `cd38a73`（`合并商品导购验收计划与交接摘要`），与本工作树 HEAD 一致。**该值是退役任务开始时的基线，不是退役合并后的 HEAD。**
- 相关规则：`F:\code\mall\AGENTS.md` 第 2、5、7、8 节

---

## 1. 背景与现状事实

商品导购智能体的运行实现已由 Python 切换为 Java 17，但旧 Python 工程与旧回滚文档仍留在仓库中。本设计只处理“旧 Python 资产的退役”，**不涉及任何 Java / API / H5 / 数据库 / Docker 栈的功能变更**。

以下事实已在当前工作树只读核实（不是历史摘要转述）：

| 事实 | 核实结果 |
| --- | --- |
| Java 实现 | `mall-master/mall-agent`（Spring Boot，`mall-master/pom.xml:20` 的 `<module>mall-agent</module>`） |
| Compose 运行实现 | `docker-compose.yml:429` 的 service key `mall-shopping-agent`，`docker-compose.yml:442-443` 用 `context: .` + `dockerfile: document/docker/Dockerfile.app`，`args.MODULE=mall-agent` |
| 旧 Python 目录 | `mall-shopping-agent/`，`git ls-files` 计 **74** 个 tracked 文件；无 untracked / ignored 残留在其中 |
| 74 个文件构成 | 目录根 3（`README.md`、`pyproject.toml`、`.dockerignore`）、`evals/` 2、`src/mall_shopping_agent/` 37、`tests/` 32 |
| 旧独立 Dockerfile | `document/docker/Dockerfile.agent`（`FROM python:3.11-slim`，`CMD uvicorn mall_shopping_agent.main:app`，端口 8086），当前 Compose 不再引用 |
| 对外边界（保持不变） | service key `mall-shopping-agent`、`app` profile、容器端口 `8086`、宿主机 `127.0.0.1:8086`、Nginx `/agent-api/` → `mall-shopping-agent:8086`、H5 `VITE_AGENT_API_BASE_URL` / `/agent-api` |
| 环境变量 | `.env.example:124` 标注 `mall-shopping-agent` 运行实现为 Java；`.env.example:193` `AGENT_IMAGE=mall-local/mall-shopping-agent:local`；23 个 `MALL_AGENT_*` 由 Java `AgentProperties` 使用 |
| 活动路径对 Python 的引用 | 仅剩注释与文档：`docker-compose.yml:415`、`docker-compose.yml:440-441`、`document/docker/local-startup.md:714-715,928-992`、`document/agent/product-shopping-agent.md` 文首备注、`docs/context/project-handoff.md:31`，以及历史计划 `docs/superpowers/plans/2026-09-22-python-product-shopping-agent.md`。Java 测试（如 `ToolRegistryTest`、`ProductToolsTest`）中的 Python parity 对照文本属保留材料，不是构建依赖 |
| 旧回滚文档失效 | `document/docker/local-startup.md:928-992` 的回滚块前提是「Java 未合并、主仓 Compose 仍为 Python、工作树 `F:\code\mall\.worktrees\java-agent-migration` 存在、`F:\code\mall\docker-compose.yml` 为 Python 构建」。实测 `git worktree list` 只有 `F:\code\mall`（main）与本工作树，`F:\code\mall\.worktrees\java-agent-migration` **不存在**；main 的 `docker-compose.yml` 已是 Java 构建。该回滚指令**不可再执行** |
| 交接摘要基线过时 | `docs/context/project-handoff.md:11,89` 记录基线 `ed5140a`，与本任务开始时实测不符（实测 `main` = `origin/main` = `cd38a73`）；其中 Python worktree 待办条目（`codex/agent-live-acceptance` 的 6 个未提交 Python 改动）为过时状态 |

---

## 2. 目标与非目标

### 2.1 目标

1. 移除旧 Python 商品导购智能体工程 `mall-shopping-agent/`（74 个 tracked 文件）与旧独立镜像定义 `document/docker/Dockerfile.agent`。
2. 更新仍指向旧 Python 资产的**活动**文档，使其不再声称“Python 源码保留可回滚”，并移除不可执行的 Python 回滚指令。
3. 更正 `docs/context/project-handoff.md` 的当前基线与 Python 相关过时状态。

### 2.2 非目标（明确不做）

- **不触碰主 Compose stack**（服务定义、`profiles`、`build`、`args`、`environment`、`ports`、`networks`、`depends_on`、`healthcheck`、`restart`）、**不改 `env` / `.env.example`**、**不改 Java / H5 代码**、**不改数据库与 API 契约**。
- 不改 `mall-master/mall-agent` 的任何 Java 源码、测试或注释（含 Python parity 对照文字）。
- 不改 `docker-compose.yml` 的 **service 定义**（service key、`profiles`、`build`、`args`、`environment`、`ports`、`depends_on`、`healthcheck`、`networks`、`restart` 一律不动），**只允许改其中的纯注释**。
- 不改 service key `mall-shopping-agent`、镜像名 `mall-local/mall-shopping-agent:local`、端口 `8086`、Nginx `/agent-api/` 上游、H5 的 `/agent-api` 与 `VITE_AGENT_API_BASE_URL`。
- 不改 `.env`、`.env.example` 的变量名或取值，不改任何真实凭据。
- 不改数据库表结构，不执行任何 SQL。
- 不改 Java 业务逻辑、API 契约、H5/移动端代码。
- 不删除 `.gitignore` 中 `__pycache__/`、`.venv/`、`.pytest_cache/` 等 Python 缓存忽略规则（保留，防止未来误提交）。
- 不删除历史计划与历史设计：`docs/superpowers/plans/2026-09-22-python-product-shopping-agent.md` 全文保留；`docs/superpowers/specs/2026-09-23-java-product-shopping-agent-design.md` 与 `docs/superpowers/plans/2026-09-23-java-product-shopping-agent.md` 保留原文，实施时仅**追加**退役附记，不全文改写。
- 不动 Docker 栈：不 `up/down/build`、不重建容器、不清理 volume。

### 2.3 关键澄清：删除 Python 源码 ≠ 删除 Java 的 `mall-shopping-agent` service

这是本任务最容易被误读的点，实施与审查都必须显式对齐：

- 被删的是**目录** `mall-shopping-agent/`（旧 Python 工程，路径名）。
- **保留**的是 Compose **service key** `mall-shopping-agent`（运行时服务名 / 容器 DNS 名 / Nginx 上游名），它由 Java 模块 `mall-master/mall-agent` 经 `document/docker/Dockerfile.app` 构建。
- 二者仅**巧合同名**。删除目录不会、也不得影响 service、`app` profile、端口、网络、健康探针或任何运行时行为。

---

## 3. 方案取舍

| 方案 | 内容 | 结论 |
| --- | --- | --- |
| A. 删除 + 文档同步（推荐） | 删除 `mall-shopping-agent/` 与 `Dockerfile.agent`，更新活动文档与交接摘要 | **已采纳并已实施**（删除由**用户手动执行**，助手不代删）。运行实现早已是 Java，Python 目录纯属死代码；留着会让后来者误以为可回滚、并误导构建路径。 |
| B. 只删 `Dockerfile.agent`，保留 Python 源码 | 仅移除独立镜像定义 | 否决。Python 源码与测试仍会被误认为“可运行实现”，且回滚文档仍指向已不可用的路径；清理不彻底。 |
| C. 全部保留，只改文档 | 仅把文档改成“已退役” | 否决。仓库继续背负 74 个失效文件与死构建路径；与用户已确认范围不符。 |
| D. 删除并改写全部历史文档 | 连历史计划/设计一起改写 | 否决。历史计划与 Java parity 对照是审查与追溯材料，改写会丢失上下文；改为“保留 + 追加短附记”。 |

---

## 4. 精确文件边界

### 4.1 删除（Delete，**由用户手动执行**）

| 路径 | 说明 |
| --- | --- |
| `mall-shopping-agent/`（整个目录，74 个 tracked 文件） | 旧 Python 工程：`README.md`、`pyproject.toml`、`.dockerignore`、`evals/**`、`src/mall_shopping_agent/**`、`tests/**` |
| `document/docker/Dockerfile.agent` | 旧 Python 独立镜像定义，当前 Compose 无引用 |

**删除由用户手动执行，助手不代删。** 执行前须确认：无 untracked / ignored 文件残留（已确认为空）；无活动构建/运行路径引用（见第 6 节）。

### 4.2 修改（Modify，仅限以下位置）

| 路径 | 允许改动 | 禁止改动 |
| --- | --- | --- |
| `docker-compose.yml` | **仅注释**：`415` 行“Python 源码与历史测试仅作参考”与 `440-441` 行“需要回到 Python 旧实现时…回滚为 `context: ./mall-shopping-agent` + `Dockerfile.agent`”更新为不再指向已删除路径 | 任何 YAML 键值、service 字段 |
| `document/docker/local-startup.md` | 4.7 节：`714-715` 行“仅作参考保留”改为“已退役删除”；**完整删除** `928-992` 行 Python 回滚块，替换为短的 Git 层 `git revert` 恢复说明（见 4.5） | 4.7 节其它 Java 运行说明、端口/网络/探针等描述 |
| `document/agent/product-shopping-agent.md` | 文首状态备注（`3-13` 行等）改为“Python 实现已退役并从仓库删除，本文仅存历史设计”；正文历史设计段落保留 | 对外边界、API 契约等仍有效的描述 |
| `docs/context/project-handoff.md` | 更正过时基线记录 `ed5140a`，表述为“本任务起始基线 `cd38a73`（`main` = `origin/main`）；后续状态以 git 实测为准”，**不预设未来合并 SHA**；Python 商品导购智能体条目状态更新为“Java 为唯一实现，Python 已退役”；旧 worktree 条目按 `git worktree list` 实测改为**历史说明**（区分 worktree 与**保留不变**的分支 ref，不清理 refs） | 其它模块基线、工作流说明 |

### 4.3 仅追加退役附记（Append only）

| 路径 | 追加内容 |
| --- | --- |
| `docs/superpowers/specs/2026-09-23-java-product-shopping-agent-design.md` | 一段短附记：指向本设计稿，说明 Python 原实现已退役删除，Java 为唯一实现 |
| `docs/superpowers/plans/2026-09-23-java-product-shopping-agent.md` | 一段短附记：记录退役动作与提交哈希（实施后回填），不改写原计划正文 |

### 4.4 保留（Keep，不动）

- Java 实现：`mall-master/mall-agent/**`、`mall-master/pom.xml` 的 `mall-agent` module。
- Compose：`docker-compose.yml` 的 `mall-shopping-agent` service 定义、`app` / `edge` / `observability` profiles、`agent-proxy-net` / `agent-backend-net`。
- 构建与代理：`document/docker/Dockerfile.app`、`document/docker/nginx/conf.d/default.conf`、`document/docker/check-agent-env.sh|.ps1`、`document/docker/check-env.*`。
- 环境变量契约：`.env.example` 中 `AGENT_IMAGE`、23 个 `MALL_AGENT_*`。
- 前端：`mall-app-web-master/**`（含 `VITE_AGENT_API_BASE_URL` / `/agent-api`）。
- 忽略规则：`.gitignore:34,36,38` 的 `__pycache__/`、`.venv/`、`.pytest_cache/`。
- Java 测试与注释中的 Python parity 对照文本。
- 历史材料：`docs/superpowers/plans/2026-09-22-python-product-shopping-agent.md` 全文。

### 4.5 回滚文档的替换原则（`local-startup.md` 4.7 节）

实施时**完整删除** `local-startup.md` 中 `928-992` 行原有的 Python 回滚块，并用一段**短说明**取代其位置。确定要求：

- **不保留、不折叠**任何原 Python 回滚命令；不得残留任何可复制的历史 Python 命令（例如 `context: ./mall-shopping-agent`、`dockerfile: ../document/docker/Dockerfile.agent`、Python 标准库 healthcheck、恢复旧 13 项 `MALL_AGENT_*` 的步骤）。
- 新说明只写两点：
  1. Python 运行时已从仓库删除，不再提供任何本地 Python 回滚路径。
  2. 如需回滚，只在 **Git 层**操作：对已合并到 `main` 的退役提交执行 `git revert <退役提交>`，并重新按第 7 节验收。
- **不写**任何直接操作或删除文件的命令（例如 `git checkout -- <path>`）；回滚一律经由 Git 提交级 revert。具体退役提交 SHA 由实施提交产生后回填，本设计稿不预设。

---

## 5. 运行时、API、数据库与配置影响

- **运行时**：无变化。容器构建路径仍为 `context: .` + `document/docker/Dockerfile.app`（`MODULE=mall-agent`）；service key、镜像标签、端口 `8086`、健康探针 `/health/live` 均不变。删除后仓库根 Docker 构建上下文更小（少一个目录），无功能风险。
- **API / 对外接口**：无变化。`/agent-api/agent/chat`、`/agent/session/*`、`/health/live`、`/health/ready` 与 Nginx 上游 `mall-shopping-agent:8086` 保持原样。
- **数据库**：无影响。不涉及任何 DDL / DML、不连库、不改表结构。
- **配置与凭据**：无变化。`MALL_AGENT_*`、`AGENT_IMAGE`、`.env` / `.env.example` 均不改；不删除、不新增、不改写任何凭据。
- **H5 / 移动端**：无影响。`/agent-api` 与 `VITE_AGENT_API_BASE_URL` 语义不变。

---

## 6. 风险与回滚

| 风险 | 影响 | 缓解 / 处置 |
| --- | --- | --- |
| 误删 Java 的 service 或误改 service 定义 | 智能体运行链路中断 | 第 2.3 节澄清 + 第 4.2 节限定“Compose 只改注释”；审查时逐行核对 service 块 diff |
| 活动构建路径仍引用被删文件 | `docker compose build` 失败 | 已扫描：仅注释/文档引用；验收阶段再全量扫描 `Dockerfile.agent` / `mall-shopping-agent/` / `mall_shopping_agent` 的活动（非历史）引用 |
| 删除目录触及项目“禁止批量删除”规则 | 违规操作 | 见第 8 节；范围已确认为两个精确路径组，**由用户手动删除**；助手**不得**使用 `git rm -r`、`rm -rf`、`rd /s`、`Remove-Item -Recurse` 等递归/批量删除命令 |
| 回滚能力丧失 | 无法本地退回 Python 运行时 | 设计取舍：Git 历史即唯一回滚来源（第 4.5 节）；本设计稿与退役附记提供追溯 |
| 误删 `.gitignore` Python 忽略规则或 Java parity 对照 | 未来误提交 .pyc / 丢失审查上下文 | 第 4.4 节明确列为“保留” |
| 交接摘要基线写错 | 后续对话基于错误基线 | 第 4.2 节将过时 `ed5140a` 更正为“本任务起始基线 `cd38a73`”，并注明后续以 git 实测为准 |

**回滚方案**：本设计的“反操作”只在 **Git 层**进行——对已合并到 `main` 的退役提交执行 `git revert <退役提交>`（保留审计轨迹），随后重新执行第 7 节验收。**不使用**任何直接操作或删除文件的命令（例如 `git checkout -- <path>`）。

---

## 7. 验收标准（实施阶段执行；本设计阶段不运行）

1. **Java 测试**：`mvn -f mall-master/pom.xml -pl mall-agent -am test` → 构建成功、测试通过（Python 退役不应影响 Java 模块）。
2. **Compose 配置**：
   - 基础：`docker compose --env-file .env.example config --quiet` → exit 0；
   - 全部 profiles：`docker compose --env-file .env.example --profile app --profile edge --profile observability config --quiet` → exit 0。
3. **引用扫描**：活动运行/构建路径无 `Dockerfile.agent`、`./mall-shopping-agent`、`mall_shopping_agent` 依赖。
   - 允许保留：历史计划/设计（`2026-09-22-python-product-shopping-agent.md`、`2026-09-23-*` 附记）、Java 测试与注释中的 Python parity 对照文本、`docs/context/project-handoff.md` 中标记为历史/已退役的条目、`local-startup.md` 4.7 节替换后的短 Git revert 说明（不含任何可复制 Python 命令）。
4. **差异与产物检查**：`git diff --check` exit 0；扫描变更集无 `.env`、`application-dev.yml`、`application-prod.yml`、`node_modules/`、`target/`、`dist/`、密钥、备份或临时迁移文件。
5. **边界复核**：`git diff` 中 Java 源码、Compose service 字段、Nginx、H5、`.env*`、`.gitignore` 均无功能改动（Compose 仅注释变化）。
6. **删除复核**：`git status` 中删除项恰为 `mall-shopping-agent/` 与 `document/docker/Dockerfile.agent`，无其它意外删除。

---

## 8. 实施顺序与合规约束

1. **已执行**：本设计稿经用户批准后，**由用户手动删除**两个目标（`mall-shopping-agent/` 与 `document/docker/Dockerfile.agent`），随后更新活动文档并追加历史附记。
2. 删除由 `AGENTS.md` 第 5 节约束，范围已确认为两个精确路径组：
   - 仅删除 `mall-shopping-agent/`（整个目录）与 `document/docker/Dockerfile.agent`，只移除**已跟踪**文件；
   - **由用户手动删除**；助手与任何自动化流程**均不得**使用 `git rm -r`、脚本循环、`del /s`、`rd /s`、`rmdir /s`、`Remove-Item -Recurse`、`rm -rf` 等**递归/批量删除**命令；
   - **历史记录（已被用户手动删除方案取代，不得执行）**：本设计稿早期版本曾把 `git rm -r -- mall-shopping-agent` 与 `git rm -- document/docker/Dockerfile.agent` 列为执行方式；该写法**已被取代**，仅作追溯说明，**禁止**作为可执行步骤。
3. 不做与本设计无关的顺手清理、重命名或“改善”。
4. 实施阶段不在工作树内 `commit` / `push` / `merge`；由主 Agent 按标准流程处理。

---

## 9. Git 约束

- 工作树 `codex/retire-python-agent`；起始基线 `main` = `origin/main` = `cd38a73`（**任务开始时实测，非退役合并后的 HEAD**）。
- **本设计阶段（当前）不执行任何 `commit` / `push` / `merge` / `rebase`**，不运行 Compose / Maven / Docker / SQL。
- 实施提交由主 Agent 负责，中文提交信息，删除与文档更新按逻辑单元组织（可为一个退役提交）。
- 合并到 `main` 前确认 `main` 工作区干净；合并后重新执行第 7 节验收。
- 未经用户明确确认不 `git push`；推送前确认远程仓库与分支。
- 本设计稿与本地报告（`.codebuddy/reports/`）默认不纳入功能提交，除非主 Agent 另行决定。

---

## 10. 已决事项与当前进度

### 10.1 已决（用户已批准，不再重新征询）

1. **删除范围**：采纳方案 A——删除整个 `mall-shopping-agent/` 目录（74 个 tracked 文件）与 `document/docker/Dockerfile.agent`，仅限这两个精确路径组，且只移除**已跟踪** Python 文件。
2. **回滚文档处理**：完整删除 `document/docker/local-startup.md` 的原 Python 回滚块（`928-992`），用短的 Git 层 `git revert` 恢复说明取代；**不保留**任何可复制的历史 Python 命令（见第 4.5 节）。
3. **手动待办 / worktree 状态**：以 `git worktree list` 实测为准（实测仅 `F:\code\mall`（main）与本工作树）；**worktree 与分支 ref 分开看待**，不清理任何 refs。
4. **删除执行方式**：**由用户手动删除**；助手不代删、不使用任何递归/批量删除命令（见第 8 节）。

### 10.2 当前进度与后续

- **已完成**：用户批准范围并**手动删除**两个目标（74 个 tracked 文件 + `document/docker/Dockerfile.agent`）；活动文档与历史附记已同步；工作树内自动化验收已完成（Java 全量测试、Compose `config` 默认 + 全 profile、差异检查、活动引用扫描）。
- **尚未完成（主 Agent 后置流程）**：**独立只读复核**（**首次复核返回 1 项 P1 + 2 项 P2，返工中、待复审，尚未通过**）、中文提交、本地合并复验、`git push`（须用户明确确认）。
- 本设计稿的「待用户审阅批准 / 唯一待办」状态**已结束**：范围已批准、实施已完成；后续为复核与提交流程。

---

## 11. 2026-09-28 实施与复核更正

> 本节为**追加**的 dated 更正记录，不改写上文历史；目标与 Java-only 边界**不变**。

**实施事实**：旧 Python 实现（`mall-shopping-agent/` 74 个 tracked 文件）与 `document/docker/Dockerfile.agent` 已**由用户手动删除**；助手未发出任何删除命令。活动文档（`docker-compose.yml` 注释、`local-startup.md`、`product-shopping-agent.md`、`project-handoff.md`）与两份 `2026-09-23-java-*` 历史附记已同步。工作树内自动化验收已完成。

**首次独立只读复核（`gpt-6-luna`，推理强度 `max`）结论：未通过**，返回 **1 项 P1 + 2 项 P2**：

| 级别 | 发现 | 处置 |
| --- | --- | --- |
| P1 | 本设计稿第 6 / 8 / 10 节仍把 **Git 递归删除旧 Python 目录写成已采纳方案**，与 `AGENTS.md` 第 5 节「禁止批量删除文件或目录」冲突；真实操作是**用户手动删除** | 已修正：第 6 / 8 节改为**用户手动删除**；递归删除命令仅在**明确标注“已被取代、不得执行”**的历史说明中提及，**不再出现步骤式递归删除指引**；第 10 节改为「已决事项与当前进度」 |
| P2 | `docs/context/project-handoff.md` 第 112 行把**已不存在的** `codex/deepseek-base-url`、`codex/java-agent-migration` worktree 列为“待清理候选”；第 121 行把**已不在 `git worktree list`** 的 `agent-live-acceptance` 放进“当前剩余任务” | 已修正：改为事实描述（旧 worktree 不在当前 `git worktree list`，**分支 ref 仍保留、不清理**，非当前 worktree 清理待办）；`agent-live-acceptance` 从「当前剩余任务」移除（详细历史保留在上一节） |
| P2 | 本设计稿仍写“待用户审阅批准/唯一待办”；实施计划进度与本地报告当时不同步 | 已修正：本稿状态更新为「已批准并已实施，后续复核/提交未完成」；实施计划与本地报告已按实际进度同步 |

**明确边界**：本节**不含**任何可执行的 Git 删除命令；**不得**把递归/批量删除作为建议或步骤。复核返工**尚未复审通过**。

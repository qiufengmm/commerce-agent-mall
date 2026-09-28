# 退役旧 Python 商品导购智能体实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task. 遵循 Mall 手工 worktree 流程；不通过 MCP 自动分派编码子任务。步骤使用 `- [ ]` 复选框跟踪状态。
>
> **当前进度（2026-09-28）**：Task 0–6 已完成；Task 7.1–7.6 已实测通过；Task 8.1 实施报告已生成；**Task 8.2 将在助手本次最终中文简短回执后完成**。文末「实施后必须由主 Agent 审查的事项」（独立只读审查、中文提交、本地合并、`push`）**仍为待办，未勾选**；`git push` 需用户明确确认。删除由**用户手动执行**；助手的编辑限于文档与 Compose 注释。
>
> **独立复核状态**：首轮独立只读复核（`gpt-6-luna`，推理强度 `max`）返回 **1 项 P1 + 2 项 P2**（文档口径），**均已修复**；**第二轮独立只读复核已通过**（首轮问题均已解决，暂未发现新阻断；第二轮 P3 非阻断建议——不应把保留分支 ref 编号放在「当前剩余任务」列表——**已通过移除重复列表项解决**）。**提交 / 本地合并 / `push` 后置流程仍未完成；`git push` 仍需用户明确确认。** 详见报告第 11 / 12 节与设计稿第 11 节。

**目标：** 从仓库中退役已不再运行的旧 Python 商品导购智能体实现（`mall-shopping-agent/` 目录与 `document/docker/Dockerfile.agent`），并同步更新仍指向旧 Python 资产的活动文档；保持 Java 运行时服务、对外契约、配置与前端完全不变。

**架构：** 运行实现早已是 Java 17 的 `mall-master/mall-agent`，Compose service key `mall-shopping-agent` 由 `document/docker/Dockerfile.app`（`MODULE=mall-agent`）构建。本计划只删除死代码路径并修正文档，不触碰任何运行链路。

**技术栈 / 工具：** Git 只读命令（`git status` / `git diff` / `git ls-files`）、PowerShell 7（`rg`、`Select-String`、`Test-Path`、`(...).Count`）、Maven（Java 17 全量测试）、Docker Compose（仅 `config` 校验）、文本编辑。**本计划不含任何递归/批量删除命令。**

**设计依据：** `docs/superpowers/specs/2026-09-28-retire-python-shopping-agent-design.md`

**工作目录（所有命令默认在此运行）：** `F:\code\mall\.worktrees\retire-python-agent`

**起始基线（任务开始时实测）：** `main` = `origin/main` = `cd38a73`（`合并商品导购验收计划与交接摘要`）。**这是起始基线，不是退役合并后的 HEAD。**

> **分支现状：** 编写本计划时特性分支 `codex/retire-python-agent` HEAD 为 `cd93893`（`编写旧 Python 商品导购退役设计`，仅新增设计稿）；`main` / `origin/main` 仍为 `cd38a73`。实施从分支当前 HEAD 开始。

> **行号说明：** 下文引用的行号取自基线 `cd38a73` 的工作树快照。编辑后行号会漂移，因此每一步都同时给出**内容锚点**（引号内的原文片段）；以锚点定位为准。

---

## 执行门禁（重要）

1. **用户先审阅本计划**；未获批准前不进入任何实施动作。
2. **两个删除目标由用户手动删除**：`mall-shopping-agent/`（74 个 tracked 文件）与 `document/docker/Dockerfile.agent`。**助手不代删**。
3. **禁止自动/批量/递归删除**：不得用 `git rm -r`、脚本循环、`Remove-Item -Recurse`、`rm -rf`、`rd /s` 等任何方式删除 `mall-shopping-agent/` 下的 74 个文件——这违反 `AGENTS.md` 第 5 节「禁止批量删除文件或目录」。遇到需要批量删除的情形一律**停下并请求用户手动处理**。
4. **续行条件**：仅当用户确认已手动删除、且主 Agent 核验删除范围恰为 74 + 1 后，才继续 Task 2–7 的文档修改与验收。删除未完成前，Task 2–7 一律不启动。

---

## 全局约束

- 删除范围仅限设计稿授权的两个精确目标：`mall-shopping-agent/`（整个目录，74 个 tracked 文件）与 `document/docker/Dockerfile.agent`；**由用户手动删除，助手不代删**。
- **禁止任何自动/批量/递归删除**：不得使用 `git rm -r`、脚本循环、`Remove-Item -Recurse`、`rm -rf`、`rd /s`、`del /s`、`rmdir /s`。单个明确文件的 `git rm -- <单个路径>` 仅可用于**未来的独立任务**；本任务的两个目标统一由用户手动处理。
- **不得改变** `docker-compose.yml` 的任何服务键/值、`profiles`、`build`、`args`、`environment`、`ports`、`networks`、`depends_on`、`healthcheck`、`restart`——**只允许改注释**。`mall-shopping-agent` 仍是 **Java 运行时**服务。
- **不得触碰**：`mall-master/mall-agent/**`、根 `mall-master/pom.xml`、Compose Java service 与所有运行集成、Nginx `/agent-api/`、H5/小程序客户端、`.env` / `.env.example`、`AGENT_IMAGE`、全部 `MALL_AGENT_*` 变量、`.gitignore` 的 Python cache 规则、Java 测试/fixtures/JavaDoc 中必要的 Python parity 说明、历史计划 `docs/superpowers/plans/2026-09-22-python-product-shopping-agent.md`。
- 不改数据库、API、H5/Java 业务逻辑；**不执行 SQL**；**不执行 `docker compose up/down/build`**；不动真实 `.env`；不 `push`。
- `document/docker/local-startup.md` 中**禁止保留**任何可复制的 Python 回滚命令；回滚只写 **Git 层 `git revert`**，不得写 `git checkout --` 或任何直接操作/删除文件的命令。
- 不得在工作树内 `commit` / `push` / `merge` / `rebase`；由主 Agent 审查真实 diff 后按项目流程处理 Git。
- 全量 Maven 测试不得使用 `maven.test.failure.ignore`。**若 Maven 或 Docker 不可用，记录阻塞，不得用历史测试报告冒充当前验收。**
- 不输出、不提交任何真实 API Key / Token / 密码 / `.env` 值。
- 执行删除前若发现**额外文件**或**用户内容**（untracked / ignored / 非授权路径），**立即停止并报告**，不得自行清理。

---

## 文件边界与职责

| 路径 | 计划职责 | 动作 |
| --- | --- | --- |
| `mall-shopping-agent/` | 旧 Python 工程（74 tracked 文件） | **由用户手动删除**（助手不代删；禁批量/递归） |
| `document/docker/Dockerfile.agent` | 旧独立 Python 镜像定义（单文件） | **由用户手动删除** |
| `docker-compose.yml` | `415`、`440-441` 行 Python 相关注释 | **仅改注释** |
| `document/docker/local-startup.md` | 4.7 节 `714-715` 行说明；`928-992` 行回滚段 | **改说明 + 删回滚段换短说明** |
| `document/agent/product-shopping-agent.md` | 顶部状态备注 `3-15` 行 | **改顶部说明，正文保留** |
| `docs/context/project-handoff.md` | 基线记录、Python agent 状态、旧 `agent-live-acceptance` worktree 历史状态 | **修正/更新** |
| `docs/superpowers/specs/2026-09-23-java-product-shopping-agent-design.md` | 结尾 | **仅追加退役附记** |
| `docs/superpowers/plans/2026-09-23-java-product-shopping-agent.md` | 结尾 | **仅追加退役附记** |

---

## Task 0：执行前置检查（只读，不改变任何状态）

**工作目录：** `F:\code\mall\.worktrees\retire-python-agent`

> **执行证据（2026-09-28 只读核实）**：分支 `codex/retire-python-agent`，`main` = `origin/main` = `cd38a73`，HEAD `cd93893`；删除前 `(git ls-files mall-shopping-agent).Count` = **74**，`git ls-files document/docker/Dockerfile.agent` = 1 行；删除前 `git status --short --ignored mall-shopping-agent` 为空；`mall-master/mall-agent/pom.xml` 存在且 `mall-agent` 在 reactor 中，Compose `mall-shopping-agent` 仍指向 `document/docker/Dockerfile.app`；`git worktree list` 仅含 main 与本工作树，分支 ref `codex/agent-live-acceptance` 仍存在。

- [x] **Step 0.1 — 确认工作树与基线**
  - 动作：`git status --short --branch`、`git rev-parse main origin/main HEAD`
  - 预期：分支为 `codex/retire-python-agent`；`main` = `origin/main` = `cd38a73`；工作区无**已跟踪文件**的改动（`M` / `D`）。
  - 停止条件：基线或分支不符 → 停止并报告。
- [x] **Step 0.2 — 确认删除目标的确切范围**
  - 动作（PowerShell 7）：`(git ls-files mall-shopping-agent).Count`、`git ls-files document/docker/Dockerfile.agent`
  - 预期：前者为 **74**；后者恰为 `document/docker/Dockerfile.agent` 一行。
  - 停止条件：数量不符 → 停止并报告。
- [x] **Step 0.3 — 确认无 untracked / ignored 残留**
  - 动作：`git status --short --ignored mall-shopping-agent`
  - 预期：**输出为空**。
  - 停止条件：出现任何 `??` 或 `!!` 条目 → 停止并报告（可能是用户内容）。
- [x] **Step 0.4 — 确认 Java 运行链路完好（只读）**
  - 动作（PowerShell 7）：`Test-Path mall-master/mall-agent/pom.xml`；`rg -n "mall-agent" mall-master/pom.xml`（**`rg` 无匹配时退出码 1 属预期**，不等于失败）
  - 预期：模块存在且已在 reactor 中声明；Compose `mall-shopping-agent` 仍指向 `document/docker/Dockerfile.app`。
- [x] **Step 0.5 — 记录前置检查结果**
  - 动作：把上述命令输出（不含任何凭据）记入实施报告草稿。
  - 预期：上述检查全部通过后才进入 Task 1。
- [x] **Step 0.6 — 核对旧 worktree 与分支 ref 状态（只读）**
  - 动作（PowerShell 7）：`git worktree list`；`git branch --list "codex/agent-live-acceptance"`
  - 预期（本计划编写时实测）：`git worktree list` 仅含 `F:/code/mall`（main）与 `F:/code/mall/.worktrees/retire-python-agent`；旧 `C:/Users/qiufengm/.codex/worktrees/agent-live-acceptance/mall` **不在列表**；分支 ref `codex/agent-live-acceptance` **仍存在**（无远端对应，仅本地 ref）。
  - 停止条件：实际状态与此不同（例如旧 worktree 又出现在列表、或分支 ref 已消失）→ **停下并报告**；**不得**清理 worktree，**不得**删除任何 branch ref。

---

## Task 1：由用户手动删除旧 Python 目录与旧独立 Dockerfile

**工作目录：** `F:\code\mall\.worktrees\retire-python-agent`｜**文件范围：** 仅 `mall-shopping-agent/`、`document/docker/Dockerfile.agent`

> **本 Task 无自动删除动作。** 助手只做只读核验与暂停请求；删除由**用户手动执行**（见「执行门禁」）。
>
> **执行证据（2026-09-28 只读核实）**：1.1 列出 74 + 1；1.2 **用户已手动删除**两个目标（助手未代删、未使用任何递归/批量命令）；1.3 `git status --porcelain` 恰为 **75 条 ` D`**（74 条 `mall-shopping-agent/...` + 1 条 `document/docker/Dockerfile.agent`），**无其它 ` M` / ` D` / `??`（计划文档为 `??`，非删除）**；`mall-shopping-agent` 与 `document/docker/Dockerfile.agent` 均已不存在（`Test-Path` = False）；未发现范围外改动。

- [x] **Step 1.1 — 只读列出确切 tracked 路径与数量（不删除）**
  - 动作（PowerShell 7）：
    - `(git ls-files mall-shopping-agent).Count` → 预期 **74**
    - `git ls-files mall-shopping-agent` → 完整路径清单（留痕）
    - `git ls-files document/docker/Dockerfile.agent` → 恰 1 行
  - 预期：74 + 1，与 Task 0 一致。
  - 停止条件：出现授权范围外的路径 → 停止并报告。
- [x] **Step 1.2 — 暂停并请求用户手动删除（助手不代删）**
  - 动作：向用户说明并请求其**手动删除**以下两个目标：
    - `mall-shopping-agent\`（整个目录，74 个 tracked 文件）
    - `document\docker\Dockerfile.agent`（单个文件）
  - 明确：助手**不得**使用 `git rm -r`、脚本循环、`Remove-Item -Recurse`、`rm -rf`、`rd /s` 等任何自动/批量/递归方式删除 `mall-shopping-agent/` 下的 74 个文件。
  - 停止条件：**在用户明确回复已手动删除之前，Task 1 就此暂停，不得继续任何后续步骤。**
- [x] **Step 1.3 — 用户确认后核验删除范围（只读）**
  - 动作（PowerShell 7，均只读）：
    - `git status --short` → 预期恰为 74 条 `mall-shopping-agent/...` 的 ` D` 项 + 1 条 ` D document/docker/Dockerfile.agent`，**无其它 ` M` / ` D` / `??`**
    - `Test-Path mall-shopping-agent` 与 `Test-Path document/docker/Dockerfile.agent` → 预期均为 `False`
    - `(git ls-files mall-shopping-agent).Count` → 预期 **74**（index 未更新时仍为 74；暂存删除由主 Agent 在审查阶段 `git add -A` 后复核 `git diff --cached`）
  - 预期：删除范围恰为 74 + 1。
  - 停止条件：出现**额外文件**、**ignored / untracked 的用户内容**，或删除范围不符 → **立即停止并报告**，不得自行清理或补偿删除。

---

## Task 2：更新 `docker-compose.yml` Python 退役注释（仅注释）

**工作目录：** `F:\code\mall\.worktrees\retire-python-agent`｜**文件范围：** `docker-compose.yml`（仅注释，禁改任何键值）

> **前置门禁：** Task 2 至 Task 7 仅在 Task 1（用户手动删除）核验通过后启动。

- [x] **Step 2.1 — 定位需改注释**
  - 动作：阅读 `docker-compose.yml` 第 `411-416` 行与 `438-443` 行，锚点：
    - `"mall-shopping-agent/ 下的 Python 源码与历史测试仅作参考，Compose 不再构建它。"`
    - `"需要回到 Python 旧实现时，按计划回滚为"` / `"context: ./mall-shopping-agent + dockerfile: ../document/docker/Dockerfile.agent。"`
  - 预期：仅这两处含退役相关注释。
- [x] **Step 2.2 — 改写为不再指向已删除路径的注释**
  - 动作：把 `415` 行注释改为说明“旧 Python 源码与独立 Dockerfile 已退役删除”；把 `440-441` 行的回滚注释改为“回滚统一走 Git 层（对退役提交 `git revert`），不再保留 Python 构建路径”。**不得**保留 `./mall-shopping-agent` / `Dockerfile.agent` 作为可执行构建指引。
  - 预期：注释文本更新；`mall-shopping-agent` service 定义体一字未动。
- [x] **Step 2.3 — 验证只有注释变化**
  - 动作：`git diff -U0 -- docker-compose.yml`，检查每个变更行去空白后是否以 `#` 开头
  - 预期：所有 `+` / `-` 行均为注释行；无任何非注释行变化。
  - 停止条件：出现非注释行变化 → **立即停止并报告**（不自动回退、不覆盖任何改动）。
  - 备注：本计划**不含** `git checkout --` 等自动回退/覆盖指令；越界差异交由用户与主 Agent 决定处理方式。
- [x] **Step 2.4 — Compose 配置校验（见 Task 7.2 统一执行）**
  - 动作：本步只在 Task 7.2 统一运行 `docker compose --env-file .env.example config --quiet`（预计 Docker CLI 可用即可，无需 daemon）。
  - 预期：exit 0；若 Docker 不可用 → 记录阻塞，不得跳过或伪造。
  - **执行证据（2026-09-28，见 Task 7.2）**：`docker compose --env-file .env.example config --quiet` **exit 0**；全 profile 同命令 **exit 0**。

---

## Task 3：更新 `document/docker/local-startup.md`

**工作目录：** `F:\code\mall\.worktrees\retire-python-agent`｜**文件范围：** `document/docker/local-startup.md`（仅 4.7 节相关处）

- [x] **Step 3.1 — 修正“仅作参考保留”表述**
  - 动作：将 `714-715` 行锚点 `"mall-shopping-agent/ 下的 Python 源码、测试与 `document/docker/Dockerfile.agent` 仅作参考保留，Compose 不再构建它们。"` 改为“旧 Python 源码与 `Dockerfile.agent` 已从仓库退役删除”。
  - 预期：不再声称 Python 源码保留在仓库。
- [x] **Step 3.2 — 完整删除旧 Python 回滚段**
  - 动作：删除从锚点 `"**回滚到 Python v1 运行时**（仅在 Java 实现验收失败、且用户确认后执行）："` 开始，到锚点 `"不要在验收失败时盲目重建共享服务或清理 volume。"` 结束的整段（基线约 `928-992` 行）。
  - 预期：该段及其内嵌代码块、列表全部移除；**不折叠、不注释保留**。
  - 停止条件：若该段边界与锚点不符（内容已被改动）→ 停止并报告。
- [x] **Step 3.3 — 插入简短 Git 层回滚说明**
  - 动作：在原位置写入简短说明，要点仅两条：
    1. Python 运行时已从仓库删除，不再提供任何本地 Python 回滚路径；
    2. 如需回滚，对已合并到 `main` 的退役提交执行 `git revert <退役提交>`，并重新按下文验收；不写 `git checkout --` 或任何直接操作/删除文件的命令。
  - 预期：新说明**不含**任何可复制 Python 命令。
- [x] **Step 3.4 — 复核 local-startup.md**
  - 动作（PowerShell 7，**拆成两条扫描**）：
    1. `rg -n "Dockerfile\.agent" document/docker/local-startup.md`
       - 预期：**唯一命中**为 Task 3.1 写入的**退役状态说明**（纯文字，说明 `Dockerfile.agent` 已退役删除）。
       - 停止条件：若该文件**没有**该退役状态说明 → 停止复核并报告；若命中**多于一处**、或命中处含可复制的 Python/Dockerfile **构建指令** → 停止并报告。
    2. `rg -n "\./mall-shopping-agent|python -c|uvicorn|mall_shopping_agent" document/docker/local-startup.md`（**`rg` 无匹配时退出码 1 属预期**）
       - 预期：**无匹配**。
       - 停止条件：仍有可复制 Python 命令 → 停止并清理后再继续。
  - 备注：**允许** `Dockerfile.agent` 出现在 Task 3.1 的状态说明中（这是必要的状态陈述）；**禁止**其作为可复制构建路径出现。**不得**要求该文件对 `Dockerfile.agent` 完全零匹配。
  - **执行证据（2026-09-28 主 Agent 独立实测）**：① `rg -n 'Dockerfile\.agent'` **唯一命中第 714 行**（退役状态说明），退出码 0；② scan2（`\./mall-shopping-agent|python -c|uvicorn|mall_shopping_agent`）**无匹配**，退出码 1（预期）；③ `git diff --check` 退出码 0；④ 真实 diff 人工审查确认 `docker-compose.yml` 仅注释行变化、启动文档已按锚点移除旧回滚块、无旧 Python 命令。**本步仅核验文档；未运行 Maven 或 Compose。**

---

## Task 4：更新 `document/agent/product-shopping-agent.md` 顶部说明

**工作目录：** `F:\code\mall\.worktrees\retire-python-agent`｜**文件范围：** `document/agent/product-shopping-agent.md`（仅顶部状态备注）

> **执行证据（2026-09-28）**：仅改顶部状态备注第 3 行为「状态备注（2026-09-28）：Python 实现已退役并从仓库删除；本文档仅作为历史设计记录保留，不代表当前运行事实」；`git diff` 该文件**仅此 1 行**变化，正文历史设计段落与 API 契约**未动**。

- [x] **Step 4.1 — 更新顶部状态备注首句**
  - 动作：将 `3` 行锚点 `"**状态备注（2026-09-25）：本文档是 Python v1 的设计记录，现作为参考保留。**"` 改为“Python 实现已**退役并从仓库删除**；本文档仅作为历史设计记录保留，不代表当前运行事实”。
  - 预期：顶部明确 Python 已删除，正文保留。
- [x] **Step 4.2 — 修正顶部“保留”相关措辞（如有）**
  - 动作：检查 `3-15` 行内是否仍有“Python 源码保留”类措辞；如有一并改为“已退役删除”。
  - 预期：顶部说明与设计稿 §4.2 一致。
- [x] **Step 4.3 — 复核**
  - 动作：`git diff -- document/agent/product-shopping-agent.md`
  - 预期：改动仅在顶部备注区；正文历史设计段落、API 契约等**未动**。
  - 停止条件：正文被改动 → 停止并报告。

---

## Task 5：更新 `docs/context/project-handoff.md`

**工作目录：** `F:\code\mall\.worktrees\retire-python-agent`｜**文件范围：** `docs/context/project-handoff.md`

> **执行证据（2026-09-28）**：① 当前基线改为「退役任务起始基线 `cd38a73`（`main` = `origin/main`），后续以 `git` 实测为准」，`ed5140a` 降级为历史基线；② 文末「2026-09-28 主栈…验收」节的基线引用同步为「该轮执行时 `ed5140a`（历史）／起始基线 `cd38a73`」；③ 完成模块 #7 追加〔2026-09-28 退役更新（当前有效）〕，明确 Java-only 且旧 Python 目录与 `Dockerfile.agent` 已由用户手动移除；④ `agent-live-acceptance` 相关条目（`15`、`106`、`110`、`121` 行）改为历史说明，注明已不在 `git worktree list`、不再是清理待办、branch ref 仍保留。`git diff` 该文件为 10 insertions / 9 deletions，**均限于商品导购智能体相关条目**；其它模块、正式域名暂缓事项、`stash` 保留事项与协作规则**未改**。未清理任何 worktree、未删除分支。

- [x] **Step 5.1 — 更正过时基线**
  - 动作：将 `11` 行锚点 `"**2026-09-28 实测基线（当前有效）**：主仓库 `main` 与 `origin/main` **一致**，均为 `ed5140a...`"` 更正为：本任务**起始基线** `main` = `origin/main` = `cd38a73`（`合并商品导购验收计划与交接摘要`），并明确“**后续状态以 git 实测为准，不预设未来合并 SHA**”；把 `ed5140a` 降级为历史记录。
  - 预期：不再把 `ed5140a` 当作当前基线；不写入任何未来合并提交哈希。
- [x] **Step 5.2 — 同步更正文末 `89` 行基线引用**
  - 动作：将 `89` 行锚点 `"- **基线**：主仓库 `main` = `origin/main` = `ed5140a`，`main` 工作区干净。"` 改为指向起始基线 `cd38a73` 并注明以实测为准。
  - 预期：全文基线口径一致。
- [x] **Step 5.3 — 更新 Python 商品导购智能体状态为 Java-only**
  - 动作：在 `29` 行锚点 `"7. Python 商品导购智能体：..."` 处补充/更新为：运行实现为 Java 17 `mall-master/mall-agent`，**旧 Python 实现与 `Dockerfile.agent` 已退役删除**（`mall-shopping-agent/` 目录已移除）；service key `mall-shopping-agent` 仍为 Java 服务。
  - 预期：完成模块 #7 明确 Java-only。
- [x] **Step 5.4 — 修正旧 `agent-live-acceptance` worktree 的历史状态**
  - 动作（先只读核对，PowerShell 7）：`git worktree list`；`git branch --list "codex/agent-live-acceptance"`
    - 实测（本计划编写时）：`git worktree list` **仅**含 `F:/code/mall`（main）与 `F:/code/mall/.worktrees/retire-python-agent`；旧 `C:/Users/qiufengm/.codex/worktrees/agent-live-acceptance/mall` **已不在列表**（该旧 dirty worktree 已在此前授权清理）；分支 ref `codex/agent-live-acceptance` **仍存在**。
  - 动作（文档修改）：把 `docs/context/project-handoff.md` 中 `15`、`105`、`110`、`120` 行涉及该 worktree 的表述，从“**当前存在 / 待用户清理 / 禁止清理**”改为**历史说明**：
    - 该旧 worktree 曾含 **6 个未提交 Python 源/测试改动**（且无对应报告）；当时的相关审查与定向 pytest 结果**仅为历史**，**不得表述成当前测试结果**；
    - 当前该旧 worktree **已不在 `git worktree list`**，**不再作为清理待办**；其实际文件改动已随旧 worktree 被清理，**不在当前 Git 工作树**；
    - 分支 ref `codex/agent-live-acceptance` 目前**仍存在**，仅**如实记为保留的分支历史**。
  - 预期：handoff 中不再出现“当前存在 / 待用户清理 / 禁止清理该 worktree”的当前性表述。
  - 停止条件：**不得**尝试清理 worktree，**不得**删除或移动任何 branch ref；若只读核对结果与上述不同 → **停下并报告**。
- [x] **Step 5.5 — 复核**
  - 动作（PowerShell 7）：`git diff -- docs/context/project-handoff.md`；`rg -n "ed5140a|cd38a73" docs/context/project-handoff.md`（**`rg` 无匹配时退出码 1 属预期**）；`rg -n "agent-live-acceptance|待用户清理|禁止清理" docs/context/project-handoff.md`
  - 预期：`ed5140a` 仅出现在历史说明中；`cd38a73` 明确标注为起始基线；无未来合并 SHA；`agent-live-acceptance` 相关表述均为**历史说明**，无“当前存在 / 待用户清理 / 禁止清理”的当前性措辞。
  - 停止条件：其它模块基线/工作流说明被误改，或仍有 worktree 清理待办措辞 → 停止并报告。

---

## Task 6：为 Java 设计稿与计划追加退役附记（仅追加）

**工作目录：** `F:\code\mall\.worktrees\retire-python-agent`｜**文件范围：** 两份既有文档，**只追加**

> **执行证据（2026-09-28）**：两份 `2026-09-23-java-*` 文件**末尾各追加** `## 2026-09-28 退役附记`（各 6 行）：说明旧 Python 实现与独立 `document/docker/Dockerfile.agent` 已由用户手动从工作区移除、Java 为唯一运行实现、Compose service key 仍为 `mall-shopping-agent`，并指向 `docs/superpowers/plans/2026-09-28-retire-python-shopping-agent.md`。`git diff --stat` 两份均为**纯新增（各 6 insertions、0 deletions）**，历史正文未重写或删除。

- [x] **Step 6.1 — 追加退役附记到 Java 设计稿**
  - 动作：在 `docs/superpowers/specs/2026-09-23-java-product-shopping-agent-design.md` **文件末尾**追加一个 `## 2026-09-28 退役附记` 小节（约 3–6 行）：说明旧 Python 实现与 `document/docker/Dockerfile.agent` 已退役删除，Java 为唯一运行时实现，详见 `docs/superpowers/specs/2026-09-28-retire-python-shopping-agent-design.md`。
  - 预期：`git diff` 该文件**只有新增行**。
  - 停止条件：出现删除/修改行 → 停止。
- [x] **Step 6.2 — 追加退役附记到 Java 计划**
  - 动作：在 `docs/superpowers/plans/2026-09-23-java-product-shopping-agent.md` **文件末尾**追加同类 `## 2026-09-28 退役附记` 小节（约 3–6 行），指向本退役计划；原计划正文（含“Python 源码保留”等历史表述）**不改写**。
  - 预期：`git diff` 该文件**只有新增行**。
- [x] **Step 6.3 — 复核两份文档**
  - 动作：`git diff --stat -- docs/superpowers/specs/2026-09-23-java-product-shopping-agent-design.md docs/superpowers/plans/2026-09-23-java-product-shopping-agent.md` 与逐文件 `git diff`
  - 预期：两份均为纯追加（no deletions）。
  - 停止条件：任何非追加改动 → 停止并报告。

---

## Task 7：验收（实施阶段运行）

**工作目录：** `F:\code\mall\.worktrees\retire-python-agent`

> **前置门禁：** 仅在 Task 1（用户手动删除）核验通过、且 Task 2–6 文档修改完成后运行。

- [x] **Step 7.1 — 全量 Java Maven 测试**
  - 动作：`mvn -f mall-master/pom.xml test`（**不得**加 `-Dmaven.test.failure.ignore`）
  - 预期：`BUILD SUCCESS`；tests failures=0、errors=0。
  - 停止条件：Maven 不可用（无网络/无依赖缓存）→ **记录阻塞**，不得引用历史测试报告冒充本次验收；出现失败 → 停止并报告。
  - **执行证据（2026-09-28，新鲜运行）**：`mvn -f mall-master/pom.xml test` 完整退出 **exit 0**、**BUILD SUCCESS**、**9 个 reactor modules**；**Tests run 997, Failures 0, Errors 0, Skipped 8**。8 个 skipped 均为需要专用 Redis 测试地址（`MALL_AGENT_TEST_REDIS_URL`）的集成项，非失败。
- [x] **Step 7.2 — Compose 配置校验（默认 + 全部 profile）**
  - 动作：
    - `docker compose --env-file .env.example config --quiet`
    - `docker compose --env-file .env.example --profile app --profile edge --profile observability config --quiet`
  - 预期：两条均 **exit 0**。
  - 停止条件：Docker 不可用 → 记录阻塞；配置报错 → 停止并报告。
  - **执行证据（2026-09-28，新鲜运行）**：默认命令 **exit 0**；全 profile（`app` + `edge` + `observability`）同命令 **exit 0**。仅 `config` 校验，**未执行 `docker compose up/down/build`**。
- [x] **Step 7.3 — 活动引用扫描与保留清单分类（确认无 Python 构建/运行依赖）**
  - **说明**：**不对整个仓库 `.` 搜同一 pattern**（会误报退役 spec/plan 与历史文档里的路径示例，且计划自身即含这些 pattern）。改为下列三类可执行检查；`rg` **无匹配时退出码 1 属预期**，不等于失败。
  - **检查 1 — 限定活动运行/构建表面扫描（PowerShell 7，已排除说明文档 / Java 源码 / target / node_modules）**：
    `rg -n -g '!**/*.md' -g '!**/*.java' -g '!**/target/**' -g '!**/node_modules/**' 'Dockerfile\.agent|mall_shopping_agent|python -c|uvicorn|\./mall-shopping-agent' docker-compose.yml document/docker .env.example mall-master mall-app-web-master mall-admin-web-master`
    - 预期：匹配**逐条人工分类**，**允许且仅允许**：① `docker-compose.yml` 中的退役注释；② `document/docker/local-startup.md` 第 4.7 节的**单条退役状态说明**。其余活动配置 / 构建 / 脚本（含 `Dockerfile.app`、`check-agent-env.*`、Nginx conf、`.env.example`）**不应**出现 Python runtime/build 路径。
    - 说明：`mall-master` 全树中的 **Java 源码 / JavaDoc Python parity 注释**因 `-g '!**/*.java'` 被排除，**不属于**检查 1 的命中；它们属允许保留区，改由**检查 3** 的保留清单只读抽样审查。
    - 停止条件：活动运行/构建表面出现上述白名单之外的命中 → 停止并报告。
  - **检查 2 — local-startup 两条定向扫描**（沿用 Task 3.4 已修订口径）：
    1. `rg -n "Dockerfile\.agent" document/docker/local-startup.md` → **唯一命中**为退役状态说明。
    2. `rg -n "\./mall-shopping-agent|python -c|uvicorn|mall_shopping_agent" document/docker/local-startup.md` → **零匹配**（退出码 1 属预期）。
  - **检查 3 — 文档 / 历史 / parity 命中只读抽样审查（不作为运行依赖）**：对 docs、历史说明、测试 parity 文本及本实施计划/退役设计稿中的命中，**只读**抽样确认均为历史或说明性内容。**保留清单**（允许存在命中，但**不得**当作运行依赖）：
    - `docs/superpowers/plans/2026-09-22-python-product-shopping-agent.md`（Python 原始历史计划）；
    - `docs/superpowers/specs/2026-09-23-java-product-shopping-agent-design.md` 与 `docs/superpowers/plans/2026-09-23-java-product-shopping-agent.md`（历史正文 + 2026-09-28 退役附记）；
    - `docs/superpowers/specs/2026-09-28-retire-python-shopping-agent-design.md` 与 `docs/superpowers/plans/2026-09-28-retire-python-shopping-agent.md`（本退役 spec/plan 自身的 pattern 示例）；
    - `docs/context/project-handoff.md` 中**明确标注为历史**的段落；
    - `mall-master/mall-agent` 测试 / fixtures / JavaDoc 中的 Python parity 对照文本。
  - 预期：三类检查完成；活动表面命中**仅为上述白名单**——**不要求活动扫描绝对 0 命中**，而是按白名单逐条分类确认。
  - 停止条件：活动运行/构建表面出现白名单外命中，或保留清单中出现被当作运行依赖的内容 → 停止并报告。
  - **执行证据（2026-09-28，新鲜运行）**：
    - **检查 1**（已排除 `**/*.md`、`**/*.java`、`**/target/**`、`**/node_modules/**`）：有匹配，且**仅为** `docker-compose.yml` 中 **两条**明确说明旧 Python 构建路径已退役的注释（白名单 ①）；其余活动配置/构建/脚本**零** Python runtime/build 命中。
    - **检查 2**：`rg -n "Dockerfile\.agent" document/docker/local-startup.md` **唯一命中**为该退役状态说明；旧 Python 可复制命令扫描（`\./mall-shopping-agent|python -c|uvicorn|mall_shopping_agent`）**0 命中**（退出码 1，属预期）。
    - **检查 3**：对 docs/历史说明/测试 parity 文本及本退役 spec/plan 中的命中，按保留清单**只读抽样**确认为历史或说明性内容，**均不作为运行依赖**；Java 源码/JavaDoc 的 parity 文本属允许保留区。
- [x] **Step 7.4 — 差异与产物检查**
  - 动作：`git diff --check`；`git diff --cached --name-only` 与 `git status --short`
  - 预期：`git diff --check` exit 0；变更集**不含** `.env`、`application-dev.yml`、`application-prod.yml`、`node_modules/`、`target/`、`dist/`、密钥、数据库备份或临时迁移文件。
  - 停止条件：命中敏感/产物文件 → 停止并报告。
  - **执行证据（2026-09-28）**：`git diff --check` **exit 0**；`git status` 仅含授权范围内的 **7 条 ` M`**（`docker-compose.yml` 仅注释、`document/docker/local-startup.md`、`document/agent/product-shopping-agent.md`、`docs/context/project-handoff.md`、`docs/superpowers/specs/2026-09-28-retire-python-shopping-agent-design.md`、两份 `2026-09-23-java-*` 仅追加）、1 条 `??`（本退役计划）与 75 条 ` D`（用户手动删除）；未含 `.env`/`target`/`node_modules`/`dist`/密钥等产物。
- [x] **Step 7.5 — 边界复核（Compose contract 不变）**
  - 动作：`git diff -- docker-compose.yml`（确认只有注释行）；确认 `git diff` 中 Java 源码、Nginx、H5、`.env*`、`.gitignore` 均无功能改动。
  - 预期：Compose service 键/值/端口/网络/依赖/healthcheck 完全未变；其余保留区零改动。
  - 停止条件：出现授权范围外的改动 → 停止并报告。
  - **执行证据（2026-09-28）**：`docker-compose.yml` 变更**仅 2 处注释行**（无任何键/值/缩进/服务定义/profile/build args/env/端口/网络/依赖/healthcheck 改动）；保护路径 **Java `mall-master/mall-agent`、根 POM、Nginx、前端、`.env`/`.env.example`、`.gitignore` 均无改动**。
- [x] **Step 7.6 — 汇总验收结果**
  - 动作：把以上命令、退出码、关键输出（脱敏）写入实施报告。
  - 预期：全部通过，或如实记录阻塞项。
  - **执行证据（2026-09-28）**：验收命令、退出码与测试数（997 run / 0 fail / 0 error / 8 skipped）已写入 `.codebuddy/reports/retire-python-agent-report.md` 第 6、7 节；无阻塞项。

---

## Task 8：实施报告与交接（不提交）

**工作目录：** `F:\code\mall\.worktrees\retire-python-agent`

- [x] **Step 8.1 — 生成实施报告**
  - 动作：创建 `.codebuddy/reports/retire-python-agent-report.md`（本地忽略，不入提交），内容含：完成概述、修改/删除文件清单、业务/API/数据库影响、验证命令与结果、Git 状态、残余风险、遗留问题。
  - 预期：报告与设计稿、实际 diff 一致。
  - **执行证据（2026-09-28）**：已创建 `.codebuddy/reports/retire-python-agent-report.md`（本地忽略、不入提交）；内容涵盖目标/概述、用户手动删除 74+1 事实、改动文件、Java-only 影响、文档变化、验证命令/退出码/测试数、8 个 Redis 集成 skip、扫描白名单结论、Git 基线/HEAD/变更状态、独立审查待结论、操作边界与遗留建议。
- [x] **Step 8.2 — 聊天简短回执**
  - 动作：聊天只返回简短回执：报告文件绝对路径、实现状态、是否存在阻塞。
  - 预期：不粘贴完整报告。
  - **执行证据（2026-09-28）**：本次调用后紧接输出的最终中文简短回执即完成本步；回执含报告绝对路径、实现/验收完成状态与阻塞情况。

---

## 实施后必须由主 Agent 审查的事项

> 以下均由主 Agent 完成，工作树不得自行执行 Git 写操作。**仅在以下全部通过后**才创建中文提交。
>
> **第二轮独立只读复核已通过**（首轮 1 P1 + 2 P2 均已解决；非阻断 P3 文案建议已通过移除重复待办项解决）。**以下独立复核/提交/本地合并/`push` 后置流程仍为待办，须由主 Agent 执行；`push` 需用户明确确认。**

1. **删除范围核验**：确认工作区删除项恰为 `mall-shopping-agent/`（74）与 `document/docker/Dockerfile.agent`（1）——这些删除由**用户手动执行**产生，助手未代删。主 Agent 审查时用 `git add -A` 暂存后读取 `git diff --cached --name-status` 复核，确认无其它意外改动。
2. **Compose contract 审查**：确认 `docker-compose.yml` 变更**仅注释**；`mall-shopping-agent` service 仍为 Java 运行构建；profiles/端口/网络/依赖/healthcheck 未变。
3. **文档一致性审查**：`local-startup.md` 无残留可复制 Python 命令；`product-shopping-agent.md` 顶部已标注退役、正文保留；`project-handoff.md` 基线表述准确（起始基线 `cd38a73`，非未来 HEAD），旧 `agent-live-acceptance` worktree 条目已改为**历史说明**（不再作为清理待办，历史测试结果未被表述为当前结果），且**未操作任何 worktree 或分支 ref**。
4. **保留区审查**：`mall-master/mall-agent/**`、根 POM、Nginx、H5、`.env` / `.env.example`、`.gitignore` Python cache 规则、Java parity 文本、Python 原始历史计划均**未被改动或删除**。
5. **验收复核**：Task 7 的 Maven 全量测试、Compose 默认 + 全 profile `config --quiet`、引用扫描、`git diff --check`、敏感/产物扫描结果真实且未被历史报告替代；不可用项是否已如实记为阻塞。
6. **只读子 Agent 审查**（按 `AGENTS.md` 第 2、5 节）：复核业务链路影响、误删风险、文档准确性；子 Agent 仅允许 `gpt-6-luna`（`max`）或 `gpt-6-sol`（`low`）。
7. **提交**：审查通过后创建中文提交（一个清晰逻辑单元）。提交前确认不包含 `.env`、密钥、`target/`、`node_modules/`、`dist/`。
8. **本地合并复验**：确认功能 worktree 与 `main` 工作区均无未提交改动后再本地合并；合并后重新运行 Task 7 关键验收并检查合并后 diff 与工作区状态。
9. **推送**：**只有用户明确确认**后才执行 `git push`；推送前确认具体远程仓库与分支。

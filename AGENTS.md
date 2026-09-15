# 项目协作规则

## 多 Agent 协作

用户提出开发、排错或分析任务后，主 Agent 自动按复杂度分工：

- 定位代码、接口、数据表或业务链路：优先调用 `pr_explorer`，只读分析。
- 所有实际代码实现：由主 Agent 完成设计与任务拆分后，**必须由 CodeBuddy 实现**；`worker` 已停用，不承担实现任务。主 Agent 通过 WorkBuddy MCP 调度 CodeBuddy，但 WorkBuddy 仅是调度层，不是编码 Agent。
- 跨前后端、订单、库存、支付、权限等核心问题：先调用 `pr_explorer` 分析；主 Agent 统一制定方案。涉及核心规则、数据库结构或大范围重构时，必须先获得用户确认。
- CodeBuddy 任务完成后：主 Agent 先核对实际 Git diff，再调用 `reviewer` 审查；主 Agent 汇总风险并运行相关验证。
- 简单任务不额外启动子 Agent；用户也可以明确要求不用某个 Agent，或指定由哪个 Agent 处理。
- CodeBuddy 因模型容量不足、权限审批、超时或等待状态无法继续时，主 Agent 必须立即停止该任务，保留已写入的工作区改动并向用户说明阻塞原因；除非用户明确授权，否则不得自行接手编写代码，也不得让任务无限等待。

### 标准实现闭环

对需要改代码的任务，主 Agent 必须按以下顺序推进：

1. 解释业务流程、影响文件、方案与验收标准；涉及核心规则、数据库结构或大范围重构时先获得用户确认；
2. 记录目标仓库的 `git status`，确认已有改动与本次任务边界；
3. 通过 WorkBuddy MCP 调用 CodeBuddy 实现，提示词必须包含已确认的设计、文件范围、禁止扩大范围与验证要求；
4. 读取通过 WorkBuddy MCP 返回的 CodeBuddy 修改文件、位置、`session` 和 `[workbuddy meta]`，但一律视为草稿；
5. 主 Agent 用 `git diff` 核对实际改动，再交由 `reviewer` 只读审查；
6. 若审查存在 P0/P1 或未满足验收标准，优先携带原 `resumeSessionId` 让同一 WorkBuddy 会话返工；
7. 审查通过后，运行与改动相关的测试、构建或接口验证；验证通过后，复核 `git status` 与暂存区，确认只包含本次任务文件；
8. 使用中文 Git 提交信息创建本地提交，并向用户报告提交哈希、文件清单与验证结果；
9. 只有在用户明确确认该提交无误后，才执行 `git push` 上传 GitHub 仓库；推送后再次报告远端分支与提交哈希。

WorkBuddy 的返回不能替代 Git diff、代码审查或实际验证。出现任务外文件、敏感文件或验证失败时，停止提交并先向用户说明。

## 通过 WorkBuddy MCP 委派 CodeBuddy（`run_workbuddy_task`）

WorkBuddy 是通过 MCP 接入的本地调度服务，用于启动外部 CodeBuddy CLI；**CodeBuddy 承担实际代码实现职责**，WorkBuddy 提供权限、会话、模型回退、串行锁、用量与 Git 安全网能力。其产出**一律视为未经验证的草稿**。

- 适合委派：已完成设计的代码实现、跨子项目（`mall-master` / `mall-admin-web-master` / `mall-app-web-master`）的大范围分析或重构、需要长时间探索代码库的调研、批量样板代码生成。
- 调用时必须显式传 `cwd` 为任务所在的子项目绝对路径（如 `F:\code\mall\mall-app-web-master`）；不传则用根目录 `F:\code\mall`。**传错目录会改错项目**。
- 主 Agent 始终负责：拆解任务、审阅 WorkBuddy 的产出、决定是否采纳。不得未经审查直接采纳。
- 涉及核心规则、数据库结构或大范围重构时，即使由 WorkBuddy 完成，仍须先获得用户确认。
- 单次调用超时 900 秒；超时未返回则停止等待、自行接手，不得无限等待。
- 同样受下方「文件操作限制」约束。

### 调用约束（Server 已增强，须配合）

- **必须串行调用**：**不得同时发起多个 `run_workbuddy_task`**。Server 侧有跨进程文件锁兜底（同一时刻只跑一个任务），但并发请求会排队等待，反而更慢。写文件的任务尤其禁止并发——多个 codebuddy 同时改同一项目会造成写冲突。
- **只读任务传 `readOnly: true`**：分析、检索、调研、定位问题等**不需要改文件**的任务一律加此参数。Server 会禁用写类工具并注入只读约束；确实要改文件的任务才不加。
- **成本可见**：返回文本末尾附 `[workbuddy meta] model=... credit=... in=... out=...`，根据 credit 判断消耗是否合理；单次消耗异常偏高时，说明任务应拆小。
- **git 安全网**：委派前先确认目标仓库 `git status` 已知（最好干净）。返回内容开头会列出本次任务实际改动的文件，据此 `git diff` 复核，确认无误再继续。
- **显式模型**：每次调用都必须传 `cwd`、`model` 和 `fallbackModel`，不依赖 Auto 或 Server 默认值。
- **会话返工**：审查或验证失败时，优先传 `resumeSessionId` 续接原会话，发送包含审查问题、文件位置和验收要求的返工任务；不重新让它无目标地阅读整个仓库。
- **写入边界**：WorkBuddy 不执行 `git commit`、`git push`、数据库结构修改、核心订单/库存/支付/权限规则修改或删除操作；这些事项先由主 Agent 说明影响并获得用户确认。

### 模型选择（`model` / `fallbackModel` 参数）

**每次调用必须显式传 `model`**，不要依赖 Auto（Auto 可能选中已禁用的型号）。

**主用模型（三选一，覆盖绝大多数情况）**：

| 型号 | 何时用 | 成本 |
|---|---|---|
| `deepseek-v4.1-flash` | **默认首选**：检索、列目录、样板代码、单模块改动、常规分析、长上下文（1M） | x0.06（最低） |
| `glm-5.3-flash` | `deepseek-v4.1-flash` 能力不足时上探；常规编码任务 | flash 档（官方未公布） |
| `hy4-preview-f` | 高难度：跨子项目重构、复杂长程分析（770B MoE / 1M 上下文） | 已收费 |

- 一律从 `deepseek-v4.1-flash` 起手（最便宜且 1M 上下文）；能力不足再上 `glm-5.3-flash`；仍不够才用 `hy4-preview-f`。

**备选模型**：仅当主用三个因能力或特性不满足时才启用，且需在回复中说明切换原因：

- `glm-5.3` —— 需要比 `glm-5.3-flash` 更强的推理能力
- `glm-5.2` —— 需要 1M 上下文的长程任务（x0.79）
- `glm-5v-turbo` —— 需要理解截图 / 图片（多模态，x0.95，仅限图片场景）

**禁用（一律不用）**：`kimi-*` 全系、`minimax-*` 全系（含 `minimax-m3`、`minimax-m2.7`）、`hy3`、`hy3-x`、`deepseek-v4-pro`、`glm-5.1`。

其他规则：

- 兜底：`fallbackModel = "glm-5.3-flash"`（与主模型不同，避免主备同时过载），遇到过载 / at capacity / 限流时自动切换。
- `glm-5.3` / `glm-5.3-flash` 官方文档尚未收录、定价未知，先用小任务验证扣费再放量。

## 文件操作限制

禁止批量删除文件或目录。

不要使用：

- `del /s`
- `rd /s`
- `rmdir /s`
- `Remove-Item -Recurse`
- `rm -rf`

需要删除文件时，只能一次删除一个明确路径的文件，例如：

```powershell
Remove-Item "C:\path\to\file.txt"
```

如果需要批量删除文件，应停止操作，并请用户手动删除。

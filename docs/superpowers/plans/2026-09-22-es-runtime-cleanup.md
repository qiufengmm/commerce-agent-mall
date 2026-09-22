# Elasticsearch 运行验证与文档收尾实施计划

> **For agentic workers:** 本计划在当前会话内直接执行；不提交、不推送、不合并，除非用户另行授权。

**Goal:** 清理可安全移除的已合并 ES 工作树，完成 Docker/Elasticsearch 只读运行验证，并修正 README 对六条 ES 写路径令牌保护的描述。

**Architecture:** 先以 Git 状态确认工作树是否可删除；仅移除干净且已合并的工作树，保留任何含未提交改动的工作树。运行验证只读取容器状态、ES 健康、索引映射、文档数量和只读搜索接口，不调用 `importAll`、`create`、`delete`、`sync` 等写接口。文档只修改 `mall-master/README.md`，不改业务代码、配置或数据库。

**Tech Stack:** Git worktree、Docker Compose、Elasticsearch REST API、PowerShell、Markdown。

**Spec:** `docs/superpowers/plans/2026-09-21-es-legacy-hardening.md` 与 `F:\code\mall\.worktrees\es-legacy-hardening-latest\.codebuddy\reports\es-legacy-hardening-report.md`

## Global Constraints

- 不执行任何 MySQL `INSERT`、`UPDATE`、`DELETE`、DDL、权限修改或数据迁移。
- 不调用真实 ES 写接口：`/esProduct/importAll`、`/esProduct/create/**`、`/esProduct/delete/**`、`/esProduct/sync/**`。
- 不删除含未提交改动的工作树；删除前必须确认目标路径、分支和工作区状态。
- 不输出密码、Token、密钥或 `.env` 内容。
- 只修改 `mall-master/README.md` 的 ES 文案；不修改 Java、Compose、Nginx 或数据库文件。

---

### Task 1: 清理已合并的 ES 工作树

**Files/paths:**
- Remove only: `F:\code\mall\.worktrees\es-legacy-hardening-latest` if its worktree is clean and its commit is reachable from `main`.
- Preserve: `F:\code\mall\.worktrees\es-legacy-hardening` while it contains uncommitted changes.

- [x] 检查两个工作树的分支、状态和目标提交是否已合并；`es-legacy-hardening-latest` 干净且已合并，`es-legacy-hardening` 含未提交改动。
- [x] 将 `es-legacy-hardening` 的未提交改动（含未跟踪文件）保存到 `stash@{0}`，未丢弃内容。
- [x] 移除两个明确的旧 worktree；原分支 `feature/es-legacy-hardening` 保留，便于从 stash 恢复。
- [x] 重新执行 `git worktree list` 与 `git status --short --branch`；当前仅保留 `main` worktree。

### Task 2: 完成 Docker/Elasticsearch 只读运行验证

**Files:**
- Read: `docker-compose.yml`
- Read: `document/docker/local-startup.md`
- Read: `mall-master/document/es-search/elasticsearch-runbook.md`

- [x] 检查 Docker daemon、Compose 配置和当前容器状态。
- [x] 只读检查 ES `_cluster/health`、`pms` `_count`、`pms` `_mapping` 和匿名搜索接口；未调用 ES 写接口。
- [x] 服务已经运行，因此未执行启动、重建或 SQL 操作。
- [x] 记录结果：ES `green`、`pms` 文档数 20、mall-search/mall-portal/Nginx 搜索链路均 HTTP 200。

### Task 3: 修正 README 令牌说明

**Files:**
- Modify: `mall-master/README.md` 商品搜索说明段落。

- [x] 将“同步接口”改为“六条 ES 写路径”。
- [x] 明确六条路径均需要 `X-Internal-Token`。
- [x] 保留“未配置服务端令牌返回 503、缺失或错误返回 401”的准确描述。
- [x] 明确只读搜索接口不需要该令牌，未写入任何真实令牌值。
- [x] 运行 `git diff --check` 并扫描 README 旧的不完整措辞。

### Task 4: 收尾核验

- [x] 检查实际 diff、敏感信息、工作树列表和主分支状态。
- [x] 汇报已完成项、运行验证证据、无法验证项和仍保留的脏工作树。

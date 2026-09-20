# Docker Runtime Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将已在本机真实验证的 MinIO Quay 镜像、Java 容器构建、Nginx 代理和健康检查修复整理到一个可复现的 Git 提交中，并在合并后的 `main` 上重新验证。

**Architecture:** 仅整理 Docker Compose、Dockerfile、Nginx 配置和启动文档，不修改业务代码、数据库结构或数据。固定的公共配置进入版本库；`.env`、数据库快照、账号密码和运行时数据继续留在本机。所有运行时验证以 Compose 渲染结果、健康检查、HTTP 入口和 ES/MinIO 只读结果为依据。

**Tech Stack:** Docker Compose v5、Docker Desktop/WSL2、Maven multi-stage Docker build、Nginx、Elasticsearch、MinIO、PowerShell。

**Spec:** 当前仓库 `AGENTS.md` 第 2、4、5、7 节及 `document/docker/local-startup.md` 的本地 Compose 启动约束。

## Global Constraints

- 不提交 `.env`、数据库快照、密码、Token、密钥、`target/`、`node_modules/` 或运行时数据。
- 不执行任何 MySQL DML、DDL、权限修改或数据迁移；本任务只做配置整理与只读验证。
- 不删除 Docker volume；不使用 `docker system prune` 或其他宽泛清理命令。
- 工作树阶段不 push、merge；主 Agent 只在审查和验证通过后提交并本地合并。
- 当前运行中的本地服务和用户已有 `main` 未提交改动必须保持可恢复，不能使用 reset 或 checkout 覆盖。

---

### Task 1: Reconcile Docker configuration changes

**Files:**
- Modify: `docker-compose.yml`
- Modify: `document/docker/Dockerfile.app`
- Modify: `document/docker/nginx/conf.d/default.conf`
- Modify: `.env.example`
- Modify: `mall-master/document/docker/docker-compose-env.yml`

**Interfaces:**
- Compose consumes the fixed MinIO Quay image tags and local port overrides from `.env`.
- The application image build skips the obsolete Fabric8 Docker plugin with `-Ddocker.skip=true`.
- Nginx health checks IPv4 loopback and assigns the upstream variable before `rewrite ... break`.

- [x] **Step 1: Reconcile the existing commit and uncommitted changes**

Keep the Quay changes from `c629448`. Apply the verified local fixes for Nginx health, Nginx upstream ordering, and the Maven build flag. Do not copy `.env`, database snapshots, passwords, or local port choices into tracked files.

- [x] **Step 2: Run static configuration checks**

Run from the isolated worktree:

```powershell
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example --profile app --profile edge --profile observability config --quiet
git diff --check
```

Expected: all commands exit 0.

- [x] **Step 3: Inspect the final diff for secrets and accidental data**

Run:

```powershell
git status --short
git diff --name-only
git diff -- .env .codebuddy document/sql
```

Expected: `.env` and local snapshots are absent from the diff; only intended configuration and documentation files remain.

### Task 2: Update and validate the startup documentation

**Files:**
- Modify: `document/docker/local-startup.md`

**Interfaces:**
- The documented commands use `.env`, `--profile app`, `--profile edge`, and optional `--profile observability`.
- The document describes `minio-init` as a one-shot service whose successful state is `Exited (0)`.
- The document never includes real credentials or claims that static checks are runtime verification.

- [x] **Step 1: Merge the verified startup checklist**

Keep the ten-step checklist, but correct the wording to say “10 个常驻服务 + 1 个一次性 minio-init 容器”, avoid hard-coding local-only object counts, and separate local `.env` results from committed defaults.

- [x] **Step 2: Validate documentation commands against the actual Compose file**

Check every service name, profile, published port, image name, and endpoint in the document against:

```powershell
docker compose --env-file .env.example --profile app --profile edge --profile observability config
```

Expected: no command references a service or port that is absent from the rendered configuration.

- [x] **Step 3: Run whitespace and sensitive-content review**

Run:

```powershell
git diff --check
rg -n "(password|token|secret|access.?key)\s*[:=]\s*[^<\n]+" document/docker .env.example
```

Expected: only placeholders or variable names, no real credential values.

### Task 3: Runtime verification and integration

**Files:**
- Modify: `docs/superpowers/plans/2026-09-20-docker-runtime-fixes.md`

**Interfaces:**
- Runtime verification uses the user’s existing local `.env` and data volumes without altering MySQL data.
- The one-shot MinIO initializer may be rerun idempotently; it must exit 0 and must not be treated as a long-running healthy service.

- [x] **Step 1: Verify current containers and HTTP endpoints**

Run:

```powershell
docker compose --env-file .env --profile app --profile edge ps -a
curl.exe -fsS http://localhost:8088/
curl.exe -fsS http://localhost:8088/admin-api/actuator/health
curl.exe -fsS http://localhost:8088/portal-api/actuator/health
curl.exe -fsS http://localhost:8088/es-api/actuator/health
curl.exe -fsS http://localhost:9000/minio/health/live
```

Expected: ten long-running services are healthy, `minio-init` is `Exited (0)`, and all HTTP checks return success.

- [x] **Step 2: Verify ES and MinIO without writes**

Run read-only checks for ES cluster health, `pms` count, bucket policy, and object count. Do not run `importAll`, SQL, upload, delete, or migration commands during this integration pass.

- [x] **Step 3: Commit and merge only after review**

Create one Chinese commit containing the reviewed configuration/documentation changes, verify both the feature worktree and `main` are clean, fast-forward merge locally, and repeat the static and runtime checks on merged `main`.

# Mall 项目新对话交接摘要

> 更新时间：2026-09-21
>
> 本文件只用于新对话恢复项目上下文，不替代 `AGENTS.md`、正式计划、代码审查和实际验证。

## 当前基线

- 项目根目录：`F:\code\mall`
- 当前分支：`main`
- `main` 已包含 Docker 本地运行收尾提交 `c629448`、`ea338b7`、`32c06e6`，核心接口与端到端测试提交 `085197b`，以及移动端 MinIO 局域网图片适配提交 `7b9a5e2`；文档更新后的最新提交以 `git log --oneline -1` 为准。
- `origin/main` 尚未包含上述本地提交，当前未执行 push。
- `feature/core-flow-tests` 已合并到 `main`，对应 worktree 当前干净但尚未删除；删除该明确路径需单独确认。
- `.env`、本地数据库快照、Docker 数据卷和运行时凭据均不纳入 Git。
- 主工作区应保持干净；合并前主工作区原有改动保留在一个可恢复 stash 中，确认不再需要后再单独处理。

## 已完成模块

1. MinIO 图片迁移与本地上传链路：数据库图片地址、MinIO bucket、公开地址和匿名只读策略已完成；历史图片是否完整取决于实际对象是否存在。
2. 商品评价：真实评价、查看全部、我的评价、后台审核/回复、登录回跳、分页并发和刷新动画已完成。
3. Elasticsearch：搜索闭环、1-based 分页、深分页保护、商品同步、内部 Token、批量上限和运行文档已完成。
4. 订单/支付/库存一致性：状态条件更新、支付归属/金额校验、库存保护、优惠券绑定和历史数据订正已完成。
5. Docker 本地全栈环境：MySQL、Redis、RabbitMQ、MongoDB、Elasticsearch、MinIO、三个 Java 服务和 Nginx 已编排；MinIO 使用固定 Quay 镜像，应用构建跳过旧 Fabric8 Docker 插件，Nginx 代理与健康检查已修复。
6. 移动端 MinIO 地址适配：统一图片 URL 转换、富文本图片转换、H5/微信开发者工具/真机环境示例和局域网联调文档已合并；代码测试与构建通过，真机运行时验证待具备设备和可访问服务后执行。

## Docker 验证基线

- 10 个常驻服务健康，`minio-init` 成功退出 `Exited (0)`。
- Nginx、三个 API 入口、MinIO 健康检查和 ES 检查均已返回成功；ES 集群为 green。
- 三个 Java 应用镜像已成功构建。
- Compose 默认配置和全 profile 配置校验均通过。
- 2026-09-20 重新启动全 profile 后完成只读冒烟：Admin 实际宿主机端口为 `.env` 中的 `ADMIN_PORT=18080`，直连与 Nginx 代理均返回 200/UP；门户和 ES 搜索均返回 `code=200`、`pageNum=1`、5 条结果；ES `pms` 实时 `_count` 为 20；MinIO 健康检查和已知对象读取均返回 200。
- 本次冒烟未执行 SQL、注册、下单、支付、同步/导入、上传或删除；历史对象是否完整仍需以实际对象清单为准。
- 启动步骤和 SQL 安全边界见 `document/docker/local-startup.md`；历史 `mall.sql` 不作为已有数据库的直接覆盖脚本。

## 当前剩余任务

1. 在具备设备和可访问 Docker 服务后，完成 H5、微信开发者工具和局域网真机 MinIO 图片联调，并验证核心页面图片 HTTP 200、MinIO 公开读、CORS 与微信本地安全域名配置。
2. 复评 Elasticsearch 遗留项：上下架事务边界、旧索引清理、同步接口保护和 MySQL 降级搜索。
3. 设计第一版商品导购智能体，先限定为只读搜索、筛选、详情问答、库存和优惠券解释。
4. 用户确认后清理已合并的 `core-flow-tests` worktree；保留功能分支历史。
5. 测试和审查完成后，由用户确认是否 push 本地 `main`；未确认前不得 push。

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

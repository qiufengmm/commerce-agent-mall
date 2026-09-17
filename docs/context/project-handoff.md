# Mall 项目新对话交接摘要

> 更新时间：2026-09-17
>
> 本文件只用于新对话恢复项目上下文，不替代 `AGENTS.md`、正式计划、代码审查和实际验证。

## 当前基线

- 项目根目录：`F:\code\mall`
- 当前分支：`main`
- 当前提交：`db38c5f 合并会员评价分页与登录回跳修复`
- `origin/main`：已同步到 `db38c5f`
- 当前工作区：干净
- 不要在旧功能分支继续开发；新任务从最新 `main` 创建新 worktree。

## 已完成模块

1. MinIO 图片迁移：图片已上传到本地 `mall` 桶，MySQL 图片域名已替换；工具位于 `F:\code\mall\imgmigrate`。
2. 商品评价：真实评价、查看全部、我的评价、后台审核/回复、登录回跳、分页并发和刷新动画已完成。
3. Elasticsearch：搜索闭环、1-based 分页、深分页保护、商品同步、内部 Token、批量上限和运行文档已完成。
4. 订单/支付/库存一致性：状态条件更新、支付归属/金额校验、库存保护、优惠券绑定和历史数据订正已完成。

## 最近重要提交

- `a78c859`：合并订单支付与优惠券状态一致性
- `f6322d0`：合并 Elasticsearch 搜索闭环加固
- `db38c5f`：合并会员评价分页与登录回跳修复

## 关键报告

- `F:\code\mall\.worktrees\comment-followup\.codebuddy\reports\comment-followup-report.md`
- `F:\code\mall\.worktrees\es-search-hardening\.codebuddy\reports\es-search-hardening-report.md`
- `F:\code\mall\.worktrees\order-state-consistency\.codebuddy\reports\order-state-consistency-report.md`

## 当前建议任务

1. 用户确认后逐个清理已合并旧 worktree。
2. 编写 Docker Compose 和完整启动文档。
3. 补关键接口和端到端测试。
4. 做手机/微信开发者工具的局域网 MinIO 联调。
5. 评估 Elasticsearch 遗留项：上下架事务、旧索引清理、同步接口保护、MySQL 降级搜索。
6. 设计第一版商品导购智能体。

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

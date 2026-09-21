# Mall 项目新对话交接摘要

> 更新时间：2026-09-21
>
> 本文件只用于新对话恢复项目上下文，不替代 `AGENTS.md`、正式计划、代码审查和实际验证。

## 当前基线

- 项目根目录：`F:\code\mall`
- 当前分支：`main`
- `main` 当前提交为 `a7cb05e 补充测试基线与ES遗留项治理计划`；功能代码已同步到 `origin/main`，仅本次治理计划提交尚未 push，后续需用户明确确认后再 push。
- `git worktree list` 当前仅保留 `F:\code\mall` 的 `main`，历史功能 worktree 已按确认逐个清理。
- `.env`、本地数据库快照、Docker 数据卷和运行时凭据均不纳入 Git。
- 主工作区应保持干净；运行时 `.env`、数据库快照、Docker 数据卷和凭据不纳入 Git。

## 已完成模块

1. MinIO 图片迁移与本地上传链路：数据库图片地址、MinIO bucket、公开地址和匿名只读策略已完成；历史图片是否完整取决于实际对象是否存在。
2. 商品评价：真实评价、查看全部、我的评价、后台审核/回复、登录回跳、分页并发和刷新动画已完成。
3. Elasticsearch：搜索闭环、1-based 分页、深分页保护、商品同步、内部 Token、批量上限和运行文档已完成。
4. 订单/支付/库存一致性：状态条件更新、支付归属/金额校验、库存保护、优惠券绑定和历史数据订正已完成。
5. Docker 本地全栈环境：MySQL、Redis、RabbitMQ、MongoDB、Elasticsearch、MinIO、三个 Java 服务和 Nginx 已编排；MinIO 使用固定 Quay 镜像，应用构建跳过旧 Fabric8 Docker 插件，Nginx 代理与健康检查已修复。
6. 移动端 MinIO 地址适配：统一图片 URL 转换、富文本图片转换、H5/微信开发者工具/真机环境示例和局域网联调文档已合并；H5 经 Nginx 的 `/static/**` 映射、通知图片文件名兼容和真实 MinIO 对象访问均已验证；夸克手机最终复测正常，微信开发者工具图片验收也已完成。正式 HTTPS 合法域名仍留待后续环境配置。

## Docker 验证基线

- 10 个常驻服务健康，`minio-init` 成功退出 `Exited (0)`。
- Nginx、三个 API 入口、MinIO 健康检查和 ES 检查均已返回成功；ES 集群为 green。
- 三个 Java 应用镜像已成功构建。
- Compose 默认配置和全 profile 配置校验均通过。
- 2026-09-20 重新启动全 profile 后完成只读冒烟：Admin 实际宿主机端口为 `.env` 中的 `ADMIN_PORT=18080`，直连与 Nginx 代理均返回 200/UP；门户和 ES 搜索均返回 `code=200`、`pageNum=1`、5 条结果；ES `pms` 实时 `_count` 为 20；MinIO 健康检查和已知对象读取均返回 200。
- 本次冒烟未执行 SQL、注册、下单、支付、同步/导入、上传或删除；历史对象是否完整仍需以实际对象清单为准。
- 启动步骤和 SQL 安全边界见 `document/docker/local-startup.md`；历史 `mall.sql` 不作为已有数据库的直接覆盖脚本。

## 当前剩余任务

1. 修复测试基线：将旧的 `@SpringBootTest` 外部依赖测试隔离为默认可重复的 H2/单元测试，并保留明确的外部集成入口。
2. 处理 Elasticsearch 遗留项：商品状态事务边界、全量导入陈旧文档清理、所有 ES 写接口 Token 保护和受控 MySQL 降级搜索。
3. 两项 worktree 任务完成后，由主 Agent 读取报告、检查真实 diff、做只读审查并在 `main` 重跑验证。
4. 设计第一版商品导购智能体，先限定为只读搜索、筛选、详情问答、库存和优惠券解释。
5. 用户确认后 push 当前本地治理计划及后续合并提交；未确认前不得 push。

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

# mall-shopping-agent

Mall 商品导购智能体（第一版）。基于 Python 3.11 + FastAPI，通过 OpenAI 兼容
`/v1/chat/completions` 接口进行受控工具调用，并且只通过 HTTP 读取 `mall-portal`
的公开与会员只读接口。

> 本服务不包含任何写操作：不领券、不加购、不下单、不支付、不改库存、不写 ES、
> 不执行 SQL、不直连 MySQL / MongoDB / RabbitMQ / Elasticsearch。

## 1. 强制执行边界

只允许调用以下 `mall-portal` 只读接口：

```text
GET /product/search
GET /product/detail/{id}
GET /sso/info
GET /member/coupon/listHistory?useStatus=0
GET /member/coupon/listByProduct/{productId}
```

只注册四个工具：

| 工具 | 参数（服务端强制） | 说明 |
| --- | --- | --- |
| `searchProducts` | `keyword`、`brandId`、`productCategoryId`、`sort`(0..4)、`pageNum`(1..20) | 服务端固定 `pageSize=5`，最多返回 5 件候选 |
| `getProductDetail` | `productId`（正整数） | 返回 SKU 可售库存（`max(stock-lockStock,0)` 汇总）、属性和公开优惠券 |
| `compareProducts` | `productIds`（2..3 个互不重复的正整数） | 逐件调用详情；不存在或已下架只标记状态 |
| `getMemberCouponsForProduct` | `productId`（正整数） | 需要会员身份；游客返回 `LOGIN_REQUIRED` 且零会员接口调用 |

模型只能决定解释文本和候选商品 ID（通过回答最后一行
`[[MALL_PRODUCTS: 27,26]]`）。卡片中的商品名、价格、图片、库存和详情路径
（`/pages/product/product?id=<id>`）全部由服务端根据工具结果构造。
参数模型使用 `extra="forbid"` + `strict=True`，模型无法传入地址、HTTP 方法、
请求头、超时或 SQL。

## 2. 目录

```text
mall-shopping-agent/
├─ pyproject.toml
├─ src/mall_shopping_agent/
│  ├─ main.py           FastAPI 装配与 lifespan
│  ├─ config.py         MALL_AGENT_* 配置
│  ├─ api/              聊天、会话、健康检查、统一错误契约
│  ├─ agent/            编排循环、提示词、输入输出类型
│  ├─ model/            OpenAI 兼容客户端与确定性 Stub
│  ├─ storefront/       mall-portal 只读 Backend 与领域模型
│  ├─ session/          身份、Redis 会话、内存替身
│  ├─ tools/            四个只读工具与注册表
│  ├─ safety/           围栏、限流、交易拒绝策略
│  └─ presentation/     服务端商品卡片构造
├─ tests/               单元测试、集成测试与测试替身
└─ evals/               离线评测集与运行入口
```

## 3. 接口

| 接口 | 说明 |
| --- | --- |
| `POST /agent/chat` | 发送消息，返回 `code/message/data`；`data` 含 `answer`、`products`、`requiresLogin`、`suggestedQuestions` |
| `GET /agent/session/{sessionId}` | 恢复最近 20 条消息与最近一组商品卡片 |
| `DELETE /agent/session/{sessionId}` | 清空当前身份命名空间的会话，幂等 |
| `GET /health/live` | 进程可响应即 200 |
| `GET /health/ready` | Redis 与 `mall-portal` 探针成功时 200；模型 Key 缺失不阻止就绪 |

会话键：

```text
mall:agent:session:guest:<sessionId>
mall:agent:session:member:<memberId>:<sessionId>
mall:agent:rate:session:<identityHash>:<window>
mall:agent:rate:ip:<ipHash>:<window>
```

### 错误契约

| 场景 | HTTP | 前端行为 |
| --- | ---: | --- |
| 模型未配置或不支持工具调用 | 503 | 显示“智能导购暂时不可用”，保留输入 |
| 模型超时或上游 5xx | 502 | 提供重试，不影响商城其它页面 |
| 门户搜索或详情失败 | 502 / 结构化工具错误 | 说明商品数据暂时无法获取，不生成商品卡片 |
| 登录失效 | 200，`requiresLogin=true` | 提供登录按钮，不清空游客对话 |
| 会话或消息参数非法 | 400 | 展示参数提示 |
| 相同请求并发重复提交 | 409 | 提示不要重复提交 |
| 达到限流 | 429 | 展示稍后重试 |
| 工具循环超过 4 轮 | 422 | 建议缩小问题范围 |
| 未知异常 | 500 | 通用文案 + `traceId`，不返回堆栈或上游正文 |

限流默认值：同一会话 5 分钟 20 次，同一来源 IP 摘要 5 分钟 60 次；
达到限制直接返回 429 且不调用模型。

## 4. 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MALL_AGENT_MODEL_MODE` | `openai` | `openai` 或 `stub`；默认必须是 `openai` |
| `MALL_AGENT_OPENAI_BASE_URL` | `https://api.openai.com/v1` | 必须包含 `/v1`，服务端会规范化 |
| `MALL_AGENT_OPENAI_API_KEY` | 空 | 只存在进程环境与请求头；占位值会被判定为不可用 |
| `MALL_AGENT_OPENAI_MODEL` | `gpt-4o-mini` | 兼容服务必须支持 `tools` / `tool_choice` / `tool_calls` |
| `MALL_AGENT_OPENAI_TIMEOUT_SECONDS` | `30` | 连接、读取与总超时均不超过该值 |
| `MALL_AGENT_PORTAL_BASE_URL` | `http://localhost:8085` | 容器内为 `http://mall-portal:8085` |
| `MALL_AGENT_REDIS_URL` | `redis://localhost:6379/0` | 容器内为 `redis://redis:6379/0` |
| `MALL_AGENT_SESSION_TTL_SECONDS` | `86400` | 会话 24 小时过期，每次有效访问续期 |
| `MALL_AGENT_MAX_TOOL_ROUNDS` | `4` | 单次回答最多工具轮数 |
| `MALL_AGENT_LOG_LEVEL` | `INFO` | 不记录 Authorization、Key、完整提示词或完整模型响应 |
| `MALL_AGENT_PORT` | `8086` | 服务监听端口 |
| `MALL_AGENT_TEST_REDIS_URL` | 未设置 | 设置后才运行 Redis 集成测试 |

模型兼容要求：只支持非流式响应；如果兼容服务不支持工具调用，聊天接口返回
503，不会退化为让模型凭空回答商品事实。

## 5. 本地运行

### 5.1 Stub 模式（无需任何模型 Key）

```powershell
Set-Location F:\code\mall\.worktrees\product-shopping-agent\mall-shopping-agent
C:\Users\<you>\.workbuddy\binaries\python\versions\3.11.9\python.exe -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"

$env:MALL_AGENT_MODEL_MODE = 'stub'
$env:MALL_AGENT_REDIS_URL = 'redis://127.0.0.1:16379/0'
$env:MALL_AGENT_PORTAL_BASE_URL = 'http://127.0.0.1:8085'
.\.venv\Scripts\python.exe -m uvicorn mall_shopping_agent.main:app --host 127.0.0.1 --port 8086
```

Stub 模式使用确定性脚本：先调用一次 `searchProducts`，再给出基于工具事实的
解释；候选商品 ID 由服务端按工具结果顺序回填。它不会访问网络，也不会读取密钥。

### 5.2 真实 OpenAI 兼容服务

```powershell
$env:MALL_AGENT_MODEL_MODE = 'openai'
$env:MALL_AGENT_OPENAI_BASE_URL = 'https://<你的兼容服务>/v1'
$env:MALL_AGENT_OPENAI_API_KEY = '<本地凭据>'
$env:MALL_AGENT_OPENAI_MODEL = '<模型名>'
.\.venv\Scripts\python.exe -m uvicorn mall_shopping_agent.main:app --host 127.0.0.1 --port 8086
```

凭据只放在本机 `.env` 或进程环境变量中，`.env` 不提交。

### 5.3 Docker Compose 与 Nginx

见 `document/docker/local-startup.md`。容器内 `MALL_AGENT_PORTAL_BASE_URL` 为
`http://mall-portal:8085`，`MALL_AGENT_REDIS_URL` 为 `redis://redis:6379/0`，
宿主机端口默认只绑定 `127.0.0.1`。

### 5.4 移动端

H5 开发环境把 `VITE_AGENT_API_BASE_URL` 指向 `http://localhost:8086`；
Nginx 环境使用 `/agent-api`（Nginx 反向代理到 `mall-shopping-agent:8086`，
`proxy_read_timeout 40s`）。微信小程序构建使用同一变量，具体地址按联调环境填写。

### 5.5 Redis 会话清理

```powershell
docker exec mall-local-redis-1 redis-cli -n 0 --scan --pattern "mall:agent:*"
docker exec mall-local-redis-1 redis-cli -n 0 del "<key>"
```

只删除 `mall:agent:` 前缀的键；不要批量清理其他键。

## 6. 验证

```powershell
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m ruff check src tests evals
.\.venv\Scripts\python.exe -m ruff format --check src tests evals
.\.venv\Scripts\python.exe evals/run_evals.py --mode stub
```

Redis 集成测试（需要本地 Redis）：

```powershell
$env:MALL_AGENT_TEST_REDIS_URL = 'redis://127.0.0.1:16379/15'
.\.venv\Scripts\python.exe -m pytest tests/integration/test_redis_session.py -q
Remove-Item Env:MALL_AGENT_TEST_REDIS_URL
```

真实模型评测只在本地显式配置凭据后运行，只输出通过率、工具次数和耗时：

```powershell
.\.venv\Scripts\python.exe evals/run_evals.py --mode live
```

## 7. 离线评测

`evals/cases.json` 覆盖 10 类对话：关键词搜索、品牌/分类筛选、预算排序、
2 至 3 款商品比较、SKU 库存、游客询问个人优惠券、会员商品优惠券、不存在商品、
交易写操作拒绝、提示注入。每条用例断言 `allowedTools`、`forbiddenTools`、
`requiresLogin`、`maxToolCalls`、`requiredFacts`（可选 `forbiddenFacts`）与
`productCardCount`，并额外校验门户调用没有越出只读白名单。

用例结构错误（未知字段、缺少必填字段、引用未注册工具）会让测试直接失败。

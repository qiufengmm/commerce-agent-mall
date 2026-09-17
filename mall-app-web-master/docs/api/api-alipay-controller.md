# AlipayController - 支付宝支付接口文档

> 数据来源：本地 Swagger UI - AlipayController
>
> 通用模型定义见 [api-common-models.md](./api-common-models.md)（CommonResult、CommonPage、PageParam）
>
> 鉴权说明：仅 `/alipay/notify` 为匿名入口（支付宝服务端回调）。`/alipay/pay`、`/alipay/webPay`、`/alipay/query` 需要携带会员 Token。

---

## 接口列表

| #   | 方法 | 路径             | 说明                       | 鉴权         |
| --- | ---- | ---------------- | -------------------------- | ------------ |
| 1   | POST | `/alipay/notify` | 支付宝异步回调             | 匿名         |
| 2   | GET  | `/alipay/pay`    | 支付宝电脑网站支付         | 需会员 Token |
| 3   | GET  | `/alipay/query`  | 支付宝统一收单线下交易查询 | 需会员 Token |
| 4   | GET  | `/alipay/webPay` | 支付宝手机网站支付         | 需会员 Token |

---

## 服务端安全约束

- `/alipay/pay`、`/alipay/webPay`：只接受 `outTradeNo`，服务端根据订单号查询订单并校验
  - 订单必须存在且未删除；
  - 订单状态必须为待付款（0）；
  - 订单必须属于当前登录会员；
  - 提交给支付宝的 `total_amount` 取自数据库 `oms_order.pay_amount`，客户端传入的 `totalAmount` 一律忽略；
  - 订单标题由服务端生成，客户端传入的 `subject` 一律忽略；
  - 校验不通过时不调用支付宝 SDK，直接返回业务异常。
- `/alipay/query`：校验当前会员与订单归属，并使用 `BigDecimal.compareTo` 比较支付宝返回金额与数据库 `oms_order.pay_amount`；
  金额缺失、格式非法或不一致时不标记支付成功、不扣减库存；SDK 调用失败时返回 `null` 而不是空指针。
  **返回语义**：只有本地订单也真正支付成功时才返回 `TRADE_SUCCESS`，详见下方第 3 节。
- `/alipay/notify`：保留支付宝匿名回调；签名校验通过后仍需校验 `total_amount` 与订单 `pay_amount` 一致才允许标记支付成功。

---

## 1. 支付宝异步回调

**POST** `/alipay/notify`

支付宝支付成功后的异步通知回调，执行成功返回 success，执行失败返回 failure。必须为 POST 请求。

### 请求参数

由支付宝服务端回调，参数格式遵循支付宝 SDK 规范。

### 响应结果

字符串：成功返回 `success`，失败返回 `failure`

---

## 2. 支付宝电脑网站支付

**GET** `/alipay/pay`

### 请求参数

Query 参数：

| 参数名      | 类型   | 必填 | 说明     |
| ----------- | ------ | ---- | -------- |
| outTradeNo  | string | 否   | 订单号   |
| subject     | string | 否   | 订单名称 |
| totalAmount | number | 否   | 金额     |

### 响应结果

返回支付宝支付页面 HTML

---

## 3. 支付宝统一收单线下交易查询

**GET** `/alipay/query`

### 请求参数

Query 参数：

| 参数名     | 类型   | 必填 | 说明             |
| ---------- | ------ | ---- | ---------------- |
| outTradeNo | string | 否   | 商户订单号       |
| tradeNo    | string | 否   | 支付宝交易凭证号 |

### 响应结果

`CommonResult<string>` — 见 [通用模型](./api-common-models.md#commonresultt)，data 类型为 string

### data 取值与含义

`data` 代表**本地订单的支付结果**，不是支付宝原始交易状态的简单透传。

| data                     | 含义                                                                       | 前端处理         |
| ------------------------ | -------------------------------------------------------------------------- | ---------------- |
| `TRADE_SUCCESS`          | 支付宝返回成功，且本地订单归属校验通过、金额一致、`paySuccessByOrderSn` 成功或幂等成功 | 显示「支付成功」 |
| `PAYMENT_VERIFY_FAILED`  | 支付宝返回成功，但订单不存在/已删除、不属于当前会员、金额缺失/非法/不一致    | 显示「支付失败」 |
| `PAYMENT_PROCESS_FAILED` | 本地校验通过，但 `paySuccessByOrderSn` 未成功，订单状态没有推进              | 显示「支付失败」 |
| `WAIT_BUYER_PAY`         | 支付宝交易已创建，等待买家付款                                              | 显示「支付处理中」 |
| `TRADE_CLOSED`           | 未付款交易超时关闭，或支付完成后全额退款                                    | 显示「支付失败」 |
| `TRADE_FINISHED`         | 交易结束，不可退款（本地不做状态推进）                                      | 显示「支付失败」 |
| `null`                   | 支付宝查询失败 / SDK 异常 / 响应为空                                        | 显示「支付失败」 |

> 移动端 `pages/money/paySuccess.vue` 只在 `data === 'TRADE_SUCCESS'` 时显示「支付成功」，
> 其余一律显示「支付失败」或「支付处理中」，不会出现「订单仍待付款但页面显示支付成功」的情况。

---

## 4. 支付宝手机网站支付

**GET** `/alipay/webPay`

### 请求参数

Query 参数：

| 参数名      | 类型   | 必填 | 说明     |
| ----------- | ------ | ---- | -------- |
| outTradeNo  | string | 否   | 订单号   |
| subject     | string | 否   | 订单名称 |
| totalAmount | number | 否   | 金额     |

### 响应结果

返回支付宝手机支付页面 HTML

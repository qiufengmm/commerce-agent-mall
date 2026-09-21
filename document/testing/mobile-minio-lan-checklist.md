# 移动端 / MinIO 局域网联调验收记录

本文件记录移动端（H5）、Nginx 与 MinIO 在局域网联调场景下的**验收步骤与判定标准**。

记录原则：

- 只记录命令、现象与结论，**不记录任何密码、Token、AccessKey 或其他凭据**；
- 宿主机 IP 只是某一台机器在某一时刻的局域网地址，文中统一用 `<LAN_IP>` 占位表示，
  实际执行时替换为本机 `ipconfig` 查到的地址；
- 通知页本地图片使用中性文件名 `notice-banner-01.jpg` / `notice-banner-02.jpg`，
  避免夸克浏览器广告过滤把 `ad1.jpg` / `ad2.jpg` 误判为广告资源；
- 结论必须来自真实执行的命令输出，不推断、不伪造。

---

## 1. 前置条件

| 项目 | 要求 |
| --- | --- |
| Docker Desktop | 已启动（`docker info` 正常返回 Server 版本） |
| `.env` | 已按 `document/docker/local-startup.md` 第 3 节创建并通过 `check-env.ps1` |
| `MINIO_BIND_ADDR` | 手机要访问 9000 时需设为 `0.0.0.0` |
| `NGINX_BIND_ADDR` | 手机要访问 8088 时需设为 `0.0.0.0` |
| `MINIO_PUBLIC_ENDPOINT` | 手机联调时需写成 `http://<LAN_IP>:9000` |
| 前端产物 | `mall-admin-web-master/dist` 与 `mall-app-web-master/dist/build/h5` 已构建 |

前端产物未构建或不是最新版本时，静态资源相关结论一律记为**环境前置条件未满足**，
不修改业务代码，也不把失败结果改写成成功。

---

## 2. 判定标准（先看这里再下结论）

### 2.1 MinIO 健康接口返回什么都不算错

`http://<LAN_IP>:9000/minio/health/live` 是 MinIO 的健康检查接口，
返回**纯文本或 XML** 都是正常形态，不是故障。

### 2.2 `NoSuchKey` 代表对象不存在，不代表网络失败

访问 `http://<LAN_IP>:9000/<不存在的路径>` 时，MinIO 返回 XML 形式的 `NoSuchKey`，
说明：

- 端口**可达**、服务**已响应**；
- 只是这个对象不存在。

因此：

> **不能**把 `NoSuchKey` 判定为"网络超时""端口不通""手机连不上 MinIO"。

要验证 MinIO 图片是否真的能访问，必须使用**真实存在的对象路径**
（先在管理后台上传图片，再用返回的 URL 验证）。

### 2.3 两类资源不要混为一谈

| 资源 | 地址形态 | 来源 |
| --- | --- | --- |
| H5 本地静态资源 | `http://<LAN_IP>:8088/static/**`、`http://<LAN_IP>:8088/h5/static/**` | H5 前端构建产物 |
| MinIO 商品图片 | `http://<LAN_IP>:9000/mall/<object>` | MinIO bucket `mall` 中的对象 |

前者由 Nginx 提供，后者由 MinIO 提供；
**不要**为 MinIO 图片额外增加 `/minio/` 反向代理，也不要用 `/static/` 去验证 MinIO。

---

## 3. 本轮验收结果（夸克浏览器 / 宿主机局域网）

宿主机局域网 IP：`<LAN_IP>`（本机实测为 `192.168.85.101`，随网络环境变化，不要写死）。

### 3.1 已确认可访问

| 序号 | 地址 | 结果 | 结论 |
| --- | --- | --- | --- |
| 1 | `http://<LAN_IP>:9000/minio/health/live` | 返回 MinIO XML 响应；测试路径不存在，返回 `NoSuchKey` | 夸克可以访问 9000 端口；`NoSuchKey` 不是网络超时 |
| 2 | `http://<LAN_IP>:8088/` | 正常打开 | Nginx 与绑定地址配置生效 |
| 3 | `http://<LAN_IP>:8088/h5/` | H5 页面可以打开 | H5 入口正常 |
| 4 | `http://<LAN_IP>:8088/h5/static/notice/ad1.jpg`（改名前） | 成功返回图片 | 带 `/h5` 前缀的静态资源正常；改名后的文件需重新构建后复测 |

### 3.2 本轮发现的问题

| 序号 | 地址 | 结果 | 结论 |
| --- | --- | --- | --- |
| 5 | `http://<LAN_IP>:8088/static/notice/ad1.jpg`（改名前） | 失败 | H5 页面内按绝对路径请求 `/static/**`，Nginx 缺少对应映射 |

根因：uni-app 的 H5 产物在运行时使用**绝对路径** `/static/...` 请求静态资源，
而页面入口是 `/h5/`，浏览器不会自动补 `/h5` 前缀，原配置只有 `location /h5/`，
因此 `/static/**` 落到管理后台根目录后找不到文件。

修复方式：`document/docker/nginx/conf.d/default.conf` 增加

```nginx
location /static/ {
    alias /usr/share/nginx/h5/static/;
    expires 7d;
    add_header Cache-Control "public";
}
```

映射只指向 H5 静态目录；管理后台使用 `base: './'` 且 `dist` 下没有 `static` 目录，
因此不会被覆盖。

---

## 4. 修复后必须重新执行的验证

```powershell
# 1) Compose 配置静态校验
docker compose --env-file .env --profile app --profile edge config --quiet

# 2) 检查 Nginx 映射文本
rg -n "location /static|location /h5/|/usr/share/nginx/h5" document/docker/nginx/conf.d/default.conf

# 3) 文档差异检查
git diff --check

# 4) 重建 Nginx（Docker 可用时）
docker compose --env-file .env --profile edge up -d --force-recreate nginx

# 5) 验证两条静态资源路径都应返回 200
curl.exe -I "http://<LAN_IP>:8088/static/notice/notice-banner-01.jpg"
curl.exe -I "http://<LAN_IP>:8088/h5/static/notice/notice-banner-01.jpg"
curl.exe -I "http://<LAN_IP>:8088/static/notice/notice-banner-02.jpg"
curl.exe -I "http://<LAN_IP>:8088/h5/static/notice/notice-banner-02.jpg"
```

预期：

| 路径 | 期望状态码 |
| --- | --- |
| `/static/notice/notice-banner-01.jpg` | `200` |
| `/h5/static/notice/notice-banner-01.jpg` | `200` |
| `/static/notice/notice-banner-02.jpg` | `200` |
| `/h5/static/notice/notice-banner-02.jpg` | `200` |

### 4.1 `/static/...` 修复前后对照（资源改名前）

| 阶段 | `/static/notice/ad1.jpg` | `/h5/static/notice/ad1.jpg` |
| --- | --- | --- |
| 修复前 | 失败 | 成功 |
| 修复后（正式 8088，2026-09-21） | `200` | `200` |

正式 Compose Nginx 已使用根目录 `.env` 重建并报告 `healthy`；实际局域网验证命令如下：

```powershell
curl.exe -I "http://192.168.85.101:8088/static/notice/ad1.jpg"
curl.exe -I "http://192.168.85.101:8088/h5/static/notice/ad1.jpg"
```

两条响应均为 `HTTP/1.1 200 OK`，内容类型为 `image/jpeg`。

### 4.2 夸克浏览器页面内图片过滤

本轮手机联调发现：通知页中的 `ad1.jpg`、`ad2.jpg` 直接打开可以加载，
但在页面内嵌显示时被夸克浏览器的广告过滤拦截；关闭夸克广告过滤后图片立即恢复显示。
该现象不是 Nginx、MinIO 或 JPEG 文件损坏导致的。

为避免要求用户关闭浏览器过滤，已将源码资源改名为中性名称：

```text
src/static/notice/ad1.jpg -> src/static/notice/notice-banner-01.jpg
src/static/notice/ad2.jpg -> src/static/notice/notice-banner-02.jpg
```

改名后需要重新构建 H5，并在夸克开启广告过滤的情况下重新打开通知页确认。

---

## 5. MinIO 真实图片的验证方式（不能省略）

`NoSuchKey` 不能作为"MinIO 图片可访问"的证据。完整验证必须包含一个**真实存在的对象**：

```powershell
# 列出 bucket 中真实存在的对象（在容器内用 mc，不在宿主机暴露凭据）
docker compose --env-file .env run --rm --no-deps minio-init `
  'mc alias set localminio http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" > /dev/null && mc ls --recursive localminio/mall'

# 用上一步列出的真实对象路径验证（<object> 必须是真实存在的路径）
curl.exe -I "http://<LAN_IP>:9000/mall/<object>"
```

判定：

- 返回 `200` 才算 MinIO 图片在局域网可访问；
- 返回 `NoSuchKey` / `404` 只说明对象不存在，需要换成真实对象路径再测；
- 返回连接超时 / 拒绝连接，才说明端口或绑定地址有问题。

---

## 6. 遗留项

- 本轮只验证了 Nginx 静态资源路径，MinIO 真实图片 `200` 仍待用**实际存在的对象路径**验证；
- H5 构建产物若未重新构建，静态资源文件名可能与线上不一致，需把"产物版本"作为前置条件记录；
- 资源改名后的夸克浏览器页面内最终验收仍需重新构建 H5 后执行；
- 联调结束后应把 `MINIO_BIND_ADDR` / `NGINX_BIND_ADDR` 改回 `127.0.0.1` 并重建容器。

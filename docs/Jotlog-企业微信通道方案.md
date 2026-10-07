# Jotlog · 企业微信通道技术方案（v0.2）

> 版本 v0.2 · 2026-10-07 · **输入通道已定：企业微信**
> 前置阅读：`Jotlog-初版设计文档.md`（竞品调研与产品设计部分仍然有效）

---

## 0. 本次决策的变更点

你在上一轮定了：**用企业微信 + 有公网服务器**。这把文档从"多方案待选"收敛成单一路径。

但我在核实企业微信官方文档时发现了一个**比公网服务器更有价值的信息**，所以这里先纠正一个可能的误解：

> ### 你有公网服务器，但 Jotlog 反而**不需要**它
>
> 企业微信 2026 年新开放的**「智能机器人长连接（WebSocket）」模式**，是**你的程序主动连企业微信**，不是企业微信来回调你。它不需要：
> - 已 ICP 备案的企业主体域名
> - 443 标准端口 HTTPS 证书（自签证书一律被拒）
> - Nginx 反代配置
> - AES 消息加解密实现
> - 固定出口 IP 白名单
>
> 只要你的服务器**能主动出网**，给它 `BotID` + `Secret` 两个字符串就能跑通。
>
> **这比 URL 回调模式省掉一整天的环境配置和一半代码量。** 你的公网服务器留给 PWA 的 HTTPS 访问用，那是必须的（下面第 4 节）。

---

## 1. 企业微信智能机器人：两种 API 模式

### 1.1 官方对比表

来源：企业微信开发者中心《智能机器人长连接》文档（最后更新 2026/05/18）。

| 特性 | URL 回调（Webhook） | WebSocket 长连接 |
|---|---|---|
| 连接方式 | 每次回调建立新连接 | 复用已建立的长连接 |
| 延迟 | 较高（每次建连） | 低（复用连接） |
| **公网要求** | **必须公网可访问的 URL** | **无需固定公网 IP** |
| **加解密** | **需要对消息加解密** | **无需加解密** |
| 实现复杂度 | 低 | 较高（需维护心跳） |
| 可靠性 | 高（无状态） | 需心跳保活、断线重连 |
| 推荐场景 | 普通回调场景 | **无公网 IP、高实时性** |

长连接的官方描述里明确写：**"无需公网 IP"、"简化开发：无需处理消息加解密逻辑"**，官方推荐用于「无公网 IP」「高实时性要求」场景。

### 1.2 我的建议：先走长连接

理由按重要性排：

1. **配置项少两个数量级。** 长连接只要 BotID + Secret 两个值；URL 回调要 URL + Token + EncodingAESKey + 可信 IP + 备案域名，少任何一个都配不上。
2. **代码量少一半。** 长连接的载荷是明文 JSON，URL 回调要实现 AES-256-CBC 加解密 + 签名校验 + PKCS#7 填充 + echostr 验证，Go 里虽然是 `crypto/*` 标准库，但光调试就得一天。
3. **调试直观。** 长连接是终端里直接打日志，能看到消息实时到达；URL 回调要开公网入口、抓 HTTPS 流量、排查 Nginx。
4. **可随时降级。** 长连接不行（比如个人微信侧发不进来），官方支持切换到 URL 回调，**架构不变，只是换个 transport**。而反过来先做回调再改长连接，要重写验签层。

**长连接的唯一代价**：一个 Bot 同时只能有一条连接（新的会把旧的踢掉），需要自己实现心跳 + 断线重连。这个成本是可控的，而且对我们反而是个约束——Jotlog 是单人单实例工具，正好符合。

### 1.3 长连接的关键协议细节

**连接地址**：`wss://openws.work.weixin.qq.com`

**连接建立流程**：

```
Jotlog                                    企业微信
  │                                          │
  │  ① WebSocket 握手                        │
  │─────────────────────────────────────────>│
  │  ② 订阅请求 aibot_subscribe              │
  │     { cmd: "aibot_subscribe",            │
  │       headers: { req_id: "..." },        │
  │       body: { bot_id, secret } }          │
  │─────────────────────────────────────────>│
  │  ③ 校验凭证，返回 errcode: 0             │
  │<─────────────────────────────────────────│
  │                                          │
  │  ④ 心跳 ping（建议 30s 一次）             │
  │<────────────────────────────────────────>│
  │                                          │
  │  ⑤ 用户发消息 → aibot_msg_callback 推送  │
  │<────────────────────────────────────────│
  │  ⑥ 回复 aibot_respond_msg（finish=true） │
  │─────────────────────────────────────────>│
  │                                          │
  │  ⑦ 主动推送 aibot_send_msg（每日回顾）    │
  │─────────────────────────────────────────>│
```

**注意事项（官方文档明确列出）**：

- ⚠️ **订阅请求有频率保护**，订阅成功后不要反复请求，否则触发系统限制。
- ⚠️ **一个机器人同一时间只能保持一个长连接**，新连接会踢掉旧连接，且会收到 `disconnected_event`。生产环境要避免多实例部署；要做高可用得用主备切换而不是同时多连。
- ⚠️ **API 模式只能二选一**：长连接和 URL 回调互斥，切换会使另一种失效。切换到长连接后，原回调地址不再生效。
- ⚠️ **Secret 只展示一次**，页面刷新就没了；后台重新生成 Secret 旧的立即失效，服务端要同步更新。
- ⚠️ **必须选「API 模式创建」**——普通模式创建拿不到 BotID。
- ⚠️ **可见范围必须包含你自己**，否则机器人收不到消息。

### 1.4 支持的消息类型

长连接模式支持以下回调类型（**注意括号内的限制**）：

| msgtype | 说明 | 限制 |
|---|---|---|
| `text` | 文本 | — |
| `image` | 图片 | **仅支持单聊** |
| `mixed` | 图文混排 | — |
| `voice` | 语音（转文本） | **仅支持单聊** |
| `file` | 文件 | **仅支持单聊** |
| `video` | 视频 | **仅支持单聊** |

> **对 Jotlog 的含义**：图片、语音、文件、视频都**只在单聊可用**。群聊里只能收纯文本和图文混排。对单人使用场景来说，这完全够用。

**多媒体资源解密**：长连接模式下 `image`/`file`/`video` 结构体会额外返回 `aeskey` 字段：

```json
{ "image": { "url": "URL", "aeskey": "AESKEY" } }
```

- `url` 有效期 **5 分钟**
- **每个下载链接的 aeskey 都是唯一的**（不同于 URL 回调模式用统一的 EncodingAESKey）
- 加密方式：AES-256-CBC，PKCS#7 填充至 32 字节倍数，IV 取 aeskey 前 16 字节

### 1.5 一个必须实测确认的风险点

这是整个方案唯一的未知，我不能替你打包票：

**资料里有冲突。**

- 社区资料（OpenClaw 生态、腾讯云开发者社区）普遍描述：**企业微信「微信插件」可以让个人微信给智能机器人发消息**，消息会同时出现在个人微信和企业微信里。
- 但企业微信官方帮助中心《如何接收微信插件消息》通篇只讲**接收**方向（"支持接收企业自建应用和第三方应用下发的消息，以及会话消息"），没有明确说能否**发送**。
- 还有一篇腾讯云文章明确说：「官方 API 智能机器人……**完全不支持外部场景：不能进外部群、不能私聊微信个人**」。

**这三条是矛盾的。** 我倾向于社区的说法是对的（企微的"微信插件"能力就是为此设计的，而且官方文档里那个"允许成员在微信插件中接收和回复聊天消息"的勾选项暗示了回复能力），**但官方文档没写清楚，所以必须实测。**

**M0 阶段第一件事就是验证这个。** 验证方法见第 6 节，30 分钟内能有结论。

**如果实测是"能发"** → 方案按原计划执行，你可以在个人微信里给机器人发消息，就像现在给文件传输助手发消息一样。

**如果实测是"不能发"** → 有两个退路，都不算失败：
- **退路 A（推荐）**：改用**企业微信官方自建应用 + URL 回调**，你在企业微信客户端里给应用发消息（不是个人微信）。体验略差于微信，但完全合规、官方支持收文件收图片。
- **退路 B**：手机侧改用 Jotlog 的 PWA（添加到主屏，有全局快捷键的类 App 体验），电脑侧继续用 PWA。**微信通道变成锦上添花而非必需项**——这也是为什么架构上 PWA 必须是一等公民，不能只是"手机端备用"。

---

## 2. 修正后的通道架构

### 2.1 三通道现状

| 通道 | 状态 | 说明 |
|---|---|---|
| **企业微信长连接** | 主通道（待实测确认） | BotID + Secret，服务器反向连接，无需公网入口 |
| **PWA + 快捷键** | 电脑端主力，永不依赖微信 | 无论微信通道成败，都必须做 |
| ClawBot | **暂不使用** | 一个微信号只能绑一个 Bot，且 24h 窗口不能主动推送。企业微信能全量覆盖它的能力 |

> 你选了企业微信，ClawBot 就从候选里划掉了。这也顺带避开了"名额被 OpenClaw 占用"的冲突风险。

### 2.2 修正后的架构图

```
┌──────────────────────────────────────────────────────┐
│                    客户端层                           │
│   ┌──────────┐   ┌──────────┐   ┌────────────────┐   │
│   │ 手机 PWA │   │ 电脑 PWA │   │ 浏览器扩展(可选) │   │
│   │ 主屏 App │   │ +快捷键  │   │  右键存入/划词   │   │
│   └────┬─────┘   └────┬─────┘   └────────┬───────┘   │
└────────┼───────────────┼────────────────┼───────────┘
         │               │                │
         └───────────────┼────────────────┘
                         │ HTTPS（需要公网域名）
                         ▼
┌──────────────────────────────────────────────────────┐
│              Jotlog Server（单二进制）                 │
│                                                      │
│  ┌──────────────┐   ┌────────────────────────────┐   │
│  │ Gin HTTP API │   │ PWA 静态资源（内嵌二进制）   │   │
│  └──────┬───────┘   └────────────────────────────┘   │
│         │                                            │
│  ┌──────▼────────────────────────────────────────┐   │
│  │ 入口适配层（Entry Adapters）                   │   │
│  │ ┌─────────────┐  ┌─────────────────────────┐ │   │
│  │ │ PWA Adapter │  │ WeCom WS Adapter        │ │   │
│  │ │  HTTP 直连  │  │  wss://openws.work...   │ │   │
│  │ └──────┬──────┘  │  · 心跳 30s             │ │   │
│  │         │         │  · 断线指数退避重连       │ │   │
│  │         │         │  · 明文 JSON（无需解密） │ │   │
│  │         │         │  · 媒体按 aeskey 解密     │ │   │
│  │         │         └───────────┬─────────────┘ │   │
│  └─────────┼─────────────────────┼───────────────┘   │
│            ▼                     ▼                   │
│  ┌──────────────────────────────────────────────┐   │
│  │ 归一化管线（异步 worker）                     │   │
│  │  链接抽取 → 类型识别 → 元数据 → AI分类 → 归档 │   │
│  └──────────────────┬───────────────────────────┘   │
│                     ▼                               │
│  ┌──────────────────────────────────────────────┐   │
│  │ SQLite (WAL) + FTS5 + 本地 attachments/       │   │
│  └──────────────────┬───────────────────────────┘   │
│                     ▼                               │
│  ┌──────────────────────────────────────────────┐   │
│  │ 每日回顾调度 → aibot_send_msg 主动推送        │   │
│  └──────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────┘
         │
         │  出站 WebSocket（只需能出网）
         ▼
   企业微信 wss://openws.work.weixin.qq.com
```

**注意架构的一个变化**：企业微信通道从"入站 HTTP 回调"变成了"出站 WebSocket 长连接"。这意味着：

- Jotlog 的 HTTP 端口**仍然必须公网可达**（给 PWA 用），但**企业微信通道不依赖这个端口**
- 两者解耦：即使 HTTP 入口挂了（比如 Nginx 配置错误），微信通道仍然工作，机器人照样能记录
- 这是个额外的好处——**故障域分离**

---

## 3. 企业微信接入实施步骤

### 3.1 前置：注册企业微信

1. 访问 `work.weixin.qq.com`，个人可注册（填虚拟公司名，无需认证）
2. 登录管理后台
3. **安全与管理 → 管理工具 → 智能机器人 → 创建机器人**
4. ⚠️ **必须拉到最底下点「API 模式创建」**——普通模式创建拿不到 BotID
5. 填写基本信息（名称、头像、简介）
6. ⚠️ **可见范围必须包含你自己**，否则机器人收不到消息
7. 选连接方式：**长连接**
8. 保存后页面自动生成 **Bot ID** 和 **Secret**
9. ⚠️ **Secret 只显示这一次**，立刻复制保存

### 3.2 个人微信接入（微信插件）

1. 管理后台 → **我的企业 → 微信插件**
2. 获取「邀请关注」二维码
3. ⚠️ **二维码有效期只有 7 天**，过期需重新获取
4. 用个人微信扫码关注
5. ⚠️ **管理后台的「成员使用微信插件时需要使用企业微信客户端」不可勾选**
6. ⚠️ 手机端企业微信 → 我 → 设置 → 消息通知 → **关闭「仅在企业微信中接收消息」**（会话消息和应用消息都要关）
7. ⚠️ 微信插件 → 右上角设置 → **开启「接收应用消息」**

> 第 5、6、7 步是官方帮助文档反复强调的排查点，不设对的话会**消息能发但收不到**，或者**在微信里看不到回复**。

### 3.3 验证清单（M0 gate）

在写任何 Jotlog 代码之前，先用最小脚本验证：

```
□ 1. 企业微信智能机器人创建成功，拿到 BotID / Secret
□ 2. 长连接建立成功（日志出现 subscribed ok）
□ 3. 在企业微信客户端给机器人发一条文字 → 服务端日志收到 aibot_msg_callback
□ 4. 服务端调用 aibot_respond_msg 回复 → 企业微信客户端收到回复
□ 5. 机器人回复 → 个人微信能否同步收到？（验证微信插件的回复方向）
□ 6. 从个人微信给机器人发一条文字 → 服务端是否收到？（★ 最关键，官方文档未明确）
□ 7. 发一张图片 → 是否收到 image 回调，media url + aeskey 是否完整
□ 8. 主动推送 aibot_send_msg → 是否能收到（每日回顾依赖这个）
```

**第 6 项是 go/no-go。** 如果失败，退路见 1.5 节末。

---

## 4. 部署方案

### 4.1 拓扑

```
                    ┌─────────────────────────┐
   手机浏览器 ──────>│  Nginx (443)             │
   电脑浏览器 ──────>│    └─> jotlog:8080       │
                    │   (PWA + API，需要 HTTPS) │
   个人微信 ────────>│                         │
   （微信插件会话）    └─────────────────────────┘
                              │
                              │ 出站 wss://openws.work.weixin.qq.com
                              │ （只需出网，无入站依赖）
                    ┌─────────▼───────────────┐
                    │  jotlog 二进制           │
                    │  /var/lib/jotlog/        │
                    │    jotlog.db             │
                    │    attachments/          │
                    └─────────────────────────┘
```

### 4.2 服务器要求

| 项 | 要求 | 说明 |
|---|---|---|
| CPU / 内存 | 1C / 512MB 起 | 单二进制 Go 程序，空载 ~30MB |
| 磁盘 | 5GB | 数据库 + 附件 |
| 系统 | Linux（任意发行版） | 单静态二进制，无 glibc 依赖 |
| 出网 | 需能访问 `openws.work.weixin.qq.com:443` | 长连接前提 |
| 入站 | 仅 PWA 需要 443 | 建议复用你 junan.cloud 的 Nginx |

> 你已经有公网服务器和 Nginx 经验（junan 项目是 systemd + rsync 部署），部署环节基本零学习成本。

### 4.3 systemd 单元

```ini
# /etc/systemd/system/jotlog.service
[Unit]
Description=Jotlog - 随手记
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=jotlog
Group=jotlog
WorkingDirectory=/var/lib/jotlog
ExecStart=/usr/local/bin/jotlog serve --config /etc/jotlog/config.toml
Restart=always
RestartSec=10
Environment=JOTLOG_LOG_LEVEL=info

# 安全加固
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/jotlog

[Install]
WantedBy=multi-user.target
```

### 4.4 Nginx 反代

```nginx
server {
    listen 443 ssl http2;
    server_name jotlog.example.com;

    ssl_certificate     /etc/letsencrypt/live/jotlog.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/jotlog.example.com/privkey.pem;

    # 附件上传（视频可能较大）
    client_max_body_size 50m;

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;

        # WebSocket 支持（如果将来加 PWA 实时推送）
        proxy_set_header   Upgrade    $http_upgrade;
        proxy_set_header   Connection "upgrade";
    }
}
```

> ⚠️ 注意：`wss://openws.work.weixin.qq.com` 是 **Jotlog 主动连出去**的，**不经过 Nginx**。上面这段 `Upgrade` 头是为将来 PWA 实时推送预留的，与企业微信通道无关。

### 4.5 配置文件

```toml
# /etc/jotlog/config.toml

[server]
listen      = "127.0.0.1:8080"
token       = "换成一个至少 32 位的随机串"
base_url    = "https://jotlog.example.com"

[storage]
data_dir   = "/var/lib/jotlog"
db_path    = "/var/lib/jotlog/jotlog.db"
attach_dir = "/var/lib/jotlog/attachments"

# 企业微信智能机器人 · 长连接
[channel.wecom]
enabled = true
bot_id  = "从管理后台复制"
secret  = "从管理后台复制（只显示一次）"

# 连接保活
heartbeat_interval = 30   # 秒
reconnect_max_wait = 300  # 指数退避上限（秒）

# 各通道统一回这个格式，格式最省事
[channel.wecom.reply]
mode    = "text"          # text | markdown | card
success = "✓ 已存 #{short_id}"
failed  = "✗ 没存上：{error}，重发一次试试"

[enrich]
fetch_opengraph     = true
fetch_github        = true
fetch_video         = true
archive_body        = false   # 默认关闭：抓正文最重
archive_screenshot  = false

[ai]
enabled  = false
provider = "ollama"          # 或 "openai"
model    = "qwen2.5:7b"

[review]
enabled     = false          # 每日回顾（M3 再开）
push_hour   = 9
random_replay_count = 1      # 随机重现 N 条旧记录
```

### 4.6 部署命令

```bash
# 1. 编译
GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o jotlog ./cmd/jotlog

# 2. 上传
scp jotlog root@your-server:/usr/local/bin/
scp config.toml root@your-server:/etc/jotlog/

# 3. 建用户与目录
ssh root@your-server
useradd -r -s /usr/sbin/nologin jotlog
mkdir -p /var/lib/jotlog/attachments /etc/jotlog
chown -R jotlog:jotlog /var/lib/jotlog

# 4. 启动
systemctl daemon-reload
systemctl enable --now jotlog
journalctl -u jotlog -f    # 看长连接是否建立成功
```

---

## 5. Go 侧实现要点

### 5.1 依赖

```go
// WebSocket —— 唯一新增依赖
github.com/coder/websocket   // 替代已弃用的 nhooyr.io/websocket
// 或
github.com/gorilla/websocket

// 其余全部标准库
crypto/aes        // 媒体解密
crypto/cipher
encoding/base64
encoding/xml      // URL 回调模式才需要（备用）
golang.org/x/net/html   // og 标签解析
```

> **长连接方案的依赖极少**，这是它相对 URL 回调的另一个隐性优势——回调模式要引入 XML 解析和完整的验签链路。

### 5.2 包结构

```
internal/channel/wecom/
├── client.go      # WebSocket 连接 + 订阅 + 重连
├── heartbeat.go   # 30s ping/pong
├── protocol.go    # cmd/req_id/errcode 数据结构
├── handler.go     # aibot_msg_callback / aibot_event_callback 分发
├── reply.go       # aibot_respond_msg 流式回复
├── push.go        # aibot_send_msg 主动推送（每日回顾用）
├── media.go       # 按 url + aeskey 下载解密图片/文件
└── queue.go       # 发送队列与限流
```

### 5.3 连接生命周期

```go
// 核心：断线指数退避重连
func (c *Client) run(ctx context.Context) {
    backoff := time.Second
    for ctx.Err() == nil {
        err := c.connectAndServe(ctx)   // 内部处理订阅+心跳+消息循环
        if ctx.Err() != nil {
            return
        }
        log.Warn().Err(err).Msg("wecom disconnected, reconnecting")

        select {
        case <-ctx.Done():
            return
        case <-time.After(backoff):
        }
        backoff = min(backoff*2, c.maxWait)   // 上限 300s
    }
}
```

**必须处理的四个事件**：

| cmd / eventtype | 处理 |
|---|---|
| `aibot_msg_callback` | 主流程：归一化 → 入库 → 回复确认 |
| `aibot_event_callback` / `enter_chat` | 首次进入会话 → 回欢迎语 |
| `aibot_event_callback` / `disconnected_event` | 旧连接被踢 → 退避重连 |
| `aibot_event_callback` / `template_card_event` | V2 的回顾卡片交互（预留） |

**去重**：官方文档说 `msgid` 是"本次回调的唯一性标志，用于事件排重"。用 `msgid` 建唯一索引，收到重复回调直接忽略——企业微信可能重推。

```sql
CREATE UNIQUE INDEX idx_entries_wecom_msgid ON entries(source_msg_id)
  WHERE source_msg_id IS NOT NULL;
```

### 5.4 消息处理流程

```go
func (h *Handler) onMessage(ctx context.Context, msg MsgCallback) error {
    // 1. 去重（msgid 幂等）
    if h.repo.Exists(ctx, msg.MsgID) {
        log.Debug().Str("msgid", msg.MsgID).Msg("duplicate, skipped")
        return nil
    }

    // 2. 按 msgtype 分派
    var entryDraft Draft
    switch msg.MsgType {
    case "text":
        entryDraft = Draft{
            RawInput: msg.Text.Content,
            Source:   "wecom",
        }
    case "image":
        // URL 5 分钟内有效，必须立刻下载
        local, err := h.media.Fetch(ctx, msg.Image.URL, msg.Image.AESKey)
        // ...
    case "file", "voice", "video":
        // 同上
    case "mixed":
        // 图文混排：text.content 里可能有链接
        // ...
    default:
        return fmt.Errorf("unsupported msgtype: %s", msg.MsgType)
    }

    // 3. 快速入库（用户已在等确认了）
    entry, err := h.repo.Create(ctx, entryDraft)
    if err != nil {
        return err   // 回"没存上"
    }

    // 4. 立刻回复确认 —— 这一步必须在异步管线之前
    h.reply.Send(ctx, msg, fmt.Sprintf("✓ 已存 #%s", entry.ShortID))

    // 5. 异步跑归一化管线（链接抽取/元数据/AI分类）
    h.pipeline.Enqueue(entry.ID)

    return nil
}
```

**关键顺序**：先入库、先回复确认，再跑异步增强。用户按下发送后 200ms 内看到 `✓`，元数据 3 秒后才补上——**确认永远不能被慢操作阻塞**。

### 5.5 媒体解密

```go
// 长连接模式：每个 URL 的 aeskey 唯一
func (m *Media) decrypt(raw, aesKeyB64 string) ([]byte, error) {
    key, err := base64.StdEncoding.DecodeString(aesKeyB64)
    if err != nil || len(key) != 32 {
        return nil, fmt.Errorf("invalid aeskey: %w", err)
    }
    block, err := aes.NewCipher(key)
    if err != nil {
        return nil, err
    }
    iv := key[:16]                       // IV = key 前 16 字节
    data := make([]byte, len(raw))
    cipher.NewCBCDecrypter(block, iv).CryptBlocks(data, raw)
    return pkcs7Unpad(data, 32)          // PKCS#7 填充到 32 字节倍数
}
```

> ⚠️ PKCS#7 的 block size 是 **32**（不是 AES 默认的 16），这是微信系协议的固定坑，写错了必然解密失败且不报错，只会是乱码。

### 5.6 每日回顾推送（M3）

```go
// 主动推送，无需用户先发消息
func (c *Client) PushDaily(ctx context.Context, review DailyReview) error {
    return c.send(ctx, "aibot_send_msg", SendMsgBody{
        ChatID:   review.ChatID,        // 已建立过会话的用户
        MsgType:  "markdown",
        Markdown: review.Render(),      // 昨天 N 条 + 随机 1 条旧记录
    })
}
```

**推送限制（官方文档）**：主动推送有额度限制，且需用户或群聊已与机器人建立过会话。**所以每日回顾必须在用户当天发过消息之后才推**——这反而是好事，可以做成"晚上 9 点回顾今天"，前提是你今天记过东西。

---

## 6. 修订后的路线图

| 阶段 | 目标 | 交付物 | 前置依赖 | 预估 |
|---|---|---|---|---|
| **M0 验证** | 通道可行性 | 企业微信注册 + BotID/Secret + **最小 WebSocket 脚本跑通 + 个人微信收发实测** | — | **0.5 天** |
| **M1 内核** | 能存能搜 | SQLite + API + PWA 三屏 + FTS5 + Markdown 导出 | M0 | 3-5 天 |
| **M2 微信通道** | 手机随手记 | WeCom WS adapter + 心跳重连 + 媒体解密 + 快闪确认 | M1 | 2 天 |
| **M3 智能** | 少动手 | 元数据补全 + AI 标签 + 每日回顾推送 | M2 | 2-3 天 |
| **M4 生态** | 融入工作流 | 浏览器扩展 + MCP Server + Obsidian 归档 | M2 | 3-5 天 |

**M2 比原计划少了 1 天**，因为长连接省掉了 URL 校验、加解密、可信 IP 和 Nginx 调试。**但多了一项必须做的：断线重连和幂等处理**——长连接是长连接，必须自己保证稳定性。

---

## 7. 修订后的风险清单

| 风险 | 等级 | 变化 | 应对 |
|---|---|---|---|
| **个人微信无法给智能机器人发消息** | **高** | 新增（上一版是 ClawBot 名额冲突，现已消除） | **M0 实测**。失败则退 A（企微自建应用+回调）或退 B（PWA 为主，微信降级为锦上添花） |
| 微信插件配置项多，易"能发收不到" | 中 | 新增 | 严格按 3.2 节的 5/6/7 三步排查；官方文档反复强调这三项 |
| 长连接被踢（单连接限制） | 中 | 新增 | 退避重连 + `disconnected_event` 监听；Jotlog 是单实例工具，天然规避 |
| 企微 API 模式变更导致失效 | 中 | 降低 | URL 回调作为 transport 层备选，切换只需换 adapter |
| 无备案域名导致回调模式不可用 | **已消除** | ✅ 长连接不需要 | — |
| 服务器故障通道全断 | 低 | 新增 | PWA + 本地 SQLite 可离线用；DB 是单文件，备份=复制 |
| AI 分类需要额外 API | 低 | — | 可用本地 Ollama，零成本零泄露；也可完全关掉 |

---

## 8. 下一步

**今天就能做完 M0。** 具体：

1. 注册企业微信，创建智能机器人（务必选 API 模式 + 长连接 + 可见范围含自己）
2. 拿 BotID / Secret
3. 个人微信扫码关注微信插件，按 3.2 节调那三个开关
4. 写一个 60 行的 Go/Python 最小脚本，连上 WebSocket，发一条消息看能不能收到回复
5. **重点测第 6 项**：从个人微信给机器人发消息，服务端是否收到

第 5 步做完，把结果告诉我：
- **通了** → 我按本方案继续，M2 代码可以马上开工
- **不通** → 切 URL 回调自建应用方案，或者直接转向 PWA 优先路线

---

## 附：本版引用的官方文档

| 内容 | 来源 |
|---|---|
| 智能机器人长连接（协议、消息格式、限制） | `developer.work.weixin.qq.com/document/path/101463`，更新于 2026/05/18 |
| URL 回调概述（加解密、配置项） | `developer.work.weixin.qq.com/document/62161` |
| 如何接收微信插件消息（接收前提条件） | `open.work.weixin.qq.com/help2/pc/18121` |
| 什么是微信插件（关注方式） | `open.work.weixin.qq.com/help2/pc/14797` |
# Jotlog · 多通道接入方案（v0.3）

> 版本 v0.3 · 2026-10-07 · **输入通道：飞书 / 钉钉 / 企业微信 三选一或多路并存**
> 前置阅读：`Jotlog-初版设计文档.md`（产品设计）、`Jotlog-企业微信通道方案.md`（v0.2，企微协议细节）

---

## 0. 好消息：你不需要赌了

v0.2 里我把整个方案押在「个人微信能否给企业微信智能机器人发消息」这个未确认的问题上，还要你花半小时实测。

**现在有更好的答案：飞书和钉钉都能用，而且 100% 确定。**

三家的共同点是都有**长连接（反向连接）模式**，都是官方 SDK、都是两个凭证、不需要公网 IP、不需要域名证书、不需要加解密。

| 平台 | 个人账号可用 | 长连接 | 媒体能力 | 配置步骤 | 官方文档明确度 |
|---|---|---|---|---|---|
| **飞书** | ✅ 直接可用 | ✅ WebSocket | 图/文/文件/富文本 | 6 步 | ✅ 明确 |
| **钉钉** | ✅ 需免费建团队 | ✅ Stream「五零」 | **图/音/视/文件全支持** | 4 步 | ✅ 明确 |
| 企业微信 | ✅ 需注册 | ✅ WebSocket | ⚠️ 多媒体**仅单聊** | 3 步 | ❌ 微信侧方向不明 |

**结论：飞书或钉钉二选一，方案立刻变成确定性的。** 企业微信从"主通道"降级为"可选通道"。

### 这是一次明确的进步，不是妥协

回看你的原始需求：

> 我现在是在微信上的文件传输助手上记忆

你要的是**一个随手发的东西**，不是一个"必须在微信里发"的东西。文件传输助手本质上只是个**草稿箱入口**——它之所以赢，纯粹是因为"打开它不需要思考"。

飞书/钉钉同样满足这一点：**在消息列表里有一个置顶的聊天对象，点开就发**。甚至更好：

- **飞书/钉钉可以直接搜应用名**，不用先打开微信再找联系人
- 群聊也能用（企微多媒体不行），意味着你可以在自己的小号群里测试
- 钉钉连视频/音频都能收，企微只能在单聊收

**唯一代价**：多装一个 App，发的时候打开的是它不是微信。

---

## 1. 平台选择建议

### 1.1 我的推荐：飞书

理由按权重：

1. **体验最好**。飞书的单聊体验在国内办公 IM 里是第一梯队，消息列表置顶、卡片消息好看、搜索能力强。Jotlog 的确认回执（`✓ 已存 #a1b2`）在飞书里可以用卡片渲染，比纯文字好看且可点击。
2. **个人账号零门槛**。飞书个人版就能创建企业自建应用，**不需要建团队、不需要认证**。钉钉要额外建个团队（免费但多一步且容易建错组织）。
3. **官方 SDK 成熟**。Go SDK 是 `github.com/larksuite/oapi-sdk-go-v3`，文档全、示例多、社区活跃。钉钉 Go SDK 相对薄一些（生态以 Java/Python 为主）。
4. **长连接是官方首推**。飞书在事件订阅里直接把「使用长连接接收事件」作为推荐选项。
5. **2026 年全面放开 API 调用限制**，免费用户也能无限次调用——适合每天定时推回顾。

**如果你已经在用钉钉**，那就用钉钉，别为了 Jotlog 多装一个 App。钉钉的 Stream 模式同样成熟，甚至在媒体类型上更全。

### 1.2 关于企业微信

**不是不能用，是没必要。** 除非你有强需求：

- 你已经有企业微信在跑，且组织架构/权限管理更方便
- 你需要让机器人进群、且群里有企业成员协作（但多媒体仍限单聊）

否则它反而是最差选择：**媒体能力最弱，且个人微信方向官方文档不明**——你等于要花时间实测一个本可以不赌的东西。

### 1.3 三家对比的完整维度

| 维度 | 飞书 | 钉钉 | 企业微信 |
|---|---|---|---|
| 长连接方式 | WebSocket（SDK） | Stream（SDK，反向连接） | WebSocket |
| 凭证 | App ID + App Secret | Client ID (AppKey) + Client Secret | BotID + Secret |
| Go SDK | ✅ 官方，成熟 | ⚠️ 有但薄 | ⚠️ 无官方，社区有 |
| 加解密 | ❌ 不需要 | ❌ 不需要 | ❌ 长连接模式不需要 |
| 公网 IP | 不需要 | 不需要 | 不需要 |
| 域名证书 | 不需要 | 不需要 | 不需要 |
| 文本 | ✅ | ✅ | ✅ |
| 图片 | ✅ | ✅ | ⚠️ 仅单聊 |
| 文件 | ✅ | ✅ | ⚠️ 仅单聊 |
| 音频 | ✅ | ✅ | ⚠️ 仅单聊 |
| 视频 | ✅ | ✅ | ⚠️ 仅单聊 |
| 富文本图文 | ✅ | — | ✅ (mixed) |
| 群聊 | ✅ | ✅ 需 @ | ✅ 仅纯文本 |
| 主动推送 | ✅ | ✅ | ✅ 有限额 |
| 个人微信互通 | ❌ | ❌ | ⚠️ 官方未明确 |

---

## 2. 架构调整：从单通道到 Adapter 多通道

### 2.1 核心设计：通道即配置

**这是从 v0.2 到 v0.3 最重要的架构决策。** 不为每个平台写一套逻辑，而是定一个 `ChannelAdapter` 接口，平台差异全部收敛到实现层。

```go
// internal/channel/adapter.go

// Entry 是归一化后的待入库条目（所有通道共用的中间表示）
type Entry struct {
    RawInput    string
    Type        EntryType   // link | text | image | file | video | audio
    URL         string
    Title       string
    Attachment  []byte     // 媒体原始内容
    Mime        string
    Filename    string
    ReplyTarget ReplyTarget // 回执发给谁
    Source      string      // feishu | dingtalk | wecom | pwa
    SourceMsgID string      // 幂等去重
}

// ReplyTarget 抽象了各平台的回复方式差异
type ReplyTarget struct {
    Kind        ReplyKind   // ReplyInThread | ReplyDirect | ReplyCard
    ChatID      string
    MessageID   string
    OpenID      string      // 飞书用
    Conversation string     // 钉钉用
}

type ChannelAdapter interface {
    // Name 返回通道标识
    Name() string

    // Start 建立长连接并阻塞，直到 ctx 取消。
    // 收到的每条消息调用 handler，永不返回错误给调用方——
    // 连接生命周期由 adapter 内部自行管理（心跳/重连/退避）。
    Start(ctx context.Context, handler Handler) error

    // Reply 回执确认
    Reply(ctx context.Context, target ReplyTarget, reply Reply) error

    // Push 主动推送（每日回顾用）
    Push(ctx context.Context, target PushTarget, msg PushMessage) error
}
```

**收益**：

- 换平台 = 改一行配置，不是重写业务逻辑
- 想同时支持飞书和钉钉（比如公司用飞书私人用钉钉），只是启用两个 adapter
- 测试时可以写一个 `mock` adapter，不碰真实平台就能跑通整个业务流程

### 2.2 修正后的架构图

```
┌─────────────────────────────────────────────────────────┐
│                      客户端层                            │
│     手机 PWA  │  电脑 PWA  │  浏览器扩展 │  ← 一等公民   │
└────────┬──────────┬───────────┬───────────┬────────────┘
         │          │           │           │ HTTPS
┌────────▼──────────▼───────────▼───────────▼────────────┐
│           Jotlog Server（单二进制，约 20-25MB）          │
│                                                         │
│  ┌──────────────────────────────────────────────────┐   │
│  │  Gin HTTP API  +  PWA 静态资源                    │   │
│  └──────────────────────┬───────────────────────────┘   │
│                         │                                │
│  ┌──────────────────────▼───────────────────────────┐   │
│  │        Channel Adapters（配置启用，可多路并存）     │   │
│  │                                                   │   │
│  │  ┌────────┐  ┌──────────┐  ┌────────┐  ┌──────┐ │   │
│  │  │PWA     │  │ Feishu   │  │DingTalk│  │WeCom │ │   │
│  │  │Adapter │  │Adapter   │  │Adapter │  │Adapter│ │   │
│  │  │HTTP    │  │WebSocket │  │Stream  │  │WS    │ │   │
│  │  └────┬───┘  └─────┬────┘  └────┬───┘  └──┬───┘ │   │
│  └───────┼────────────┼────────────┼─────────┼─────┘   │
│          │            │            │         │          │
│  ┌───────▼────────────▼────────────▼─────────▼─────┐  │
│  │       统一收口：转成 Entry，中间表示唯一          │  │
│  └──────────────────────┬──────────────────────────┘  │
│                         ▼                              │
│  ┌──────────────────────────────────────────────────┐   │
│  │ 归一化管线（异步 worker）                         │   │
│  │  链接抽取 → 类型识别 → 元数据 → AI分类 → 归档     │   │
│  └──────────────────────┬──────────────────────────┘   │
│                         ▼                              │
│  ┌──────────────────────────────────────────────────┐   │
│  │ SQLite (WAL) + FTS5 + attachments/ + Markdown 导出│   │
│  └──────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
        │ 出站长连接（只需能出网，不需要入站）
        ├───> 飞书     open.feishu.cn
        ├───> 钉钉     gateway.dingtalk.com
        └───> 企微     openws.work.weixin.qq.com
```

**关键设计**：所有通道最终都产出同一个 `Entry` 结构，交给同一个归一化管线。**这是从 `libi/tfo` 学到的最重要的一点**（它用 HTTP API 收口所有通道）。这样飞书传来的链接和 PWA 粘贴的链接，走的是完全一样的解析逻辑。

---

## 3. 飞书接入实施（推荐路径）

### 3.1 配置步骤

```
□ 1. 打开 https://open.feishu.cn/ 用飞书账号登录（个人账号即可）
□ 2. 创建应用 → 选「企业自建应用」（不是商店应用）
      名称：随手记
      描述：记录碎片
      图标：≥240×240px，≤2MB
□ 3. 添加应用能力 → 选「机器人」→ 添加
□ 4. 权限管理 → 批量导入 → 粘贴 JSON（见下）→ 申请开通
      ★个人账号即刻生效，企业账号需管理员审核
□ 5. 事件与回调 → 事件配置
      订阅方式：★使用长连接接收事件（WebSocket）
      添加事件：接收消息 v2.0 (im.message.receive_v1)
□ 6. 版本管理与发布 → 创建版本（1.0.0）→ 发布
      ★必须发布！不发布事件订阅不生效
      个人账号发布后直接生效
□ 7. 凭证与基础信息 → 复制 App ID（cli_ 开头）和 App Secret（只显示一次）
```

**权限 JSON**（第 4 步粘贴用）：

```json
{
  "scopes": {
    "tenant": [
      "im:message",
      "im:message:send_as_bot",
      "im:message.p2p_msg:readonly",
      "im:chat.access_event.bot_p2p_chat:read"
    ],
    "user": []
  }
}
```

| 权限 | 用途 |
|---|---|
| `im:message` | 读取消息 |
| `im:message:send_as_bot` | 以机器人身份发消息（回执 + 每日回顾） |
| `im:message.p2p_msg:readonly` | 读取单聊消息 |
| `im:chat.access_event.bot_p2p_chat:read` | 单聊会话事件 |

### 3.2 三个最容易踩的坑

| 坑 | 症状 | 解法 |
|---|---|---|
| **忘了发布版本** | 后台配置都对，但机器人完全无响应 | 第 6 步必须做，且每次改配置后可能要重新发布 |
| **选了「请求地址」而非长连接** | 一直连不上 | 事件订阅那里必须选「使用长连接接收事件」 |
| **群聊没 @ 机器人** | 群里发消息没反应 | 群聊必须 @ 才触发 `im.message.receive_v1`（这是飞书的设计，避免刷屏） |

> ⚠️ 群聊 @ 才触发这个规则，意味着 Jotlog 的记录动作在群里**必须带 @**，比单聊多一步。单聊无此限制。

### 3.3 Go SDK

```bash
go get github.com/larksuite/oapi-sdk-go/v3
```

```go
package feishu

import (
    larkcore "github.com/larksuite/oapi-sdk-go/v3/core"
    larkevent "github.com/larksuite/oapi-sdk-go/v3/event"
    larkim "github.com/larksuite/oapi-sdk-go/v3/service/im/v1"
)

type Adapter struct {
    appID     string
    appSecret string
    handler   channel.Handler
    client    *larkcore.Client
}

func (a *Adapter) Start(ctx context.Context, h channel.Handler) error {
    a.handler = h
    dispatcher := larkevent.NewEventDispatcher("",
        larkevent.WithLogger(larkcore.NewDefaultLogger(...)),
    )
    dispatcher.OnP2MessageReceiveV1(func(ctx context.Context, ev *larkevent.P2ImMessageReceiveV1) error {
        return a.onMessage(ctx, ev)
    })

    // SDK 内部管理长连接：自动连接、心跳、断线重连
    err := dispatcher.Start(ctx,
        larkcore.WithAppCredential(a.appID, a.appSecret),
        larkcore.WithLogLevel(larkcore.LogLevelInfo),
    )
    return err
}
```

> **注意**：飞书 Go SDK 的 `larkevent.EventDispatcher.Start()` 内部已经实现了长连接、心跳、重连。**这比 v0.2 手写企微的退避重连省事得多**——官方 SDK 帮你做了。

### 3.4 消息处理

```go
func (a *Adapter) onMessage(ctx context.Context, ev *larkevent.P2ImMessageReceiveV1) error {
    evv := ev.Event
    chatID := evv.Message.ChatId
    msgID := evv.Message.MessageId
    chatType := evv.Message.ChatType   // p2p / group

    // 群聊必须 @ 机器人才触发；这里再做一次防御
    if chatType == "group" && !strings.Contains(evv.Message.Content, "@") {
        return nil
    }

    // 解析 content（是 JSON 字符串，不是纯文本）
    var content struct {
        Text     string `json:"text"`
        ImageKey string `json:"image_key"`
        FileKey  string `json:"file_key"`
    }
    _ = json.Unmarshal([]byte(evv.Message.Content), &content)

    // 取发送者 open_id —— 单聊需要用它才能发消息
    var openID string
    if id := evv.Sender.SenderId; id != nil {
        if id.OpenId != "" {
            openID = id.OpenId
        } else if id.UserId != "" {
            // 非自建应用 sender_id 是加密的，需要调 API 换明文
            openID = a.resolveOpenID(ctx, id.UserId)
        }
    }

    entry := channel.Entry{
        RawInput: content.Text,
        Source:   "feishu",
        SourceMsgID: msgID,
        ReplyTarget: channel.ReplyTarget{
            Kind:    channel.ReplyDirect,
            ChatID:  chatID,
            MessageID: msgID,
            OpenID:  openID,
        },
    }
    return a.handler(ctx, entry)
}
```

### 3.5 回执与卡片

```go
// 极简文字回执
func (a *Adapter) Reply(ctx context.Context, t channel.ReplyTarget, r channel.Reply) error {
    content, _ := json.Marshal(map[string]string{"text": r.Text})
    _, err := a.client.Im.V1.Message.Create(ctx,
        larkim.NewCreateMessageReqBuilder().
            ReceiveIdType("chat_id").
            Body(larkim.NewCreateMessageReqBodyBuilder().
                ReceiveId(t.ChatID).
                MsgType("text").
                Content(string(content)).
                Build()).
            Build(),
    )
    return err
}
```

**卡片回执（V2 可做）**：飞书的交互卡片可以做成按钮，比如：

```
┌────────────────────────────────┐
│ ✓ 已存                          │
│                                 │
│ karakeep-app/karakeep           │
│ 29.4k · TypeScript              │
│                                 │
│ [ 归档 ]  [ 加标签 ]  [ 打开 ]  │
└────────────────────────────────┘
```

卡片回调走 `card.action.trigger` 事件，**这就是 M4 做回顾交互的基础**。

---

## 4. 钉钉接入（备选）

如果你更熟钉钉，配置比飞书还简单：

```
□ 1. 手机钉钉 → 通讯录 → 创建团队
      ★一个手机号最多建 10 个团队，免费不需认证
□ 2. open.dingtalk.com 扫码登录 → ★顶部切换到刚建的新组织
      ⚠️ 应用创建后绑定组织，挪不走，一定别建错地方
□ 3. 应用开发 → 企业内部应用 → 创建应用（H5 微应用）
□ 4. 添加应用能力 → 机器人 → 消息接收模式选 ★Stream 模式
□ 5. 权限管理：申请消息通知与群会话相关权限
□ 6. 版本管理与发布 → 可用范围选你自己（关键）
□ 7. 凭证与基础信息 → 复制 Client ID + Client Secret（只显示一次）
```

**Stream 模式的官方「五零」承诺**：

- 零公网 IP
- 零加解密/签名/TLS 证书管理
- 零防火墙白名单
- 零网关部署
- 零内网穿透

```bash
go get github.com/open-ding-talk/st-go
```

**注意**：钉钉 Go SDK 生态薄于飞书，官方示例以 Java/Python 为主。如果 Go 里遇到问题，得自己看协议文档。

---

## 5. 配置文件（多通道版）

```toml
# /etc/jotlog/config.toml

[server]
listen   = "127.0.0.1:8080"
token    = "换成至少 32 位随机串"
base_url = "https://jotlog.example.com"

[storage]
data_dir   = "/var/lib/jotlog"
db_path    = "/var/lib/jotlog/jotlog.db"
attach_dir = "/var/lib/jotlog/attachments"

# ============ 通道配置（可同时启用多个）============

[channel.feishu]
enabled  = true                    # ← 主通道
app_id   = "cli_xxxxxxxxxxxx"
app_secret = "xxxxxxxxxxxxxxxx"

[channel.dingtalk]
enabled    = false
client_id  = "dinge9fjs5bijaq65z3m"
client_secret = "xxxxxxxxxxxxxxxx"

[channel.wecom]
enabled    = false
bot_id     = ""
secret     = ""

# 统一回执配置（各通道通用）
[channel.reply]
mode    = "text"                   # text | card
success = "✓ 已存 #{short_id}"
failed  = "✗ 没存上：{error}"
include_summary = true             # 入库成功后异步补充摘要
include_meta    = true             # 补充抓取到的标题/星标数

[enrich]
fetch_opengraph = true
fetch_github    = true
fetch_video     = true
archive_body    = false            # 抓正文最重，默认关

[ai]
enabled  = false                   # 可用本地 Ollama
provider = "ollama"
model    = "qwen2.5:7b"

[review]
enabled           = false          # 每日回顾
push_hour         = 9
random_replay_count = 1             # 随机重现 N 条旧记录
```

**多路并存的样子**：

```toml
[channel.feishu]
enabled = true      # 公司用飞书 → 飞书记
[channel.dingtalk]
enabled = true      # 私人用钉钉 → 钉钉记
```

两路的记录进同一个库，`source` 字段区分来源。**这不是坏事**——反而给了你"哪条是从哪个平台来的"这个信息。

---

## 6. Go 依赖汇总

```go
// 通道（按启用情况选其一或多个）
github.com/larksuite/oapi-sdk-go/v3          // 飞书，官方，成熟
github.com/open-ding-talk/st-go              // 钉钉，生态薄
// 企业微信无官方 Go SDK，需手写 WebSocket 客户端

// WebSocket（自己实现 adapter 时才需要）
github.com/coder/websocket

// 其余全部标准库
crypto/aes, crypto/cipher                    // 媒体解密（如需要）
encoding/base64, encoding/json
golang.org/x/net/html                         // og 标签解析
```

> **好消息**：飞书/钉钉的长连接、心跳、重连都由官方 SDK 内部处理，**不需要自己写退避重连逻辑**。这比 v0.2 手写企微方案少一大块工作量。

---

## 7. 修订后的风险清单

| 风险 | 等级 | 变化 | 应对 |
|---|---|---|---|
| ~~个人微信无法给企微机器人发消息~~ | — | ✅ **已绕开** | 走飞书/钉钉，不再赌这个未知数 |
| 多装一个 App 的习惯成本 | **中** | 新增 | **这是现在唯一的真实风险**。缓解：把飞书 App 置顶，设全局快捷键，电脑端优先用 PWA |
| 群聊必须 @ 机器人 | 低 | 新增 | 单聊无此限制，个人使用场景不受影响 |
| 忘记发布版本导致无响应 | 低 | 新增 | 配置清单里标了星号；启动时健康检查主动探测 |
| Go SDK 生态薄（钉钉） | 低 | 新增 | 优先飞书；钉钉遇到问题可切回 |
| App Secret 泄露 | 中 | — | 只显示一次，存 config.toml 且权限 600，`.gitignore` 排除 |
| 通道平台政策变化 | 低 | 降低 | adapter 抽象，换平台只改配置 |
| 服务器故障 | 低 | — | PWA + 本地 SQLite 可离线用；DB 单文件，备份=复制 |

---

## 8. 修订后的路线图

| 阶段 | 目标 | 交付物 | 预估 |
|---|---|---|---|
| **M0 验证** | 通道可行性 | 飞书建应用 + 60 行最小脚本跑通收发 | **0.5 天** |
| **M1 内核** | 能存能搜 | SQLite + API + PWA 三屏 + FTS5 + Markdown 导出 | 3-5 天 |
| **M2 飞书通道** | 手机随手记 | Feishu adapter + 媒体下载 + 快闪确认 | **1.5 天**（SDK 已包心跳重连） |
| **M3 智能** | 少动手 | 元数据补全 + AI 标签 + 每日回顾推送 | 2-3 天 |
| **M4 生态** | 融入工作流 | 浏览器扩展 + MCP Server + 钉钉 adapter（可选） | 3-5 天 |

**M2 从 v0.2 的 2 天降到 1.5 天**——官方 SDK 省掉了心跳和重连。

---

## 9. 下一步

**今天就能做完 M0，比上一版更快**：

1. 打开 `open.feishu.cn`，用飞书账号登录
2. 创建企业自建应用 → 加机器人能力
3. 批量导入权限 JSON
4. 事件订阅选**长连接**，添加 `im.message.receive_v1`
5. **发布版本**（这步最容易漏）
6. 复制 App ID / App Secret
7. 写个 60 行 Go 脚本（用官方 SDK），连上看能不能收发

比企微方案省事的地方：**不用注册企业、不用建团队、不用配微信插件那三个开关、不用实测未知方向**。

---

## 附：引用文档

| 内容 | 来源 |
|---|---|
| 飞书长连接事件订阅 | `open.feishu.cn` 开发者后台 → 事件与回调 → 事件配置 |
| 飞书 Go SDK | `github.com/larksuite/oapi-sdk-go/v3` |
| 钉钉 Stream 模式「五零」 | `open.dingtalk.com` 文档 → 服务端 Stream 模式 |
| 钉钉 Go SDK | `github.com/open-ding-talk/st-go` |
| 企业微信长连接 | `developer.work.weixin.qq.com/document/path/101463`（v0.2 已详述） |
| 微信插件接收条件 | `open.work.weixin.qq.com/help2/pc/18121` |
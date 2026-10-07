# Jotlog · 初版产品与技术文档

> 版本 v0.1 · 2026-10-07 · 状态：**部分已被 v0.2 取代**
> 一句话：**把微信文件传输助手变成一个真正能检索、能沉淀、跨手机和电脑的随手记收件箱。**

---

> ## ⚠️ 阅读提示（2026-10-07 更新）
>
> 本文档中**第 1、4、5、7 节仍然有效**（现状诊断、产品设计、数据模型、界面设计、差异化功能）。
>
> **以下内容已被 `Jotlog-企业微信通道方案.md`（v0.2）取代，请勿按本文档执行：**
>
> | 本文位置 | 已被取代的原因 |
> |---|---|
> | §3.2 通道 A（ClawBot） | 已选定长连接路线，ClawBot 从候选中划掉 |
> | §3.2 通道 B（企业微信 URL 回调） | **改为长连接模式**，省掉备案域名 + 加解密 + 可信 IP |
> | §5.1 技术选型中的通道层 | 同上 |
> | §5.3 项目结构中的 `internal/channel/ilink/` | 改为 `internal/channel/{feishu,dingtalk,wecom}/` |
> | §6 路线图 M0/M2 | 依赖关系与预估已调整，见 v0.3 §8 |
> | §9 待确认问题 Q1/Q2 | **已确认**：走飞书/钉钉/企微任一长连接，见 v0.3 |
> | §9 待确认问题 Q3（是否开源） | **仍未确认** |

### 版本演进
- **v0.1**（本文档）：竞品调研 + 产品设计 + 数据模型
- **v0.2**（`Jotlog-企业微信通道方案.md`）：企微长连接协议细节 —— **已被 v0.3 降级为可选通道**
- **v0.3**（`Jotlog-多通道接入方案.md`）✅ **当前版本**：飞书/钉钉/企微多通道 adapter 架构

---

## 0. 先说结论

调研了 GitHub 上 2363 个 bookmarks 相关项目 + read-it-later 主题全部主流项目后，结论是三句话：

1. **没有现成项目直接命中你的场景。** 主流项目（Karakeep / linkding / Wallabag / Readeck）都是为「稍后读网页」设计的，不是为「随手记碎片」设计的。你记的不只是链接，还有视频、金句、开源项目、灵感片段——这些是它们的弱项。
2. **但有两个项目值得直接抄。** `libi/tfo` 的产品形态和你想的一模一样（Markdown 纯文件 + 微信 Bot 直达 + 本地优先），只是它 40 star、停更半年、没有手机端；`Memos` 工程成熟度最高（62.8k star、Go 单二进制 20MB、SQLite 零依赖），但没有任何微信通道。
3. **你的最大风险不在技术，在输入通道。** 「文件传输助手」没有官方 API，任何声称能接管它的方案都是逆向/hook，有封号风险。**正确做法是保留「随手转发」这个动作，替换收件箱的落点。**

**我的建议：自建，Go + SQLite + PWA，微信侧走 ClawBot / 企业微信双通道。**

---

## 1. 现状诊断：你为什么现在用文件传输助手

先把你的习惯拆开看，这决定了产品该做什么。

### 1.1 你实际在记的四类东西

你描述的"好用的网站 / 开源项目 / 视频 / 深受影响的话"，本质是**四种不同性质的信息**：

| 类型 | 你的实际动作 | 特征 | 现有工具的短板 |
|---|---|---|---|
| **好网站** | 分享链接 | 有 URL，可抓标题正文 | 书签工具能存，但没时间读 |
| **开源项目** | 分享链接 | 有 URL，GitHub 有 star/语言/描述 | 需要自动补元数据，否则半年后不记得为什么存 |
| **视频** | 分享链接（B站/YouTube） | 有 URL + 时长 + 封面 | Wallabag/Readeck 能抓字幕，但国内 B站支持弱 |
| **金句 / 灵感** | 复制一段文字 | 无 URL，纯文本 | **书签类工具完全无能为力**，这是最容易被丢掉的类型 |

第四类是你的记忆里最值钱、但现有工具最不擅长的一类。一个只会存 URL 的产品，会让你最珍视的那部分内容继续烂在聊天记录里。

### 1.2 现在的三个真实痛点

- **找不回来。** 微信搜索只能按关键词匹配聊天记录，不能按内容语义搜。三个月后记得"有个项目做过类似的事"，但想不起叫什么名字。
- **存了等于没存。** 没有分类、没有标签、没有归档，钱包和灵感混在一起，越存越不想翻。
- **只能看，不能用。** 记下来的东西没有任何二次利用路径——不能导出、不能检索、不能喂给你的 Agent。

### 1.3 顺便说一句：一个反直觉的风险

**微信文件传输助手不是备份工具。** 里面的内容本质上是聊天记录，会被清理、会因为换手机/重装微信而丢失，而且它不是"知识库"——没有任何机制保证三个月后你还能找到它。

如果这些内容里有任何一个你觉得"丢了会心疼"的东西，现在就该导出一份留底。Jotlog 做完之后，数据是你自己的 Markdown 文件，存在你自己的机器上。

---

## 2. 竞品调研

### 2.1 六大主流项目横向对比

数据来源：各项目 GitHub / Codeberg API 与官方文档，核对日期 2026-10-07。

| 项目 | Star | 技术栈 | 部署 | 资源占用 | 许可证 | 致命短板 |
|---|---|---|---|---|---|---|
| **Karakeep**（原 Hoarder） | 29.4k | Next.js + PG + Meilisearch + 无头浏览器 | 3+ 容器 | ~500MB | AGPL-3.0 | 重。对"随手记"是杀鸡用牛刀 |
| **Memos** | 62.8k | Go 单二进制 + SQLite | 单容器 | ~20MB | MIT | 无原生手机 App；无微信通道 |
| **linkding** | 11.3k | Django + SQLite | 单容器 | ~80MB | MIT | 只存链接 + 标签，纯文本记录是二等公民 |
| **Wallabag** | 13k | PHP/Symfony + MySQL | 多容器 | ~256MB | MIT | PHP 栈重；无 AI；界面偏旧 |
| **Readeck** | 1.1k | Go 单二进制 + SQLite | 单二进制 | 轻 | AGPL-3.0 | 冷门；无移动端；无微信通道 |
| **Shiori** | 11.6k | Go + SQLite | 单二进制 | 轻 | MIT | 更新频率低（2024-09 最后发布） |
| **Linkwarden** | 19.9k | Next.js + PG | 多容器 | ~512MB | AGPL-3.0 | 面向团队协作，个人用太重 |

**许可证注意**：Karakeep / Readeck / Linkwarden 都是 AGPL-3.0，如果你打算改造成服务给别人用，必须开放源码。linkding / Memos / Wallabag / Shiori 是 MIT，最友好。

### 2.2 最值得抄的两个

#### `libi/tfo` — 产品形态几乎就是你要的

这是我找到的**最贴合你需求**的项目（MIT，40 star，创建于 2026-04，最后提交 2026-04-20，已停更但代码质量不错）：

- **描述**：极简、本地优先的碎片化记事工具。快捷键一键呼出，随手记录灵感；微信消息直达笔记；数据文本文件存储，无数据库，无云端依赖。
- **形态**：Go + Gin + Bleve 全文索引 + fsnotify 文件监听 + Next.js/Tailwind 前端内嵌进二进制 + 桌面托盘常驻（macOS 用 Swift 壳）+ CLI。
- **能力**：全局快捷键快闪输入、微信 Bot 消息转笔记、纯 Markdown 文件存储、索引可随时重建、跨平台。
- **配置项**：`wechat.enabled` / `wechat.baseUrl`——说明它就是接的 ClawBot 一类的微信 Bot 服务。

**它的致命短板**：只有 macOS / Windows / Linux **桌面端**，完全没有手机端；微信通道依赖外部服务；停更半年。

**可借鉴**：产品定位、Markdown 纯文件存储哲学、"快闪输入"这个交互设计、索引可重建的架构。

#### `Memos` — 工程成熟度天花板

- 62.8k star、MIT、Go 单二进制 ~20MB、SQLite 无 CGO、ConnectRPC 同时提供 HTTP/gRPC、Protobuf 优先设计、内置 MCP server。
- 时间流（timeline-first）交互，天然适合"随手记"。
- **短板**：无原生手机 App（PWA 是妥协）；多标签 AND 筛选有回归 bug；无任何微信通道。

**可借鉴**：单二进制 + SQLite 的部署模型、时间流 UI、Protobuf-first 的 API 设计。

### 2.3 中文社区已有的"微信→笔记"尝试

这几个项目证明了一件事：**需求真实存在，但没人做好跨端**。

| 项目 | 思路 | 问题 |
|---|---|---|
| `wangqingjie/wechat-to-obsidian` | 通过 wx-cli / WeFlow 读本地微信 DB → 导入 Obsidian | 导入桥而非实时同步；只支持 macOS；依赖重签名微信 |
| `captainChaozi/wx-ai-collect` | 微信助手 → AI 分类 → 写入飞书文档 | 需飞书企业账号 + Gemini proxy + Celery；太重 |
| `libi/tfo` | 微信 Bot → 本地 Markdown | 最简洁，但无手机端 |
| 某掘金项目 | 微信助手 → 自动归档 Markdown 到本地笔记库 | 依赖本地微信 DB，触发风控风险；无跨端 |

**共同缺失的部分，就是你的机会：跨手机 + 跨电脑 + 无依赖 + 真检索。**

---

## 3. 微信输入通道：必须讲清楚的硬约束

这一节是整个项目风险最集中的地方，我按调研结果如实列出，**包括那些听起来很美但实际走不通的路子**。

### 3.1 先排除三条死路

| 方案 | 为什么不行 |
|---|---|
| **Hook / 逆向微信 PC 客户端** | 注入 DLL、内存读写，违反平台规则，高封号风险。**不要碰你的主号。** |
| **iPad 协议 / WeChatPadPro / itchat** | 灰产协议，随时失效且违反协议条款。 |
| **接管「文件传输助手」本身** | 它没有开放 API。任何声称"自动归档文件传输助手"的项目，本质都是在读本地加密 DB 或做 Hook。 |

> 调研中发现大量项目宣称"不触发风控、纯本地链路"，实际都是靠解密本地 `message_*.db`。这类方案在新版微信上随时失效，且账号风险不可控。**Jotlog 不采用任何此类方案。**

### 3.2 可行通道（按推荐度排序）

#### 通道 A：微信 ClawBot（官方 iLink Bot API）⭐ 推荐

腾讯 2026 年 3 月正式开放的官方插件，是目前**合规且体验最接近"发给一个聊天对象"**的方案。

- **原理**：底层走腾讯 iLink 协议（`ilinkai.weixin.qq.com`），标准 HTTP/JSON，无需 SDK。
- **入口**：微信 → 我 → 设置 → 插件 → 微信 ClawBot。启用后 Bot 以"普通联系人"形态出现在聊天列表，带 AI 标识。
- **安装**：终端执行 `npx -y @tencent-weixin/openclaw-weixin-cli@latest install`，扫码绑定。
- **能力边界（官方 FAQ 明确）**：

| 项 | 限制 |
|---|---|
| 消息类型 | 文字、图片、语音（语音仅入站 STT，Bot 不能主动发语音） |
| 会话形式 | **仅支持私聊，群聊未开放** |
| 绑定数量 | **一个微信号只能绑一个 ClawBot**（1:1 独占） |
| 时效 | 必须在用户最近一条消息后 **24 小时内**回复，否则送达不到 |
| 主动消息 | 24h 窗口内可用 context_token 主动追消息 |
| 数据访问 | **看不到群聊、朋友圈、其他联系人**，纯消息通道 |
| 合规性 | 走官方插件体系，有《微信 ClawBot 功能使用条款》背书 |

**⚠️ 关键冲突风险**：如果你已经在用 OpenClaw（社区很火的"养龙虾"方案），**ClawBot 名额已被占用**。腾讯后台会自动解绑前一个，旧网关立刻断线。**这一点必须先确认。**

参考实现：`nightsailer/wechat-clawbot`（Python SDK，含 iLink API client、getUpdates 长轮询、AES-128-ECB CDN 加解密媒体、QR 登录、多 Bot 网关）。

#### 通道 B：企业微信自建应用 ⭐ 最稳

- 个人可注册企业微信（填个虚拟公司名即可）。
- 创建自建应用 → 拿到 CorpID / AgentID / Secret / Token / EncodingAESKey。
- 配置 API 接收地址 → 设置「企业可信 IP」→ 个人微信扫码关注「微信插件」。
- **优势**：能进群、能收文件、能主动推送、稳定性远高于 ClawBot。
- **代价**：需要一台公网服务器（2C2G 足够，2核2G）；配置步骤多（约 15 分钟）。

**这是唯一能同时满足"手机随手记"+"电脑同步"+"长期稳定"的方案。**

#### 通道 C：PWA + 全局快捷键（电脑端主力）

- 手机和电脑都是同一个 Web 应用，PWA 可添加到桌面/主屏，天然跨端。
- 电脑端配一个全局快捷键（`Alt+Shift+F`）一键呼出输入框，粘贴即存。
- **这条路完全不依赖微信，是整个系统的兜底保证**——即使微信通道全挂，Jotlog 依然可用。

#### 通道 D：微信小程序（备选，作为未来增强）

微信生态内体验最好，但需要小程序资质、开发与审核成本高，且无法接收微信客户端内的分享动作。**V2 再考虑。**

### 3.3 通道组合策略

```
手机端输入  →  通道 B（企业微信）优先，通道 A（ClawBot）作为备选
电脑端输入  →  通道 C（PWA + 快捷键）为主
微信侧读取  →  通道 A/B 均只支持"用户主动发给 Bot"
              ⚠️ 这意味着：你不能"自动读取历史文件传输助手记录"，必须改变输入动作
```

**这最后一条必须说清楚**：因为官方通道只能接收"发给 Bot 的消息"，而文件传输助手的历史记录无法迁移。所以 Jotlog 上线后，你要做的第一个动作是：**把文件传输助手置顶改名成"Jotlog"，然后把新的东西发给它**。旧记录只能手动导出留底。

---

## 4. 产品设计

### 4.1 核心设计原则

1. **记录成本必须低于微信文件传输助手。** 如果一条记录需要超过 5 秒，产品就是失败的。
2. **不要求你在记录的当下分类。** 分类是事后行为，AI 可以帮你做。
3. **零删除。** 不提供删除操作，只提供归档。记录本身不应该有心理负担。
4. **数据是自己的。** 一键导出全部 Markdown / JSON，不做任何锁定。
5. **只做记录和检索，不做任务管理。** 那是 Todoist 的地盘，不是你的痛点。

### 4.2 核心交互：快闪输入

**这是整个产品的门面交互，来自 tfo 的设计，我要保留并强化。**

- 电脑端任意界面，按下快捷键 → 一个不抢焦点的浮层弹出 → 粘贴/输入 → 回车 → 窗口消失。
- 整屏只有一个输入框，**没有标题、没有标签、没有分类、没有确认按钮**。
- 保存后弹一个极轻的 Toast：`已存 · [回车撤销]`，3 秒后自动消失。
- 手机端：给企业微信/ClawBot 发消息，Bot 回一个 `✓ 已存 #123`，就结束了。

> **为什么这样设计**：你的记录是「电光石火」的。任何在记录当下要求你做分类/起标题的设计，都会让你在 3 天内放弃。所以 Jotlog 把"整理"这件事整个推到事后，由 AI + 时间流帮你完成。

### 4.3 数据模型

核心是**一条通用的 `entries` 表 + 类型化的 `meta`**，而不是为每种类型建表。

```sql
-- 主表：所有记录
CREATE TABLE entries (
  id           TEXT PRIMARY KEY,      -- ULID，天然按时间有序
  type         TEXT NOT NULL,         -- link | video | repo | text | image | file
  raw_input    TEXT NOT NULL,         -- 原始输入文本，永不丢失
  title        TEXT,
  summary      TEXT,
  url          TEXT,
  domain       TEXT,
  content      TEXT,                  -- 抓取或摘录的正文
  thumbnail    TEXT,                  -- 本地相对路径
  attachment   TEXT,                  -- 本地相对路径（图片/文件）
  tags         TEXT,                  -- JSON 数组，手动 + AI 合并
  starred      INTEGER DEFAULT 0,
  archived     INTEGER DEFAULT 0,
  source       TEXT,                  -- wechat_bot | wecom | pwa | extension | api
  client_id    TEXT,                  -- 去重用（防多端重复提交）
  created_at   INTEGER NOT NULL,      -- Unix ms
  updated_at   INTEGER NOT NULL
);

CREATE INDEX idx_entries_created ON entries(created_at DESC);
CREATE INDEX idx_entries_type    ON entries(type);
CREATE INDEX idx_entries_domain  ON entries(domain);
CREATE VIRTUAL TABLE entries_fts USING fts5(
  title, content, raw_input, tags,
  content='entries', content_rowid='rowid'
);

-- 附件元数据
CREATE TABLE attachments (
  id          TEXT PRIMARY KEY,
  entry_id    TEXT NOT NULL REFERENCES entries(id),
  filename    TEXT,
  mime        TEXT,
  size        INTEGER,
  sha256      TEXT UNIQUE,           -- 内容去重
  local_path  TEXT NOT NULL
);

-- 归档导出目录（每日一个 Markdown 文件）
CREATE TABLE exports (
  date        TEXT PRIMARY KEY,       -- YYYY-MM-DD
  path        TEXT NOT NULL,
  entry_count INTEGER,
  created_at  INTEGER NOT NULL
);
```

**设计要点：**

- **`raw_input` 永不删除。** 即使自动解析失败，原始输入一定保存。这是数据可信度的底线。
- **`client_id` 唯一约束做幂等。** 多端同时提交同一条不会重复入库（配合 `ContentHash`）。
- **FTS5 外部内容表。** 索引和数据同源，删条目索引自动同步，索引可随时 `rebuild`。
- **附件按 sha256 去重。** 同一张图发两次只存一份。

### 4.4 自动归一化管线

收到一条原始输入后，服务端按顺序执行（**全异步，任何一步失败都不阻塞保存**）：

```
原始文本
  ↓
① 链接抽取      正则提取 URL（支持裸域名、分享文案包裹、多链接）
  ↓
② 类型识别      GitHub URL → repo
                B站/YouTube/腾讯视频 → video
                其他 URL → link
                纯文本 → text
  ↓
③ 元数据补全    抓 og:title / og:description / og:image
                GitHub API → star / 语言 / 最后提交时间 / topics
                视频 → 标题 / 封面 / 时长
  ↓
④ AI 分类       纯文本 → 生成 1-3 个标签 + 一句话摘要
                （可选用本地 Ollama，零成本零隐私泄露）
  ↓
⑤ 正文归档      可选（配置开关）：抓正文 + 截图存本地
                默认关闭——这是最重的一步，不该默认开
  ↓
⑥ 入库 + 建索引
```

**关键设计：①② 同步执行（<200ms，用户立刻拿到 ✓），③④⑤⑥ 异步执行。**

### 4.5 界面：只有三屏

**刻意做减法。** 一个记录工具最容易死在"功能太多导致找不到入口"。

```
┌─────────────────────────────────────────┐
│ ①  时间流（首页）                       │
│                                         │
│  今天 · 10月7日                         │
│  ┌─────────────────────────────────┐    │
│  │ [thumb] karakeep-app/karakeep   │    │
│  │ 29.4k · TypeScript · 自托管书签  │    │
│  │ #收藏 #开源 #待读                 │    │
│  └─────────────────────────────────┘    │
│  ┌─────────────────────────────────┐    │
│  │ "把复杂留给自己，把简单留给用户"  │    │
│  │ #金句                             │    │
│  └─────────────────────────────────┘    │
│                                         │
│  10月6日                                │
│  ...                                    │
├─────────────────────────────────────────┤
│ ②  搜索                                │
│  [ 输入关键词...          ]             │
│  时间 / 类型 / 标签 / 归档状态 筛选条    │
│  结果列表（同卡片样式）                  │
├─────────────────────────────────────────┤
│ ③  我的                                │
│  归档导出 / 每日回顾 / 通道配置 / 数据导出 │
└─────────────────────────────────────────┘
```

底部三个 Tab，没有第四个。没有设置页的二级菜单，没有标签管理页，没有收藏夹管理页。**标签在搜索页里管就够了。**

### 4.6 每日回顾（对抗"存了不看"）

这是把工具从"收藏夹"变成"有用的东西"的关键机制：

- 每天早上（或你指定的时刻）生成一条摘要：**昨天记了什么 + 一条随机重现的旧记录**。
- 随机重现很重要：它让你重新遇到三个月前存的那个项目，可能那时你正需要它。
- 通过企业微信通道推送（ClawBot 受 24h 窗口限制，推不动）。
- **这一条你可以先不做**，但它决定了这个工具是"=web 收藏夹"还是"= 你的第二大脑"。

---

## 5. 技术架构

### 5.1 技术选型

| 层 | 选型 | 理由 |
|---|---|---|
| 语言 | **Go 1.25+** | 单二进制、零依赖、跨平台交叉编译。tfo/Memos/Readeck 都选它，生态成熟 |
| 存储 | **SQLite**（WAL 模式） | 单文件、免部署、备份=复制文件。FTS5 满足中文全文检索需求 |
| HTTP 框架 | **Gin** | tfo 用的就是它，轻量够用 |
| 前端 | **PWA**（Preact 或 Svelte + Tailwind） | 刻意不用 Next.js——不需要 SSR，要的是极小体积和离线可用 |
| 长轮询 | **原生 goroutine + HTTP 客户端** | 对接 iLink `getUpdates`，无需额外依赖 |
| 元数据抓取 | `net/http` + `golang.org/x/net/html` | 抓 og 标签；不引入无头浏览器（太重） |
| 部署 | 单二进制 + systemd / Docker | 一个文件丢上去就能跑 |

**为什么不用 Java/Spring Boot**：你熟 Java，但这个项目的核心不是企业应用，是**单文件、零依赖、跨平台**。Go 的交叉编译和 20MB 单二进制是 Java 做不到的。而且 Jotlog 和你主线（Agent 编排引擎）是两个独立项目，不该互相绑死技术栈。

### 5.2 架构图

```
┌─────────────────────────────────────────────────────┐
│                   客户端层                            │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │
│  │ 手机 PWA │  │ 电脑 PWA │  │ 浏览器扩展(可选)  │  │
│  └────┬─────┘  └────┬─────┘  └────────┬─────────┘  │
└───────┼─────────────┼────────────────┼─────────────┘
        │             │                │
        └─────────────┼────────────────┘
                      │ HTTPS / JSON API
┌─────────────────────▼────────────────────────────────┐
│                  Jotlog Server（单二进制）              │
│                                                      │
│  ┌────────────┐  ┌─────────────┐  ┌──────────────┐  │
│  │ Gin Router │  │ Auth 中间件  │  │ 静态资源(PWA) │  │
│  └─────┬──────┘  └─────────────┘  └──────────────┘  │
│        │                                           │
│  ┌─────▼───────────────────────────────────────┐   │
│  │ 入口适配层（Entry Adapters）                 │   │
│  │ ┌────────┐ ┌────────┐ ┌──────────┐         │   │
│  │ │ PWA    │ │iLink   │ │ 企业微信   │         │   │
│  │ │ Adapter│ │Adapter │ │ Adapter  │         │   │
│  │ └────┬───┘ └───┬────┘ └────┬─────┘         │   │
│  └──────┼─────────┼───────────┼───────────────┘   │
│         ▼         ▼           ▼                    │
│  ┌──────────────────────────────────────────────┐  │
│  │ 归一化管线（异步 worker）                      │  │
│  │  链接抽取 → 类型识别 → 元数据 → AI分类 → 归档  │  │
│  └──────────────────┬───────────────────────────┘  │
│                     ▼                              │
│  ┌──────────────────────────────────────────────┐  │
│  │ 存储层                                        │  │
│  │  SQLite (WAL) + FTS5 + 本地 attachments/     │  │
│  └──────────────────────────────────────────────┘  │
│                     │                              │
│  ┌──────────────────▼───────────────────────────┐  │
│  │ 导出器：每日 Markdown 归档 / 全文 JSON 导出    │  │
│  └──────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

### 5.3 项目结构

```
jotlog/
├── cmd/jotlog/main.go           # 单二进制入口
├── internal/
│   ├── server/                   # Gin 路由、中间件
│   │   ├── router.go
│   │   ├── auth.go
│   │   └── static.go
│   ├── entry/                    # 核心领域模型
│   │   ├── model.go              # Entry 结构体 + 类型定义
│   │   ├── repo.go               # SQLite CRUD
│   │   ├── normalize.go          # 归一化管线
│   │   ├── classify.go           # 类型识别
│   │   └── dedupe.go             # 去重逻辑
│   ├── channel/                  # 输入通道适配
│   │   ├── adapter.go            # 接口定义
│   │   ├── ilink/                # 微信 ClawBot
│   │   │   ├── client.go         # getUpdates 长轮询
│   │   │   ├── media.go          # AES-128-ECB CDN 加解密
│   │   │   └── qrlogin.go        # 扫码绑定
│   │   ├── wecom/                # 企业微信自建应用
│   │   │   └── adapter.go        # 签名校验 + XML 解析
│   │   └── pwa/                  # Web 端提交
│   ├── enrich/                   # 元数据补全
│   │   ├── opengraph.go          # og 标签抓取
│   │   ├── github.go             # GitHub API
│   │   └── video.go              # B站/YouTube
│   ├── ai/                       # AI 分类（可选）
│   │   └── ollama.go
│   ├── search/                   # FTS5 查询
│   ├── export/                   # 归档导出
│   └── config/                   # 配置管理
├── web/                          # PWA 前端源码
│   ├── index.html
│   ├── app.js                    # 原生 JS or Preact
│   └── sw.js                     # Service Worker
├── data/                         # 运行时数据（.gitignore）
│   ├── jotlog.db
│   └── attachments/
├── config.example.toml
├── Makefile
├── Dockerfile
└── README.md
```

### 5.4 API 设计

刻意极简，只暴露必要的接口。

```http
# 认证：单一 Token，Header 携带
Authorization: Bearer <token>

# 列表（时间流）
GET    /api/v1/entries?cursor=&limit=30&type=&tag=&archived=
GET    /api/v1/entries/:id
POST   /api/v1/entries            # 创建（各通道统一入口）
PATCH  /api/v1/entries/:id        # 编辑/打标/归档/收藏
DELETE /api/v1/entries/:id        # 软删除（进回收站，30天清理）

# 搜索
GET    /api/v1/search?q=&type=&tag=&from=&to=

# 归档
GET    /api/v1/exports
POST   /api/v1/exports/:date       # 手动触发某天归档

# 数据自主权
GET    /api/v1/export/markdown     # 全量 Markdown zip
GET    /api/v1/export/json         # 全量 JSON
GET    /api/v1/health
```

**统一入口的价值**：所有通道（微信、企业微信、PWA、未来的浏览器扩展）最终都调 `POST /api/v1/entries`，归一化逻辑只有一份。这是从 tfo 的架构里学到的最重要的一点。

### 5.5 部署

**最简形态（推荐）**：

```bash
# 二进制 20MB，无依赖
scp jotlog root@your-server:/usr/local/bin/
ssh root@your-server
```

```toml
# /etc/jotlog/config.toml
[server]
listen = "127.0.0.1:8080"
token  = "你的随机长token"

[storage]
data_dir    = "/var/lib/jotlog"
db_path     = "/var/lib/jotlog/jotlog.db"
attach_dir  = "/var/lib/jotlog/attachments"

[channel.ilink]
enabled = true          # ClawBot

[channel.wecom]
enabled = false
corp_id = ""
agent_id = ""
corp_secret = ""
token = ""
encoding_aes_key = ""

[enrich]
fetch_opengraph = true
fetch_github    = true
fetch_video     = true
archive_body    = false   # 默认关闭，抓正文最重
archive_screenshot = false

[ai]
enabled = false
provider = "ollama"      # 或 "openai"
model = "qwen2.5:7b"
```

Nginx 反代 + HTTPS（企业微信和 ClawBot 都要求公网 HTTPS 回调）。

---

## 6. 差异化功能（V2+，按优先级）

这些不是必须的，但构成产品护城河。

| 优先级 | 功能 | 说明 |
|---|---|---|
| **P0** | 浏览器扩展 | 右键"存入 Jotlog"、划词存入。这是电脑端最高频的输入方式 |
| **P0** | 每日回顾推送 | 见 4.6，对抗"存了不看" |
| **P1** | 自动归档 Markdown | 每天生成 `archive/2026-10-07.md`，可直接被 Obsidian 吃 |
| **P1** | 语义搜索 | 本地 embedding（bge-small），比 FTS5 强一个量级 |
| **P1** | 双链 | `#引用` 语法 + 反向链接视图，接 Obsidian 生态 |
| **P2** | MCP Server | **把你的笔记库喂给你的 Coding Agent**。这条对你个人价值极高——你在自研 Agent，笔记可以直接作为 Agent 的记忆层 |
| **P2** | 微信文章/公众号链接解析 | 你会存公众号文章，需要专门适配 |
| **P3** | 图片 OCR | 截图里的文字提取入库 |

> **P2 的 MCP Server 值得单独说一句**：你在自研 Coding Agent，也在做 Java Agent 编排引擎。Jotlog 如果提供 MCP 接口，你所有 Agent 项目都能直接查你的笔记。这是一个纯自己受益的差异化点。

---

## 7. 路线图

| 阶段 | 目标 | 交付物 | 预估 |
|---|---|---|---|
| **M0 验证** | 确认输入通道可用 | 跑通 ClawBot 或企业微信收发，确认你的微信号没被占用 | 0.5 天 |
| **M1 内核** | 能存能搜 | SQLite 模型 + API + PWA 三屏 + 全文检索 + Markdown 导出 | 3-5 天 |
| **M2 微信通道** | 手机随手记 | ClawBot 或企业微信适配 + 媒体下载 + 快闪确认 | 2-3 天 |
| **M3 智能** | 少动手 | 元数据补全 + AI 标签 + 每日回顾 | 2-3 天 |
| **M4 生态** | 融入工作流 | 浏览器扩展 + MCP Server + Obsidian 归档 | 3-5 天 |

**M0 是 gate，必须先做。** 如果你的微信号 ClawBot 名额已被 OpenClaw 占用，整个 M2 就要改走企业微信，架构上没问题但部署复杂度上升。

---

## 8. 风险清单

| 风险 | 等级 | 应对 |
|---|---|---|
| **ClawBot 名额被占 / 官方策略变化** | 高 | 双通道并行（PWA 兜底永不依赖微信）；企业微信作备选 |
| **微信入口不如文件传输助手顺手** | 中 | 把 Bot 置顶、命名成"随手记"；**承认这个动作必须改变**，无法自动化 |
| **ClawBot 无法主动推送** | 中 | 每日回顾走企业微信；或只做「打开 App 时看到回顾」 |
| 长期不整理导致库变乱 | 中 | 事后分类 + 随机重现，不在记录当下要求分类 |
| 自托管服务器成本 | 低 | 2C2G 轻量服务器约 ¥60-80/月；也可在家跑 |
| AGPL 传染性 | 低 | 核心代码自写不 fork；仅参考 MIT 项目（tfo/Memos） |

---

## 9. 待你确认的三个问题

这三个问题的答案会实质改变架构，需要你先定：

### Q1：你的微信号 ClawBot 名额是否已被占用？

如果你已经在用 OpenClaw 养龙虾，ClawBot 就绑不了，微信通道必须走企业微信。**建议 M0 第一件事就是去微信「设置 → 插件」看一眼有没有 ClawBot。**

### Q2：ClawBot vs 企业微信，你倾向哪个？

| | ClawBot | 企业微信 |
|---|---|---|
| 配置耗时 | 2 分钟 | 约 15 分钟 |
| 需要服务器 | 不需要（本机跑即可） | 需要公网 IP |
| 能进群 | 不能 | 能 |
| 能收文件 | 有限 | 能 |
| 主动推送 | 不能（24h 窗口） | 能 |
| 稳定性 | 依赖官方插件策略 | 高 |

**我的建议：ClawBot 先跑通验证（M0），如果不够用再上企业微信。** PWA 通道无论如何都要做，它是系统的地基。

### Q3：这个项目是纯自用，还是打算开源？

- **纯自用**：技术选型可以完全按你舒服来，AGPL 顾虑不存在，可以直接 fork Karakeep 改。
- **打算开源**：应该自写内核、参考 MIT 项目设计，且需要认真做 README 和部署体验——这两个决定它能不能有 star。

---

## 附录：核心信息源

| 项目 | 地址 | 许可证 | 核对日期 |
|---|---|---|---|
| Memos | `usememos/memos` | MIT | 2026-10-07 |
| tfo | `libi/tfo` | MIT | 2026-10-07 |
| linkding | `sissbruecker/linkding` | MIT | 2026-10-07 |
| Karakeep | `karakeep-app/karakeep` | AGPL-3.0 | 2026-10-07 |
| Readeck | `codeberg.org/readeck/readeck` | AGPL-3.0 | 2026-10-07 |
| Wallabag | `wallabag/wallabag` | MIT | 2026-10-07 |
| Linkwarden | `linkwarden/linkwarden` | AGPL-3.0 | 2026-10-07 |
| ClawBot Python SDK | `nightsailer/wechat-clawbot` | — | 2026-10-07 |
| 微信→Obsidian | `wangqingjie/wechat-to-obsidian` | — | 2026-10-07 |
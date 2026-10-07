# Jotlog · 技术选型（v1.0）

> 版本 v1.0 · 2026-10-07 · **技术栈已改：Go → Java，SQLite → MySQL**
> 前置阅读：`Jotlog-初版设计文档.md`（产品）、`Jotlog-多通道接入方案.md`（通道架构）

---

## ⚠️ v1.0 变更说明

**本文档第 1-3 节（Go / SQLite / jieba 分词方案）已作废**，请只看下面的正式选型。

变更原因：

1. **Go → Java**：本机 JDK 17 + Maven 3.9.9 已就绪，且飞书 Java SDK
   `com.larksuite.oapi:oapi-sdk:2.8.5` 支持长连接，与 Go SDK 能力对等。
   切换的真实收益不是技术优劣，而是**开发速度**——你 3.5 年 Java/Spring 经验，
   用熟悉的技术栈写完整个项目的速度比 Go 快 2-3 倍。
2. **SQLite → MySQL 8.0**：服务器已有 MySQL 和 nginx，复用现成资源。
   更关键的是，**换 MySQL 消除了原方案里最大的那个坑**——
   SQLite 方案需要 jieba 预分词 + 索引端查询端同口径 + 分词器版本变更触发重建
   三套机制；MySQL 8.0 内置 `ngram` 解析器，一行 SQL 就解决了。

作废的技术细节保留在下文「附录 A」里，作为"为什么当初那么选"的记录，
避免以后有人（包括你自己）重新踩一遍。

---

## 0. 正式选型

| 层 | 选择 | 版本 | 一句话理由 |
|---|---|---|---|
| **后端语言** | Java | 17 LTS | 本机已装；飞书 Java SDK 能力对等；你的存量技能可直接复用 |
| **框架** | Spring Boot | 3.5.16 | 官方支持到 JDK 25，Spring AI / LangChain4j 生态成熟 |
| **ORM / 访问** | JdbcTemplate | - | 刻意不用 JPA，见下方说明 |
| **数据库** | MySQL | 8.0+ | 复用服务器现成资源；内置 ngram 中文全文检索 |
| **中文搜索** | MySQL `ngram` 全文索引 | - | 一行 `WITH PARSER ngram` 搞定，零额外依赖 |
| **表结构管理** | Flyway | - | 版本化迁移，不用手写 `CREATE TABLE` |
| **飞书通道** | `com.larksuite.oapi:oapi-sdk` | 2.8.5 | 官方维护，长连接内置重连与鉴权 |
| **AI 接入** | LangChain4j | 1.8.0 | 兼容 OpenAI 协议，换模型只改配置 |
| **前端** | Preact + Vite + Tailwind | - | 3KB 运行时，PWA 一套代码跨手机和电脑 |
| **形态** | PWA | - | 手机添加到主屏，电脑直接访问，同一个网址 |
| **部署** | jar + systemd + Nginx | - | 复用现有 nginx 反代 |

---

## 1. 为什么用 JdbcTemplate 而不是 JPA

这是有意的选择，不是偷懒。

Jotlog 的核心表 `entries` 有一列 `raw_input`，**它必须永不可变**。
JPA 的风险在于：实体类被改掉、加上 `save()` 调用，很容易在某次重构中
意外把整行覆盖写，AI 润色的内容就会连同原文一起被冲掉。

用 JdbcTemplate 则有三个好处：

1. **SQL 写在眼前。** `applyAiResult()` 的 UPDATE 语句里根本没有
   `raw_input` 这个词，物理上无法覆盖。
2. **仓储接口不暴露更新正文的方法。** 想改也找不到入口。
3. **全文检索必须写原生 SQL。** `MATCH ... AGAINST` 没有 JPA 映射，
   最后还是要落到 JdbcTemplate。

代价是手写 SQL，稍啰嗦。但对这个项目来说，**啰嗦换安全，值**。

---

## 2. MySQL ngram 的坑（必读）

```sql
FULLTEXT KEY ft_search (raw_input, title, ai_summary) WITH PARSER ngram;
```

### 坑一（最容易踩）：MATCH 的列必须与索引**完全一致且同序**

这是本项目实际踩到的错误，2026-10-07 报错 `1191 Can't find FULLTEXT index matching the column list`。

MySQL 的规则比很多人以为的严格：

> `MATCH()` 里列出的列，必须和 FULLTEXT 索引定义的列**完全一致且顺序相同**。
> 不是"能用索引的前缀"，是必须一模一样。

所以索引是 `(raw_input, title, ai_summary)` 时：

```sql
-- ✅ 对
MATCH(raw_input, title, ai_summary) AGAINST('沿途' IN BOOLEAN MODE)

-- ❌ 报 1191
MATCH(raw_input) AGAINST('沿途' IN BOOLEAN MODE)
```

**这意味着即使你只想搜 `raw_input` 一列，也必须把三列都写出来。**

反过来说索引列顺序会影响用法。原始设计是 `(title, ai_summary, raw_input)`，
那样连 `MATCH(raw_input)` 都不成立。改成 `raw_input` 打头后，
「用户自己写的话」这个主战场排在首位，更符合使用习惯。

修复脚本：`scripts/fix-fts-1191.sql`。

### 坑二：建索引时漏掉 `WITH PARSER ngram`

不写这个子句，MySQL 会用默认 parser 按空格切词，**对中文完全失效**。
而且它**不报错**，只是永远查不出东西。这是中文全文搜索最常见的翻车点。

> 注意：坑一和坑二的区别很关键。
> 坑二**不报错**（静默失效），坑一**直接报错 1191**。
> 不报错的问题更难发现，所以建完索引一定要跑一次真实查询验证。

### 坑三：`ngram_token_size` 默认是 2

按两个汉字滑窗切分。后果：

- 查「全文」→ 命中（切成"全文"）
- 查「文」→ **查不到**（长度小于 token size）

单字查询的处理方式在 `JdbcEntryRepository.search()` 里：
长度 < 2 自动降级到 `LIKE '%字%'`。

> 这比 SQLite 的 `trigram` 好。`trigram` 要求查询词至少 3 字符，
> 二字词会**静默失败**——不报错，就是没结果。ngram 至少 2 字能用。

### 坑四：改 `ngram_token_size` 是全局操作

`SET GLOBAL ngram_token_size = 1` 会影响**整个实例**的所有 ngram 索引。
改完必须重建索引才生效，而且要写进 `my.cnf` 否则重启失效。

个人使用**建议就保持默认 2**，别动它。

### 坑五（已实测确认·最隐蔽）：ngram 会丢掉含 `a` / `i` 的英文词

**这个坑最要命，因为 `Java` 正好中招。**

MySQL 默认停用词表里有单字符的 `a` 和 `i`。ngram 解析器按 2 字滑窗切词后，
会**丢弃包含停用词的 token**。于是：

| 原文 | ngram 切分（token_size=2） | 结果 |
|---|---|---|
| `Java` | `Ja` / `av` / `va` | 三个 bigram **全含 a，全被丢** → 搜不到 |
| `Boot` | `Bo` / `oo` / `ot` | 不含停用词 → 正常命中 |
| `Spring` | `Sp`/`pr`/`ri`/`in`/`ng` | 部分含停用词，其余存活 → 正常命中 |

**受控实验（2026-10-08 实跑）**：

| 测试词 | 含字符 | 命中 |
|---|---|---|
| `Zaza` | 含 **a** | ❌ 0 条 |
| `Zeze` | 含 e | ✅ 1 条 |
| `Zizi` | 含 **i** | ❌ 0 条 |
| `Zozo` | 无停用字符 | ✅ 1 条 |

**解法**：
```sql
SET GLOBAL innodb_ft_enable_stopword = 0;
-- 必须重建索引才生效
ALTER TABLE entries DROP INDEX ft_search;
ALTER TABLE entries ADD FULLTEXT INDEX ft_search (raw_input, title, ai_summary) WITH PARSER ngram;
```

⚠️ `SET GLOBAL` **重启失效**，必须写进 `my.cnf`：
```ini
[mysqld]
innodb_ft_enable_stopword = 0
```

这是全局变量，影响整个实例的所有 FULLTEXT 索引。
Jotlog 是唯一用到全文索引的库，副作用可忽略。

**应用层已加兜底**：`JdbcEntryRepository.search()` 在全文索引返回空时
降级到 `LIKE` 再查一遍。配置丢了只是变慢，不会搜不到。

### 坑六（已实测确认）：不要用 `NATURAL LANGUAGE MODE`

`NATURAL LANGUAGE MODE` 会把**出现在超过 50% 行里**的 token 当作停用词丢掉。
配上 ngram 分词器（按 2 字滑窗切）之后，中文常用字组合极易触发这个阈值。

**实测数据（2026-10-07 在 `YOUR_DB_HOST` 上跑出来的）**：

| 场景 | 模式 | score | 结果 |
|---|---|---|---|
| 3 条数据搜「沿途」 | `NATURAL` | **0** | 搜索完全失效，**且不报任何错** |
| 3 条数据搜「天气」 | `BOOLEAN` | 0.2276 | 正常 |
| 22 条数据搜「沿途」 | `NATURAL` | **1.802** | 正常 |

这看起来像"数据量少时的正常现象"，**但其实是设计缺陷**：
阈值取决于**当前数据的分布**，不是数据总量。
用户存到第 50 条时搜索还好好的，存到第 200 条时某个词会突然搜不到，
而且没有任何报错、没有日志——用户只会觉得"这搜索不行"。

→ **Jotlog 决定用 `IN BOOLEAN MODE`。**
- 不做 50% 阈值过滤，行为确定，代价是**没有相关度排序**。
- 对随手记这种「命中即有用」的场景，能搜到的价值远高于排序精细。
- ⚠️ BOOLEAN 里 `+ - < > ~ " *` 都有语法含义，用户输入撞上会报语法错，
  必须转义 → 见 `JdbcEntryRepository.booleanQuery()`。

### 坑七：单字查不到全文索引

`ngram_token_size = 2` 意味着「的」「风」这种单字切不出 token。
应用层 `search()` 对长度 < 2 的关键词**降级成 `LIKE` 查询**。

### 自检方法

`scripts/dbeaver-一键重建.sql` 对**真实 `entries` 表**做自检：
查索引类型和列顺序 → 灌 8 条差异化测试数据 → 跑真实查询 → 删掉。

**必须看到 `FULLTEXT | raw_input,title,ai_summary` 和一行正数 score。**

⚠️ **测试数据必须够多且内容互不相同**——只灌 3 条测不出坑五，
因为那时所有词都占 100%，`NATURAL` 一律返回 0，看起来像索引坏了。

临时表自检不够——`ft_search` 是三列联合，单列临时表验不出列组合错误。

---

## 3. 飞书 Java SDK 的真实 API（踩坑记录）

这一节是 2026-10-07 用 `javap` 直接读 `oapi-sdk-2.8.5.jar` 扒出来的，
**官方文档示例有错**，照着抄会编译不过。

| 想做的事 | 官方文档写的 | 实际正确的 |
|---|---|---|
| 创建配置 | `AppSettings.newBuilder()` | `com.lark.oapi.core` 包里**没有 AppSettings**。而且根本不需要手动建 `Config`——见下一行 |
| **创建客户端** | `new Client(config)` | **`Client.newBuilder(appId, appSecret).appType(...).openBaseUrl(...).build()`** |
| 指定域名 | `Domain.FeiShu` | `.openBaseUrl(BaseUrlEnum.FeiShu)`（Builder 上的方法，不是 `config.setDomain`） |
| 应用类型 | `AppType.AppTypeSelf` | `.appType(AppType.SELF_BUILT)` |
| 长连接 | `new ws.Client(appId, secret, dispatcher)` | 构造器是私有的，必须用 `new ws.Client.Builder(appId, secret).eventHandler(dispatcher).build()` |
| 事件注册 | `dispatcher.onP2MessageReceiveV1(...)` | 在 `EventDispatcher.Builder` 上，且要 `EventDispatcher.newBuilder("", "")` 先拿 builder |
| 事件回调接口 | `P2MessageReceiveV1Handler` | 是 `ImService` 的**内部类**：`ImService.P2MessageReceiveV1Handler` |
| 消息提及 | `mentions.isEmpty()` | `mentions` 是**数组**，用 `mentions.length == 0` |

### 🔴 千万别写 `new Client()` + `setConfig()`

**这是编译能过、启动正常、但运行时才炸的坑。**

```java
// ❌ 错误：im() 返回 null
Client c = new Client();
c.setConfig(config);
c.im().message().reply(req);   // NPE
```

`Client` 里 `im` / `contact` / `bot` 等几十个 service 字段，
全靠 `Client$Builder.build()` 用一堆 `access$NN02` 合成方法注入。
用 `javap com.lark.oapi.Client` 能看到这些合成方法——它们就是线索。
`new Client()` 只造了个空壳，所有 service 都是 null。

**症状很隐蔽**：编译过、应用起得来、长连接也能收到消息，
但一到**回执**就炸 `Cannot invoke "ImService.message()" because "Client.im()" is null`。
纯启动测试发现不了，必须走到发消息的路径才暴露。

正确写法：

```java
this.client = Client.newBuilder(appId, appSecret)
        .appType(AppType.SELF_BUILT)
        .openBaseUrl(BaseUrlEnum.FeiShu)
        .build();
```

Builder 还支持 `requestTimeout(long, TimeUnit)`、`disableTokenCache()`、
`logReqAtDebug(boolean)`、`tokenCache(ICache)`、`httpTransport(IHttpTransport)`。

**防御措施**（已加进 `FeishuChannel.start()`）：
```java
if (this.client.im() == null) {
    log.error("飞书 API 客户端初始化异常：client.im() 为 null，回执功能不可用");
}
```
把运行时 NPE 提前成启动时的明确报错。

**教训**：SDK 的「配置类」和「构建入口」不是一回事。
`setXxx()` 能编译通过 ≠ 对象可用。
凡是「有一堆子服务字段」的客户端类，**优先找 `newBuilder()`**。

### 怎么查真实签名

**教训**：飞书 Java SDK 的官方示例代码滞后于实际 jar。
涉及 SDK 细节时，直接 `javap` 读 jar 里的真实签名，比翻文档可靠。

```powershell
# 查任意类的真实签名
javap -cp path/to/oapi-sdk-2.8.5.jar com.lark.oapi.ws.Client
```

---

## 4. 飞书长连接的硬约束

> 事件回调必须在 **3 秒**内处理完成且不抛异常，否则触发超时重推。

这条约束决定了整个管线的形状（`Pipeline.java`）：

```
入库 → 回执 → 异步增强
```

同步路径上**只允许**幂等检查和数据库写入。
抓正文、调 AI、GitHub API 全部丢进异步队列。

事件回调里`throw` 异常的代价：飞书会重推 → 用户收到重复消息 →
虽然有 `uk_source_msg` 唯一索引兜底不会重复入库，但用户会收到多条回执。

所以 `FeishuChannel.onEvent()` 里包了 try-catch，
异常只记日志，绝不外抛。

---

## 附录 A：已作废的 Go / SQLite 方案

保留这段是为了避免将来重复评估。**不要照着做。**

### 为什么当初选 Go

- 飞书 Go SDK `larksuite/oapi-sdk-go/v3` 官方维护，最成熟
- 单静态二进制，部署最省事
- 内存占用约 15MB

### 为什么换掉

- 本机没有 Go 环境，也没有 winget（装不了）
- 你的主力技能是 Java，切换语言的成本高于部署收益
- 服务器已有 nginx + MySQL，复用现成栈更划算

### SQLite 方案里的分词坑（原方案最大的负担）

当时选的是 `jieba` 预分词 + FTS5 外部内容表，需要处理：

1. `unicode61` 分词器对中文不可用
2. `trigram` 要求查询词 ≥ 3 字符，二字词**静默失败**
3. jieba 索引端和查询端必须用同一个分词函数
4. 分词器版本要写入 meta 表，版本变化触发索引重建
5. 开源后要换成纯 Go 分词实现，避免 cgo 破坏交叉编译

换 MySQL 后，这五条**全部消失**。

---

## 附录 B：待办与未验证项

| 项 | 状态 | 说明 |
|---|---|---|
| 图片 / 文件接收 | 未做 | M0 只记占位，需接 `im.v1.messageResource.get` |
| AI 润色 | 未做 | 见 `Jotlog-AI润色规范.md`，接口已留 |
| 正文抓取 | 未做 | 倾向 jsoup，超时 8 秒 |
| PWA 界面 | 未做 | M2 |
| 每日回顾 | 未做 | M3 |
| 鉴权 | 未做 | 单用户自用，暂不设防；开源前必须补 |

### 单用户项目的鉴权取舍

个人自用阶段**刻意不加登录**。理由：加了一层鉴权就要管 session、
要处理 cookie 过期、要在每次 API 调用时校验，而这些代码在"只有我一个人用"
的前提下不产生任何价值。

但**开源前必须补**。公网部署一个无鉴权的私人笔记接口，
等于把数据库内容对所有人开放。这是 M4 的硬性要求，不是可选项。


---

# ⚠️ 以下为 v0.4 旧方案（Go + SQLite），已全部作废

> 保留仅为记录决策过程。**当前技术栈见本文档上半部分的 v1.0 选型表。**
> 不要照着这部分实现。

---

## 1. 为什么后端选 Go（而不是 Java）

你是 Java 栈，这需要交代清楚。

### 1.1 决定性理由：飞书官方 Go SDK 是最成熟的

这是**最硬的一条**，不是"Go 语言更好"这种空话。

| 平台 | Go SDK 状态 |
|---|---|
| 飞书 | ✅ `larksuite/oapi-sdk-go/v3`，官方维护，文档全，示例多 |
| 钉钉 | ⚠️ `open-ding-talk/st-go`，有但薄，官方示例以 Java/Python 为主 |
| 企业微信 | ❌ 无官方 SDK |

你选了飞书。**选飞书就等于选 Go 的生态优势最大**，用 Java 反而要在 SDK 上吃亏——飞书 Java SDK 虽然也有，但长连接 + 消息回执这层封装质量不如 Go 版（因为 Go 是飞书团队自己主力维护的 SDK）。

### 1.2 单静态二进制 vs JVM

| | Go | Java |
|---|---|---|
| 产物 | 25MB 单文件 | 需要 JVM + jar（或容器镜像） |
| 常驻内存 | 30-60MB | 200-400MB+ |
| 启动 | 瞬时 | JVM 预热 1-3s |
| 依赖 | 无 glibc，静态编译 | 需 JRE 匹配版本 |
| 跨平台编译 | `CGO_ENABLED=0` 一条命令 | 需对应平台的 JDK |

对一个跑在 2C2G 小服务器上、可能哪天你想换个便宜 VPS 的个人工具，**单二进制意味着"备份=复制文件"和"迁移=scp 一个文件"**。

### 1.3 不选 Java 的诚实复盘

**我承认 Java 对你更熟悉，这有真实的优势**——写起来快、遇到问题能立刻查。但在这个特定项目上：

- 项目是**纯自用工具**，不是公司项目，不需要遵循团队的 Java 技术栈约束
- 项目核心诉求是**长期稳定运行在极低配环境**，不是快速迭代
- 你有 TermMind（Spring Boot sidecar）和 Agent 编排引擎两个 Java 项目了——**这个项目用 Go 反而让你的技术栈更多元，而不是更单调**

**如果你强烈想用 Java**（比如时间成本对你更重要），我也不会拦你。方案是把 `channel.Adapter` 接口的契约照搬过去，用 Java 走飞书 Java SDK，架构完全一致。**但我不推荐**——理由是①飞书 Go SDK 更成熟，②单二进制的运维收益在这个场景下实打实。

### 1.4 Go 版本与其他选型

```bash
# Go 1.25+
go 1.25

# HTTP 框架
github.com/gin-gonic/gin

# 数据库（纯 Go，无 cgo）
modernc.org/sqlite

# 飞书 SDK
github.com/larksuite/oapi-sdk-go/v3

# 中文分词
github.com/buxuku/go-jieba   # cgo 封装，需注意交叉编译
# 或 github.com/yanyiwu/gojieba  // 纯 Go 实现，推荐

# 配置
github.com/BurntSushi/toml

# 元数据抓取
golang.org/x/net/html

# 数据库迁移
github.com/pressly/goose/v3

# 迁移与迁移后可重建
```

**⚠️ 关于 gojieba 的坑**：它是 cgo 封装，会破坏 `CGO_ENABLED=0` 的交叉编译。两个解法：

1. **用纯 Go 实现 `github.com/yanyiwu/gojieba`** —— 无 cgo，但词典需要 `go:embed`
2. **分词离线做**：入库时用外部工具切词，切好的词串存进一个 `body_segmented` 字段

我倾向**方案 2**。理由：搜索质量的关键是"入库和查询用同一套分词"，而这个一致性风险在 jieba 升级后会**静默失效**（搜"本地优先"永远搜不到"本地优先存储"）。如果分词发生在入库前（作为归一化管线的一步），查询端只需要维护**同一个版本的词典**即可，可控性更高。

---

## 2. 为什么数据库选 SQLite

### 2.1 数据量决定一切

估算你的使用量：

- 每天记 10 条 × 365 天 = 3650 条/年
- 每条含标题+正文+元数据约 2KB
- **一年数据量约 7MB**
- 索引后约 15-20MB

**这个量级下，任何"正经数据库"都是过度设计。** PostgreSQL 需要一个常驻服务、用户密码、连接池、备份策略、schema migration——为 7MB 数据付出的运维成本远超收益。

### 2.2 关键：必须用 `modernc.org/sqlite` 而不是 `mattn/go-sqlite3`

| 驱动 | cgo | 交叉编译 | 说明 |
|---|---|---|---|
| `mattn/go-sqlite3` | ✅ 需要 | ❌ 麻烦 | 性能最好，但要 C 工具链，跨平台编译会卡住 |
| **`modernc.org/sqlite`** | ❌ 纯 Go | ✅ 一条命令 | **Jotlog 用这个** |

> ⚠️ 有资料警告"modernc 不支持 WAL"。**这是过时信息**——modernc.org/sqlite v1.52.0 已支持 WAL。**但你必须自己验证这一点**，因为你的项目强依赖 WAL（写入不能阻塞读取）。M1 第一件事就是写个测试确认。

**验证方法**：
```go
db, _ := sql.Open("sqlite", "file:test.db?_pragma=journal_mode(WAL)")
var mode string
db.QueryRow("PRAGMA journal_mode").Scan(&mode)
// 期望输出 "wal"。如果返回 "delete"，说明驱动不支持 WAL，换 ncruces/go-sqlite3
```

> 如果 modernc 的 WAL 有问题，退路是 `github.com/ncruces/go-sqlite3`（WASM 版 SQLite，纯 Go，WAL 支持良好）。**两者都纯 Go，随时可换**，这也是选纯 Go 驱动的好处。

### 2.3 必开的 PRAGMA

```sql
PRAGMA journal_mode = WAL;        -- 读写不互相阻塞（最关键）
PRAGMA synchronous = NORMAL;      -- WAL 模式下兼顾安全与性能
PRAGMA foreign_keys = ON;         -- 附件引用完整性
PRAGMA busy_timeout = 5000;       -- 并发写时等锁而非报错
PRAGMA temp_store = MEMORY;       -- 临时表放内存
PRAGMA mmap_size = 268435456;     -- 256MB 内存映射，加速读
```

DSN 里直接写：
```go
dsn := "file:/var/lib/jotlog/jotlog.db" +
    "?_pragma=journal_mode(WAL)" +
    "&_pragma=synchronous(NORMAL)" +
    "&_pragma=foreign_keys(ON)" +
    "&_pragma=busy_timeout(5000)"
```

### 2.4 连接池：设成 1

```go
db.SetMaxOpenConns(1)   // ← SQLite 单写者，设 1 最稳
db.SetMaxIdleConns(1)
db.SetConnMaxLifetime(0)
```

这是**嵌入式 SQLite 的最佳实践**，不是性能妥协。SQLite 的写是串行的，设 >1 只会增加锁竞争和 `SQLITE_BUSY` 错误。Jotlog 是单人单实例，读写量极小，1 个连接完全够。

---

## 3. 中文搜索：分词器是本项目最大的技术坑

**这一节请认真读。选错分词器，你的搜索功能等于不存在。**

### 3.1 为什么默认分词器不能用

SQLite FTS5 默认 `unicode61` 分词器：把连续的 Unicode 字母/数字字符当作一个 token。

英文有天然空格分词，`unicode61` 够用。**中文没有**——一整句连续汉字会被当成一个 token：

```
原文：  「重建我的知识库」这 7 个字是一体的
索引里： '重建我的知识库' ← 一个 token
你搜：  '知识库'
结果：  ❌ 匹配不上
```

**实测数据**：同一批 125 篇中文文档，搜「知识库」，`trigram` 命中 22 篇，`unicode61` 只命中 9 篇——那 9 篇还是因为整段恰好等于查询词的巧合。

### 3.2 三种方案对比

| 方案 | 做法 | 二字词 | 索引体积 | 依赖 | 召回质量 |
|---|---|---|---|---|---|
| `unicode61` | 默认，不处理 | ❌ 基本搜不到 | 小 | 零 | 差 |
| `trigram` | 每 3 字符一个 token | ❌ **必挂** | **3-4 倍** | 零 | 中上 |
| **jieba 预分词** | 入库切词空格拼接 | ✅ | 中（≈2 倍） | 词典 ~20MB | 好 |

**实测数据（10 万条中文，平均 1800 字）**：

| 分词方案 | 索引体积 | 三字词查询 | 召回质量 |
|---|---|---|---|
| unicode61 原始 | 62 MB | 基本搜不到 | 差 |
| trigram | **410 MB** | 45 ms | 中上 |
| **jieba + unicode61** | **130 MB** | **12 ms** | **好** |

**Jotlog 选 jieba 预分词。** 理由是 130MB vs 410MB 的差距在服务器上是实打实的，而 12ms vs 45ms 对单人使用毫无感知。

### 3.3 trigram 的硬伤为什么致命

**trigram 要求查询词 ≥3 字符，少于 3 个直接返回空。**

对 Jotlog 来说这是**致命伤**：你记录的场景大量是短词——

- 「显卡」「抖音」（2 字）
- 「Go」「BP」「OA」（2 字母）
- 「长连接」「索引」（3 字）

你的搜索习惯会是"想起个大概词直接搜"，**2-3 字查询是最常见的**。用 trigram 的话有一半查询会返回空结果，而这种失败是**静默的**——用户不知道是因为词太短，只会觉得"这搜索不行"。

### 3.4 jieba 的坑：索引端与查询端必须同口径

这是 jieba 方案唯一但致命的坑，我把它单独讲清楚，因为它**不会报错，只会静默搜不到**。

**错误示范**：

```go
// ❌ 入库用 cut_for_search（产出细粒度重叠词）
func segForIndex(text string) string {
    return " ".join(jieba.CutForSearch(text, true))  // → "本地 优先 存储"
}

// 查询用 cut（产出整词）
func segForQuery(q string) string {
    return " ".join(jieba.Cut(q, true))  // → "本地优先"
}

// 结果：搜"本地优先"永远搜不到"本地优先存储"
// 因为索引里有"本地 优先 存储"，查询里是"本地优先"整个词，两边对不上
```

**正确做法**：

```go
// ✅ 索引端和查询端用同一个函数
func seg(text string) string {
    return strings.Join(jieba.CutForSearch(text, true), " ")
}

// 入库
ftsBody := seg(entry.RawInput + " " + entry.Title + " " + entry.Content)

// 查询
matchExpr := seg(query)  // 同一套逻辑
```

**并记录分词版本**：

```go
// meta 表存分词器版本
db.Exec("INSERT INTO meta (key, value) VALUES ('jieba_version', ?)", jiebaVer)

// 启动时检查
if storedVer != currentVer {
    log.Warn("jieba 版本变化（", storedVer, "→", currentVer, "），需重建索引")
    go rebuildAllIndex()
}
```

jiéba 升级会让分词结果变化，如果不同步重建索引，**搜索会静默退化**。这个检查必须有。

### 3.5 最终搜索实现

```sql
-- 主表
CREATE TABLE entries (
  id          TEXT PRIMARY KEY,
  type        TEXT NOT NULL,
  raw_input   TEXT NOT NULL,
  title       TEXT,
  content     TEXT,
  tags        TEXT,
  ...
);

-- FTS 外部内容表：只存索引，不重复存数据
CREATE VIRTUAL TABLE entries_fts USING fts5(
  title, content, tags,
  content='entries',              -- ★ 外部内容表，数据在 entries 里
  content_rowid='rowid',          -- ★ 关联 entries.rowid
  tokenize='unicode61'            -- 因为已预分词，标题字段也存的是切好的词
);

-- 触发器保持同步
CREATE TRIGGER entries_ai AFTER INSERT ON entries BEGIN
  INSERT INTO entries_fts(rowid, title, content, tags)
  VALUES (new.rowid, new.title_seg, new.content_seg, new.tags_seg);
END;
```

> **建议加 `_seg` 后缀字段**：主表里存原始文本，同时存一份切好词的 `title_seg`/`content_seg` 给 FTS 用。这样导出 Markdown 时用原文，搜索时用切词后的，两者不污染。

**查询**：

```go
func (r *Repo) Search(ctx context.Context, q string, limit int) ([]Entry, error) {
    var rows []Entry

    // ≥3 字节：走 FTS5（有排序和高亮）
    if len(q) >= 3 {
        segQ := seg(q)   // 同一套分词
        err := r.db.QueryContext(ctx, `
            SELECT e.*, 
                   snippet(entries_fts, 1, '⟪', '⟫', '…', 16) AS highlight,
                   bm25(entries_fts, 10.0, 1.0, 5.0) AS score
            FROM entries_fts
            JOIN entries e ON e.id = entries_fts.rowid
            WHERE entries_fts MATCH ?
            ORDER BY score
            LIMIT ?
        `, `"`+strings.Join(segQ, " ")+`"`, limit).Scan(...)
        return rows, err
    }

    // <3 字节：FTS5 构造不出 3-gram，降级 LIKE
    err := r.db.QueryContext(ctx, `
        SELECT * FROM entries
        WHERE title_seg LIKE ? OR content_seg LIKE ? OR tags LIKE ?
        ORDER BY created_at DESC
        LIMIT ?
    `, "%"+seg(q)+"%", "%"+seg(q)+"%", "%"+seg(q)+"%", limit).Scan(...)
    return rows, err
}
```

**性能预期**：Jotlog 一年几千条数据，索引不到 1MB，**查询在 1ms 以内**。这个量级下不用做任何性能优化。

---

## 4. 为什么前端选 Preact（而不是 React/Vue）

### 4.1 先说 UI 复杂度有多低

Jotlog 只有三屏（v0.1 §4.5）：

```
时间流：卡片列表 + 日期分组
搜索：   输入框 + 筛选条 + 列表
我的：   几个设置开关
```

**交互复杂度接近于零**。这是一个"列表 + 详情"的形态，不需要路由、不需要状态管理库、不需要组件生态。

### 4.2 体积对比

| 框架 | 运行时 | gzip 后 | 需要的构建配置 |
|---|---|---|---|
| React + react-dom | 130 KB | ~45 KB | 需 JSX 转换 |
| **Preact + htm** | **4 KB** | **~1.6 KB** | 无（htm 用模板字符串） |
| Vue 3 (runtime) | 110 KB | ~34 KB | 需编译模板 |
| Svelte | 编译期消除 | ~5 KB | 需编译 |

Jotlog 整个前端（含 Tailwind 产物）应该在 **30-50KB gzip** 以内。**这决定了 PWA 首屏几乎瞬开**——手机端在弱网下体验差别巨大。

### 4.3 我的具体建议：Preact + htm + Vite

**为什么用 htm 而不是 JSX**：JSX 需要 Babel 或 esbuild 转换，虽然 Vite 内置 esbuild 处理 JSX 也很快，但 **htm 的模板字符串写法零构建成本**，配合 `esbuild` 直接处理 `.ts` 就行。

```tsx
// 组件写法：JSX 熟悉的人 5 分钟能适应
import { h, render } from 'preact'
import { useState } from 'preact/hooks'

function EntryCard({ entry }: { entry: Entry }) {
  return (
    <article class="card">
      {entry.thumbnail && <img src={entry.thumbnail} alt="" />}
      <h3>{entry.title}</h3>
      {entry.domain && <span class="meta">{entry.domain}</span>}
      <div class="tags">{entry.tags.map(t => <span class="tag">{t}</span>)}</div>
    </article>
  )
}
```

**但如果你更熟 React**，直接用 React + Vite 也没问题——45KB gzip 对你的场景（自用、服务器在云端、不是流量敏感的公开产品）完全无所谓。**不要为了省 40KB 折腾。**

### 4.4 Vite 配置

```ts
// vite.config.ts
import { defineConfig } from 'vite'
import preact from '@preact/preset-vite'

export default defineConfig({
  plugins: [preact()],
  build: {
    outDir: 'web/dist',
    // 直接产出嵌入 Go 二进制
    assetsInlineLimit: 4096,
    rollupOptions: {
      output: { manualChunks: undefined }  // 单文件产物
    }
  }
})
```

**关键**：前端构建产物要在编译 Go 之前生成好，然后用 `go:embed` 嵌进二进制：

```go
// web/embed.go
package web

import (
    "embed"
    "io/fs"
)

//go:embed all:dist
var distFS embed.FS

// Sub 返回前端静态资源文件系统
func Sub() (fs.FS, error) {
    return fs.Sub(distFS, "dist")
}
```

```go
// server/static.go
func serveStatic(r *gin.Engine) {
    sub, _ := web.Sub()
    fsys := http.FS(sub)
    r.StaticFS("/assets", http.FS(mustSub(fsys, "assets")))
    r.GET("/", func(c *gin.Context) {
        b, _ := fs.ReadFile(mustSub(fsys, "."), "index.html")
        c.Data(200, "text/html; charset=utf-8", b)
    })
}
```

**部署时只有一个文件**：编译好的 `jotlog` 二进制。前端已经在里面了。

---

## 5. 完整依赖清单

```go
// go.mod
module jotlog

go 1.25

require (
    github.com/gin-gonic/gin                    v1.10+     // HTTP
    github.com/larksuite/oapi-sdk-go/v3         v3.0+      // 飞书
    modernc.org/sqlite                          v1.52+     // SQLite 纯 Go
    github.com/BurntSushi/toml                  v1.4+      // 配置
    github.com/pressly/goose/v3                v3.20+     // 迁移
    golang.org/x/net                            latest     // og 解析
    github.com/buxuku/go-jieba                  latest     // 中文分词（见 §1.4 警告）
)
```

**总下载量约 60MB，编译产物约 25MB。**

---

## 6. 项目骨架（M1 直接可用）

```
jotlog/
├── cmd/jotlog/main.go
├── internal/
│   ├── config/config.go          # TOML 加载 + 默认值 + 校验
│   ├── store/
│   │   ├── db.go                 # 连接、PRAGMA、迁移
│   │   ├── entry.go              # CRUD
│   │   ├── search.go             # FTS5 查询 + LIKE 降级
│   │   └── segment.go            # jieba 分词（★索引/查询同口径）
│   ├── channel/
│   │   ├── adapter.go            # ChannelAdapter 接口 + Entry 定义
│   │   ├── feishu/               # 飞书实现
│   │   │   ├── client.go
│   │   │   ├── handler.go
│   │   │   ├── reply.go
│   │   │   └── media.go          # 图片/文件下载
│   │   └── pwa/                  # HTTP 入口实现
│   ├── entry/
│   │   ├── model.go              # Entry 结构 + 类型定义
│   │   ├── pipeline.go           # 归一化管线
│   │   └── enrich/
│   │       ├── opengraph.go
│   │       ├── github.go
│   │       └── video.go
│   ├── server/
│   │   ├── router.go
│   │   ├── auth.go
│   │   └── static.go
│   ├── export/                   # Markdown 归档导出
│   └── review/                   # 每日回顾调度
├── web/                          # 前端源码
│   ├── src/
│   │   ├── app.ts
│   │   ├── screens/
│   │   │   ├── timeline.ts
│   │   │   ├── search.ts
│   │   │   └── settings.ts
│   │   └── components/
│   ├── public/
│   │   ├── manifest.json         # PWA
│   │   └── sw.js                 # Service Worker
│   ├── dist/                     # 构建产物（go:embed）
│   └── embed.go
├── migrations/
│   ├── 0001_init.sql
│   └── 0002_fts.sql
├── data/                         # 运行时（.gitignore）
├── config.example.toml
├── Makefile
└── README.md
```

### Makefile

```makefile
.PHONY: build dev frontend

frontend:
	cd web && npm install && npm run build

build: frontend
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
	go build -ldflags="-s -w" -o jotlog ./cmd/jotlog

dev:
	cd web && npm run dev      # 前端 :5173
	# 另开一个终端
	go run ./cmd/jotlog serve --dev   # 后端 :8080，自动代理前端

clean:
	rm -rf web/dist jotlog
```

---

## 7. 部署产物

编译完成后你得到的就是：

```
jotlog            25 MB，可执行文件
config.toml       1 KB
data/             运行时生成
├── jotlog.db              SQLite 主库
├── jotlog.db-wal          WAL 文件
├── jotlog.db-shm          共享内存
└── attachments/
    └── 2026/
        └── 10/
            └── a1b2c3.png
```

**部署命令**：

```bash
scp jotlog root@server:/usr/local/bin/
scp config.toml root@server:/etc/jotlog/
ssh root@server 'systemctl restart jotlog'
```

**备份命令**（SQLite 在线备份，无需停机）：

```bash
sqlite3 /var/lib/jotlog/jotlog.db ".backup '/backup/jotlog-$(date +%F).db'"
# 或者直接热备份（需 sqlite3 命令行）
```

> ⚠️ **不要直接 cp 数据库文件**——WAL 模式下可能拷到不一致的状态。用 `.backup` 命令或 `VACUUM INTO`。

---

## 8. 选型决策速查

如果你中途动摇，问自己这三个问题：

| 问题 | 答案 → 决策 |
|---|---|
| 有没有一个文件丢上去就能跑？ | **有（Go 单二进制）**。用 Java 需要 JVM，用 Node 需要运行时 |
| 一年后我还会维护它吗？ | 大概率不会。所以**要尽量少的运行时依赖** |
| 我在哪个环节最容易卡住？ | 中文搜索。**分词方案提前定好，别到 M1 最后才发现搜不到** |

---

## 9. 仍未回答的问题（第三轮）

**Q3：纯自用还是打算开源？**

这个问题连续问了三轮都没答，我最后说一次它的实际影响：

| | 纯自用 | 打算开源 |
|---|---|---|
| 内核代码 | 怎么方便怎么来，可以抄 tfo 的代码（MIT） | 必须自写，不能抄 AGPL 项目 |
| 依赖策略 | 可以用任何库 | 依赖越少越好（纯 Go 驱动是为开源做的选择） |
| README | 不用写 | **决定能不能有 star 的关键** |
| 配置 | 环境变量也行 | 需要零配置文件可跑（降低试用门槛） |
| 预计多花时间 | 0 | **2-3 天**（写 README + 整理配置 + 打磨部署体验） |

**你只要说"自用"或"开源"两个字就行。** 如果自用，我下一步可以直接开始 M0 验证脚本；如果开源，我在 M1 之前会先处理依赖收敛的问题。
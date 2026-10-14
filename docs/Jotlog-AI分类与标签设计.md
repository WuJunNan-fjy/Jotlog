# Jotlog AI 分类与标签设计

> 状态：**M1 已实现（2026-10-13）** —— 后端 25 项单测全绿、前端生产构建通过；待真实 MySQL + DeepSeek Key 端到端验证
> 关联：`Jotlog-AI润色规范.md`、`V3__ai_classification.sql`、`PolishQueue.java`
> 范围：分类体系（单选）+ 标签体系（多选）+ AI 增强链路落地（多链接抽取、素材抓取、DeepSeek 分类总结）

---

## 一、需求与结论

用户转发一段混杂内容（抖音口令 + GitHub 地址 + 官网 + npm 包名）时，
Jotlog 应能自动回答两个问题：

1. **这是什么** —— 归入一个分类（如"开源工具"），打上若干标签（如"逆向工程"）。
2. **讲了什么** —— 用一两句人话总结，三个月后回看不用重新点开所有链接。

讨论后确定的关键决策：

| 决策点 | 结论 |
|---|---|
| 组织模型 | **分类（单选，做导航）+ 标签（多选，做细粒度检索）** |
| 分类治理 | **预设分类为主，AI 只能选已有分类；不合适时给"新分类建议"，用户一键采纳** |
| 模型 | **DeepSeek**（OpenAI 兼容协议，LangChain4j 接入，换厂商只改配置） |
| Key 存放 | **配置文件 / 环境变量**，不进 Git；设置页只显示配置状态，不回显 |
| 多链接 | **保持一条记录**，抽取全部链接分头抓取，AI 综合成一条摘要 |
| 数据模型 | **实体化**：categories / tags / entry_tags 新表 |
| 纯文本笔记 | 默认**不调 AI**（省钱、不污染原话）；设置页提供开关"AI 也为笔记总结分类"，开启后立即对存量笔记补跑 |

---

## 二、数据流

铁律不变：**入库 → 回执 → 异步增强**。当前 `PolishQueue` 只攒不消费，本次接上真实 worker。

```
PolishQueue（现有）
  └─ PolishWorker（新增，单线程串行消费，避免限流）
       ① 抽链接   改造 Extractor：抽出 raw_input 中【全部】URL（现在只抽第一个）
       ② 抓素材   按域名分头抓取，单个超时 8 秒，失败只丢素材、不影响主流程
                    · github.com       → GitHub API：仓库描述 + README 前若干字符
                    · registry.npmjs   → npm registry API：包名/描述/官网
                    · 视频站（抖音等） → 跟随短链重定向，只取 <title>/OG 标题，不碰正文
                    · 其他网页         → jsoup 取 OG title/description + 正文首段
       ③ AI 整理  素材 + 现有分类清单 + 常用标签 → DeepSeek → 强校验 JSON
       ④ 回写     title / ai_summary / ai_tags（原始快照）+ category_id + entry_tags
```

以"rea 反编译工具"转发为例，预期产物：

- **分类**：开源工具
- **标签**：`逆向工程` `反编译` `rea-agents` `开源`（3–5 个）
- **标题**：rea —— 开源反编译/逆向分析工具
- **摘要**：rea 是 morluto 开源的本地软件反编译工具，提供 npm 包 `rea-agents`
  （`npx rea-agents@latest setup`）与官网 rea.tools；抖音图文（flywave）的分享文案
  称"试用后本地软件不再有秘密"。视频画面内容不可得，不臆测。

### 主体类型判定（多链接时）

一条消息含多个链接时，`entry_type` 按主体优先级归类：**repo > video > link**。
上例含 GitHub 链接 → `repo`；但所有链接都抽取、都抓取、都进 AI 素材。

---

## 三、数据库变更（V3 迁移）

```sql
-- 分类（单选，用户预设为主）
CREATE TABLE categories (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64) NOT NULL,
    color       VARCHAR(16) NULL,          -- 前端色点，预留
    sort_order  INT         NOT NULL DEFAULT 0,
    is_system   TINYINT(1)  NOT NULL DEFAULT 0,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 标签（多选，自由生成 + 归一化复用）
CREATE TABLE tags (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64) NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tag_name (name)
);

CREATE TABLE entry_tags (
    entry_id    BIGINT NOT NULL,
    tag_id      BIGINT NOT NULL,
    PRIMARY KEY (entry_id, tag_id),
    KEY idx_tag (tag_id),
    CONSTRAINT fk_et_entry FOREIGN KEY (entry_id) REFERENCES entries(id) ON DELETE CASCADE,
    CONSTRAINT fk_et_tag   FOREIGN KEY (tag_id)   REFERENCES tags(id)   ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 一条记录的多个链接与各自抓取结果（素材落库，重跑 AI 不必重复抓取）
CREATE TABLE entry_links (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    entry_id       BIGINT       NOT NULL,
    url            VARCHAR(2048) NOT NULL,
    domain         VARCHAR(255) NULL,
    kind           VARCHAR(16)  NOT NULL,   -- repo / video / npm / web
    fetched_title  VARCHAR(512) NULL,
    fetched_excerpt TEXT        NULL,
    fetch_status   VARCHAR(16)  NOT NULL DEFAULT 'pending', -- pending/done/failed/skipped
    fetched_at     DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_el_entry (entry_id),
    CONSTRAINT fk_el_entry FOREIGN KEY (entry_id) REFERENCES entries(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 网页可改的用户偏好（键值表，首版只存 ai_polish_notes）
CREATE TABLE user_settings (
    `key`       VARCHAR(64)  NOT NULL,
    `value`     VARCHAR(255) NULL,
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE entries ADD COLUMN category_id BIGINT NULL AFTER entry_type;
ALTER TABLE entries ADD KEY idx_category (category_id);
ALTER TABLE entries ADD CONSTRAINT fk_entry_category
    FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE SET NULL;
```

字段约定：

- `entries.category_id = NULL` 表示**待分类**（含 note 与 AI 尚未处理的条目）。
- `entries.ai_tags`（V1 已有，逗号分隔字符串）**保留**，存当次 AI 输出的原始标签快照，
  便于排查"AI 当时给了什么"；真实可检索标签以 `tags` / `entry_tags` 为准。
- AI 建议了新分类但用户未采纳前，建议名暂存 `ai_error` 之外的专用位置：
  新增可空列 `entries.suggested_category VARCHAR(64) NULL`，采纳后清空。
- 迁移同时写入初始分类种子（见第七节）。

---

## 四、AI 接口设计

### 配置（`application.yml`，真值走 local/prod 覆盖文件与环境变量）

```yaml
jotlog:
  ai:
    enabled: ${JOTLOG_AI_ENABLED:false}
    base-url: ${JOTLOG_AI_BASE_URL:https://api.deepseek.com}
    api-key: ${JOTLOG_AI_KEY:}
    model: ${JOTLOG_AI_MODEL:deepseek-chat}
    # 纯文本笔记是否也做 AI 总结分类（默认关）
    polish-notes: ${JOTLOG_AI_POLISH_NOTES:false}
    fetch-timeout-seconds: 8
```

- `enabled=false` 或 key 为空：worker 不启动，条目安静停在 `pending`，Jotlog 完全可用。
- `polish-notes` 同时可在**设置页**切换。V3 一并新增极简键值表 `user_settings(key VARCHAR 主键, value VARCHAR)`，
  网页改动落库后作为运行时真值覆盖配置文件默认值；首版只存 `ai_polish_notes` 一个键，不为单条偏好建专表。

### 模块划分

| 组件 | 职责 |
|---|---|
| `LinkExtractor`（改造现有 Extractor 或新增多链接方法） | 抽出全部 URL、去尾随标点、去重、判 kind |
| `LinkFetcher`（接口）+ `GitHubFetcher` / `NpmFetcher` / `VideoTitleFetcher` / `WebFetcher` | 各源抓取，统一返回 `FetchedLink(title, excerpt, status)` |
| `AiClient` | LangChain4j 封装，唯一与模型对话的地方；换厂商不动其他代码 |
| `PolishWorker` | 消费 PolishQueue：抓取 → 组 prompt → 调 AI → 校验 → 回写 |
| `CategoryService` / `TagService` | 分类标签的查询、归一化建标、建议采纳 |

### Prompt 骨架（在现有规范骨架上扩展）

```
你在整理用户的私人随手记。

规则：
1. 只依据提供的材料，不补充材料之外的信息；材料不足就写"信息不足"，不要猜测。
2. category 必须从给定分类清单中选一个，返回其 id；都不合适时返回 null，
   并在 suggestedCategory 给出一个 <=6 字的新分类建议名。
3. tags 优先复用"常用标签"清单中的原词；确需新词时 3–5 个，每个 <=6 字。
4. summary 不超过 200 字，中文，平实，不要营销腔。
   视频类没有正文时，只能描述用户转发文案与页面标题，明确是"分享文案称…"。
5. title 不超过 40 字。

输入：
- 用户原话：{raw_input}
- 链接素材：{每个链接的 kind/url/title/excerpt}
- 分类清单：{id: name}
- 常用标签：{最近使用最多的 N 个}

只输出 JSON：
{"categoryId":12,"suggestedCategory":null,"title":"","summary":"","tags":["",""]}
```

### 校验与失败处理（沿用现有规范红线）

- JSON 解析失败 / 字段超长 / categoryId 不在清单 / 标签数量超限 → 视为失败，不写半成品。
- `ai_status`：`pending / processing / done / failed / skipped`。
  - note 且 `polish-notes=false` → `skipped`，不调模型。
- 失败写 `ai_error`，不自动无限重试（最多 2 次、间隔递增），网页端可单条手动重跑。
- 记录耗时 `ai_latency_ms` 与模型名 `ai_model`。

---

## 五、前端改动

- **侧边栏（SideNav）**：星标/归档下方新增"分类"分组，每项名称 + 条数 + 色点，
  底部固定"待分类"（显示数量，为 0 时隐藏数字）；窄轨只显示色点。
- **路由**：`/c/:id`（按分类）、`/tag/:name`（按标签），复用 TimelineView 的列表能力。
- **条目卡片（EntryCard）**：分类名/色点、AI title、最多 3 个标签 chip；
  `processing` 显示细微加载点，`failed` 显示可点的重试入口。
- **详情面板（EntryDetailPanel）**：
  - 分类下拉（可手动改选）、新分类建议条（采纳 / 忽略）；
  - 标签可删、可加（输入时复用已有标签）；
  - "重新 AI 分析"按钮；失败显示 `ai_error` 与重试。
- **设置页（SettingsView）**：
  - AI 状态区：是否启用、key 是否就位（只显示"已配置/未配置"）、模型名；
  - "AI 也为笔记总结分类"开关；
  - 冷启动引导（见下）与"批量补跑"入口（扫描 pending，每条间隔 1–2 秒、可中断、进度可见）。
- **冷启动引导**：categories 为空时，设置页出现"让 AI 基于现有内容提议初始分类"：
  AI 抽样 raw_input 后建议 6–8 个分类 → 用户勾选/改名确认 → 写入种子。
  用户跳过则直接使用内置种子（第七节）。

---

## 六、API 增量（初版）

| 方法/路径 | 作用 |
|---|---|
| `GET /api/categories` | 分类清单（含各分类条数、待分类数） |
| `POST /api/categories` `PUT /api/categories/{id}` | 新建 / 改名改色（M1 仅基础能力，合并在 M2） |
| `GET /api/tags?q=` | 标签联想（详情页加标签用） |
| `PUT /api/entries/{id}/category` | 手动设置/清除分类；body 可带 `acceptSuggestion` 采纳建议 |
| `PUT /api/entries/{id}/tags` | 全量覆盖该条标签 |
| `POST /api/entries/{id}/repollish` | 单条重新 AI 分析（复用已抓素材，素材过期才重抓） |
| `POST /api/ai/bootstrap-categories` | 冷启动：AI 建议初始分类（预览，不直接落库） |
| `POST /api/ai/batch-repolish` | 批量补跑（启动/中断/进度） |
| 列表接口 | 支持 `categoryId` / `tag` 查询参数；行数据带上 category 与 tags |

---

## 七、初始分类种子

冷启动引导的 AI 建议以此为基础；用户也可在引导中完全自定义。

| name | 说明 |
|---|---|
| 工具软件 | 可安装使用的软件、CLI、App |
| 开源项目 | GitHub/Gitee 等仓库（rea 归这里还是"工具软件"由 AI 按素材判断，用户可改） |
| 教程文章 | 博客、文档、长文 |
| 视频影像 | B站/抖音/YouTube 等视频分享 |
| 灵感随记 | 纯文本想法、金句 |
| 资讯动态 | 新闻、发布、行业动态 |
| 资源素材 | 素材包、设计资源、合集导航 |

---

## 八、依赖与分期

- 新增依赖：`langchain4j-open-ai`（Spring Boot starter）、`jsoup`。
  **不上 Playwright**：视频站本就不抓正文，不值得为此引入浏览器。
- **M1（核心闭环，一次交付）**：
  V3 迁移与种子 → 多链接抽取 → 四类抓取器 + entry_links 落库 →
  AiClient/PolishWorker 接 DeepSeek → 分类标签回写与归一化 →
  侧边栏/卡片/详情编辑/单条重跑 → 设置页状态与笔记开关 →
  冷启动引导 → 存量批量补跑。
- **M2（后续）**：分类/标签管理页（合并、批量改分类）、文件与图片的内容理解
  （现规范本就排在 M2 之后）。

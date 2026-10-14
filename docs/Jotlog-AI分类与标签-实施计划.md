# AI 分类与标签 实施计划（M1）

> **For agentic workers:** 步骤用 `- [ ]` 跟踪。后端 Java 17 + Spring Boot 3.5，前端 Vue 3 + TS（无 UI 库，Tailwind 4）。测试只写纯单测（仓库层无 H2，靠编译 + 手动验证）。

**Goal:** 落地"多链接抽取 → 素材抓取 → DeepSeek 分类/打标签/总结 → 实体化回写 → 前端分类导航与编辑"的完整闭环。

**Architecture:** 铁律不变（入库 → 回执 → 异步增强）。新增 `com.jotlog.enrich` 包：PolishWorker 单线程消费现有 PolishQueue，jsoup 统一抓取，langchain4j-open-ai 直连 DeepSeek（OpenAI 兼容）。分类/标签/链接/偏好四张新表实体化；AI 只写补充区，raw_input 永不更新。

**Tech Stack:** MySQL 8 / Flyway / JdbcTemplate / jsoup 1.18.3 / langchain4j-open-ai 1.8.0 / Vue 3 + Pinia + vue-router

**设计文档：** `docs/Jotlog-AI分类与标签设计.md`

**关键命令：**
- 后端单测：`mvn -B -Dtest=ExtractorTest,AiResponseParserTest,TagNormalizerTest test`
- 后端全量编译：`mvn -B clean compile`
- 前端类型检查：`cd web; npm run typecheck`
- 前端构建：`cd web; npm run build`

---

## 文件结构总览

**新建（后端）**
- `src/main/resources/db/migration/V3__ai_classification.sql`
- `src/main/java/com/jotlog/config/AiProperties.java`
- `src/main/java/com/jotlog/repository/CategoryRepository.java`、`JdbcCategoryRepository.java`
- `src/main/java/com/jotlog/repository/TagRepository.java`、`JdbcTagRepository.java`
- `src/main/java/com/jotlog/repository/EntryLinkRepository.java`、`JdbcEntryLinkRepository.java`
- `src/main/java/com/jotlog/repository/UserSettingsRepository.java`、`JdbcUserSettingsRepository.java`
- `src/main/java/com/jotlog/enrich/AiOrganizer.java`（接口）、`AiResult.java`
- `src/main/java/com/jotlog/enrich/DeepSeekConfig.java`、`DeepSeekAiOrganizer.java`、`AiResponseParser.java`
- `src/main/java/com/jotlog/enrich/LinkKind.java`、`LinkMaterial.java`
- `src/main/java/com/jotlog/enrich/LinkFetchService.java`
- `src/main/java/com/jotlog/enrich/TagNormalizer.java`
- `src/main/java/com/jotlog/enrich/EnrichmentService.java`
- `src/main/java/com/jotlog/enrich/PolishWorker.java`
- `src/main/java/com/jotlog/enrich/BatchPolishService.java`
- `src/main/java/com/jotlog/web/CategoryController.java`、`TagController.java`、`AiController.java`
- 测试：`AiResponseParserTest.java`、`TagNormalizerTest.java`（ExtractorTest 追加用例）

**修改（后端）**
- `pom.xml`、`src/main/resources/application.yml`
- `config/application-local.yml.example`、`config/application-prod.yml.example`
- `core/Extractor.java`、`core/IngestService.java`、`core/PolishQueue.java`
- `repository/EntryRepository.java`、`repository/JdbcEntryRepository.java`
- `web/EntryController.java`

**新建/修改（前端）**
- 改：`web/src/types.ts`、`web/src/api/http.ts`、`web/src/router/index.ts`
- 新建：`web/src/stores/categories.ts`、`web/src/components/CategoryDot.vue`、`web/src/components/TagEditor.vue`
- 改：`web/src/components/SideNav.vue`、`EntryCard.vue`、`EntryDetailPanel.vue`
- 改：`web/src/views/TimelineView.vue`、`web/src/views/SettingsView.vue`

---

## Task 1: V3 数据库迁移与分类种子

**Files:**
- Create: `src/main/resources/db/migration/V3__ai_classification.sql`

- [ ] **Step 1: 写迁移文件**

```sql
-- ============================================================
-- Jotlog V3：AI 分类 / 标签 / 多链接 / 用户偏好
-- raw_input 不可变的铁律不变；AI 仍只写补充区。
-- ============================================================

CREATE TABLE categories (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL,
    color       VARCHAR(16)  NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    is_system   TINYINT(1)   NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '分类（单选）。预设为主，AI 不允许自建，只能建议。';

CREATE TABLE tags (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tag_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '标签（多选）。归一化后内容寻址式复用。';

CREATE TABLE entry_tags (
    entry_id    BIGINT NOT NULL,
    tag_id      BIGINT NOT NULL,
    PRIMARY KEY (entry_id, tag_id),
    KEY idx_et_tag (tag_id),
    CONSTRAINT fk_et_entry FOREIGN KEY (entry_id) REFERENCES entries (id) ON DELETE CASCADE,
    CONSTRAINT fk_et_tag   FOREIGN KEY (tag_id)   REFERENCES tags (id)    ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- url 最长 2048，无法直接做唯一键（utf8mb4 索引长度超限），
-- 用 url 的 sha-256 在"同一 entry 内"去重。
CREATE TABLE entry_links (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    entry_id        BIGINT        NOT NULL,
    url             VARCHAR(2048) NOT NULL,
    url_hash        CHAR(64)      NOT NULL,
    domain          VARCHAR(255)  NULL,
    kind            VARCHAR(16)   NOT NULL DEFAULT 'web', -- repo/video/npm/web
    fetched_title   VARCHAR(512)  NULL,
    fetched_excerpt TEXT          NULL,
    fetch_status    VARCHAR(16)   NOT NULL DEFAULT 'pending', -- pending/done/failed/skipped
    fetched_at      DATETIME(3)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_el_entry_url (entry_id, url_hash),
    KEY idx_el_entry (entry_id),
    CONSTRAINT fk_el_entry FOREIGN KEY (entry_id) REFERENCES entries (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '一条记录的多个链接及抓取结果。素材落库后重跑 AI 不必重复抓取。';

CREATE TABLE user_settings (
    `key`       VARCHAR(64)  NOT NULL,
    `value`     VARCHAR(255) NULL,
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '网页可改的用户偏好。首版只存 ai_polish_notes。';

ALTER TABLE entries
    ADD COLUMN category_id        BIGINT      NULL AFTER entry_type,
    ADD COLUMN suggested_category VARCHAR(64) NULL AFTER category_id,
    ADD KEY idx_category (category_id),
    ADD CONSTRAINT fk_entry_category
        FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE SET NULL;

-- 初始种子。冷启动引导允许用户改名/增删，这只是兜底。
INSERT INTO categories (name, color, sort_order, is_system) VALUES
    ('工具软件', '#7C9885', 1, 1),
    ('开源项目', '#5B8A72', 2, 1),
    ('教程文章', '#B08968', 3, 1),
    ('视频影像', '#A5A58D', 4, 1),
    ('灵感随记', '#9C89B8', 5, 1),
    ('资讯动态', '#6D8A96', 6, 1),
    ('资源素材', '#C08552', 7, 1);
```

- [ ] **Step 2: 启动应用验证迁移成功**

Run: `mvn -B spring-boot:run`（本地 MySQL 须可连），看到 `Successfully applied 1 migration to schema ... (V3__ai_classification.sql)`，且启动无报错后停止。

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V3__ai_classification.sql
git commit -m "feat(db): V3 分类/标签/多链接/偏好表与初始分类种子"
```

---

## Task 2: 依赖与 AI 配置

**Files:**
- Modify: `pom.xml`（在 jjwt 依赖之后插入）
- Create: `src/main/java/com/jotlog/config/AiProperties.java`
- Modify: `src/main/resources/application.yml`（`jotlog:` 块内，storage 之后）
- Modify: `config/application-local.yml.example`、`config/application-prod.yml.example`

- [ ] **Step 1: pom.xml 加两个依赖**

```xml
    <!-- AI：只用 OpenAI 兼容协议层直连 DeepSeek，不引 starter，Bean 按配置条件创建 -->
    <dependency>
      <groupId>dev.langchain4j</groupId>
      <artifactId>langchain4j-open-ai</artifactId>
      <version>${langchain4j.version}</version>
    </dependency>

    <!-- 网页正文 / OG 元信息抓取。版本跟随 Spring Boot BOM 亦可，这里显式锁定 -->
    <dependency>
      <groupId>org.jsoup</groupId>
      <artifactId>jsoup</artifactId>
      <version>1.18.3</version>
    </dependency>
```

- [ ] **Step 2: 新建 AiProperties**

```java
package com.jotlog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 增强配置。真值只走 application-local/prod.yml 或环境变量，application.yml 里只有占位符。
 * enabled=false 或 api-key 为空时，enrich 包的 Bean 全部不创建，Jotlog 照常可用。
 */
@ConfigurationProperties(prefix = "jotlog.ai")
public class AiProperties {

    private boolean enabled = false;
    private String baseUrl = "https://api.deepseek.com";
    private String apiKey = "";
    private String model = "deepseek-chat";
    /** 纯文本 note 是否也调 AI。默认关：省钱，也不污染原话。网页端可改（user_settings 覆盖）。 */
    private boolean polishNotes = false;
    private int fetchTimeoutSeconds = 8;

    public boolean isReady() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public boolean isPolishNotes() { return polishNotes; }
    public void setPolishNotes(boolean polishNotes) { this.polishNotes = polishNotes; }
    public int getFetchTimeoutSeconds() { return fetchTimeoutSeconds; }
    public void setFetchTimeoutSeconds(int fetchTimeoutSeconds) { this.fetchTimeoutSeconds = fetchTimeoutSeconds; }
}
```

- [ ] **Step 3: application.yml 加配置块**

在 `jotlog:` 下 `storage:` 块之后同级加入：

```yaml
  # ---------- AI 增强（DeepSeek，OpenAI 兼容协议）----------
  # 没配 key 保持 enabled=false：worker 不启动，条目停在 pending，设置页提示"未配置"。
  ai:
    enabled: ${JOTLOG_AI_ENABLED:false}
    base-url: ${JOTLOG_AI_BASE_URL:https://api.deepseek.com}
    api-key: ${JOTLOG_AI_KEY:}
    model: ${JOTLOG_AI_MODEL:deepseek-chat}
    polish-notes: ${JOTLOG_AI_POLISH_NOTES:false}
    fetch-timeout-seconds: 8
```

- [ ] **Step 4: 两个 example 文件补模板**

`application-local.yml.example` 的 `jotlog:` 块内（storage 之后）加：

```yaml
  # ---------- AI 增强 ----------
  # DeepSeek 控制台 https://platform.deepseek.com 创建 API key。
  # 不配就保持 enabled:false，整个 AI 链路不启动，不影响记录功能。
  ai:
    enabled: false
    base-url: https://api.deepseek.com
    api-key: YOUR_DEEPSEEK_API_KEY
    model: deepseek-chat
    polish-notes: false
```

`application-prod.yml.example` 同样加一份（api-key 留空字符串，真值走环境变量）。
在 local example 文末"环境变量对照表"追加：

```
#   JOTLOG_AI_ENABLED        是否启用 AI 增强，默认 false
#   JOTLOG_AI_BASE_URL       OpenAI 兼容端点，默认 https://api.deepseek.com
#   JOTLOG_AI_KEY            DeepSeek API key
#   JOTLOG_AI_MODEL          模型名，默认 deepseek-chat
#   JOTLOG_AI_POLISH_NOTES   纯文本笔记是否也 AI 总结，默认 false
```

- [ ] **Step 5: 编译验证**

Run: `mvn -B clean compile`，Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/jotlog/config/AiProperties.java src/main/resources/application.yml config/
git commit -m "feat(ai): 加 langchain4j/jsoup 依赖与 jotlog.ai 配置"
```

---

## Task 3: 分类 / 标签 / 链接 / 偏好 四个仓库

**Files（全部 Create）:**
- `repository/CategoryRepository.java`、`repository/JdbcCategoryRepository.java`
- `repository/TagRepository.java`、`repository/JdbcTagRepository.java`
- `repository/EntryLinkRepository.java`、`repository/JdbcEntryLinkRepository.java`
- `repository/UserSettingsRepository.java`、`repository/JdbcUserSettingsRepository.java`

- [ ] **Step 1: CategoryRepository 接口 + JDBC 实现**

```java
package com.jotlog.repository;

import java.util.List;

public interface CategoryRepository {

    record CategoryRow(long id, String name, String color, int sortOrder, boolean system, long count) {
    }

    /** 分类清单，count=该分类下未归档条目数；按 sort_order 排序。 */
    List<CategoryRow> list();

    List<CategoryRow> listAll();

    CategoryRow findById(long id);

    CategoryRow findByName(String name);

    long insert(String name, String color);

    void rename(long id, String name, String color);

    /** 未归档且 category_id 为空的条目数（"待分类"角标）。 */
    long uncategorizedCount();
}
```

```java
package com.jotlog.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcCategoryRepository implements CategoryRepository {

    private final JdbcTemplate jdbc;

    public JdbcCategoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT = """
            SELECT c.id, c.name, c.color, c.sort_order, c.is_system,
                   (SELECT COUNT(*) FROM entries e
                     WHERE e.category_id = c.id AND e.archived = 0) AS cnt
            FROM categories c
            """;

    @Override
    public List<CategoryRow> list() {
        return jdbc.query(SELECT + " ORDER BY c.sort_order ASC, c.id ASC",
                (rs, i) -> map(rs));
    }

    @Override
    public List<CategoryRow> listAll() {
        return list();
    }

    @Override
    public CategoryRow findById(long id) {
        var rows = jdbc.query(SELECT + " WHERE c.id = ?", (rs, i) -> map(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public CategoryRow findByName(String name) {
        var rows = jdbc.query(SELECT + " WHERE c.name = ?", (rs, i) -> map(rs), name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public long insert(String name, String color) {
        Integer maxOrder = jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), 0) FROM categories", Integer.class);
        jdbc.update("INSERT INTO categories (name, color, sort_order, is_system) VALUES (?, ?, ?, 0)",
                name, color, (maxOrder == null ? 0 : maxOrder) + 1);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    @Override
    public void rename(long id, String name, String color) {
        jdbc.update("UPDATE categories SET name = ?, color = COALESCE(?, color) WHERE id = ?",
                name, color, id);
    }

    @Override
    public long uncategorizedCount() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM entries WHERE category_id IS NULL AND archived = 0", Long.class);
        return n == null ? 0 : n;
    }

    private static CategoryRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CategoryRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("color"),
                rs.getInt("sort_order"),
                rs.getBoolean("is_system"),
                rs.getLong("cnt"));
    }
}
```

- [ ] **Step 2: TagRepository 接口 + 实现（含条目关联批量读写）**

```java
package com.jotlog.repository;

import java.util.List;

public interface TagRepository {

    /** 归一化与建标由上层完成，这里只保证"名字 → id"，不存在就建。 */
    long findOrCreate(String name);

    /** 全量替换某条目的标签。tagIds 为空=清空。 */
    void replaceForEntry(long entryId, List<Long> tagIds);

    /** 一批条目各自的标签名，key=entryId。列表页一次性加载，避免 N+1。 */
    java.util.Map<Long, List<String>> namesForEntries(List<Long> entryIds);

    List<String> namesForEntry(long entryId);

    /** 使用频次最高的若干标签，喂给 AI 做复用提示。 */
    List<String> popular(int limit);

    /** 详情页输入联想。 */
    List<String> suggest(String q, int limit);
}
```

```java
package com.jotlog.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcTagRepository implements TagRepository {

    private final JdbcTemplate jdbc;

    public JdbcTagRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long findOrCreate(String name) {
        List<Long> ids = jdbc.query("SELECT id FROM tags WHERE name = ? LIMIT 1",
                (rs, i) -> rs.getLong("id"), name);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        // 并发兜底：两个 worker（不会有，worker 单线程）/手动同时建同名时靠 uk_tag_name 挡重
        try {
            jdbc.update("INSERT INTO tags (name) VALUES (?)", name);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            return jdbc.queryForObject("SELECT id FROM tags WHERE name = ? LIMIT 1", Long.class, name);
        }
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Override
    public void replaceForEntry(long entryId, List<Long> tagIds) {
        jdbc.update("DELETE FROM entry_tags WHERE entry_id = ?", entryId);
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        var distinct = tagIds.stream().distinct().toList();
        jdbc.batchUpdate("INSERT IGNORE INTO entry_tags (entry_id, tag_id) VALUES (?, ?)",
                distinct.stream().map(id -> new Object[]{entryId, id}).toList());
    }

    @Override
    public Map<Long, List<String>> namesForEntries(List<Long> entryIds) {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        if (entryIds == null || entryIds.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", entryIds.stream().map(x -> "?").toList());
        jdbc.query("""
                SELECT et.entry_id AS entry_id, t.name AS name
                FROM entry_tags et JOIN tags t ON t.id = et.tag_id
                WHERE et.entry_id IN (%s)
                ORDER BY et.tag_id ASC
                """.formatted(placeholders),
                rs -> {
                    long eid = rs.getLong("entry_id");
                    result.computeIfAbsent(eid, k -> new ArrayList<>()).add(rs.getString("name"));
                    return null; // ResultSetExtractor 要求返回值
                },
                entryIds.toArray());
        return result;
    }

    @Override
    public List<String> namesForEntry(long entryId) {
        return namesForEntries(List.of(entryId)).getOrDefault(entryId, List.of());
    }

    @Override
    public List<String> popular(int limit) {
        return jdbc.query("""
                SELECT t.name FROM tags t
                JOIN entry_tags et ON et.tag_id = t.id
                GROUP BY t.id ORDER BY COUNT(*) DESC, t.id ASC LIMIT ?
                """, (rs, i) -> rs.getString("name"), limit);
    }

    @Override
    public List<String> suggest(String q, int limit) {
        if (q == null || q.isBlank()) {
            return popular(limit);
        }
        return jdbc.query("SELECT name FROM tags WHERE name LIKE ? ORDER BY id ASC LIMIT ?",
                (rs, i) -> rs.getString("name"), "%" + q.trim() + "%", limit);
    }
}
```

注意 `jdbc.query(String, ResultSetExtractor, args)` 重载存在；上面 lambda 用的是 `ResultSetExtractor`（无返回值）。

- [ ] **Step 3: EntryLinkRepository**

```java
package com.jotlog.repository;

import java.util.List;

public interface EntryLinkRepository {

    record LinkRow(long id, String url, String domain, String kind,
                   String fetchedTitle, String fetchStatus) {
    }

    /** 不存在则插（entry+url_hash 唯一），已存在不覆盖抓取结果。 */
    void registerIfAbsent(long entryId, String url, String urlHash, String domain, String kind);

    void saveFetch(long entryId, String urlHash, String title, String excerpt, String status);

    List<LinkRow> listByEntry(long entryId);

    /** 已成功抓取的链接是否存在（决定重跑 AI 时要不要重新抓）。 */
    boolean hasDoneFetch(long entryId);
}
```

```java
package com.jotlog.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcEntryLinkRepository implements EntryLinkRepository {

    private final JdbcTemplate jdbc;

    public JdbcEntryLinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void registerIfAbsent(long entryId, String url, String urlHash, String domain, String kind) {
        jdbc.update("""
                INSERT IGNORE INTO entry_links (entry_id, url, url_hash, domain, kind, fetch_status)
                VALUES (?, ?, ?, ?, ?, 'pending')
                """, entryId, url, urlHash, domain, kind);
    }

    @Override
    public void saveFetch(long entryId, String urlHash, String title, String excerpt, String status) {
        jdbc.update("""
                UPDATE entry_links
                SET fetched_title = ?, fetched_excerpt = ?, fetch_status = ?, fetched_at = NOW(3)
                WHERE entry_id = ? AND url_hash = ?
                """, title, excerpt, status, entryId, urlHash);
    }

    @Override
    public List<LinkRow> listByEntry(long entryId) {
        return jdbc.query("""
                SELECT id, url, domain, kind, fetched_title, fetch_status
                FROM entry_links WHERE entry_id = ? ORDER BY id ASC
                """, (rs, i) -> new LinkRow(
                rs.getLong("id"),
                rs.getString("url"),
                rs.getString("domain"),
                rs.getString("kind"),
                rs.getString("fetched_title"),
                rs.getString("fetch_status")), entryId);
    }

    @Override
    public boolean hasDoneFetch(long entryId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM entry_links WHERE entry_id = ? AND fetch_status = 'done'",
                Integer.class, entryId);
        return n != null && n > 0;
    }
}
```

- [ ] **Step 4: UserSettingsRepository**

```java
package com.jotlog.repository;

public interface UserSettingsRepository {
    /** 不存在返回 null。 */
    String get(String key);

    boolean getBool(String key, boolean fallback);

    void put(String key, String value);
}
```

```java
package com.jotlog.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcUserSettingsRepository implements UserSettingsRepository {

    private final JdbcTemplate jdbc;

    public JdbcUserSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String get(String key) {
        List<String> v = jdbc.query("SELECT `value` FROM user_settings WHERE `key` = ?",
                (rs, i) -> rs.getString("value"), key);
        return v.isEmpty() ? null : v.get(0);
    }

    @Override
    public boolean getBool(String key, boolean fallback) {
        String v = get(key);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }

    @Override
    public void put(String key, String value) {
        jdbc.update("""
                INSERT INTO user_settings (`key`, `value`) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE `value` = VALUES(`value`)
                """, key, value);
    }
}
```

- [ ] **Step 5: 编译 + 提交**

Run: `mvn -B clean compile`，Expected: BUILD SUCCESS。

```bash
git add src/main/java/com/jotlog/repository/
git commit -m "feat(repo): 分类/标签/链接/偏好四个 JDBC 仓库"
```

---

## Task 4: EntryRepository 扩展（分类、标签、过滤、AI 回写）

**Files:**
- Modify: `repository/EntryRepository.java`
- Modify: `repository/JdbcEntryRepository.java`

- [ ] **Step 1: Row / Page / 接口签名改造**

`EntryRepository.Row` 增加分类与标签字段（标签在 Jdbc 实现里二次批量填充）：

```java
    record Row(
            long id,
            String source,
            String entryType,
            String rawInput,
            String url,
            String domain,
            String title,
            String aiSummary,
            String aiTags,
            String aiStatus,
            String createdAt,
            boolean starred,
            boolean archived,
            String imageSha,
            long attachmentCount,
            Long categoryId,
            String categoryName,
            String suggestedCategory,
            List<String> tags
    ) {
    }
```

`searchPage` 签名增加分类与标签过滤：

```java
    Page searchPage(String keyword, String type, Boolean starred, boolean includeArchived,
                    Long categoryId, String tag, boolean uncategorized,
                    int limit, long offset);
```

接口里旧的四参 `searchPage` 声明删除（调用方 Task 8/12 同步改）。新增：

```java
    /** AI 整理完成的回写：title/摘要/标签快照/分类/新分类建议。SQL 不出现 raw_input。 */
    void applyAiResult(long id, String title, String summary, String tagsSnapshot,
                       Long categoryId, String suggestedCategory, String model, int latencyMs);

    void markAiProcessing(long id);

    /** note 跳过 AI 时按规则归入指定分类。 */
    void skipWithCategory(long id, Long categoryId);

    /** 批量补跑扫描：pending/failed，类型由 polishNotes 决定是否含 note。 */
    List<Long> pendingForPolish(int limit, boolean includeNotes);

    /** 冷启动引导抽样：只取原文，按时间倒序。 */
    List<String> sampleRawInput(int limit);
```

- [ ] **Step 2: Jdbc 实现：COLUMNS 与 ROW mapper**

`COLUMNS` 改为（末尾加三列 + 分类名 join 放到外层，直接在 e 后加列最简）：

```java
    private static final String COLUMNS = """
            e.id, e.source, e.entry_type, e.raw_input, e.url, e.domain,
            e.title, e.ai_summary, e.ai_tags, e.ai_status, e.created_at,
            e.starred, e.archived,
            e.category_id, e.suggested_category,
            (SELECT c.name FROM categories c WHERE c.id = e.category_id) AS category_name,
            (SELECT a.sha256 FROM attachments a
              WHERE a.entry_id = e.id AND a.mime LIKE 'image/%'
              ORDER BY a.id LIMIT 1)                          AS image_sha,
            (SELECT COUNT(*) FROM attachments a
              WHERE a.entry_id = e.id)                        AS attachment_count
            """;
```

`ROW` mapper 构造参数末尾补四个（mapper 无法直接拿 tags，先传空 list，由 searchPage 批量填充）：

```java
            rs.getObject("category_id", Long.class),
            rs.getString("category_name"),
            rs.getString("suggested_category"),
            new java.util.ArrayList<String>()
```

- [ ] **Step 3: searchPage / conditions / query / count 改造**

```java
    @Override
    public Page searchPage(String keyword, String type, Boolean starred, boolean includeArchived,
                           Long categoryId, String tag, boolean uncategorized,
                           int limit, long offset) {
        String kw = keyword == null ? "" : keyword.trim();
        String mode = kw.isEmpty() ? "none" : (kw.length() < 2 ? "like" : "match");

        List<Row> items = query(mode, kw, type, starred, includeArchived, categoryId, tag, uncategorized, limit, offset);
        long total = count(mode, kw, type, starred, includeArchived, categoryId, tag, uncategorized);

        if (items.isEmpty() && "match".equals(mode)) {
            items = query("like", kw, type, starred, includeArchived, categoryId, tag, uncategorized, limit, offset);
            total = count("like", kw, type, starred, includeArchived, categoryId, tag, uncategorized);
        }

        // 批量填充标签，避免逐条查
        if (!items.isEmpty()) {
            var ids = items.stream().map(Row::id).toList();
            var tagMap = jdbc.query("""
                    SELECT et.entry_id AS eid, t.name AS name FROM entry_tags et
                    JOIN tags t ON t.id = et.tag_id WHERE et.entry_id IN (%s)
                    ORDER BY et.tag_id ASC
                    """.formatted(String.join(",", ids.stream().map(x -> "?").toList())),
                    (rs, i) -> new Object[]{rs.getLong("eid"), rs.getString("name")},
                    ids.toArray());
            var byId = items.stream().collect(java.util.stream.Collectors.toMap(Row::id, r -> r));
            for (Object[] pair : tagMap) {
                Row r = byId.get((Long) pair[0]);
                if (r != null) r.tags().add((String) pair[1]);
            }
        }
        return new Page(items, total);
    }
```

`conditions` 末尾（starred 之后）加：

```java
        if (categoryId != null) {
            sql.append(" AND category_id = ?");
            params.add(categoryId);
        }
        if (uncategorized) {
            sql.append(" AND category_id IS NULL");
        }
        if (tag != null && !tag.isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM entry_tags et2 JOIN tags t2 ON t2.id = et2.tag_id"
                    + " WHERE et2.entry_id = entries.id AND t2.name = ?)");
            params.add(tag.trim());
        }
```

同步修改 `query(...)`、`count(...)` 的参数表，把 `categoryId, tag, uncategorized` 透传；`recent` / `search` 这两个旧入口改为：

```java
    @Override
    public List<Row> recent(int limit) {
        return searchPage(null, null, null, false, null, null, false, limit, 0).items();
    }

    @Override
    public List<Row> search(String keyword, int limit) {
        return searchPage(keyword, null, null, false, null, null, false, limit, 0).items();
    }
```

`findById` 单条查到后用 `tags` 字段补查一次：

```java
    @Override
    public Row findById(long id) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM entries e WHERE e.id = ?", ROW, id);
        if (rows.isEmpty()) return null;
        Row r = rows.get(0);
        jdbc.query("SELECT t.name FROM entry_tags et JOIN tags t ON t.id = et.tag_id WHERE et.entry_id = ?",
                (rs, i) -> r.tags().add(rs.getString("name")), id);
        return r;
    }
```

- [ ] **Step 4: AI 回写 / 状态 / 扫描方法**

```java
    @Override
    public void applyAiResult(long id, String title, String summary, String tagsSnapshot,
                              Long categoryId, String suggestedCategory, String model, int latencyMs) {
        jdbc.update("""
                UPDATE entries
                SET title = ?, ai_summary = ?, ai_tags = ?,
                    category_id = ?, suggested_category = ?,
                    ai_model = ?, ai_latency_ms = ?,
                    ai_status = 'done', ai_error = NULL
                WHERE id = ?
                """, title, summary, tagsSnapshot, categoryId, suggestedCategory, model, latencyMs, id);
    }

    @Override
    public void markAiProcessing(long id) {
        jdbc.update("UPDATE entries SET ai_status = 'processing' WHERE id = ?", id);
    }

    @Override
    public void skipWithCategory(long id, Long categoryId) {
        jdbc.update("UPDATE entries SET ai_status = 'skipped', ai_error = NULL, category_id = ? WHERE id = ?",
                categoryId, id);
    }

    @Override
    public List<Long> pendingForPolish(int limit, boolean includeNotes) {
        String types = includeNotes
                ? "('link', 'repo', 'video', 'note')"
                : "('link', 'repo', 'video')";
        return jdbc.query("""
                SELECT id FROM entries
                WHERE ai_status IN ('pending', 'failed')
                  AND entry_type IN %s
                  AND created_at < NOW(3) - INTERVAL 5 SECOND
                ORDER BY created_at ASC LIMIT ?
                """.formatted(types), (rs, i) -> rs.getLong("id"), limit);
    }

    @Override
    public List<String> sampleRawInput(int limit) {
        return jdbc.query("SELECT raw_input FROM entries ORDER BY created_at DESC LIMIT ?",
                (rs, i) -> rs.getString("raw_input"), limit);
    }
```

删除旧的 `applyAiResult`（5 参）与旧 `pendingForPolish(int)`；`markAiFailed` 保留不动。

- [ ] **Step 5: 编译（此时 EntryController 会报错，属预期，Task 12 修）**

Run: `mvn -B clean compile 2>&1 | Select-String "BUILD|EntryController"`。Expected：仅 EntryController 调用点报错。提交本任务先 stash 调用点不合适——改为本任务暂不提交，与 Task 12 Step 1 一起提交；或现在先把 EntryController.list 调用改成新签名（加四个 null/false 参数）即可编译通过：

`EntryController.java` 第 61 行先临时改为：

```java
        return entries.searchPage(q, type, starred, archived, null, null, false,
                safeSize, (long) safePage * safeSize);
```

Run: `mvn -B clean compile`，Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/jotlog/repository/ src/main/java/com/jotlog/web/EntryController.java
git commit -m "feat(repo): Entry 行携带分类与标签，列表支持分类/标签/待分类过滤"
```

---

## Task 5: 多链接抽取（纯规则，TDD）

**Files:**
- Modify: `core/Extractor.java`
- Test: `src/test/java/com/jotlog/core/ExtractorTest.java`

- [ ] **Step 1: 先在 ExtractorTest 追加失败用例**

```java
    @Test
    void extractAll_抽出全部链接并去重_主体优先级repo最高() {
        String text = """
                3.02 复制打开抖音 https://v.douyin.com/w_6uKY4ZYbk/ gOX
                GitHub：https://github.com/morluto/rea
                主页：https://rea.tools
                再发一次 GitHub https://github.com/morluto/rea
                """;
        var all = Extractor.extractAll(text);
        assertEquals(3, all.links().size());
        assertEquals("github.com", all.primary().domain());
        assertEquals(EntryType.REPO, all.primary().type());
    }

    @Test
    void extractAll_无链接时空结果() {
        var all = Extractor.extractAll("人生不是赶路，是看风景");
        assertTrue(all.links().isEmpty());
        assertNull(all.primary());
    }

    @Test
    void extractAll_纯视频链接判video() {
        var all = Extractor.extractAll("看看这个 https://www.bilibili.com/video/BV1xx");
        assertEquals(EntryType.VIDEO, all.primary().type());
    }
```

（文件顶部确保有 `import static org.junit.jupiter.api.Assertions.*;`，没有则加。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -B -Dtest=ExtractorTest test`，Expected: 编译失败（`extractAll` 不存在）。

- [ ] **Step 3: 实现 extractAll，extract 改为委托**

在 `Extractor` 中把 `extract` 主体替换为委托，并新增：

```java
    /** 一个链接及其判定结果。 */
    public record Link(String url, String domain, EntryType type) {
    }

    /** 多链接抽取结果。primary 按 repo > video > link 取主体，同优先级取最先出现的。 */
    public record MultiLinks(List<Link> links, Link primary) {
        public static MultiLinks empty() {
            return new MultiLinks(List.of(), null);
        }
    }

    public static MultiLinks extractAll(String text) {
        if (text == null || text.isBlank()) {
            return MultiLinks.empty();
        }
        LinkedHashMap<String, Link> unique = new LinkedHashMap<>();
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String raw = m.group();
            while (raw.length() > 0 && ".,;:!?)]}'\"、，。；：！？）】》".indexOf(raw.charAt(raw.length() - 1)) >= 0) {
                raw = raw.substring(0, raw.length() - 1);
            }
            String domain = hostOf(raw);
            if (unique.putIfAbsent(raw, new Link(raw, domain, classify(domain))) != null) {
                // 同 URL 重复出现，保留第一次
            }
        }
        if (unique.isEmpty()) {
            return MultiLinks.empty();
        }
        List<Link> links = List.copyOf(unique.values());
        Link primary = null;
        for (EntryType t : List.of(EntryType.REPO, EntryType.VIDEO, EntryType.LINK)) {
            primary = links.stream().filter(l -> l.type() == t).findFirst().orElse(null);
            if (primary != null) break;
        }
        return new MultiLinks(links, primary != null ? primary : links.get(0));
    }

    public static IngestService.Extracted extract(String text) {
        MultiLinks all = extractAll(text);
        if (all.primary() == null) {
            return new IngestService.Extracted(null, null, EntryType.NOTE);
        }
        Link p = all.primary();
        return new IngestService.Extracted(p.url(), p.domain(), p.type());
    }
```

加 import：`java.util.LinkedHashMap`。旧的单次 `m.find()` 逻辑删除（被上面两个方法取代）。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -B -Dtest=ExtractorTest test`，Expected: 全部 PASS。

- [ ] **Step 5: IngestService 用主体链接落库**

`IngestService.persist` 中第 62–65 行的：

```java
        Extracted extracted = Extractor.extract(entry.rawInput());
```

保持不变即可——`extract` 已委托到主体链接。无需其他改动。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/jotlog/core/Extractor.java src/test/java/com/jotlog/core/ExtractorTest.java
git commit -m "feat(core): 支持多链接抽取与 repo>video>link 主体判定"
```

---

## Task 6: 链接素材抓取服务（jsoup）

**Files（Create）:**
- `enrich/LinkKind.java`、`enrich/LinkMaterial.java`、`enrich/LinkFetchService.java`

- [ ] **Step 1: 两个值对象**

```java
package com.jotlog.enrich;

/** entry_links.kind。npm 单独一类，因为它走 registry JSON API。 */
public enum LinkKind {
    REPO, VIDEO, NPM, WEB;

    public static LinkKind fromDomain(String domain, String url) {
        if (domain == null) return WEB;
        String d = domain.toLowerCase(java.util.Locale.ROOT);
        if (d.equals("github.com") || d.equals("gitee.com") || d.equals("gitlab.com")
                || d.equals("codeberg.org") || d.equals("gitcode.com")) return REPO;
        if (d.equals("registry.npmjs.org") || d.equals("www.npmjs.com") || d.equals("npmjs.com")) return NPM;
        for (String h : java.util.List.of("youtube.com", "youtu.be", "bilibili.com", "vimeo.com",
                "douyin.com", "ixigua.com", "iqiyi.com", "youku.com", "xiaohongshu.com")) {
            if (d.contains(h)) return VIDEO;
        }
        return WEB;
    }
}
```

```java
package com.jotlog.enrich;

/** 喂给 AI 的单链接素材。excerpt 已截断，绝不整页灌给模型。 */
public record LinkMaterial(
        String url,
        String domain,
        LinkKind kind,
        String title,
        String excerpt,
        String status          // done / failed / skipped
) {
}
```

- [ ] **Step 2: LinkFetchService（jsoup 统一 HTTP，8s 超时，失败降级）**

```java
package com.jotlog.enrich;

import com.jotlog.config.AiProperties;
import com.jotlog.core.Extractor;
import com.jotlog.repository.EntryLinkRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * 按链接类型抓取素材。每个链接独立超时、独立失败：
 * 抓不到的链接 status=failed，AI 拿到的素材里明确是"信息不足"，不允许编造。
 *
 * 统一用 jsoup（自动跟随重定向，正好覆盖抖音短链 v.douyin.com → 落地页）。
 */
@Service
public class LinkFetchService {

    private static final Logger log = LoggerFactory.getLogger(LinkFetchService.class);
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final int EXCERPT_MAX = 1200;

    private final EntryLinkRepository links;
    private final AiProperties props;

    public LinkFetchService(EntryLinkRepository links, AiProperties props) {
        this.links = links;
        this.props = props;
    }

    /** 抽取、登记并抓取整条消息的所有链接，返回给 AI 的素材（顺序与原文一致）。 */
    public List<LinkMaterial> fetchAll(long entryId, String rawInput) {
        var all = Extractor.extractAll(rawInput);
        return all.links().stream().map(l -> {
            LinkKind kind = LinkKind.fromDomain(l.domain(), l.url());
            String hash = sha256(l.url());
            links.registerIfAbsent(entryId, l.url(), hash, l.domain(), kind.name().toLowerCase());
            try {
                LinkMaterial m = switch (kind) {
                    case REPO -> fetchGitHub(l.url());
                    case NPM -> fetchNpm(l.url());
                    case VIDEO -> fetchVideo(l.url());
                    case WEB -> fetchWeb(l.url());
                };
                links.saveFetch(entryId, hash, clip(m.title(), 500), clip(m.excerpt(), EXCERPT_MAX * 2), m.status());
                return m;
            } catch (Exception e) {
                log.warn("链接抓取失败 entryId={} url={}", entryId, l.url(), e);
                links.saveFetch(entryId, hash, null, null, "failed");
                return new LinkMaterial(l.url(), l.domain(), kind, null, null, "failed");
            }
        }).toList();
    }

    private LinkMaterial fetchGitHub(String url) throws Exception {
        String path = URI.create(url).getPath();
        var seg = path == null ? List.<String>of() : List.of(path.split("/"));
        // "/owner/repo" split → ["", "owner", "repo", ...]
        if (seg.size() < 3) {
            return fetchWeb(url);
        }
        String owner = seg.get(1);
        String repo = stripSuffix(seg.get(2));
        String api = "https://api.github.com/repos/" + owner + "/" + repo;
        Document meta = get(api);
        String readme = "";
        try {
            Document rm = Jsoup.connect(api + "/readme")
                    .header("Accept", "application/vnd.github.raw")
                    .header("User-Agent", UA).ignoreContentType(true)
                    .timeout(timeoutMs()).execute().parse();
            readme = clip(rm.text(), EXCERPT_MAX);
        } catch (Exception ignore) {
            // README 403/404 不致命，仓库描述已够用
        }
        var node = parseJson(meta.text());
        String desc = jsonString(node, "description");
        String home = jsonString(node, "homepage");
        String title = (repo + "（GitHub 仓库）" + (desc == null ? "" : "：" + desc));
        String excerpt = String.join("\n",
                desc == null ? "" : "简介：" + desc,
                home == null || home.isBlank() ? "" : "主页：" + home,
                readme.isBlank() ? "" : "README 摘录：" + readme).trim();
        return new LinkMaterial(url, "github.com", LinkKind.REPO, clip(title, 500), excerpt, "done");
    }

    private LinkMaterial fetchNpm(String url) throws Exception {
        String name;
        if (url.contains("registry.npmjs.org")) {
            String p = URI.create(url).getPath();
            name = p == null ? "" : p.replace("/", "").trim();
        } else {
            String p = URI.create(url).getPath();
            var seg = p == null ? List.<String>of() : List.of(p.split("/"));
            // /package/name 或 /package/@scope/name
            int idx = seg.indexOf("package");
            name = idx < 0 ? "" : String.join("/", seg.subList(idx + 1, seg.size()));
        }
        if (name.isBlank()) return fetchWeb(url);
        Document doc = get("https://registry.npmjs.org/" + name);
        var root = parseJson(doc.text());
        var latest = root instanceof java.util.Map<?, ?> m && m.get("latest") instanceof java.util.Map<?, ?> lm
                ? lm : root;
        String desc = jsonString(latest, "description");
        String home = latest instanceof java.util.Map<?, ?> lm2
                ? (lm2.get("homepage") instanceof String s ? s : null) : null;
        String title = name + "（npm 包）" + (desc == null ? "" : "：" + desc);
        String excerpt = String.join("\n",
                desc == null ? "" : "简介：" + desc,
                home == null ? "" : "主页：" + home).trim();
        return new LinkMaterial(url, "npmjs", LinkKind.NPM, clip(title, 500), excerpt, "done");
    }

    private LinkMaterial fetchVideo(String url) throws Exception {
        Document doc = get(url);
        String og = attr(doc, "meta[property=og:title]");
        String title = firstNonBlank(og, doc.title());
        String desc = attr(doc, "meta[property=og:description]");
        // 刻意不抓正文：视频站正文大多不可得，只把"页面标题 + 用户自己的转发文案"留给 AI
        return new LinkMaterial(url, host(url), LinkKind.VIDEO, clip(title, 500),
                desc == null ? null : clip(desc, EXCERPT_MAX), title == null ? "failed" : "done");
    }

    private LinkMaterial fetchWeb(String url) throws Exception {
        Document doc = get(url);
        String og = attr(doc, "meta[property=og:title]");
        String title = firstNonBlank(og, doc.title());
        String desc = firstNonBlank(attr(doc, "meta[property=og:description]"),
                attr(doc, "meta[name=description]"));
        String body = clip(doc.body() == null ? "" : doc.body().text(), EXCERPT_MAX);
        String excerpt = desc != null && !desc.isBlank() ? desc + "\n" + body : body;
        return new LinkMaterial(url, host(url), LinkKind.WEB, clip(title, 500), excerpt, "done");
    }

    private Document get(String url) throws java.io.IOException {
        return Jsoup.connect(url)
                .header("User-Agent", UA)
                .ignoreContentType(true)
                .timeout(timeoutMs())
                .execute().parse();
    }

    private int timeoutMs() {
        return Math.max(2, props.getFetchTimeoutSeconds()) * 1000;
    }

    /* ---------- 极简 JSON 读取：只取顶层字符串字段，用 Jackson（spring-web 已传递依赖） ---------- */

    private static Object parseJson(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(text, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String jsonString(Object node, String key) {
        if (node instanceof java.util.Map<?, ?> m && m.get(key) instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private static String attr(Document doc, String css) {
        var el = doc.selectFirst(css);
        if (el == null) return null;
        String v = el.attr("content");
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? null : h.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripSuffix(String s) {
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 3: 编译验证（网络调用不写单测，避免 CI 不稳定）**

Run: `mvn -B clean compile`，Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/jotlog/enrich/
git commit -m "feat(enrich): 按类型抓取 GitHub/npm/视频/网页素材并落 entry_links"
```

---

## Task 7: AI 结果归一化与 JSON 解析（TDD）

**Files（Create）:**
- `enrich/TagNormalizer.java` + 测试
- `enrich/AiResult.java`、`enrich/AiResponseParser.java` + 测试

- [ ] **Step 1: TagNormalizer + 测试**

```java
package com.jotlog.enrich;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 标签归一化：去空白/#、拉丁字母转小写（让 AI/ai 收敛成一个）、
 * 超长截断、去重、上限 5 个。中文名保持原样。
 */
public final class TagNormalizer {

    public static final int MAX_TAGS = 5;
    public static final int MAX_LEN = 16;

    private TagNormalizer() {
    }

    public static List<String> normalize(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return List.of();
        }
        for (String r : raw) {
            if (r == null) continue;
            String t = r.trim().replaceAll("^#+\s*", "").replaceAll("\\s+", " ");
            if (t.isEmpty()) continue;
            t = latinLower(t);
            if (t.length() > MAX_LEN) t = t.substring(0, MAX_LEN);
            out.add(t);
            if (out.size() >= MAX_TAGS) break;
        }
        return new ArrayList<>(out);
    }

    /** 只对纯 ASCII 标签做小写化；含中文的整体不动，避免误伤。 */
    private static String latinLower(String s) {
        return s.chars().allMatch(c -> c < 0x80) ? s.toLowerCase(Locale.ROOT) : s;
    }
}
```

测试 `TagNormalizerTest.java`：

```java
package com.jotlog.enrich;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TagNormalizerTest {
    @Test
    void 去井号去空白_拉丁小写_去重() {
        assertEquals(List.of("ai", "逆向工程"),
                TagNormalizer.normalize(List.of("  AI ", "#ai", " 逆向工程 ")));
    }

    @Test
    void 最多五个_超长截断() {
        var r = TagNormalizer.normalize(List.of("a1", "a2", "a3", "a4", "a5", "a6"));
        assertEquals(5, r.size());
        assertEquals(16, TagNormalizer.normalize(List.of("一二三四五六七八九十一二三四五六七")).get(0).length());
    }
}
```

- [ ] **Step 2: AiResult + AiResponseParser**

```java
package com.jotlog.enrich;

import java.util.List;

/** AI 的结构化结论。categoryId 为 null 且 suggestedCategory 非空 = 建议新分类。 */
public record AiResult(
        Long categoryId,
        String suggestedCategory,
        String title,
        String summary,
        List<String> tags
) {
}
```

```java
package com.jotlog.enrich;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jotlog.repository.CategoryRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 严格解析模型输出。任何不合规都抛 IllegalArgumentException，
 * 由 worker 记 ai_error，绝不把半成品写进补充区。
 */
public final class AiResponseParser {

    private static final int TITLE_MAX = 40;
    private static final int SUMMARY_MAX = 200;
    private static final int SUGGEST_MAX = 16;

    private AiResponseParser() {
    }

    public static AiResult parse(String json, List<CategoryRepository.CategoryRow> categories) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("模型返回为空");
        }
        String body = stripCodeFence(json.strip());
        Map<?, ?> node;
        try {
            node = new ObjectMapper().readValue(body, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("模型返回不是合法 JSON: " + e.getMessage());
        }

        Set<Long> validIds = categories.stream()
                .map(CategoryRepository.CategoryRow::id).collect(Collectors.toSet());

        Long categoryId = null;
        Object cid = node.get("categoryId");
        if (cid instanceof Number n) {
            long v = n.longValue();
            if (!validIds.contains(v)) {
                throw new IllegalArgumentException("模型返回了不存在的 categoryId=" + v);
            }
            categoryId = v;
        } else if (cid != null) {
            // 容忍 null；非数字非 null 直接拒
            throw new IllegalArgumentException("categoryId 必须是数字或 null");
        }

        String title = cleanStr(node.get("title"));
        String summary = cleanStr(node.get("summary"));
        if (title == null && summary == null) {
            throw new IllegalArgumentException("title 与 summary 不能同时为空");
        }
        if (title != null) title = clip(title, TITLE_MAX);
        if (summary != null) summary = clip(summary, SUMMARY_MAX);

        String suggested = categoryId == null ? clip(cleanStr(node.get("suggestedCategory")), SUGGEST_MAX) : null;

        Object tagsNode = node.get("tags");
        if (!(tagsNode instanceof List<?> rawTags)) {
            throw new IllegalArgumentException("tags 必须是数组");
        }
        List<String> tags = TagNormalizer.normalize(
                rawTags.stream().filter(t -> t instanceof String).map(t -> (String) t).toList());

        return new AiResult(categoryId, suggested, title, summary, tags);
    }

    private static String stripCodeFence(String s) {
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                return s.substring(firstNl + 1, lastFence).strip();
            }
        }
        return s;
    }

    private static String cleanStr(Object o) {
        if (!(o instanceof String s)) return null;
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

`AiResponseParserTest.java`：

```java
package com.jotlog.enrich;

import com.jotlog.repository.CategoryRepository.CategoryRow;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AiResponseParserTest {

    private final List<CategoryRow> cats = List.of(
            new CategoryRow(1, "工具软件", "#000", 1, true, 0),
            new CategoryRow(2, "开源项目", "#000", 2, true, 0));

    @Test
    void 正常解析_带代码围栏() {
        String json = """
                ```json
                {"categoryId":2,"suggestedCategory":null,"title":"rea 反编译工具",
                 "summary":"开源的本地软件反编译工具。","tags":["#逆向","AI","ai"]}
                ```
                """;
        AiResult r = AiResponseParser.parse(json, cats);
        assertEquals(2L, r.categoryId());
        assertEquals("rea 反编译工具", r.title());
        assertEquals(List.of("逆向", "ai"), r.tags());
        assertNull(r.suggestedCategory());
    }

    @Test
    void 非法分类id被拒() {
        assertThrows(IllegalArgumentException.class, () ->
                AiResponseParser.parse("{\"categoryId\":99,\"title\":\"x\",\"summary\":\"y\",\"tags\":[]}", cats));
    }

    @Test
    void 无分类时允许建议名() {
        AiResult r = AiResponseParser.parse(
                "{\"categoryId\":null,\"suggestedCategory\":\"硬件折腾\",\"title\":\"x\",\"summary\":\"y\",\"tags\":[]}",
                cats);
        assertNull(r.categoryId());
        assertEquals("硬件折腾", r.suggestedCategory());
    }

    @Test
    void 非JSON被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> AiResponseParser.parse("我觉得这是一个好工具", cats));
    }
}
```

- [ ] **Step 3: 跑两个测试类**

Run: `mvn -B -Dtest=TagNormalizerTest,AiResponseParserTest test`，Expected: 全 PASS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/jotlog/enrich/TagNormalizer.java src/main/java/com/jotlog/enrich/AiResult.java src/main/java/com/jotlog/enrich/AiResponseParser.java src/test/java/com/jotlog/enrich/
git commit -m "feat(enrich): 标签归一化与 AI JSON 严格解析（含单测）"
```

---

## Task 8: DeepSeek 客户端与编排

**Files（Create）:**
- `enrich/AiOrganizer.java`、`enrich/DeepSeekConfig.java`、`enrich/DeepSeekAiOrganizer.java`

- [ ] **Step 1: 接口 + 条件化 Bean 配置**

```java
package com.jotlog.enrich;

import com.jotlog.repository.CategoryRepository.CategoryRow;
import java.util.List;

public interface AiOrganizer {

    /** 分类 + 总结 + 标签。素材不足时模型须如实说明，解析/校验失败抛异常。 */
    AiResult organize(String rawInput, List<LinkMaterial> materials,
                      List<CategoryRow> categories, List<String> popularTags);

    /** 冷启动引导：基于抽样原文建议 6-8 个分类名。 */
    List<String> suggestCategories(List<String> sampleRawInputs);
}
```

```java
package com.jotlog.enrich;

import com.jotlog.config.AiProperties;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 只有 jotlog.ai.enabled=true 时才创建模型 Bean。
 * key 为空时 isReady=false，organizer 也不建（见下面条件）。
 */
@Configuration
@ConditionalOnProperty(name = "jotlog.ai.enabled", havingValue = "true")
public class DeepSeekConfig {

    @Bean
    public OpenAiChatModel openAiChatModel(AiProperties props) {
        if (!props.isReady()) {
            throw new IllegalStateException("jotlog.ai.enabled=true 但未配置 jotlog.ai.api-key");
        }
        return OpenAiChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(props.getModel())
                .timeout(Duration.ofSeconds(30))
                .maxRetries(1)
                .build();
    }
}
```

- [ ] **Step 2: DeepSeekAiOrganizer**

```java
package com.jotlog.enrich;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jotlog.config.AiProperties;
import com.jotlog.repository.CategoryRepository;
import com.jotlog.repository.CategoryRepository.CategoryRow;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(ChatModel.class)
public class DeepSeekAiOrganizer implements AiOrganizer {

    private final ChatModel chat;
    private final CategoryRepository categories;
    private final AiProperties props;

    public DeepSeekAiOrganizer(ChatModel chat, CategoryRepository categories, AiProperties props) {
        this.chat = chat;
        this.categories = categories;
        this.props = props;
    }

    @Override
    public AiResult organize(String rawInput, List<LinkMaterial> materials,
                             List<CategoryRow> cats, List<String> popularTags) {
        String prompt = """
                你在整理用户的私人随手记。只输出 JSON，不要输出任何解释或 markdown 代码块。

                规则：
                1. 只依据"材料"，不补充材料之外的信息；材料不足就在 summary 里写"信息不足"，不要猜测。
                2. categoryId 必须从"分类清单"中选一个 id；都不合适时填 null，
                   并在 suggestedCategory 给一个不超过 6 个字的新分类名。
                3. tags 优先复用"常用标签"里的原词；确需新词时给 3-5 个，每个尽量不超过 6 个字。
                4. summary 不超过 200 字，中文，平实，不要营销腔。
                   视频类没有正文时只能描述页面标题与用户转发文案，写成"分享文案称…"，禁止描述画面。
                5. title 不超过 40 字。

                输出格式：{"categoryId":数字或null,"suggestedCategory":字符串或null,
                "title":"","summary":"","tags":["",""]}
                """
                + "\n分类清单：\n" + cats.stream().map(c -> c.id() + ":" + c.name())
                        .collect(Collectors.joining(", "))
                + "\n常用标签：" + String.join("、", popularTags)
                + "\n用户原话：\n" + clip(rawInput, 2000)
                + "\n材料：\n" + materialsText(materials);

        String answer = chat.chat(prompt);
        return AiResponseParser.parse(answer, cats);
    }

    @Override
    public List<String> suggestCategories(List<String> samples) {
        String prompt = """
                下面是用户随手记的一些原文抽样。请归纳出 6-8 个互斥、好懂的中文分类名
                （每个不超过 6 个字，覆盖工具/项目/文章/视频/想法/资讯这类常见场景，按 JSON 输出）。
                只输出：{"categories":["名1","名2"]}

                原文抽样：
                """ + samples.stream().map(s -> "- " + clip(s, 200)).collect(Collectors.joining("\n"));
        String answer = chat.chat(prompt);
        try {
            String body = answer.strip().replaceAll("(?s)^```(json)?", "").replaceAll("```$", "").strip();
            Map<?, ?> node = new ObjectMapper().readValue(body, Map.class);
            Object arr = node.get("categories");
            if (arr instanceof List<?> list) {
                return list.stream().filter(x -> x instanceof String)
                        .map(x -> ((String) x).strip()).filter(x -> !x.isEmpty()).limit(8).toList();
            }
        } catch (Exception ignore) {
            // 落到兜底
        }
        return List.of();
    }

    private static String materialsText(List<LinkMaterial> ms) {
        if (ms == null || ms.isEmpty()) {
            return "（本条没有链接）";
        }
        StringBuilder sb = new StringBuilder();
        for (LinkMaterial m : ms) {
            sb.append("- [").append(m.kind()).append("] ").append(m.url());
            if (m.title() != null) sb.append("\n  标题：").append(m.title());
            if (m.excerpt() != null) sb.append("\n  摘录：").append(m.excerpt());
            if ("failed".equals(m.status())) sb.append("\n  （抓取失败，不要猜测该链接内容）");
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 供 worker 记录模型来源。 */
    public String modelName() {
        return props.getModel();
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn -B clean compile`，Expected: BUILD SUCCESS（enabled 默认 false，Bean 不创建也能编译）。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/jotlog/enrich/AiOrganizer.java src/main/java/com/jotlog/enrich/DeepSeekConfig.java src/main/java/com/jotlog/enrich/DeepSeekAiOrganizer.java
git commit -m "feat(enrich): DeepSeek(OpenAI兼容) 条件化客户端与分类总结编排"
```

---

## Task 9: 结果回写服务 + Worker + 队列改造

**Files:**
- Modify: `core/PolishQueue.java`
- Create: `enrich/EnrichmentService.java`、`enrich/PolishWorker.java`、`enrich/BatchPolishService.java`
- Modify: `web/EntryController.java`（web 录入也入增强队列）

- [ ] **Step 1: PolishQueue 增加阻塞消费与手动入队**

在 `PolishQueue` 中增加：

```java
    /** worker 主循环用。 */
    public Task take() throws InterruptedException {
        return queue.take();
    }

    /** 手动重跑 / 批量补跑：按已入库条目构造任务，无回执目标。 */
    public void enqueueEntry(long entryId, String url, String rawInput, int attempts) {
        boolean ok = queue.offer(new Task(entryId, "manual", url,
                com.jotlog.core.Entry.ReplyTarget.none(), rawInput, attempts));
        if (!ok) {
            log.warn("增强队列已满，丢弃 id={}", entryId);
        }
    }
```

`Task` record 增加 `int attempts` 字段；现有 `enqueue(long, Entry)` 内构造改为 `new Task(entryId, entry.source(), entry.url(), entry.replyTarget(), entry.rawInput(), 0)`。

- [ ] **Step 2: EnrichmentService（事务化回写：分类 + 标签 + 补充区）**

```java
package com.jotlog.enrich;

import com.jotlog.repository.CategoryRepository;
import com.jotlog.repository.EntryRepository;
import com.jotlog.repository.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AI 结论落库的唯一入口。
 * 补充区字段 + category_id + entry_tags 在一个事务里替换；
 * 全程不出现 raw_input。
 */
@Service
public class EnrichmentService {

    /** note 不调 AI 时的规则归属分类名。 */
    public static final String NOTE_CATEGORY = "灵感随记";

    private final EntryRepository entries;
    private final TagRepository tags;
    private final CategoryRepository categories;

    public EnrichmentService(EntryRepository entries, TagRepository tags, CategoryRepository categories) {
        this.entries = entries;
        this.tags = tags;
        this.categories = categories;
    }

    @Transactional
    public void applyDone(long entryId, AiResult result, String model, int latencyMs) {
        List<Long> tagIds = result.tags().stream().map(tags::findOrCreate).toList();
        tags.replaceForEntry(entryId, tagIds);
        String snapshot = String.join(",", result.tags());
        entries.applyAiResult(entryId, result.title(), result.summary(), snapshot,
                result.categoryId(), result.suggestedCategory(), model, latencyMs);
    }

    @Transactional
    public void applyNoteSkipped(long entryId) {
        CategoryRepository.CategoryRow c = categories.findByName(NOTE_CATEGORY);
        entries.skipWithCategory(entryId, c == null ? null : c.id());
    }
}
```

- [ ] **Step 3: PolishWorker（单线程串行，失败重试一次）**

```java
package com.jotlog.enrich;

import com.jotlog.config.AiProperties;
import com.jotlog.core.PolishQueue;
import com.jotlog.repository.CategoryRepository;
import com.jotlog.repository.EntryRepository;
import com.jotlog.repository.TagRepository;
import com.jotlog.repository.UserSettingsRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 增强 worker。AI 未配置时整个 Bean 不创建（队列仍可观测、可入队，条目停在 pending）。
 * 单线程串行：个人量级足够，且天然规避模型限流；失败延迟 30s 重试一次，仍失败置 failed。
 */
@Service
@ConditionalOnBean(AiOrganizer.class)
public class PolishWorker {

    private static final Logger log = LoggerFactory.getLogger(PolishWorker.class);
    private static final int MAX_ATTEMPTS = 2;

    private final PolishQueue queue;
    private final AiOrganizer ai;
    private final LinkFetchService fetcher;
    private final EnrichmentService enrichment;
    private final EntryRepository entries;
    private final CategoryRepository categories;
    private final TagRepository tags;
    private final UserSettingsRepository settings;
    private final AiProperties props;
    private final DeepSeekAiOrganizer organizer; // 仅用于取模型名，可为通用接口方法

    private final ScheduledExecutorService scheduler;
    private volatile boolean running = true;

    public PolishWorker(PolishQueue queue, AiOrganizer ai, LinkFetchService fetcher,
                        EnrichmentService enrichment, EntryRepository entries,
                        CategoryRepository categories, TagRepository tags,
                        UserSettingsRepository settings, AiProperties props) {
        this.queue = queue;
        this.ai = ai;
        this.fetcher = fetcher;
        this.enrichment = enrichment;
        this.entries = entries;
        this.categories = categories;
        this.tags = tags;
        this.settings = settings;
        this.props = props;
        this.organizer = (ai instanceof DeepSeekAiOrganizer d) ? d : null;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "jotlog-polish");
            t.setDaemon(true);
            return t;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
    }

    @PostConstruct
    void start() {
        scheduler.submit(this::loop);
    }

    @PreDestroy
    void stop() {
        running = false;
        scheduler.shutdownNow();
    }

    private void loop() {
        while (running) {
            try {
                PolishQueue.Task task = queue.take();
                process(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("增强循环异常", e);
            }
        }
    }

    void process(PolishQueue.Task task) {
        long id = task.entryId();
        var row = entries.findById(id);
        if (row == null) {
            return;
        }
        boolean polishNotes = settings.getBool("ai_polish_notes", props.isPolishNotes());
        if ("note".equals(row.entryType()) && !polishNotes) {
            enrichment.applyNoteSkipped(id);
            return;
        }

        long started = System.currentTimeMillis();
        try {
            entries.markAiProcessing(id);
            var materials = fetcher.fetchAll(id, row.rawInput());
            var cats = categories.listAll();
            var popular = tags.popular(15);
            AiResult result = ai.organize(row.rawInput(), materials, cats, popular);
            enrichment.applyDone(id, result,
                    organizer != null ? organizer.modelName() : props.getModel(),
                    (int) (System.currentTimeMillis() - started));
            log.info("AI 增强完成 id={} 耗时={}ms 标签={}", id, System.currentTimeMillis() - started,
                    result.tags());
        } catch (Exception e) {
            log.warn("AI 增强失败 id={} attempt={}", id, task.attempts() + 1, e);
            if (task.attempts() + 1 < MAX_ATTEMPTS) {
                scheduler.schedule(
                        () -> queue.enqueueEntry(id, row.url(), row.rawInput(), task.attempts() + 1),
                        30, TimeUnit.SECONDS);
            } else {
                entries.markAiFailed(id, e.getMessage() == null ? "unknown" : e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: BatchPolishService（批量补跑，可中断、带进度）**

```java
package com.jotlog.enrich;

import com.jotlog.core.PolishQueue;
import com.jotlog.repository.EntryRepository;
import com.jotlog.repository.UserSettingsRepository;
import com.jotlog.config.AiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 批量补跑：扫 pending/failed 入队，每条间隔 1.2 秒；状态存内存（个人单实例够用）。 */
@Service
@ConditionalOnBean(AiOrganizer.class)
public class BatchPolishService {

    private final EntryRepository entries;
    private final PolishQueue queue;
    private final UserSettingsRepository settings;
    private final AiProperties props;
    private final ScheduledExecutorService exec;

    private volatile boolean active = false;
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger enqueued = new AtomicInteger();

    public BatchPolishService(EntryRepository entries, PolishQueue queue,
                              UserSettingsRepository settings, AiProperties props) {
        this.entries = entries;
        this.queue = queue;
        this.settings = settings;
        this.props = props;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jotlog-batch-polish");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        if (active) return;
        boolean includeNotes = settings.getBool("ai_polish_notes", props.isPolishNotes());
        List<Long> ids = entries.pendingForPolish(500, includeNotes);
        total.set(ids.size());
        enqueued.set(0);
        active = true;
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            // 重跑需要原文；延迟按序入队，真正处理时 worker 会再 findById
            exec.schedule(() -> {
                if (!active) return;
                var row = entries.findById(id);
                if (row != null) {
                    queue.enqueueEntry(id, row.url(), row.rawInput(), 0);
                }
                enqueued.incrementAndGet();
            }, 1200L * (i + 1), TimeUnit.MILLISECONDS);
        }
        // 全部入队后 30 秒收尾（处理进度由条目 ai_status 体现，这里只管入队节奏）
        exec.schedule(() -> active = false, 1200L * (ids.size() + 25), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        active = false;
    }

    public java.util.Map<String, Object> progress() {
        return java.util.Map.of("active", active, "total", total.get(), "enqueued", enqueued.get());
    }
}
```

- [ ] **Step 5: web 录入也入增强队列**

`EntryController.create` 在 `ingest.persist(entry)` 之后、return 之前加：

```java
        if (!result.duplicate()) {
            polishQueue.enqueue(result.id(), entry);
        }
```

（`polishQueue` 已注入。）

- [ ] **Step 6: 编译 + 提交**

Run: `mvn -B clean compile`，Expected: BUILD SUCCESS。

```bash
git add src/main/java/com/jotlog/core/PolishQueue.java src/main/java/com/jotlog/enrich/ src/main/java/com/jotlog/web/EntryController.java
git commit -m "feat(enrich): AI worker 串行消费、失败重试一次、批量补跑与回写服务"
```

---

## Task 10: REST 接口（分类 / 标签 / 条目编辑 / AI 管理）

**Files（Create）:**
- `web/CategoryController.java`、`web/TagController.java`、`web/AiController.java`
- Modify: `web/EntryController.java`

- [ ] **Step 1: CategoryController**

```java
package com.jotlog.web;

import com.jotlog.repository.CategoryRepository;
import com.jotlog.repository.CategoryRepository.CategoryRow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryRepository categories;

    public CategoryController(CategoryRepository categories) {
        this.categories = categories;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("categories", categories.list(), "uncategorized", categories.uncategorizedCount());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody NameRequest req) {
        String name = req == null ? null : req.name() == null ? null : req.name().trim();
        if (name == null || name.isBlank() || name.length() > 16) {
            return ResponseEntity.badRequest().body(Map.of("error", "分类名需 1-16 个字"));
        }
        if (categories.findByName(name) != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "同名分类已存在"));
        }
        long id = categories.insert(name, req.color());
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 冷启动引导确认时批量建（已存在的名字跳过）。 */
    @PostMapping("/bulk")
    public Map<String, Object> bulk(@RequestBody BulkRequest req) {
        List<String> names = req == null || req.names() == null ? List.of() : req.names();
        int created = 0;
        for (String n : names) {
            String name = n == null ? "" : n.trim();
            if (name.isEmpty() || name.length() > 16 || categories.findByName(name) != null) continue;
            categories.insert(name, null);
            created++;
        }
        return Map.of("created", created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> rename(@PathVariable long id, @RequestBody NameRequest req) {
        CategoryRow c = categories.findById(id);
        if (c == null) return ResponseEntity.notFound().build();
        String name = req.name() == null ? c.name() : req.name().trim();
        if (name.isBlank() || name.length() > 16) {
            return ResponseEntity.badRequest().body(Map.of("error", "分类名需 1-16 个字"));
        }
        categories.rename(id, name, req.color());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    public record NameRequest(String name, String color) {
    }

    public record BulkRequest(List<String> names) {
    }
}
```

- [ ] **Step 2: TagController**

```java
package com.jotlog.web;

import com.jotlog.repository.TagRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagRepository tags;

    public TagController(TagRepository tags) {
        this.tags = tags;
    }

    @GetMapping
    public List<String> suggest(@RequestParam(required = false) String q) {
        return tags.suggest(q, 20);
    }
}
```

- [ ] **Step 3: EntryController 加分类/标签/重跑/链接接口**

构造器注入新增：`CategoryRepository categories`、`TagRepository tagRepo`、
`ObjectProvider<com.jotlog.enrich.BatchPolishService> batch`、
`ObjectProvider<com.jotlog.enrich.AiOrganizer> ai`。

新增方法：

```java
    @PutMapping("/entries/{id}/category")
    public ResponseEntity<?> setCategory(@PathVariable long id, @RequestBody CategoryRequest req) {
        if (entries.findById(id) == null) return ResponseEntity.notFound().build();
        Long cid = req.categoryId();
        if (cid != null && categories.findById(cid) == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "分类不存在"));
        }
        // acceptSuggestion：采纳 AI 建议名 → 建分类并挂上去，清空 suggested_category
        if (Boolean.TRUE.equals(req.acceptSuggestion())) {
            var row = entries.findById(id);
            if (row.suggestedCategory() != null) {
                var c = categories.findByName(row.suggestedCategory());
                cid = c != null ? c.id() : categories.insert(row.suggestedCategory(), null);
            }
        }
        entries.setCategory(id, cid);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PutMapping("/entries/{id}/tags")
    public ResponseEntity<?> setTags(@PathVariable long id, @RequestBody TagsRequest req) {
        if (entries.findById(id) == null) return ResponseEntity.notFound().build();
        var names = com.jotlog.enrich.TagNormalizer.normalize(
                req == null || req.tags() == null ? java.util.List.of() : req.tags());
        var ids = names.stream().map(tagRepo::findOrCreate).toList();
        tagRepo.replaceForEntry(id, ids);
        return ResponseEntity.ok(Map.of("ok", true, "tags", names));
    }

    @PostMapping("/entries/{id}/repolish")
    public ResponseEntity<?> repolish(@PathVariable long id) {
        var row = entries.findById(id);
        if (row == null) return ResponseEntity.notFound().build();
        if (ai.getIfAvailable() == null) {
            return ResponseEntity.status(503).body(Map.of("error", "AI 未配置或未启用"));
        }
        polishQueue.enqueueEntry(id, row.url(), row.rawInput(), 0);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/entries/{id}/links")
    public List<?> links(@PathVariable long id) {
        return entryLinks.listByEntry(id);
    }

    public record CategoryRequest(Long categoryId, Boolean acceptSuggestion) {
    }

    public record TagsRequest(List<String> tags) {
    }
```

`EntryRepository` 加一个方法（Jdbc 实现）：

```java
    void setCategory(long id, Long categoryId);
```

```java
    @Override
    public void setCategory(long id, Long categoryId) {
        jdbc.update("UPDATE entries SET category_id = ?, suggested_category = NULL WHERE id = ?",
                categoryId, id);
    }
```

注入字段加 `private final EntryLinkRepository entryLinks;`（构造器参数同步加）。

- [ ] **Step 4: AiController（状态 / 笔记开关 / 冷启动 / 批量）**

```java
package com.jotlog.web;

import com.jotlog.config.AiProperties;
import com.jotlog.enrich.AiOrganizer;
import com.jotlog.enrich.BatchPolishService;
import com.jotlog.repository.CategoryRepository;
import com.jotlog.repository.EntryRepository;
import com.jotlog.repository.UserSettingsRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final List<String> SEED_CATEGORIES = List.of(
            "工具软件", "开源项目", "教程文章", "视频影像", "灵感随记", "资讯动态", "资源素材");

    private final AiProperties props;
    private final ObjectProvider<AiOrganizer> ai;
    private final ObjectProvider<BatchPolishService> batch;
    private final UserSettingsRepository settings;
    private final EntryRepository entries;
    private final CategoryRepository categories;

    public AiController(AiProperties props, ObjectProvider<AiOrganizer> ai,
                        ObjectProvider<BatchPolishService> batch, UserSettingsRepository settings,
                        EntryRepository entries, CategoryRepository categories) {
        this.props = props;
        this.ai = ai;
        this.batch = batch;
        this.settings = settings;
        this.entries = entries;
        this.categories = categories;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        boolean ready = props.isReady() && ai.getIfAvailable() != null;
        return Map.of(
                "enabled", props.isEnabled(),
                "configured", props.isReady(),
                "ready", ready,
                "model", props.getModel(),
                "polishNotes", settings.getBool("ai_polish_notes", props.isPolishNotes()),
                "batch", batch.getIfAvailable() != null ? batch.getObject().progress() : Map.of("active", false));
    }

    @PutMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody SettingsRequest req) {
        if (req != null && req.polishNotes() != null) {
            settings.put("ai_polish_notes", String.valueOf(req.polishNotes()));
        }
        return Map.of("ok", true, "polishNotes",
                settings.getBool("ai_polish_notes", props.isPolishNotes()));
    }

    /** 冷启动建议：AI 可用就基于抽样生成；不可用直接给内置种子。只预览，不直接落库。 */
    @PostMapping("/bootstrap-categories")
    public Map<String, Object> bootstrap() {
        List<String> suggested;
        if (ai.getIfAvailable() != null) {
            List<String> samples = entries.sampleRawInput(40);
            suggested = ai.getObject().suggestCategories(samples);
            if (suggested.isEmpty()) suggested = SEED_CATEGORIES;
        } else {
            suggested = SEED_CATEGORIES;
        }
        return Map.of("suggestions", suggested, "existing",
                categories.list().stream().map(CategoryRepository.CategoryRow::name).toList());
    }

    @PostMapping("/batch-repolish")
    public ResponseEntity<?> batch(@RequestBody BatchRequest req) {
        var svc = batch.getIfAvailable();
        if (svc == null) {
            return ResponseEntity.status(503).body(Map.of("error", "AI 未配置或未启用"));
        }
        if (req != null && "stop".equals(req.action())) {
            svc.stop();
        } else {
            svc.start();
        }
        return ResponseEntity.ok(svc.progress());
    }

    public record SettingsRequest(Boolean polishNotes) {
    }

    public record BatchRequest(String action) {
    }
}
```

- [ ] **Step 5: 编译**

Run: `mvn -B clean compile`，Expected: BUILD SUCCESS。

- [ ] **Step 6: 跑全部后端单测 + 提交**

Run: `mvn -B test`，Expected: 全 PASS。

```bash
git add src/main/java/com/jotlog/web/
git commit -m "feat(api): 分类/标签/重跑/链接/AI状态与批量补跑接口"
```

---

## Task 11: 前端基础——类型、API、路由

**Files:**
- Modify: `web/src/types.ts`、`web/src/api/http.ts`、`web/src/router/index.ts`

- [ ] **Step 1: types.ts 增加类型**

```ts
export interface Category {
  id: number
  name: string
  color: string | null
  sortOrder: number
  system: boolean
  count: number
}

export interface EntryLink {
  id: number
  url: string
  domain: string | null
  kind: string
  fetchedTitle: string | null
  fetchStatus: string
}

export interface AiStatusInfo {
  enabled: boolean
  configured: boolean
  ready: boolean
  model: string
  polishNotes: boolean
  batch: { active: boolean; total: number; enqueued: number }
}
```

`Entry` 接口中 `aiTags: string | null` 之后追加：

```ts
  categoryId: number | null
  categoryName: string | null
  suggestedCategory: string | null
  tags: string[]
```

`Stats` 不动（待分类数走 categories 接口）。

- [ ] **Step 2: http.ts 扩展 api 对象**

`entries` 的参数类型加 `categoryId?: number; tag?: string; uncategorized?: boolean`，并透传到 params。

在 `api` 对象内追加：

```ts
  categories: () =>
    request<{ categories: Category[]; uncategorized: number }>('/api/categories'),

  createCategory: (name: string, color?: string | null) =>
    request<{ id: number }>('/api/categories', { method: 'POST', body: { name, color: color ?? null } }),

  bulkCategories: (names: string[]) =>
    request<{ created: number }>('/api/categories/bulk', { method: 'POST', body: { names } }),

  tagsSuggest: (q?: string) =>
    request<string[]>('/api/tags', { params: { q } }),

  setEntryCategory: (id: number, categoryId: number | null, acceptSuggestion = false) =>
    request<{ ok: boolean }>(`/api/entries/${id}/category`, {
      method: 'PUT',
      body: { categoryId, acceptSuggestion },
    }),

  setEntryTags: (id: number, tags: string[]) =>
    request<{ ok: boolean; tags: string[] }>(`/api/entries/${id}/tags`, {
      method: 'PUT',
      body: { tags },
    }),

  repolish: (id: number) =>
    request<{ ok: boolean }>(`/api/entries/${id}/repolish`, { method: 'POST' }),

  entryLinks: (id: number) => request<EntryLink[]>(`/api/entries/${id}/links`),

  aiStatus: () => request<AiStatusInfo>('/api/ai/status'),
  updateAiSettings: (polishNotes: boolean) =>
    request<{ ok: boolean; polishNotes: boolean }>('/api/ai/settings', {
      method: 'PUT',
      body: { polishNotes },
    }),
  bootstrapCategories: () =>
    request<{ suggestions: string[]; existing: string[] }>('/api/ai/bootstrap-categories', {
      method: 'POST',
    }),
  batchRepolish: (action: 'start' | 'stop') =>
    request<{ active: boolean; total: number; enqueued: number }>('/api/ai/batch-repolish', {
      method: 'POST',
      body: { action },
    }),
```

import 行改为包含新类型：`import type { AttachmentMeta, Category, Entry, EntryLink, AiStatusInfo, Page, Stats, User } from '../types'`

- [ ] **Step 3: 路由加分类/标签页**

router children 中（archive 之后）加：

```ts
        {
          path: 'c/:id',
          name: 'category',
          component: () => import('../views/TimelineView.vue'),
          props: (route) => ({ mode: 'category', categoryId: Number(route.params.id) }),
          meta: { title: '分类' },
        },
        {
          path: 'tag/:name',
          name: 'tag',
          component: () => import('../views/TimelineView.vue'),
          props: (route) => ({ mode: 'tag', tag: String(route.params.name) }),
          meta: { title: '标签' },
        },
        {
          path: 'inbox',
          name: 'uncategorized',
          component: () => import('../views/TimelineView.vue'),
          props: { mode: 'uncategorized' },
          meta: { title: '待分类' },
        },
```

- [ ] **Step 4: 类型检查**

Run: `cd web; npm run typecheck`。Expected: 报错仅来自尚未改的 Vue 组件（Entry 新字段在组件里尚未使用不报错；TimelineView mode 类型下一步改）。若仅类型层面无阻断即可，本任务与 Task 14 一起再次 typecheck。

- [ ] **Step 5: Commit**

```bash
git add web/src/types.ts web/src/api/http.ts web/src/router/index.ts
git commit -m "feat(web): 分类标签相关类型、API 与路由"
```

---

## Task 12: 分类 store + 色点组件 + 侧边栏导航

**Files（Create/Modify）:**
- Create: `web/src/stores/categories.ts`、`web/src/components/CategoryDot.vue`
- Modify: `web/src/components/SideNav.vue`

- [ ] **Step 1: stores/categories.ts**

```ts
import { defineStore } from 'pinia'
import { api } from '../api/http'
import type { Category } from '../types'

interface State {
  categories: Category[]
  uncategorized: number
  loaded: boolean
}

export const useCategoryStore = defineStore('categories', {
  state: (): State => ({ categories: [], uncategorized: 0, loaded: false }),
  actions: {
    async load(force = false) {
      if (this.loaded && !force) return
      const r = await api.categories()
      this.categories = r.categories
      this.uncategorized = r.uncategorized
      this.loaded = true
    },
  },
})
```

- [ ] **Step 2: CategoryDot.vue**

```vue
<template>
  <span
    class="inline-block h-[8px] w-[8px] shrink-0 rounded-full"
    :style="{ background: color ?? '#9a9a8f' }"
  />
</template>

<script setup lang="ts">
defineProps<{ color?: string | null }>()
</script>
```

- [ ] **Step 3: SideNav 导航区插入分类分组**

`<nav>` 内现有 `v-for` 之后追加（完整宽模式显示文字+计数，窄轨显示色点列）：

```vue
      <!-- 分类分组 -->
      <div class="mt-3 pt-2 border-line-soft border-t">
        <RouterLink
          v-for="c in categoryStore.categories"
          :key="c.id"
          :to="`/c/${c.id}`"
          :title="collapsed ? c.name : undefined"
          class="mb-0.5 flex items-center gap-3 rounded-[3px] px-2.5 py-2 text-[13.5px] transition-colors"
          :class="[
            collapsed ? 'justify-center' : '',
            route.path === `/c/${c.id}` ? 'bg-ink text-paper' : 'text-ink-2 hover:bg-paper-sunken hover:text-ink',
          ]"
        >
          <CategoryDot :color="c.color" class="shrink-0" />
          <span v-if="!collapsed" class="flex-1 truncate">{{ c.name }}</span>
          <span v-if="!collapsed && c.count" class="tabular text-[12px] text-ink-3">{{ c.count }}</span>
        </RouterLink>

        <RouterLink
          to="/inbox"
          :title="collapsed ? '待分类' : undefined"
          class="mb-0.5 flex items-center gap-3 rounded-[3px] px-2.5 py-2 text-[13.5px] transition-colors"
          :class="[
            collapsed ? 'justify-center' : '',
            route.path === '/inbox'
              ? 'bg-ink text-paper'
              : 'text-ink-2 hover:bg-paper-sunken hover:text-ink',
          ]"
        >
          <Icon name="unknown" :size="15" class="shrink-0" />
          <span v-if="!collapsed" class="flex-1 truncate">待分类</span>
          <span v-if="!collapsed && categoryStore.uncategorized" class="tabular text-[12px] text-ink-3">
            {{ categoryStore.uncategorized }}
          </span>
        </RouterLink>
      </div>
```

script 部分：import `CategoryDot`、`useCategoryStore`、`useRoute`；`const route = useRoute()`（已有 useRoute 则复用）；const `categoryStore = useCategoryStore()`；组件挂载时 `categoryStore.load()`：

```ts
import { onMounted } from 'vue'
// ...
onMounted(() => { categoryStore.load().catch(() => {}) })
```

- [ ] **Step 4: typecheck**

Run: `cd web; npm run typecheck`，Expected: 通过。

- [ ] **Step 5: Commit**

```bash
git add web/src/stores/categories.ts web/src/components/CategoryDot.vue web/src/components/SideNav.vue
git commit -m "feat(web): 侧边栏分类导航与待分类入口"
```

---

## Task 13: TimelineView 支持分类/标签/待分类模式

**Files:**
- Modify: `web/src/views/TimelineView.vue`

- [ ] **Step 1: props 与标题**

props 改为：

```ts
const props = withDefaults(
  defineProps<{
    mode?: 'all' | 'starred' | 'archive' | 'category' | 'tag' | 'uncategorized'
    categoryId?: number
    tag?: string
  }>(),
  { mode: 'all' },
)
```

模式标题（替换模板里 `mode !== 'all'` 那个 h2）：

```vue
    <h2 v-if="mode !== 'all'" class="t-rule mb-3">{{ heading }}</h2>
```

```ts
const heading = computed(() => {
  if (props.mode === 'starred') return '星标'
  if (props.mode === 'archive') return '归档'
  if (props.mode === 'uncategorized') return '待分类'
  if (props.mode === 'tag') return `# ${props.tag ?? ''}`
  return '分类'
})
```

- [ ] **Step 2: 请求参数与重新加载**

`fetchNextPage` 内 api.entries 改为：

```ts
    const result = await api.entries({
      starred: props.mode === 'starred' ? true : undefined,
      archived: props.mode === 'archive',
      categoryId: props.mode === 'category' ? props.categoryId : undefined,
      tag: props.mode === 'tag' ? props.tag : undefined,
      uncategorized: props.mode === 'uncategorized',
      page: page.value,
      size: SIZE,
    })
```

现有 `watch(() => props.mode, ...)` 改为同时监听路由参数：

```ts
watch(
  () => [props.mode, props.categoryId, props.tag],
  () => reload(),
)
```

空态文案补两个分支（emptyTitle / emptyHint）：uncategorized → "没有待分类的内容"；category/tag → "这个分类下还没有内容"。

- [ ] **Step 3: typecheck + commit**

Run: `cd web; npm run typecheck`，Expected: 通过。

```bash
git add web/src/views/TimelineView.vue
git commit -m "feat(web): 时间线支持分类/标签/待分类三种过滤视图"
```

---

## Task 14: 条目卡片——分类色点、标签、处理状态

**Files:**
- Modify: `web/src/components/EntryCard.vue`

- [ ] **Step 1: 标签与分类展示**

`tags` computed 改为优先用结构化标签，回退快照字符串：

```ts
import CategoryDot from './CategoryDot.vue'
import { useCategoryStore } from '../stores/categories'
const categoryStore = useCategoryStore()

const tags = computed(() => {
  const structured = props.entry.tags ?? []
  const fromSnapshot = (props.entry.aiTags ?? '')
    .split(/[,，\s]+/)
    .map((t) => t.trim())
    .filter(Boolean)
  const all = structured.length ? structured : fromSnapshot
  return [...new Set(all)].slice(0, 3)
})

const categoryColor = computed(
  () => categoryStore.categories.find((c) => c.id === props.entry.categoryId)?.color ?? null,
)
```

模板中标签 chip 区（`v-for="tag in tags"` 那个 span）加点击跳转（用 RouterLink 包裹文字），并在 TypeBadge 之前加分类点：

```vue
        <RouterLink
          v-if="entry.categoryId"
          :to="`/c/${entry.categoryId}`"
          class="e-chip chip bg-paper-sunken text-ink-3 inline-flex items-center gap-1.5"
          @click.stop
        >
          <CategoryDot :color="categoryColor" />
          {{ entry.categoryName }}
        </RouterLink>
```

标签 span 换成：

```vue
          <RouterLink
            v-for="tag in tags"
            :key="tag"
            :to="`/tag/${encodeURIComponent(tag)}`"
            class="e-chip chip bg-paper-sunken text-ink-3"
            @click.stop
          >
            #{{ tag }}
          </RouterLink>
```

- [ ] **Step 2: 处理中/失败微状态**

在标签行内、sourceLabel 前加：

```vue
          <span v-if="entry.aiStatus === 'processing'" class="e-chip chip text-ink-4 inline-flex items-center gap-1">
            <span class="h-[5px] w-[5px] animate-pulse rounded-full bg-ink-3" />
            整理中
          </span>
          <button
            v-else-if="entry.aiStatus === 'failed'"
            class="e-chip chip text-danger underline-offset-2 hover:underline"
            @click.stop="$emit('repolish')"
          >
            整理失败，点此重试
          </button>
```

emits 增加 `repolish: []`；TimelineView 的 `<EntryCard>` 加 `@repolish="handleRepolish(entry)"`，并在 TimelineView 实现：

```ts
async function handleRepolish(entry: Entry) {
  try {
    await api.repolish(entry.id)
    entry.aiStatus = 'pending'
  } catch (e) {
    // 列表页静默：详情面板有完整错误入口
  }
}
```

- [ ] **Step 3: typecheck + commit**

Run: `cd web; npm run typecheck`，Expected: 通过。

```bash
git add web/src/components/EntryCard.vue web/src/views/TimelineView.vue
git commit -m "feat(web): 卡片展示分类点/标签/整理状态与失败重试"
```

---

## Task 15: 详情面板——分类编辑、标签编辑、多链接、重跑

**Files:**
- Modify: `web/src/components/EntryDetailPanel.vue`
- Create: `web/src/components/TagEditor.vue`

- [ ] **Step 1: TagEditor 组件**

```vue
<template>
  <div class="flex flex-wrap items-center gap-1.5">
    <span
      v-for="tag in modelValue"
      :key="tag"
      class="chip bg-paper-sunken text-ink-3 inline-flex items-center gap-1"
    >
      #{{ tag }}
      <button
        class="text-ink-4 hover:text-danger"
        title="移除标签"
        @click="remove(tag)"
      >
        <Icon name="x" :size="11" />
      </button>
    </span>
    <input
      ref="inputEl"
      v-model="draft"
      class="min-w-[90px] flex-1 bg-transparent text-[13px] outline-none placeholder:text-ink-4"
      placeholder="加标签后回车"
      list="tag-suggestions"
      @keydown.enter.prevent="commit"
      @keydown.delete="backspaceRemove"
      @focus="loadSuggestions"
    />
    <datalist id="tag-suggestions">
      <option v-for="s in suggestions" :key="s" :value="s" />
    </datalist>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api/http'
import Icon from './Icon.vue'

const props = defineProps<{ modelValue: string[] }>()
const emit = defineEmits<{ 'update:modelValue': [string[]] }>()

const draft = ref('')
const suggestions = ref<string[]>([])
const inputEl = ref<HTMLInputElement | null>(null)

async function loadSuggestions() {
  try {
    suggestions.value = await api.tagsSuggest(draft.value || undefined)
  } catch {
    suggestions.value = []
  }
}

function emitNext(next: string[]) {
  emit('update:modelValue', [...new Set(next)])
}

function commit() {
  const v = draft.value.trim().replace(/^#+/, '')
  if (!v) return
  if (!props.modelValue.includes(v)) emitNext([...props.modelValue, v])
  draft.value = ''
  loadSuggestions()
}

function remove(tag: string) {
  emitNext(props.modelValue.filter((t) => t !== tag))
}

function backspaceRemove() {
  if (!draft.value && props.modelValue.length) {
    emitNext(props.modelValue.slice(0, -1))
  }
}
</script>
```

- [ ] **Step 2: 详情面板 script 增加状态与动作**

```ts
import { onMounted } from 'vue'
import TagEditor from './TagEditor.vue'
import CategoryDot from './CategoryDot.vue'
import type { EntryLink } from '../types'
import { useCategoryStore } from '../stores/categories'

const categoryStore = useCategoryStore()
const links = ref<EntryLink[]>([])
const editingTags = ref<string[]>([])
const tagSaving = ref(false)
const repolishing = ref(false)
const aiError = ref('')

onMounted(() => { categoryStore.load().catch(() => {}) })

watch(
  () => props.entry.id,
  async () => {
    // 既有 watch 内追加：
    links.value = []
    editingTags.value = [...(props.entry.tags ?? [])]
    try {
      links.value = await api.entryLinks(props.entry.id)
    } catch {
      links.value = []
    }
  },
  { immediate: true },
)

async function chooseCategory(ev: Event) {
  const v = (ev.target as HTMLSelectElement).value
  const cid = v === '' ? null : Number(v)
  await api.setEntryCategory(props.entry.id, cid)
  props.entry.categoryId = cid
  props.entry.categoryName =
    cid == null ? null : categoryStore.categories.find((c) => c.id === cid)?.name ?? null
}

async function acceptSuggestion() {
  await api.setEntryCategory(props.entry.id, null, true)
  const fresh = await api.entry(props.entry.id)
  Object.assign(props.entry, { categoryId: fresh.categoryId, categoryName: fresh.categoryName, suggestedCategory: null })
}

async function saveTags() {
  tagSaving.value = true
  try {
    const r = await api.setEntryTags(props.entry.id, editingTags.value)
    props.entry.tags = r.tags
    toast.push('标签已更新', 'success')
  } catch (e) {
    toast.push('标签没保存成', 'danger')
  } finally {
    tagSaving.value = false
  }
}

async function repolish() {
  repolishing.value = true
  try {
    await api.repolish(props.entry.id)
    props.entry.aiStatus = 'pending'
    toast.push('已重新排队整理', 'success')
  } catch (e) {
    toast.push(e instanceof Error ? e.message : 'AI 未配置', 'danger')
  } finally {
    repolishing.value = false
  }
}
```

（`toast.push` 现有用法第二参数类型以 useToast 实现为准；若只接受一个参数，去掉第二个实参。）

- [ ] **Step 3: 模板插入四块 UI**

① 在"AI 补充"卡片头部行内加重跑按钮：

```vue
        <p class="mb-1.5 flex items-center gap-1.5 text-[11.5px] tracking-wide text-ink-3">
          <Icon name="sparkles" :size="13" />
          AI 补充
          <button
            class="ml-auto text-ink-3 underline-offset-2 hover:text-ink hover:underline disabled:opacity-50"
            :disabled="repolishing"
            @click="repolish"
          >
            {{ repolishing ? '排队中…' : '重新分析' }}
          </button>
        </p>
```

② AI 卡片下方加失败原因：

```vue
        <p v-if="entry.aiStatus === 'failed'" class="mt-2 text-[12px] text-danger">
          上次整理失败，可点"重新分析"重试。
        </p>
```

③ 原单链接块替换为"分类选择 + 建议条 + 多链接列表"，插在 AI 补充 section 之后：

```vue
      <!-- 分类 -->
      <section class="mt-4">
        <p class="mb-1.5 flex items-center gap-1.5 text-[11.5px] tracking-wide text-ink-3">
          分类
        </p>
        <div
          v-if="entry.suggestedCategory && entry.categoryId == null"
          class="mb-2 flex items-center gap-2 rounded-lg border border-brass/40 bg-brass-soft/40 px-3 py-2 text-[12.5px]"
        >
          <span class="flex-1">AI 建议归入「{{ entry.suggestedCategory }}」</span>
          <button class="underline underline-offset-2" @click="acceptSuggestion">采纳</button>
        </div>
        <select
          class="field max-w-[220px] py-1.5 text-[13px]"
          :value="entry.categoryId ?? ''"
          @change="chooseCategory"
        >
          <option value="">待分类</option>
          <option v-for="c in categoryStore.categories" :key="c.id" :value="c.id">
            {{ c.name }}
          </option>
        </select>
      </section>

      <!-- 链接（多链接卡片） -->
      <section v-if="links.length" class="mt-4 space-y-2">
        <a
          v-for="l in links"
          :key="l.id"
          :href="l.url"
          target="_blank"
          rel="noopener noreferrer"
          class="flex items-center gap-3 rounded-lg border border-line px-3.5 py-2.5 transition-colors hover:border-accent hover:bg-accent-soft/40"
        >
          <CategoryDot
            :color="kindColor(l.kind)"
            class="!rounded-[3px] !h-[14px] !w-[14px]"
          />
          <span class="min-w-0 flex-1">
            <span class="block truncate text-[13px] text-ink">
              {{ l.fetchedTitle || l.domain || l.url }}
            </span>
            <span class="block truncate text-[11.5px] text-ink-3">{{ l.url }}</span>
          </span>
          <Icon name="external" :size="15" class="shrink-0 text-ink-3" />
        </a>
      </section>
```

加 helper：

```ts
function kindColor(kind: string): string {
  return ({ repo: '#5B8A72', video: '#A5A58D', npm: '#B08968', web: '#9a9a8f' } as Record<string, string>)[kind] ?? '#9a9a8f'
}
```

④ 原"标签"静态块替换为可编辑 TagEditor（放在链接 section 之后）：

```vue
      <section class="mt-4">
        <p class="mb-1.5 text-[11.5px] tracking-wide text-ink-3">标签</p>
        <TagEditor v-model="editingTags" />
        <button
          class="btn btn-ghost mt-2 !py-1 text-[12.5px]"
          :disabled="tagSaving"
          @click="saveTags"
        >
          {{ tagSaving ? '保存中…' : '保存标签' }}
        </button>
      </section>
```

旧的单个链接 `<a v-if="entry.url">` 块与只读标签 `<div v-if="tags.length">` 块删除，避免重复。

- [ ] **Step 4: typecheck + 构建**

Run: `cd web; npm run typecheck`，Expected: 通过。

- [ ] **Step 5: Commit**

```bash
git add web/src/components/TagEditor.vue web/src/components/EntryDetailPanel.vue
git commit -m "feat(web): 详情面板支持分类选择/建议采纳/多链接/标签编辑/重新分析"
```

---

## Task 16: 设置页——AI 状态、笔记开关、冷启动引导、批量补跑

**Files:**
- Modify: `web/src/views/SettingsView.vue`

- [ ] **Step 1: script 增加 AI 状态逻辑**

在 `<script setup>` 中加（与现有 auth/theme 状态并列）：

```ts
import { onMounted, ref } from 'vue'
import { api } from '../api/http'
import type { AiStatusInfo } from '../types'
import { useCategoryStore } from '../stores/categories'
import { useToast } from '../composables/useToast'

const ai = ref<AiStatusInfo | null>(null)
const suggestions = ref<string[]>([])
const picked = ref<string[]>([])
const bootstrapBusy = ref(false)
const batchBusy = ref(false)
const categoryStore = useCategoryStore()
const toast = useToast()

async function refreshAi() {
  try {
    ai.value = await api.aiStatus()
  } catch {
    ai.value = null
  }
}

async function togglePolishNotes(v: boolean) {
  await api.updateAiSettings(v)
  await refreshAi()
}

async function runBootstrap() {
  bootstrapBusy.value = true
  try {
    const r = await api.bootstrapCategories()
    suggestions.value = r.suggestions
    picked.value = r.suggestions.filter((s) => !r.existing.includes(s))
  } finally {
    bootstrapBusy.value = false
  }
}

async function confirmCategories() {
  await api.bulkCategories(picked.value)
  suggestions.value = []
  picked.value = []
  await categoryStore.load(true)
  toast.push('分类已创建')
}

async function batchStart() {
  batchBusy.value = true
  try {
    await api.batchRepolish('start')
    await refreshAi()
    setTimeout(() => { batchBusy.value = false; refreshAi() }, 1500)
  } catch {
    batchBusy.value = false
  }
}

async function batchStop() {
  await api.batchRepolish('stop')
  await refreshAi()
}

onMounted(refreshAi)
```

- [ ] **Step 2: 模板加"AI 整理"卡片**

插在"数据"卡片之前：

```vue
      <!-- AI 整理 -->
      <section class="card p-5">
        <h2 class="t-rule mb-4">AI 整理</h2>

        <dl class="space-y-3">
          <div class="flex items-baseline justify-between gap-4">
            <dt class="text-[13px] text-ink-3">服务状态</dt>
            <dd class="text-[14px]">
              <span v-if="ai?.ready" class="text-accent">已就绪 · {{ ai.model }}</span>
              <span v-else-if="ai?.enabled" class="text-danger">已启用但 Key 无效</span>
              <span v-else class="text-ink-3">未启用（在 application-local.yml 配置 jotlog.ai）</span>
            </dd>
          </div>
        </dl>

        <label class="mt-4 flex items-center justify-between gap-4 text-[13.5px]">
          <span>纯文本随记也用 AI 总结分类</span>
          <input
            type="checkbox"
            class="h-4 w-4 accent-ink"
            :checked="!!ai?.polishNotes"
            :disabled="!ai?.ready"
            @change="togglePolishNotes(($event.target as HTMLInputElement).checked)"
          />
        </label>
        <p class="mt-1.5 text-[12px] text-ink-3">
          关闭时随记按规则直接归入「灵感随记」，不调用模型、不花钱。
        </p>

        <div class="mt-4 border-line-soft border-t pt-4">
          <p class="mb-2 text-[13px]">初始分类</p>
          <div v-if="!suggestions.length" class="flex items-center gap-3">
            <button class="btn btn-ghost" :disabled="bootstrapBusy" @click="runBootstrap">
              {{ bootstrapBusy ? '生成中…' : (ai?.ready ? '让 AI 提议一套分类' : '使用内置分类') }}
            </button>
            <span class="text-[12px] text-ink-3">已有分类可在侧栏直接使用</span>
          </div>
          <div v-else class="space-y-2">
            <label
              v-for="s in suggestions"
              :key="s"
              class="flex items-center gap-2 text-[13px]"
            >
              <input type="checkbox" class="h-4 w-4 accent-ink" v-model="picked" :value="s" />
              {{ s }}
            </label>
            <button class="btn btn-primary mt-1 !py-1.5 text-[13px]" @click="confirmCategories">
              确认创建选中分类
            </button>
          </div>
        </div>

        <div class="mt-4 border-line-soft border-t pt-4">
          <p class="mb-2 text-[13px]">存量条目补跑</p>
          <div class="flex items-center gap-3">
            <button class="btn btn-ghost" :disabled="!ai?.ready || batchBusy" @click="batchStart">
              批量 AI 整理
            </button>
            <button class="btn btn-quiet" :disabled="!ai?.batch?.active" @click="batchStop">
              中断
            </button>
            <span v-if="ai?.batch?.active" class="text-[12px] text-ink-3 tabular">
              入队 {{ ai.batch.enqueued }} / {{ ai.batch.total }}
            </span>
          </div>
          <p class="mt-1.5 text-[12px] text-ink-3">
            扫描所有"排队中/失败"的链接、仓库、视频条目，逐条间隔约 1 秒处理。
          </p>
        </div>
      </section>
```

（`toast.push('分类已创建')` 若 useToast 只收一个参数就用单参；`btn-primary`/`btn-quiet`/`field`/`card`/`t-rule` 均为项目现有 class。）

- [ ] **Step 3: 前端完整构建**

Run: `cd web; npm run build`，Expected: vue-tsc + vite 均通过，产出 web/dist。

- [ ] **Step 4: Commit**

```bash
git add web/src/views/SettingsView.vue
git commit -m "feat(web): 设置页 AI 状态/笔记开关/分类引导/批量补跑"
```

---

## Task 17: 端到端验证

- [ ] **Step 1: 后端全量测试与打包**

Run: `mvn -B clean verify`，Expected: 测试全过、jar 产出。

- [ ] **Step 2: 无 Key 降级验证**

保持 `jotlog.ai.enabled=false` 启动；网页新建一条带多链接的记录：

```
3.02 复制打开抖音 https://v.douyin.com/w_6uKY4ZYbk/ gOX
GitHub：https://github.com/morluto/rea 主页：https://rea.tools
```

预期：入库成功；`entry_links` 出现 3 行；`ai_status=pending`；侧栏"待分类"有数字；应用日志无异常。

- [ ] **Step 3: 配 Key 后闭环验证**

`config/application-local.yml` 填真实 DeepSeek key 并 `enabled: true`，重启。

预期（约 3–10 秒内）：
1. 条目 `ai_status=done`，`title/ai_summary/ai_tags` 有值，`category_id` 落在"开源项目"或"工具软件"；
2. `entry_tags` 有 3–5 行，标签为合理中文词（如"逆向工程/反编译/开源"）；
3. `entry_links` 中 github 行 `fetch_status=done`、title 含 rea；抖音行至少有落地页标题或 failed（不得因此拖垮整条）；
4. 网页：侧栏出现分类与计数，卡片有色点/标签，详情页可改分类、增删标签、点"重新分析"，标签跳转 `/tag/...` 正常。

- [ ] **Step 4: 边界验证**

- 详情页对 AI 建议的新分类点"采纳"：分类被创建、条目挂入、建议条消失。
- 设置页关掉"笔记 AI"：新建纯文本 → `ai_status=skipped`、归入"灵感随记"，且不产生模型调用。
- 设置页"批量 AI 整理"：进度数字增长、可"中断"；failed 条目可重跑成功（断网造一条 failed 再恢复）。
- 重复转发同一文本：幂等不产生第二条（现有 uk_source_msg / content 逻辑不受影响）。

- [ ] **Step 5: 更新设计文档状态并提交**

把 `docs/Jotlog-AI分类与标签设计.md` 顶部状态改为 `> 状态：M1 已实现（2026-10-13）`，提交：

```bash
git add docs/Jotlog-AI分类与标签设计.md
git commit -m "docs: 标记 AI 分类标签 M1 已实现"
```

---

## 自查记录（计划与设计文档对照）

- 分类单选/标签多选、预设+AI 建议：Task 1/3/9/10/15/16 覆盖。
- DeepSeek + key 走配置文件、设置页只显状态：Task 2/8/16 覆盖。
- 多链接保持一条、repo>video>link 主体、分头抓取：Task 5/6 覆盖。
- 素材落库重跑不重抓：Task 3 `entry_links` + Task 6 `registerIfAbsent/saveFetch`；重跑复用简化为"链接行已存在不重复注册"，fetcher 仍会刷新 done 内容（可接受；如需严格跳过可在 PolishWorker 先查 `hasDoneFetch`，列为后续小优化）。
- note 默认跳过 + 设置页开关：Task 4/9/16 覆盖。
- 防发散（分类白名单、标签归一化与复用）：Task 7/8 覆盖。
- 失败两次、手动/批量重跑、冷启动引导：Task 9/10/16 覆盖。
- 侧边栏/卡片/详情/路由：Task 11–15 覆盖。
- M2 项（分类合并、文件图片理解）明确不在本计划。

-- ============================================================
-- 手动建表（Flyway 的备用方案）
--
-- 正常情况下**不需要**跑这个文件。
-- Flyway 会在应用启动时自动执行 V1__init.sql 建表。
--
-- 什么时候才需要手动跑：
--   1. 你想在启动应用前先把表准备好
--   2. Flyway 迁移失败了，你想手动排查
--   3. 你不想启动应用，只想先验证 SQL 语法
--
-- 用法：
--   mysql -h YOUR_DB_HOST -u root -p jotlog < V1__init.sql
-- ============================================================

-- 确认在正确的库上操作，避免误建到别的库
SELECT DATABASE();

-- 建表前先看看有没有残留
SHOW TABLES LIKE 'entries';

CREATE TABLE IF NOT EXISTS entries (
    id              BIGINT       NOT NULL AUTO_INCREMENT,

    -- 来源通道：feishu / pwa / manual / mcp / cli / selftest
    source          VARCHAR(32)  NOT NULL DEFAULT 'feishu',
    -- 通道内唯一消息 id，用于幂等去重。NULL 表示非通道来源
    source_msg_id   VARCHAR(64)  NULL,
    chat_id         VARCHAR(64)  NULL,
    chat_type       VARCHAR(16)  NULL,
    sender_open_id  VARCHAR(64)  NULL,
    sender_name     VARCHAR(64)  NULL,

    -- link / repo / video / note / file / image / unknown
    entry_type      VARCHAR(16)  NOT NULL DEFAULT 'unknown',

    -- ============ 不可变区：开始 ============
    -- 用户原话。任何流程都不得 UPDATE 这一列。
    raw_input       TEXT         NOT NULL,
    -- ============ 不可变区：结束 ============

    -- 结构化抽取结果（规则产生，非 AI）
    url             VARCHAR(2048) NULL,
    domain          VARCHAR(255)  NULL,
    file_key        VARCHAR(255)  NULL,
    file_name       VARCHAR(512)  NULL,
    mime            VARCHAR(128)  NULL,
    size_bytes      BIGINT        NULL,
    content_hash    CHAR(64)      NULL,

    -- AI 补充区：可为空、可重跑、可被覆盖
    title           VARCHAR(512)  NULL,
    ai_summary      TEXT          NULL,
    ai_tags         VARCHAR(512)  NULL,
    ai_status       VARCHAR(16)   NOT NULL DEFAULT 'pending',
    ai_error        VARCHAR(512)  NULL,
    ai_model        VARCHAR(64)   NULL,
    ai_latency_ms   INT           NULL,

    -- 元数据补全（OG / GitHub API）
    enrich_status   VARCHAR(16)   NOT NULL DEFAULT 'pending',
    enrich_error    VARCHAR(512)  NULL,

    -- 用户侧状态
    starred         TINYINT(1)    NOT NULL DEFAULT 0,
    archived        TINYINT(1)    NOT NULL DEFAULT 0,
    note            TEXT          NULL,

    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    reviewed_at     DATETIME(3)   NULL,

    PRIMARY KEY (id),

    -- 幂等：同一通道同一消息只入库一次。
    -- MySQL 唯一索引允许多个 NULL，手动记录不受影响。
    UNIQUE KEY uk_source_msg (source, source_msg_id),

    -- 中文全文检索：ngram 解析器，按 2 字滑窗切分。
    --
    -- ⚠️ MySQL 硬规则：MATCH() 的列必须与索引定义的列【完全一致且顺序相同】，
    --    否则报 1191 Can't find FULLTEXT index matching the column list。
    --    所以搜 raw_input 时也要写 MATCH(raw_input, title, ai_summary)，
    --    不能只写 MATCH(raw_input)。
    --
    -- raw_input 放首位：它是搜索主战场（用户自己写的话），
    -- title 和 ai_summary 是补充。
    --
    -- 注意：ngram_token_size 是全局变量，默认 2。
    --       查 1 个汉字时走不到这个索引，应用层会降级到 LIKE。
    FULLTEXT KEY ft_search (raw_input, title, ai_summary) WITH PARSER ngram,

    KEY idx_created (created_at DESC),
    KEY idx_ai_status (ai_status),
    KEY idx_type (entry_type),
    KEY idx_starred (starred, created_at DESC),
    KEY idx_content_hash (content_hash)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '随手记主表。raw_input 不可变，ai_* 为 AI 补充区。';


CREATE TABLE IF NOT EXISTS attachments (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    entry_id     BIGINT       NOT NULL,
    sha256       CHAR(64)     NOT NULL,
    filename     VARCHAR(512) NOT NULL,
    mime         VARCHAR(128) NULL,
    size_bytes   BIGINT       NOT NULL,
    storage_path VARCHAR(1024) NOT NULL,
    source_key   VARCHAR(255) NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_entry_sha (entry_id, sha256),
    KEY idx_sha (sha256),
    KEY idx_entry (entry_id),

    CONSTRAINT fk_attach_entry
        FOREIGN KEY (entry_id) REFERENCES entries (id)
        ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '附件元数据。文件落磁盘，库中只存路径与哈希。';


CREATE TABLE IF NOT EXISTS exports (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    entry_id    BIGINT       NOT NULL,
    fmt         VARCHAR(16)  NOT NULL,
    target      VARCHAR(255) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'pending',
    error       VARCHAR(512) NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    KEY idx_entry (entry_id),
    KEY idx_status (status, created_at),

    CONSTRAINT fk_export_entry
        FOREIGN KEY (entry_id) REFERENCES entries (id)
        ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '导出任务（Markdown / JSON / 印象笔记等）。';


-- ---------- 建完表立刻验证 ----------
-- 必须返回 FULLTEXT | raw_input,title,ai_summary
SELECT INDEX_NAME, INDEX_TYPE,
       GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'entries'
   AND INDEX_NAME = 'ft_search'
 GROUP BY INDEX_NAME, INDEX_TYPE;

-- 灌一条测试数据
INSERT INTO entries (source, entry_type, raw_input)
VALUES ('selftest', 'note', '人生不是要赶到哪里去，而是要学会欣赏沿途的风景');

-- 必须返回 1 行
-- ⚠️ MATCH 里必须写全三列且与索引同序。只写 MATCH(raw_input) 会报 1191。
-- ⚠️ 用 BOOLEAN 不用 NATURAL：只灌 1 条 = 词占 100% 行，
--    NATURAL 的 50% 阈值会把它当停用词丢掉，返回 0 行（看着像索引坏了，其实不是）。
SELECT id,
       MATCH(raw_input, title, ai_summary) AGAINST ('沿途' IN BOOLEAN MODE) AS score
  FROM entries
 WHERE MATCH(raw_input, title, ai_summary) AGAINST ('沿途' IN BOOLEAN MODE);

-- 清理
DELETE FROM entries WHERE source = 'selftest';

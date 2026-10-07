-- ============================================================================
-- Jotlog 数据库一键修复脚本（DBeaver 可直接执行）
--
-- 用法：整段复制粘贴到 DBeaver 的 SQL 编辑器，按 Ctrl+Enter（或 F2）执行
--      已选中脚本后按 Ctrl+Shift+Enter 可整段执行
--
-- 本脚本会：
--   1. 删掉旧表（含外键依赖，按依赖顺序）
--   2. 用正确的列顺序重建（ft_search 的raw_input 必须在首位）
--   3. 灌测试数据验证 ngram 中文检索
--   4. 清理测试数据
--
-- 前提：先执行 USE jotlog;如果库不存在，见文件末尾说明。
-- ============================================================================

USE jotlog;

-- ----------------------------------------------------------------------------
-- 第 1 步：删表
--
-- 注意：attachments 和 exports 都有外键指向 entries，
--       所以必须先删它们，直接 DROP TABLE entries 会报 1217 错误。
-- ----------------------------------------------------------------------------

DROP TABLE IF EXISTS attachments;
DROP TABLE IF EXISTS exports;
DROP TABLE IF EXISTS entries;

-- ----------------------------------------------------------------------------
-- 第 2 步：重建 entries
--
-- ⭐ 关键：ft_search 的列顺序是 (raw_input, title, ai_summary)
--    MySQL 硬规则：MATCH() 的列必须与索引定义的列完全一致且顺序相同，
--    否则报 1191 Can't find FULLTEXT index matching the column list。
--    所以查中文时必须写：
--        MATCH(raw_input, title, ai_summary) AGAINST('词' IN BOOLEAN MODE)
--    只写 MATCH(raw_input) 会报 1191。
--    用 BOOLEAN 不用 NATURAL 的原因见第 4 步开头。
-- ----------------------------------------------------------------------------

CREATE TABLE entries (
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

    -- ⭐ 中文全文检索。列顺序必须是 (raw_input, title, ai_summary)
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


CREATE TABLE attachments (
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


CREATE TABLE exports (
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


-- ----------------------------------------------------------------------------
-- 第 3 步：验证索引已就位
--
-- 必须返回：FULLTEXT | raw_input,title,ai_summary
-- ----------------------------------------------------------------------------

SELECT INDEX_NAME AS索引名,
       INDEX_TYPE AS 类型,
       GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS 列顺序
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = 'jotlog'
   AND TABLE_NAME   = 'entries'
   AND INDEX_NAME   = 'ft_search'
 GROUP BY INDEX_NAME, INDEX_TYPE;


-- ----------------------------------------------------------------------------
-- 第 4 步：实测中文检索
--
-- ⚠️ 关于测试数据条数，这里有个实测踩过的坑：
--
--   NATURAL LANGUAGE MODE 会把「出现在超过 50% 行里」的 token 当停用词丢掉。
--   配 ngram 分词器（按 2 字滑窗切）后，中文极易触发这个阈值。
--   实测：只有 3 条数据时搜「沿途」score = 0（搜索静默失效）；
--        灌到 22 条差异化数据后 score = 1.80（正常）。
--   所以 3 条数据测不出问题，测试数据必须够多、且内容互不相同。
--   应用层已改用 BOOLEAN MODE，它不做阈值过滤，行为确定。
--
--   下面是 8 条差异化数据，用来验证索引是否可用（不是验证 NATURAL 是否可用）。
-- ----------------------------------------------------------------------------

INSERT INTO entries (source, entry_type, raw_input)
VALUES ('selftest', 'note', '人生不是要赶到哪里去，而是要学会欣赏沿途的风景');

INSERT INTO entries (source, entry_type, raw_input)
VALUES ('selftest', 'note', '今天天气不错，适合出门走一走');

INSERT INTO entries (source, entry_type, raw_input)
VALUES ('selftest', 'link', 'Java and Spring Boot microservices');

INSERT INTO entries (source, entry_type, raw_input)
VALUES ('selftest', 'note', '机器学习模型训练需要大量标注数据支撑');

INSERT INTO entries (source, entry_type, raw_input)
VALUES ('selftest', 'note', '开源项目许可证该选 MIT 还是 Apache 2');

INSERT INTO entries (source, entry_type, raw_input)
VALUES ('selftest', 'note', '合同审查流程中用印环节的风险点有哪些');

INSERT INTO entries (source, entry_type, raw_input)
VALUES ('selftest', 'note', '数据库索引设计要结合查询场景来评估');

INSERT INTO entries (source, entry_type, raw_input)
VALUES ('selftest', 'note', '重构代码之前先补齐单元测试覆盖率');

-- 搜「沿途」（BOOLEAN）：必须返回第 1 条，score > 0
SELECT id, LEFT(raw_input, 20) AS 内容,
       MATCH(raw_input, title, ai_summary) AGAINST ('沿途' IN BOOLEAN MODE) AS score
  FROM entries
 WHERE MATCH(raw_input, title, ai_summary) AGAINST ('沿途' IN BOOLEAN MODE);

-- 搜「Java」（BOOLEAN）：验证英文词能命中
SELECT id, LEFT(raw_input, 20) AS 内容,
       MATCH(raw_input, title, ai_summary) AGAINST ('Java' IN BOOLEAN MODE) AS score
  FROM entries
 WHERE MATCH(raw_input, title, ai_summary) AGAINST ('Java' IN BOOLEAN MODE);

-- 搜「索引」（BOOLEAN）：验证第 2 个中文词也能命中
SELECT id, LEFT(raw_input, 20) AS 内容,
       MATCH(raw_input, title, ai_summary) AGAINST ('索引' IN BOOLEAN MODE) AS score
  FROM entries
 WHERE MATCH(raw_input, title, ai_summary) AGAINST ('索引' IN BOOLEAN MODE);

-- 对比：NATURAL 模式在同一份数据上可能返回 0 行，这正是要避开它的原因。
-- 这是故意保留的反例，不是让你用它的。
SELECT 'NATURAL 可能返回0行' AS 提示, COUNT(*) AS 命中数
  FROM entries
 WHERE MATCH(raw_input, title, ai_summary) AGAINST ('沿途' IN NATURAL LANGUAGE MODE);

-- 单字「风」：ngram_token_size=2，全文索引查不到，这是预期行为。
-- 应用层对单字会自动降级到 LIKE 查法，这里验证降级路径：
SELECT id, LEFT(raw_input, 20) AS 内容
  FROM entries
 WHERE raw_input LIKE '%风景%';


-- ----------------------------------------------------------------------------
-- 第 5 步：清理测试数据
-- ----------------------------------------------------------------------------

DELETE FROM entries WHERE source = 'selftest';

-- 确认已清空
SELECT COUNT(*) AS 剩余测试数据 FROM entries;


-- ============================================================================
-- 如果第 1 步报「Unknown database 'jotlog'」，说明库还没建。
-- 在 DBeaver 里单独执行下面这一句，然后重新运行本脚本：
--
--   CREATE DATABASE jotlog DEFAULT CHARACTER SET utf8mb4
--     DEFAULT COLLATE utf8mb4_0900_ai_ci;
--
-- ============================================================================


-- ============================================================================
-- 执行完的验收标准
--
-- 1. 第 3 步返回：FULLTEXT | raw_input,title,ai_summary
-- 2. 第 4 步 BOOLEAN 搜「沿途」返回 1 行，score 是正数
-- 3. 第 4 步 BOOLEAN 搜「Java」返回 1 行
-- 4. 第 4 步 BOOLEAN 搜「索引」返回 1 行
-- 5. 第 5 步剩余测试数据 = 0
--
-- 全都符合 → 数据库这层就通了。
-- 下一步：启动应用（Flyway 会记录迁移），然后配飞书。
-- ============================================================================

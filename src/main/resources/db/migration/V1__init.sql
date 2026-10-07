-- ============================================================
-- Jotlog V1 初始结构
--
-- 铁律：raw_input 是用户原话，永不可变。
--       AI 只允许写入 ai_* 字段，永远不允许覆盖 raw_input。
--       这条约束必须在 schema 层面体现，不能只靠应用层自觉。
-- ============================================================

CREATE TABLE entries (
    id              BIGINT       NOT NULL AUTO_INCREMENT,

    -- 来源通道：feishu / pwa / manual / mcp / cli
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
    -- ⚠️ 前置条件：必须 SET GLOBAL innodb_ft_enable_stopword = 0，否则搜不到含 a/i 的英文词。
    --   MySQL 默认停用词表含单字符 a 和 i，ngram 会【丢弃包含停用词的 token】。
    --   实测「Java」被切成 Ja/av/va，三个 bigram 全含 a 全被丢 → 搜出 0 条。
    --   而「Boot」切成 Bo/oo/ot 不含停用词 → 正常命中。
    --   详见 scripts/init-db.sql（含 my.cnf 持久化说明）。
    --
    -- ⚠️ MySQL 硬规则：MATCH() 的列必须与索引定义的列【完全一致且顺序相同】，
    --    否则报 1191Can't find FULLTEXT index matching the column list。
    --    所以想单独搜 raw_input 也必须写成 MATCH(raw_input, title, ai_summary)。
    --
    -- 为什么 raw_input 放第一位：它是搜索的主战场（用户自己写的话），
    --    title 和 ai_summary 是补充。放首位也让单列 MATCH 写法成立。
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
    -- 同一 entry 内按内容去重
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

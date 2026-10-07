-- ============================================================
-- Jotlog 数据库初始化
--
-- 执行前请先确认：
--   1. MySQL 版本 >= 8.0（5.7 的 ngram 有 bug，搜中文会不准）
--   2. 字符集为 utf8mb4
--
-- 用法：
--   mysql -u root -p < init-db.sql
-- ============================================================

-- ---------- 版本自检 ----------
SELECT VERSION() AS mysql_version;

-- 确认 ngram 解析器可用。返回空不代表失败，它随服务启动自动加载，
-- 不作为传统插件注册在 INFORMATION_SCHEMA.PLUGINS 里。
SELECT @@version AS version,
       @@ngram_token_size AS ngram_token_size,
       @@character_set_server AS charset_server;

-- 如果 ngram_token_size 不是 2，考虑改成 2：
-- SET GLOBAL ngram_token_size = 2;
-- 注意：这是全局参数，改完要重建全文索引才生效，且需写进 my.cnf 才会重启后保留。


-- ----------------------------------------------------------------------------
-- ⚠️ 必须做：关闭全文检索停用词（否则搜不到含 a / i 的英文词）
--
-- 实测踩过的坑：MySQL 默认停用词表含单字符 a 和 i。
--   ngram 解析器按 2 字滑窗切词后会【丢弃包含停用词的 token】。
--   于是「Java」被切成 Ja / av / va —— 三个 bigram 全都含 a，全被丢光，
--   搜出来 0 条。而「Boot」切成 Bo / oo / ot 不含停用词，正常命中。
--   受控实验：Zaza(含a)=0条，Zeze(含e)=1条，Zizi(含i)=0条，Zozo=1条。
--
-- 这是全局变量，影响整个实例的所有 FULLTEXT 索引。Jotlog 是唯一用到全文索引的库，
-- 所以副作用可忽略。改完必须【重建全文索引】才生效：
--   ALTER TABLE entries DROP INDEX ft_search;
--   ALTER TABLE entries ADD FULLTEXT INDEX ft_search (raw_input, title, ai_summary) WITH PARSER ngram;
-- ----------------------------------------------------------------------------

SET GLOBAL innodb_ft_enable_stopword = 0;

-- 确认生效
SELECT @@innodb_ft_enable_stopword AS stopword_enabled;
-- 期望：0


-- ⚠️ SET GLOBAL 重启后失效！必须把下面两行写进 /etc/my.cnf（或 my.ini）的
--    [mysqld] 段，否则 MySQL 重启后「Java」这类词又会搜不到：
--
--      [mysqld]
--      innodb_ft_enable_stopword = 0
--
--    改完重启 MySQL，再重建一次全文索引（上面的两条 ALTER）。
--    应用层 JdbcEntryRepository.search() 有 LIKE 兜底，配置丢了也只是变慢，不会搜不到。


-- ---------- 建库 ----------
CREATE DATABASE IF NOT EXISTS jotlog
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

-- ---------- 建账号（已按你的选择跳过） ----------
--
-- 你决定直接用 root 账号，不建独立账号。这在自用场景下可行，
-- 但要知道代价：
--   1. Jotlog 进程一旦被攻破，攻击者拿到的是整个 MySQL 实例的root 权限，
--      能读写你服务器上的所有库（如果还有别的业务库）。
--   2. root 密码要写进 systemd 配置文件，文件权限必须是 600。
--   3. Flyway 需要 DDL 权限，root 天然有——这是直接用 root 的主要好处。
--
-- 如果以后想收紧，最小改动是把下面两行的账号换成限定库权限：
--   CREATE USER IF NOT EXISTS 'jotlog'@'127.0.0.1' IDENTIFIED BY 'xxx';
--   GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
--     ON jotlog.* TO 'jotlog'@'127.0.0.1';
-- 注意不能只给 DML，Flyway 建表需要 CREATE/ALTER。

FLUSH PRIVILEGES;


-- ============================================================
-- 自检 ngram 全文索引（对真实表做，不是临时表）
--
-- 前置：Jotlog 至少启动过一次，Flyway 已建好 entries 表。
-- 直接手动跑本段会报错，因为表还没建。
--
-- 为什么用真实表：entries.ft_search 的索引列是
-- (raw_input, title, ai_summary) 三列联合，跟单列自检不是一回事。
-- 列组合错了自检也发现不了。
-- ============================================================

USE jotlog;

-- 1. 确认表和全文索引都在
SELECT TABLE_NAME, TABLE_ROWS
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = 'jotlog' AND TABLE_NAME = 'entries';

-- 确认 ft_search 索引存在，且是 FULLTEXT 类型
SELECT INDEX_NAME, INDEX_TYPE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = 'jotlog' AND TABLE_NAME = 'entries' AND INDEX_NAME = 'ft_search'
 GROUP BY INDEX_NAME, INDEX_TYPE;
-- 期望：INDEX_TYPE = FULLTEXT，cols = raw_input,title,ai_summary

-- 2. 灌一条测试数据
INSERT INTO entries (source, entry_type, raw_input, url, domain)
VALUES ('selftest', 'note', '人生不是要赶到哪里去，而是要学会欣赏沿途的风景', NULL, NULL);

-- 3. 二字词检索（走全文索引）
-- ⚠️ MATCH 的列必须与索引定义【完全一致且顺序相同】，否则报 1191。
--    只写 MATCH(raw_input) 是错的。
-- ⚠️ 必须用 BOOLEAN MODE，不能用 NATURAL LANGUAGE MODE：
--    NATURAL 会把「出现在 >50% 行里」的 token 当停用词丢掉，
--    这里只灌 1 条数据 = 100%，所以 NATURAL 必然返回 0 行（会误判成索引坏了）。
SELECT id,
       MATCH(raw_input, title, ai_summary) AGAINST ('沿途' IN BOOLEAN MODE) AS score
  FROM entries
 WHERE MATCH(raw_input, title, ai_summary) AGAINST ('沿途' IN BOOLEAN MODE);
-- 期望：返回刚插入的那条，score > 0

-- 4. 联合索引验证：补一个 title 后再搜 title 里的词
UPDATE entries SET title = '随手记项目' WHERE source = 'selftest';
SELECT id, title,
       MATCH(raw_input, title, ai_summary) AGAINST ('随手记' IN BOOLEAN MODE) AS score
  FROM entries
 WHERE MATCH(raw_input, title, ai_summary) AGAINST ('随手记' IN BOOLEAN MODE);
-- 期望：返回同一条

-- 5. 清理
DELETE FROM entries WHERE source = 'selftest';


-- ---------- 验收标准 ----------
-- 第 1 步要看到 FULLTEXT | raw_input,title,ai_summary
-- 第 3、4 步都要各返回 1 行。
-- 都对了，中文搜索这条链路就通了。
--
-- 常见失败原因：
--   1. 索引建的时候漏了 WITH PARSER ngram（静默失效，不报错）
--   2. ngram_token_size 被改成 1 或更大
--   3. 字符集不是 utf8mb4
--   4. 查询词长度小于 ngram_token_size（默认 2，即单字查不到）
--      → 单字走应用层的 LIKE 降级，不走这里
--   5. Flyway 没跑成功（看 application.yml 的 ddl-auto 和启动日志）
--   6. MATCH 列与索引列不一致或顺序不同 → 报 1191
--      修复见 scripts/fix-fts-1191.sql

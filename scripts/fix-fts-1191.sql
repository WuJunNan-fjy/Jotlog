-- ============================================================
-- 修复 1191：Can't find FULLTEXT index matching the column list
--
-- 原因：V1__init.sql 里索引定义是
--     FULLTEXT KEY ft_search (title, ai_summary, raw_input)
--   而你执行的查询是 MATCH(raw_input)。
--
--   MySQL 硬规则：MATCH() 里的列必须与 FULLTEXT 索引定义的列
--   【完全一致且顺序相同】。只写其中一列会报 1191。
--   （不是"能用索引的前缀"，是必须完全一致）
--
-- 修复：把 raw_input 挪到索引首位。
--   索引：(raw_input, title, ai_summary)
--   这样单列 MATCH(raw_input, title, ai_summary) 成立，
--   单独搜 raw_input 时也可写 MATCH(raw_input, title, ai_summary)。
--
-- 执行：
--   mysql -h YOUR_DB_HOST -u root -p jotlog < fix-fts-1191.sql
-- ============================================================

USE jotlog;

-- ---------- 1. 先看当前索引长什么样 ----------
SELECT INDEX_NAME, INDEX_TYPE,
       GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols,
       SEQ_IN_INDEX
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'entries'
   AND INDEX_NAME = 'ft_search'
 GROUP BY INDEX_NAME, INDEX_TYPE;
-- 如果返回空，说明表根本没建或索引名不同，
-- 直接跑 scripts/create-tables.sql 重建即可。


-- ---------- 2. 删掉旧索引 ----------
-- 如果上面查到了才需要执行；没查到会报错，说明本来就没有，可以跳过。
ALTER TABLE entries DROP INDEX ft_search;


-- ---------- 3. 用正确顺序重建 ----------
ALTER TABLE entries
  ADD FULLTEXT INDEX ft_search (raw_input, title, ai_summary) WITH PARSER ngram;


-- ---------- 4. 验证索引已就位 ----------
-- 必须返回：FULLTEXT | raw_input,title,ai_summary
SELECT INDEX_NAME, INDEX_TYPE,
       GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'entries'
   AND INDEX_NAME = 'ft_search'
 GROUP BY INDEX_NAME, INDEX_TYPE;


-- ---------- 5. 实测检索（注意 MATCH 里必须写全三列） ----------
INSERT INTO entries (source, entry_type, raw_input)
VALUES ('selftest', 'note', '人生不是要赶到哪里去，而是要学会欣赏沿途的风景');

-- ✅ 正确：列与索引完全一致，且用 BOOLEAN MODE
--    （只灌 1 条 = 词占 100% 行，NATURAL 的 50% 阈值会返回 0 行，看着像索引坏了）
SELECT id, MATCH(raw_input, title, ai_summary) AGAINST ('沿途' IN BOOLEAN MODE) AS score
  FROM entries
 WHERE MATCH(raw_input, title, ai_summary) AGAINST ('沿途' IN BOOLEAN MODE);

-- ❌ 错误示范（会报 1191，注释掉别执行）：
-- SELECT id FROM entries
--  WHERE MATCH(raw_input) AGAINST ('沿途' IN BOOLEAN MODE);

DELETE FROM entries WHERE source = 'selftest';


-- ---------- 6. 同步修正 Flyway 迁移记录 ----------
-- 如果你之前已经用旧版V1__init.sql 启动过应用，
-- Flyway 会认为 V1 已执行过，不再重跑，索引就一直是旧的。
--
-- 二选一：
--   A. 删表重建（数据是测试数据时可这样做，最干净）：
--        DROP TABLE entries;
--      然后重启应用，Flyway 会重新执行修正后的 V1__init.sql。
--
--   B. 有真实数据不能删表：改用上面的步骤 2-5 手动修索引即可，
--      但要注意 V1__init.sql 已改过内容，下次 baseline 时 checksum 会不匹配。
--      需要同时执行：
--        DELETE FROM flyway_schema_history WHERE version = '1';
--      之后重启应用让它重跑。


-- ---------- 验收标准 ----------
-- 第 4 步返回：FULLTEXT | raw_input,title,ai_summary
-- 第 5 步返回 1 行，且 score > 0
-- 都对了就通了。

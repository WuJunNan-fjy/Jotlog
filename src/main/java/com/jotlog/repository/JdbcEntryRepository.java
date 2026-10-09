package com.jotlog.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Repository
public class JdbcEntryRepository implements EntryRepository {

    /**
     * 所有查询共用同一组列，避免各处手写漏列。
     *
     * 附件聚合是两个关联子查询：单用户量级（几千条、附件几条/条）代价可忽略，
     * 换来前端列表一次请求就拿到"有没有图、有几个附件"，不用逐条再查。
     * image_sha 取第一张图（按附件 id 升序，即上传顺序），前端拿它拼 raw 预览地址。
     */
    private static final String COLUMNS = """
            e.id, e.source, e.entry_type, e.raw_input, e.url, e.domain,
            e.title, e.ai_summary, e.ai_tags, e.ai_status, e.created_at,
            e.starred, e.archived,
            (SELECT a.sha256 FROM attachments a
              WHERE a.entry_id = e.id AND a.mime LIKE 'image/%'
              ORDER BY a.id LIMIT 1)                          AS image_sha,
            (SELECT COUNT(*) FROM attachments a
              WHERE a.entry_id = e.id)                        AS attachment_count
            """;

    private final JdbcTemplate jdbc;

    public JdbcEntryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Long findIdBySourceMsg(String source, String sourceMsgId) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM entries WHERE source = ? AND source_msg_id = ? LIMIT 1",
                (rs, i) -> rs.getLong("id"),
                source, sourceMsgId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Override
    public long insert(NewEntry e) {
        jdbc.update("""
                INSERT INTO entries
                    (source, source_msg_id, chat_id, chat_type, sender_open_id,
                     entry_type, raw_input, url, domain, content_hash, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(3), NOW(3))
                """,
                e.source(), e.sourceMsgId(), e.chatId(), e.chatType(), e.senderOpenId(),
                e.entryType(), e.rawInput(), e.url(), e.domain(), e.contentHash());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    @Override
    public List<Row> recent(int limit) {
        return searchPage(null, null, null, false, limit, 0).items();
    }

    @Override
    public Row findById(long id) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM entries e WHERE e.id = ?", ROW, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<Row> search(String keyword, int limit) {
        return searchPage(keyword, null, null, false, limit, 0).items();
    }

    /**
     * 分页查询。
     *
     * 三种检索模式：
     *   none  —— 没关键词，纯按时间翻页
     *   match —— 两个字及以上，走 ngram 全文索引
     *   like  —— 一个字（ngram_token_size=2 命中不了），或全文查不到时的兜底
     */
    @Override
    public Page searchPage(String keyword, String type, Boolean starred, boolean includeArchived,
                           int limit, long offset) {
        String kw = keyword == null ? "" : keyword.trim();
        String mode = kw.isEmpty() ? "none" : (kw.length() < 2 ? "like" : "match");

        List<Row> items = query(mode, kw, type, starred, includeArchived, limit, offset);
        long total = count(mode, kw, type, starred, includeArchived);

        // 兜底：全文索引查不到时用 LIKE 再查一遍。
        //
        // 为什么必须兜底：ngram 解析器会丢弃「包含停用词的 token」，
        // 而 MySQL 默认停用词表含单字符 a 和 i。于是含 a/i 的英文词会整词失效——
        // 实测「Java」被切成 Ja/av/va，三个 bigram 全含 a 全被丢，搜出来 0 条。
        // 解法是 SET GLOBAL innodb_ft_enable_stopword=0（见 scripts/init-db.sql），
        // 但那是全局变量，MySQL 重启就失效。兜底保证即使配置丢了也只是变慢，不会搜不到。
        if (items.isEmpty() && "match".equals(mode)) {
            items = query("like", kw, type, starred, includeArchived, limit, offset);
            total = count("like", kw, type, starred, includeArchived);
        }

        return new Page(items, total);
    }

    private List<Row> query(String mode, String kw, String type, Boolean starred,
                            boolean archived, int limit, long offset) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM entries e WHERE ");
        List<Object> params = new ArrayList<>();
        conditions(sql, params, mode, kw, type, starred, archived);
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbc.query(sql.toString(), ROW, params.toArray());
    }

    private long count(String mode, String kw, String type, Boolean starred, boolean archived) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM entries WHERE ");
        List<Object> params = new ArrayList<>();
        conditions(sql, params, mode, kw, type, starred, archived);
        Long n = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return n == null ? 0 : n;
    }

    /**
     * 拼 WHERE 条件。
     *
     * MATCH 的列必须与 ft_search 索引定义完全一致且同序，否则报 1191。
     * 索引定义在 V1__init.sql：FULLTEXT KEY ft_search (raw_input, title, ai_summary)
     *
     * 用 BOOLEAN MODE 而不是 NATURAL LANGUAGE MODE，这是踩过坑的：
     *   NATURAL 会把「出现在超过 50% 行里」的 token 当停用词丢掉。
     *   配 ngram 分词器（按 2 字滑窗切）后，中文常用字组合极易触发这个阈值，
     *   实测数据量小时搜「沿途」score 直接为 0 —— 搜索静默失效，用户毫无感知。
     *   BOOLEAN 不做阈值过滤，行为确定，代价是没有相关度排序。
     *   对随手记这种「命中即有用」的场景，排序价值远低于「不能漏」。
     */
    private static void conditions(StringBuilder sql, List<Object> params, String mode, String kw,
                                   String type, Boolean starred, boolean archived) {
        sql.append("archived = ?");
        params.add(archived ? 1 : 0);

        if ("match".equals(mode)) {
            sql.append(" AND MATCH(raw_input, title, ai_summary) AGAINST (? IN BOOLEAN MODE)");
            params.add(booleanQuery(kw));
        } else if ("like".equals(mode)) {
            sql.append(" AND (raw_input LIKE ? OR title LIKE ? OR ai_summary LIKE ?)");
            String like = "%" + kw + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }

        if (type != null && !type.isBlank()) {
            sql.append(" AND entry_type = ?");
            params.add(type.trim().toLowerCase());
        }
        if (starred != null) {
            sql.append(" AND starred = ?");
            params.add(starred ? 1 : 0);
        }
    }

    /**
     * 把用户输入转成 BOOLEAN MODE 查询串。
     *
     * BOOLEAN 模式里 + - < > ~ " * 都有语法含义，用户输入可能撞上。
     * 全部转义掉：只保留纯词条，多个词用空格分隔表示「任一命中」。
     */
    private static String booleanQuery(String keyword) {
        StringBuilder sb = new StringBuilder();
        // 按非字母数字（ASCII 边界）切词。中文连续汉字会成一个整词，
        // 交给 ngram 解析器自己滑窗，这正是我们要的。
        for (String token : keyword.split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
            if (token.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(token);
        }
        // 整串都是标点时兜底，避免拼出空查询串导致 MATCH 语法错。
        return sb.isEmpty() ? "\"\"" : sb.toString();
    }

    /**
     * 改星标 / 归档 / 备注。
     *
     * 只改这三个字段。这里永远不出现 raw_input —— 那是用户原话。
     */
    @Override
    public void updateFlags(long id, Boolean starred, Boolean archived, String note) {
        StringBuilder sql = new StringBuilder("UPDATE entries SET updated_at = NOW(3)");
        List<Object> params = new ArrayList<>();
        if (starred != null) {
            sql.append(", starred = ?");
            params.add(starred ? 1 : 0);
        }
        if (archived != null) {
            sql.append(", archived = ?");
            params.add(archived ? 1 : 0);
        }
        if (note != null) {
            sql.append(", note = ?");
            params.add(note);
        }
        sql.append(" WHERE id = ?");
        params.add(id);
        jdbc.update(sql.toString(), params.toArray());
    }

    /**
     * 物理删除。
     *
     * 不做软删，是因为"归档"已经是软删了。再藏一层 deleted_at
     * 只会让每个查询多一个条件，而用户删掉就是想删掉。
     * 附件靠外键 ON DELETE CASCADE 一起带走。
     */
    @Override
    public void deleteById(long id) {
        jdbc.update("DELETE FROM entries WHERE id = ?", id);
    }

    @Override
    public Stats stats() {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM entries WHERE archived = 0)                             AS total,
                    (SELECT COUNT(*) FROM entries WHERE archived = 0 AND created_at >= CURDATE())  AS today,
                    (SELECT COUNT(*) FROM entries WHERE archived = 0
                       AND created_at >= DATE_SUB(CURDATE(), INTERVAL 7 DAY))                      AS week,
                    (SELECT COUNT(*) FROM entries WHERE archived = 0 AND starred = 1)              AS starred,
                    (SELECT COUNT(*) FROM entries WHERE archived = 1)                              AS archived,
                    (SELECT COUNT(*) FROM entries WHERE ai_status = 'pending')                     AS pending_ai
                """, (ResultSet rs, int i) -> new Stats(
                rs.getLong("total"),
                rs.getLong("today"),
                rs.getLong("week"),
                rs.getLong("starred"),
                rs.getLong("archived"),
                rs.getLong("pending_ai")));
    }

    /**
     * AI 结果回写。
     *
     * SQL 里刻意不出现 raw_input。润色只能补充，不能覆盖。
     */
    @Override
    public void applyAiResult(long id, String title, String summary, String tags, String model, int latencyMs) {
        jdbc.update("""
                UPDATE entries
                SET title = ?, ai_summary = ?, ai_tags = ?,
                    ai_model = ?, ai_latency_ms = ?,
                    ai_status = 'done', ai_error = NULL
                WHERE id = ?
                """, title, summary, tags, model, latencyMs, id);
    }

    @Override
    public void markAiFailed(long id, String error) {
        jdbc.update("""
                UPDATE entries
                SET ai_status = 'failed', ai_error = ?
                WHERE id = ?
                """, error == null ? "unknown" : error.substring(0, Math.min(500, error.length())), id);
    }

    @Override
    public List<Long> pendingForPolish(int limit) {
        return jdbc.query("""
                SELECT id FROM entries
                WHERE ai_status = 'pending'
                  AND entry_type IN ('link', 'repo', 'video')
                  AND created_at < NOW(3) - INTERVAL 5 SECOND
                ORDER BY created_at ASC
                LIMIT ?
                """, (rs, i) -> rs.getLong("id"), limit);
    }

    private static final RowMapper<Row> ROW = (ResultSet rs, int i) -> new Row(
            rs.getLong("id"),
            rs.getString("source"),
            rs.getString("entry_type"),
            rs.getString("raw_input"),
            rs.getString("url"),
            rs.getString("domain"),
            rs.getString("title"),
            rs.getString("ai_summary"),
            rs.getString("ai_tags"),
            rs.getString("ai_status"),
            rs.getTimestamp("created_at") == null
                    ? null
                    : rs.getTimestamp("created_at").toLocalDateTime().toString(),
            rs.getBoolean("starred"),
            rs.getBoolean("archived"),
            rs.getString("image_sha"),
            rs.getLong("attachment_count"));
}

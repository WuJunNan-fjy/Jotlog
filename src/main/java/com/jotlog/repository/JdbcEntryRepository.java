package com.jotlog.repository;

import com.jotlog.core.Extractor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class JdbcEntryRepository implements EntryRepository {

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
        return jdbc.query("""
                SELECT id, source, entry_type, raw_input, url, domain,
                       title, ai_summary, ai_tags, ai_status, created_at
                FROM entries
                WHERE archived = 0
                ORDER BY created_at DESC
                LIMIT ?
                """, ROW, limit);
    }

    @Override
    public Row findById(long id) {
        List<Row> rows = jdbc.query("""
                SELECT id, source, entry_type, raw_input, url, domain,
                       title, ai_summary, ai_tags, ai_status, created_at
                FROM entries
                WHERE id = ?
                """, ROW, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 全文检索。
     *
     * ngram_token_size 默认 2，两个及以上汉字走 FULLTEXT。
     * 单字查询 ngram 命中不了，降级到 LIKE —— 语法上 5.7+ 都成立，
     * 不像 trigram 那样静默返回空结果。
     */
    @Override
    public List<Row> search(String keyword, int limit) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return recent(limit);
        }
        // 单字走不了 ngram 索引（ngram_token_size=2），直接 LIKE
        if (kw.length() < 2) {
            return likeSearch(kw, limit);
        }
        // MATCH 的列必须与 ft_search 索引定义完全一致且同序，否则报 1191。
        // 索引定义在 V1__init.sql：FULLTEXT KEY ft_search (raw_input, title, ai_summary)
        //
        // 用 BOOLEAN MODE 而不是 NATURAL LANGUAGE MODE，这是踩过坑的：
        //   NATURAL 会把「出现在超过 50% 行里」的 token 当停用词丢掉。
        //   配 ngram 分词器（按 2 字滑窗切）后，中文常用字组合极易触发这个阈值，
        //   实测数据量小时搜「沿途」score 直接为 0 —— 搜索静默失效，用户毫无感知。
        //   BOOLEAN 不做阈值过滤，行为确定，代价是没有相关度排序。
        //   对随手记这种「命中即有用」的场景，排序价值远低于「不能漏」。
        List<Row> rows = jdbc.query("""
                SELECT id, source, entry_type, raw_input, url, domain,
                       title, ai_summary, ai_tags, ai_status, created_at
                FROM entries
                WHERE MATCH(raw_input, title, ai_summary) AGAINST (? IN BOOLEAN MODE)
                  AND archived = 0
                ORDER BY created_at DESC
                LIMIT ?
                """, ROW, booleanQuery(kw), limit);

        // 兜底：全文索引查不到时用 LIKE 再查一遍。
        //
        // 为什么必须兜底：ngram 解析器会丢弃「包含停用词的 token」，
        // 而 MySQL 默认停用词表含单字符 a 和 i。于是含 a/i 的英文词会整词失效——
        // 实测「Java」被切成 Ja/av/va，三个 bigram 全含 a 全被丢，搜出来 0 条。
        // 解法是 SET GLOBAL innodb_ft_enable_stopword=0（见 scripts/init-db.sql），
        // 但那是全局变量，MySQL 重启就失效。兜底保证即使配置丢了也只是变慢，不会搜不到。
        if (rows.isEmpty()) {
            return likeSearch(kw, limit);
        }
        return rows;
    }

    private List<Row> likeSearch(String kw, int limit) {
        return jdbc.query("""
                SELECT id, source, entry_type, raw_input, url, domain,
                       title, ai_summary, ai_tags, ai_status, created_at
                FROM entries
                WHERE archived = 0
                  AND (raw_input LIKE ? OR title LIKE ? OR ai_summary LIKE ?)
                ORDER BY created_at DESC
                LIMIT ?
                """, ROW, "%" + kw + "%", "%" + kw + "%", "%" + kw + "%", limit);
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
                    : rs.getTimestamp("created_at").toLocalDateTime().toString());
}

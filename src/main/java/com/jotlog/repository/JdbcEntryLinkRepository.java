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

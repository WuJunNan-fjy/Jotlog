package com.jotlog.repository;

import org.springframework.dao.DuplicateKeyException;
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
        try {
            jdbc.update("INSERT INTO tags (name) VALUES (?)", name);
        } catch (DuplicateKeyException dup) {
            // 并发兜底：同名标签被另一个请求先建了，靠 uk_tag_name 挡重后回查
            Long existing = jdbc.queryForObject(
                    "SELECT id FROM tags WHERE name = ? LIMIT 1", Long.class, name);
            return existing == null ? -1 : existing;
        }
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    @Override
    public void replaceForEntry(long entryId, List<Long> tagIds) {
        jdbc.update("DELETE FROM entry_tags WHERE entry_id = ?", entryId);
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        List<Object[]> batch = tagIds.stream()
                .distinct()
                .map(id -> new Object[]{entryId, id})
                .toList();
        jdbc.batchUpdate("INSERT IGNORE INTO entry_tags (entry_id, tag_id) VALUES (?, ?)", batch);
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
                    return null; // ResultSetExtractor 要求有返回值
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

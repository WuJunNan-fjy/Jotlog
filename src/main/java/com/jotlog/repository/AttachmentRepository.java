package com.jotlog.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class AttachmentRepository {

    private final JdbcTemplate jdbc;

    public AttachmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(NewAttachment a) {
        jdbc.update("""
                INSERT INTO attachments
                    (entry_id, sha256, filename, mime, size_bytes, storage_path, source_key)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """,
                a.entryId(), a.sha256(), a.filename(), a.mime(),
                a.sizeBytes(), a.storagePath(), a.sourceKey());
    }

    public List<Map<String, Object>> listByEntry(long entryId) {
        return jdbc.queryForList(
                "SELECT id, filename, mime, size_bytes FROM attachments WHERE entry_id = ?",
                entryId);
    }

    public record NewAttachment(
            long entryId,
            String sha256,
            String filename,
            String mime,
            Long sizeBytes,
            String storagePath,
            String sourceKey
    ) {
    }
}

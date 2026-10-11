package com.jotlog.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** 某条记录的全部附件，含下载用的 id 和展示用的 sha。按上传顺序。 */
    public List<Meta> metaByEntry(long entryId) {
        return jdbc.query(
                "SELECT id, sha256, filename, mime, size_bytes FROM attachments "
                        + "WHERE entry_id = ? ORDER BY id",
                (rs, i) -> new Meta(
                        rs.getLong("id"),
                        rs.getString("sha256"),
                        rs.getString("filename"),
                        rs.getString("mime"),
                        rs.getLong("size_bytes")),
                entryId);
    }

    /** 按 sha 找附件（raw 端点用）。sha 全库唯一索引，同一文件去重共享。 */
    public Optional<Stored> findBySha(String sha) {
        List<Stored> rows = jdbc.query(
                "SELECT id, sha256, filename, mime, size_bytes, storage_path FROM attachments "
                        + "WHERE sha256 = ? ORDER BY id LIMIT 1",
                (rs, i) -> new Stored(
                        rs.getLong("id"),
                        rs.getString("sha256"),
                        rs.getString("filename"),
                        rs.getString("mime"),
                        rs.getLong("size_bytes"),
                        rs.getString("storage_path")),
                sha);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 给前端的附件元数据。 */
    public record Meta(long id, String sha256, String filename, String mime, long sizeBytes) {
    }

    /**
     * 全库仍被引用的存储 key（DISTINCT）。
     * 垃圾回收拿它与存储后端实际对象对账：同 sha 被多条记录共享只算一个引用。
     */
    public List<String> allReferencedKeys() {
        return jdbc.queryForList("""
                SELECT DISTINCT storage_path FROM attachments
                WHERE storage_path IS NOT NULL AND storage_path <> ''
                """, String.class);
    }

    /** 全部附件元数据（带所属条目），数据导出用。一次查完在内存分组，避免 N+1。 */
    public List<ExportAttachment> allForExport() {
        return jdbc.query("""
                SELECT entry_id, sha256, filename, mime, size_bytes
                FROM attachments ORDER BY entry_id, id
                """, (rs, i) -> new ExportAttachment(
                rs.getLong("entry_id"),
                rs.getString("sha256"),
                rs.getString("filename"),
                rs.getString("mime"),
                rs.getLong("size_bytes")));
    }

    /** 导出用的附件行。 */
    public record ExportAttachment(long entryId, String sha256, String filename,
                                   String mime, long sizeBytes) {
    }

    /** 含存储位置的完整信息，只有服务端读内容用，不出去。 */
    public record Stored(long id, String sha256, String filename, String mime,
                         long sizeBytes, String storagePath) {
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

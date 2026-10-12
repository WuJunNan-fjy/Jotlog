package com.jotlog.core;

import com.jotlog.core.Entry.Attachment;
import com.jotlog.core.Entry.ReplyTarget;
import com.jotlog.repository.AttachmentRepository;
import com.jotlog.repository.EntryRepository;
import com.jotlog.repository.JdbcEntryRepository;
import com.jotlog.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * 统一入库服务。
 *
 * 同步路径上唯一允许做的事：幂等检查 → 落库 → 返回。
 * 这里绝对不能出现网络调用、AI 调用、正文抓取，任何慢操作都会
 * 撞上飞书 3 秒超时并触发重推。
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final EntryRepository entries;
    private final AttachmentRepository attachments;
    private final StorageService storage;

    public IngestService(JdbcEntryRepository entries,
                         AttachmentRepository attachments,
                         StorageService storage) {
        this.entries = entries;
        this.attachments = attachments;
        this.storage = storage;
    }

    /**
     * 幂等入库。
     *
     * @return 已存在的 id，或新插入的 id
     */
    @Transactional
    public IngestResult persist(Entry entry) {
        // 1. 幂等：同一通道同一消息只处理一次
        if (entry.sourceMsgId() != null) {
            Long existing = entries.findIdBySourceMsg(entry.source(), entry.sourceMsgId());
            if (existing != null) {
                log.debug("重复消息，跳过。source={} msgId={} id={}",
                        entry.source(), entry.sourceMsgId(), existing);
                return new IngestResult(existing, true);
            }
        }

        // 2. 抽取 URL 与类型（纯规则，无网络）
        Extracted extracted = Extractor.extract(entry.rawInput());
        EntryType type = entry.type() == EntryType.UNKNOWN ? extracted.type() : entry.type();
        String url = entry.url() != null ? entry.url() : extracted.url();

        // 3. 落库。raw_input 原样写入，此后再不更新。
        long id = entries.insert(new EntryRepository.NewEntry(
                entry.source(),
                entry.sourceMsgId(),
                entry.replyTarget() == null ? null : entry.replyTarget().chatId(),
                entry.replyTarget() == null ? null : entry.replyTarget().chatType(),
                entry.replyTarget() == null ? null : entry.replyTarget().senderOpenId(),
                type.name().toLowerCase(),
                entry.rawInput(),
                url,
                extracted.domain(),
                sha256(entry.rawInput().getBytes(java.nio.charset.StandardCharsets.UTF_8))
        ));

        // 4. 附件落盘 + 元数据入库
        saveAttachments(id, entry.attachments());

        log.info("已入库 id={} type={} url={} 有附件={}",
                id, type, url, entry.attachments().size());
        return new IngestResult(id, false);
    }

    /**
     * 附件落盘 + 元数据入库。
     *
     * 公有两处调用：persist 的同步路径（消息里直接带字节的场景），
     * 和懒下载补挂的异步路径（飞书 file/image 消息，回执后由 Pipeline 下载）。
     *
     * bytes 为 null 的占位附件直接跳过 —— 那是"待下载"的记号，
     * 下载成功后会带着字节再来一次。
     *
     * 附件失败不能拖垮主记录，原文已经存下来了。
     *
     * @return 实际保存成功的附件数
     */
    public int saveAttachments(long entryId, List<Entry.Attachment> list) {
        int saved = 0;
        for (Attachment att : list) {
            if (att.bytes() == null) {
                continue; // 懒下载占位，不入库
            }
            try {
                String sha = sha256(att.bytes());
                String filename = att.filename() == null ? sha : att.filename();
                // TODO(M1): oss 后端这里是网络调用，飞书图片走进同步路径可能撞 3 秒超时；
                // 接入真实附件量后评估把上传挪到异步 worker
                StorageService.StoredObject stored =
                        storage.store(att.bytes(), filename, att.mime(), sha);
                attachments.insert(new AttachmentRepository.NewAttachment(
                        entryId, sha, filename, att.mime(), (long) att.bytes().length,
                        stored.key(), att.sourceKey()));
                saved++;
            } catch (Exception e) {
                log.warn("附件保存失败，entryId={} filename={}", entryId, att.filename(), e);
            }
        }
        return saved;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 飞书 ≥100MB 大附件的补挂：内容已由调用方分片下载到临时文件，
     * 这里把它流式搬进存储后端并写元数据，全程不把文件读进堆。
     *
     * 与 {@link #saveAttachments} 的 byte[] 路径共用同一张表和内容寻址规则，
     * 区别只在"内容从哪来"。无论成败都删除临时文件 —— 它只是下载中转。
     *
     * @param sha 调用方在下载时用 DigestOutputStream 边写边算好的 sha256
     * @return 1=已挂接；0=失败（仅告警，不拖垮主记录）
     */
    @Transactional
    public int saveAttachmentFile(long entryId, String filename, String mime,
                                  Path tempFile, long size, String sha, String sourceKey) {
        try {
            StorageService.StoredObject stored =
                    storage.storeFile(tempFile, size, filename, mime, sha);
            attachments.insert(new AttachmentRepository.NewAttachment(
                    entryId, sha, filename, mime, size, stored.key(), sourceKey));
            log.info("飞书大附件已保存 entryId={} filename={} size={} sha={}",
                    entryId, filename, size, sha.substring(0, 12));
            return 1;
        } catch (Exception e) {
            log.warn("大附件保存失败，entryId={} filename={}", entryId, filename, e);
            return 0;
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("临时附件文件删除失败 path={}", tempFile, e);
            }
        }
    }

    /**
     * 网页端上传的附件挂到一条【已存在】的条目上。
     *
     * 与飞书路径共用同一套「算 sha → 内容寻址存储 → 元数据落库」，
     * source_key 统一记 "web"，以后排查"这张图从哪来的"有据可查。
     *
     * @return 附件 sha256；同内容已存过则只补关联行，不重复写对象
     */
    @Transactional
    public String attachUploaded(long entryId, String rawFilename, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        String sha = sha256(bytes);
        String filename = sanitizeFilename(rawFilename, sha);
        String mime = sanitizeMime(contentType);

        StorageService.StoredObject stored = storage.store(bytes, filename, mime, sha);
        attachments.insert(new AttachmentRepository.NewAttachment(
                entryId, sha, filename, mime, (long) bytes.length, stored.key(), "web"));
        log.info("网页附件已保存 entryId={} filename={} size={} sha={}",
                entryId, filename, bytes.length, sha.substring(0, 12));
        return sha;
    }

    /**
     * 文件名清洗：去掉客户端可能带来的路径分隔符（Windows 反斜杠和 POSIX 斜杠都防），
     * 剔控制字符，超长截断。绝不能让 filename 携带路径语义 —— 虽然存储 key 与文件名
     * 无关，Content-Disposition 里也会再编码，但脏数据不该进库。
     */
    private static String sanitizeFilename(String raw, String fallbackSha) {
        if (raw == null) {
            return fallbackSha;
        }
        String name = raw.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            return fallbackSha;
        }
        // 512 是库里的列宽，留足余量给编码与扩展名之外的开销
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    /** 浏览器上报的 Content-Type 不可信，但这里只用于存储元数据；raw 端点还有 inline 白名单兜底。 */
    private static String sanitizeMime(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        String mime = contentType.split(";", 2)[0].trim().toLowerCase();
        return mime.matches("[\\w.+-]+/[\\w.+-]+") ? mime : "application/octet-stream";
    }

    public record IngestResult(long id, boolean duplicate) {
    }

    /** 抽取结果。 */
    public record Extracted(String url, String domain, EntryType type) {
    }
}

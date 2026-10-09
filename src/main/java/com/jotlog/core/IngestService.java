package com.jotlog.core;

import com.jotlog.config.StorageProperties;
import com.jotlog.core.Entry.Attachment;
import com.jotlog.core.Entry.ReplyTarget;
import com.jotlog.repository.AttachmentRepository;
import com.jotlog.repository.EntryRepository;
import com.jotlog.repository.JdbcEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

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
    private final Path storageRoot;

    public IngestService(JdbcEntryRepository entries,
                         AttachmentRepository attachments,
                         StorageProperties storage) {
        this.entries = entries;
        this.attachments = attachments;
        this.storageRoot = storage.getAttachmentPath();
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
        for (Attachment att : entry.attachments()) {
            try {
                String sha = sha256(att.bytes());
                String filename = att.filename() == null ? sha : att.filename();
                Path target = storageRoot.resolve(sha.substring(0, 2)).resolve(sha);
                java.nio.file.Files.createDirectories(target.getParent());
                if (!java.nio.file.Files.exists(target)) {
                    java.nio.file.Files.write(target, att.bytes());
                }
                attachments.insert(new AttachmentRepository.NewAttachment(
                        id, sha, filename, att.mime(), (long) att.bytes().length,
                        target.toString(), att.sourceKey()));
            } catch (Exception e) {
                // 附件失败不能拖垮主记录，原文已经存下来了
                log.warn("附件保存失败，entryId={} filename={}", id, att.filename(), e);
            }
        }

        log.info("已入库 id={} type={} url={} 有附件={}",
                id, type, url, entry.attachments().size());
        return new IngestResult(id, false);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public record IngestResult(long id, boolean duplicate) {
    }

    /** 抽取结果。 */
    public record Extracted(String url, String domain, EntryType type) {
    }
}

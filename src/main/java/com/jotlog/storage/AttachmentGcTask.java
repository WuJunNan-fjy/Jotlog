package com.jotlog.storage;

import com.jotlog.repository.AttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 附件垃圾回收。
 *
 * 为什么需要它：附件按内容 sha256 去重存储，条目删除时数据库行走外键级联删掉，
 * 但存储后端里的对象【不能】在删条目时立刻删 —— 同一个 sha 可能还被别的条目引用。
 * 所以删除动作统一推迟到这里：每天凌晨把存储后端的对象清单与 attachments 表对账，
 * 全库没有任何行引用的对象才是真孤儿，可以回收。
 *
 * 保守优先：
 *   - key 形状不符（不是 xx/64hex）的对象一律不碰，bucket 与别的应用共用也安全
 *   - 枚举或对账阶段出错就整轮跳过，绝不带着不完整的引用集合去删东西
 *   - 单个对象删除失败只告警，不影响其他对象
 *
 * 关闭：jotlog.storage.gc.enabled=false。
 */
@Component
@ConditionalOnProperty(prefix = "jotlog.storage.gc", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AttachmentGcTask {

    private static final Logger log = LoggerFactory.getLogger(AttachmentGcTask.class);

    private static final Pattern KEY_SHAPE = Pattern.compile("[0-9a-f]{2}/[0-9a-f]{64}");

    private final StorageService storage;
    private final AttachmentRepository attachments;

    public AttachmentGcTask(StorageService storage, AttachmentRepository attachments) {
        this.storage = storage;
        this.attachments = attachments;
    }

    /** 默认每天 03:40 跑一次。cron 可用 jotlog.storage.gc.cron 覆盖。 */
    @Scheduled(cron = "${jotlog.storage.gc.cron:0 40 3 * * ?}")
    public void collect() {
        List<String> storedKeys;
        Set<String> referenced;
        try {
            storedKeys = storage.listKeys();
            referenced = new HashSet<>(attachments.allReferencedKeys());
        } catch (Exception e) {
            // 拿不到完整视图就不删：宁可漏收一轮，不可误删一个
            log.error("附件 GC 对账信息获取失败，本轮跳过", e);
            return;
        }

        int removed = 0;
        int failed = 0;
        for (String key : storedKeys) {
            if (key == null || !KEY_SHAPE.matcher(key).matches() || referenced.contains(key)) {
                continue;
            }
            try {
                storage.delete(key);
                removed++;
            } catch (Exception e) {
                failed++;
                log.warn("孤儿附件删除失败 key={}", key, e);
            }
        }

        if (removed > 0 || failed > 0) {
            log.info("附件 GC 完成：扫描 {} 个对象，回收孤儿 {} 个，失败 {} 个",
                    storedKeys.size(), removed, failed);
        } else {
            log.debug("附件 GC 完成：扫描 {} 个对象，无孤儿", storedKeys.size());
        }
    }
}

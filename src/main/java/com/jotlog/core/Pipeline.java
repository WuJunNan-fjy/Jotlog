package com.jotlog.core;

import com.jotlog.channel.ChannelAdapter;
import com.jotlog.channel.feishu.FeishuChannel;
import com.jotlog.config.FeishuProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一管线。
 *
 * 顺序是铁律，不可调换：
 *   入库 → 回执 → 异步增强
 *
 * 任何慢操作（抓正文、AI、GitHub API）放进异步队列，
 * 绝不允许出现在同步路径上，否则撞上飞书 3 秒超时会被重推。
 */
@Service
public class Pipeline {

    private static final Logger log = LoggerFactory.getLogger(Pipeline.class);

    private final List<ChannelAdapter> channels;
    private final IngestService ingest;
    private final FeishuProperties props;
    private final PolishQueue polishQueue;

    private final ExecutorService workers =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "jotlog-worker");
                t.setDaemon(true);
                return t;
            });

    public Pipeline(List<ChannelAdapter> channels,
                    IngestService ingest,
                    FeishuProperties props,
                    PolishQueue polishQueue) {
        this.channels = channels;
        this.ingest = ingest;
        this.props = props;
        this.polishQueue = polishQueue;
    }

    @PostConstruct
    public void start() {
        for (ChannelAdapter channel : channels) {
            try {
                channel.start(this::handleAsync);
                log.info("通道已启动: {}", channel.name());
            } catch (Exception e) {
                log.error("通道启动失败: {}", channel.name(), e);
            }
        }
    }

    /**
     * 通道回调入口。
     *
     * 飞书的 SDK 在这个线程里等待我们返回，所以真正的处理
     * 必须立刻丢给工作线程，这个方法一秒都不占。
     */
    private void handleAsync(Entry entry) {
        workers.submit(() -> handle(entry));
    }

    void handle(Entry entry) {
        IngestService.IngestResult result;
        try {
            result = ingest.persist(entry);
        } catch (Exception e) {
            log.error("入库失败，source={} msgId={}", entry.source(), entry.sourceMsgId(), e);
            return;
        }

        if (result.duplicate()) {
            log.debug("重复消息，跳过回执。id={}", result.id());
            return;
        }

        // 第二步：回执。必须在增强之前，因为用户等着确认。
        if (entry.replyTarget().valid()) {
            ChannelAdapter adapter = adapterFor(entry.source());
            if (adapter != null) {
                String receipt = props.getReceiptText() == null || props.getReceiptText().isBlank()
                        ? "已记下，稍后补全"
                        : props.getReceiptText();
                try {
                    adapter.reply(entry.replyTarget(), receipt);
                } catch (Exception e) {
                    log.warn("回执失败，不影响已入库的记录", e);
                }
            }
        }

        // 第三步：异步增强。仍在 worker 线程里（不占飞书回调线程），
        // 顺序做两件事：先把附件补挂上，再丢润色队列。
        backfillAttachments(entry, result.id());
        polishQueue.enqueue(result.id(), entry);
    }

    /**
     * 懒下载附件补挂。
     *
     * 通道投递的附件里 bytes 为 null 的是"待下载"占位（飞书 file/image 消息）。
     * 此时用户已经拿到回执，这里慢一点无所谓 —— 下载成功就落盘挂到条目上，
     * 失败只 warn：原文早已入库，附件缺失是可接受的降级。
     *
     * 重试策略刻意从简：飞书消息本身可以重新转发给机器人再触发一次
     * （幂等键挡住重复入库，附件 sha 去重挡住重复落盘），
     * 比在本地维护一套重试队列简单得多。
     */
    private void backfillAttachments(Entry entry, long entryId) {
        List<Entry.Attachment> lazy = entry.attachments().stream()
                .filter(a -> a.bytes() == null && a.sourceKey() != null)
                .toList();
        if (lazy.isEmpty()) {
            return;
        }

        if (!"feishu".equals(entry.source()) || !(adapterFor("feishu") instanceof FeishuChannel feishu)) {
            log.warn("通道 {} 声明了懒下载附件但没有实现下载，跳过", entry.source());
            return;
        }

        boolean isImage = entry.type() == EntryType.IMAGE;
        String messageId = entry.sourceMsgId();

        for (Entry.Attachment att : lazy) {
            FeishuChannel.Downloaded dl = feishu.download(
                    messageId, att.sourceKey(), isImage ? "image" : "file");
            if (dl == null || dl.bytes() == null || dl.bytes().length == 0) {
                continue;
            }

            // 文件消息飞书不给 mime：图片按魔数嗅探，其他按扩展名映射
            String filename = dl.filename() != null && !dl.filename().isBlank()
                    ? dl.filename() : att.filename();
            String mime = isImage
                    ? sniffImageMime(dl.bytes())
                    : mimeByExtension(filename);

            int saved = ingest.saveAttachments(entryId, List.of(new Entry.Attachment(
                    filename, mime, dl.bytes(), att.sourceKey())));
            if (saved > 0) {
                log.info("附件补挂成功 entryId={} filename={} size={}",
                        entryId, filename, dl.bytes().length);
            }
        }
    }

    /** 只认字节魔数 —— image_key 没有扩展名，猜 mime 只能看内容本身。 */
    private static String sniffImageMime(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E') return "image/webp";
        if (b.length >= 4 && b[0] == 'B' && b[1] == 'M') return "image/bmp";
        return "application/octet-stream";
    }

    private static String mimeByExtension(String filename) {
        if (filename == null) return "application/octet-stream";
        String ext = "";
        int dot = filename.lastIndexOf('.');
        if (dot >= 0 && dot < filename.length() - 1) {
            ext = filename.substring(dot + 1).toLowerCase();
        }
        return switch (ext) {
            case "txt", "log" -> "text/plain";
            case "md" -> "text/markdown";
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "mp4" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "zip" -> "application/zip";
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            default -> "application/octet-stream";
        };
    }

    private ChannelAdapter adapterFor(String name) {
        for (ChannelAdapter c : channels) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }
}

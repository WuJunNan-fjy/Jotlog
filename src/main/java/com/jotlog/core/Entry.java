package com.jotlog.core;

import java.time.Instant;
import java.util.List;

/**
 * 归一化后的统一中间表示。
 *
 * 所有通道（飞书 / PWA / CLI / MCP）都必须把自己的原始输入
 * 转换成Entry，再交给统一管线。通道只负责"投递"，不负责"理解"。
 *
 * 这样做的收益：换通道、加通道都不需要动管线代码。
 */
public record Entry(
        /** 通道原始文本，永不可变，最终原样落库。 */
        String rawInput,

        /** 结构化类型。 */
        EntryType type,

        /** 抽取到的链接，可能为 null。 */
        String url,

        /** 通道回复目标，飞书回执用。 */
        ReplyTarget replyTarget,

        /** 来源通道名。 */
        String source,

        /** 通道内唯一消息 id，用于幂等。 */
        String sourceMsgId,

        /** 附件字节，可能为空列表。 */
        List<Attachment> attachments,

        /** 收到时间。 */
        Instant receivedAt
) {

    public Entry {
        if (rawInput == null || rawInput.isBlank()) {
            throw new IllegalArgumentException("rawInput 不能为空");
        }
        if (type == null) {
            type = EntryType.UNKNOWN;
        }
        if (attachments == null) {
            attachments = List.of();
        }
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
    }

    public static Entry ofText(String rawInput, ReplyTarget target, String source, String msgId) {
        return new Entry(rawInput, EntryType.NOTE, null, target, source, msgId, List.of(), Instant.now());
    }

    /**
     * 附件载荷。
     *
     * bytes 有值 = 字节已在内存，走完管线立即落盘，不长期驻留。
     * bytes 为 null = 懒下载占位：通道只先报个名（sourceKey 是飞书 file_key），
     * 真正的下载发生在回执之后的异步阶段 —— 下载是网络调用，
     * 出现在同步路径上就会撞飞书 3 秒超时被重推。
     */
    public record Attachment(
            String filename,
            String mime,
            byte[] bytes,
            /** 通道自己的资源标识，飞书是 file_key，用于懒下载。 */
            String sourceKey
    ) {
    }

    /** 回执定位信息。 */
    public record ReplyTarget(
            String chatId,
            String chatType,
            String messageId,
            String senderOpenId
    ) {
        public static ReplyTarget none() {
            return new ReplyTarget(null, null, null, null);
        }

        public boolean valid() {
            return chatId != null && messageId != null;
        }
    }
}

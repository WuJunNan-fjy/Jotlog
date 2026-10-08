package com.jotlog.repository;

import java.util.List;

/**
 * 条目持久化。
 *
 * 刻意不暴露"更新正文"的方法——这是让 raw_input 不可变的
 * 第一道防线，第二个防线是应用层没有对应 SQL。
 */
public interface EntryRepository {

    /** 按通道消息 id 查已存在的条目 id，查不到返回 null。 */
    Long findIdBySourceMsg(String source, String sourceMsgId);

    /** 插入新条目，返回自增 id。 */
    long insert(NewEntry entry);

    /** 最近若干条。 */
    List<Row> recent(int limit);

    /** 按 id 精确查。 */
    Row findById(long id);

    /** 标题搜索。1 字查询由上层降级到 LIKE，这里只处理 >= 2 字。 */
    List<Row> search(String keyword, int limit);

    /** 写入 AI 补充区。这是唯一允许改写已有条目的路径。 */
    void applyAiResult(long id, String title, String summary, String tags, String model, int latencyMs);

    /** 记录 AI 失败原因，条目本身保持已入库状态。 */
    void markAiFailed(long id, String error);

    /** 待润色队列：状态为 pending 且超过指定时间。 */
    List<Long> pendingForPolish(int limit);

    /**
     * 分页查询。 keyword 为空时就是"按时间倒序翻页"。
     *
     * @param includeArchived true 时只看归档箱，false 时只看正常条目
     */
    Page searchPage(String keyword, String type, Boolean starred, boolean includeArchived,
                    int limit, long offset);

    /** 改星标 / 归档 / 备注。三个都传 null 表示什么都不改。 */
    void updateFlags(long id, Boolean starred, Boolean archived, String note);

    /** 物理删除。附件走外键级联。 */
    void deleteById(long id);

    /** 首页顶部那几个数字。 */
    Stats stats();

    record Stats(long total, long today, long week, long starred, long archived, long pendingAi) {
    }

    record Page(List<Row> items, long total) {
    }

    record NewEntry(
            String source,
            String sourceMsgId,
            String chatId,
            String chatType,
            String senderOpenId,
            String entryType,
            String rawInput,
            String url,
            String domain,
            String contentHash
    ) {
    }

    /**
     * 返回给前端的行。
     *
     * 带 starred / archived 是为了让列表能显示当前状态 ——
     * 否则前端在"全部"列表里根本不知道哪条加了星标。
     */
    record Row(
            long id,
            String source,
            String entryType,
            String rawInput,
            String url,
            String domain,
            String title,
            String aiSummary,
            String aiTags,
            String aiStatus,
            String createdAt,
            boolean starred,
            boolean archived
    ) {
    }
}

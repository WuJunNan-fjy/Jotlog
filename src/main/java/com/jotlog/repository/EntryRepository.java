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

    /**
     * 写入 AI 补充区。这是唯一允许改写已有条目补充字段的路径，
     * SQL 里不出现 raw_input。分类与标签建议由上层在同事务内另行写关联表。
     */
    void applyAiResult(long id, String title, String summary, String tagsSnapshot,
                       Long categoryId, String suggestedCategory, String model, int latencyMs);

    void markAiProcessing(long id);

    void markAiFailed(long id, String error);

    /** note 跳过 AI 时按规则归入指定分类（可为 null）。 */
    void skipWithCategory(long id, Long categoryId);

    /** 待润色队列：pending/failed 且超过指定时间；includeNotes 决定是否含纯文本随记。 */
    List<Long> pendingForPolish(int limit, boolean includeNotes);

    /** 冷启动引导抽样：只取原文，按时间倒序。 */
    List<String> sampleRawInput(int limit);

    /** 手动设置/清除分类；清除或改选时同时清空 AI 新分类建议。 */
    void setCategory(long id, Long categoryId);

    /**
     * 分页查询。 keyword 为空时就是"按时间倒序翻页"。
     *
     * @param includeArchived true 时只看归档箱，false 时只看正常条目
     * @param categoryId      非空时只看该分类
     * @param tag             非空时只看带该标签的条目
     * @param uncategorized   true 时只看 category_id 为空的条目
     */
    Page searchPage(String keyword, String type, Boolean starred, boolean includeArchived,
                    Long categoryId, String tag, boolean uncategorized,
                    int limit, long offset);

    /** 改星标 / 归档 / 备注。三个都传 null 表示什么都不改。 */
    void updateFlags(long id, Boolean starred, Boolean archived, String note);

    /** 物理删除。附件与标签关联走外键级联。 */
    void deleteById(long id);

    /** 首页顶部那几个数字。 */
    Stats stats();

    /** 全量条目（含归档、备注 note），按时间正序。只给数据导出用，列表接口不许碰它。 */
    List<ExportRow> exportAll();

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
     *
     * imageSha / attachmentCount 是关联子查询聚合出来的附件摘要：
     * 列表页靠它们决定要不要给缩略图和附件标记，不用逐条再查。
     * imageSha 为 null 表示这条没有图片附件。
     *
     * tags 是可变 List：Jdbc 层查出页数据后二次批量填充标签名，
     * 避免在 RowMapper 里做 N+1。
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
            boolean archived,
            String imageSha,
            long attachmentCount,
            Long categoryId,
            String categoryName,
            String suggestedCategory,
            List<String> tags
    ) {
    }

    /**
     * 导出用的完整行。比列表 Row 多了备注 note，字段平铺、不带附件聚合，
     * 附件清单由导出接口另行一次性查询后在内存里关联。
     */
    record ExportRow(
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
            String note,
            boolean starred,
            boolean archived,
            String createdAt
    ) {
    }
}

package com.jotlog.enrich;

import com.jotlog.repository.CategoryRepository;
import com.jotlog.repository.EntryRepository;
import com.jotlog.repository.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 结论落库的唯一入口。
 *
 * 补充区字段 + category_id + entry_tags 在一个事务里替换，
 * 全程不出现 raw_input。标签先 findOrCreate 再全量替换关联，
 * 保证"AI 这轮给出的标签集合"就是条目最终的标签集合。
 */
@Service
public class EnrichmentService {

    /** note 不调 AI 时按规则归入的分类名（V3 种子里有它）。 */
    public static final String NOTE_CATEGORY = "灵感随记";

    private final EntryRepository entries;
    private final TagRepository tags;
    private final CategoryRepository categories;

    public EnrichmentService(EntryRepository entries, TagRepository tags,
                             CategoryRepository categories) {
        this.entries = entries;
        this.tags = tags;
        this.categories = categories;
    }

    @Transactional
    public void applyDone(long entryId, AiResult result, String model, int latencyMs) {
        var tagIds = result.tags().stream().map(tags::findOrCreate).toList();
        tags.replaceForEntry(entryId, tagIds);
        String snapshot = String.join(",", result.tags());
        entries.applyAiResult(entryId, result.title(), result.summary(), snapshot,
                result.categoryId(), result.suggestedCategory(), model, latencyMs);
    }

    /** 纯文本随记跳过 AI：状态置 skipped，按规则归入「灵感随记」（分类不存在则保持待分类）。 */
    @Transactional
    public void applyNoteSkipped(long entryId) {
        CategoryRepository.CategoryRow c = categories.findByName(NOTE_CATEGORY);
        entries.skipWithCategory(entryId, c == null ? null : c.id());
    }
}

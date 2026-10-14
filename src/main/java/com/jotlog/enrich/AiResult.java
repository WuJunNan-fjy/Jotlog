package com.jotlog.enrich;

import java.util.List;

/**
 * AI 的结构化结论。
 *
 * categoryId 为 null 且 suggestedCategory 非空 = 现有分类都不合适，
 * AI 建议了一个新分类名，等用户在页面采纳（AI 自己不允许建分类）。
 */
public record AiResult(
        Long categoryId,
        String suggestedCategory,
        String title,
        String summary,
        List<String> tags
) {
}

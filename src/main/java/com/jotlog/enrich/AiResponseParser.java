package com.jotlog.enrich;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jotlog.repository.CategoryRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 严格解析模型输出。
 *
 * 任何不合规（非 JSON、categoryId 不在白名单、字段类型错、tags 不是数组）
 * 都抛 IllegalArgumentException，由 worker 记 ai_error，
 * 绝不把半成品写进补充区——宁可失败重试，也不让脏数据污染条目。
 */
public final class AiResponseParser {

    private static final int TITLE_MAX = 40;
    private static final int SUMMARY_MAX = 200;
    private static final int SUGGEST_MAX = 16;

    private static final ObjectMapper JSON = new ObjectMapper();

    private AiResponseParser() {
    }

    public static AiResult parse(String json, List<CategoryRepository.CategoryRow> categories) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("模型返回为空");
        }
        String body = stripCodeFence(json.strip());
        Map<?, ?> node;
        try {
            node = JSON.readValue(body, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("模型返回不是合法 JSON: " + e.getMessage());
        }

        Set<Long> validIds = categories.stream()
                .map(CategoryRepository.CategoryRow::id)
                .collect(Collectors.toSet());

        Long categoryId = null;
        Object cid = node.get("categoryId");
        if (cid instanceof Number n) {
            long v = n.longValue();
            if (!validIds.contains(v)) {
                throw new IllegalArgumentException("模型返回了不存在的 categoryId=" + v);
            }
            categoryId = v;
        } else if (cid != null) {
            // 容忍 null；非数字非 null 直接拒
            throw new IllegalArgumentException("categoryId 必须是数字或 null");
        }

        String title = clip(cleanStr(node.get("title")), TITLE_MAX);
        String summary = clip(cleanStr(node.get("summary")), SUMMARY_MAX);
        if (title == null && summary == null) {
            throw new IllegalArgumentException("title 与 summary 不能同时为空");
        }

        // 选了已有分类时，建议名必须清空，避免两边语义打架
        String suggested = categoryId == null
                ? clip(cleanStr(node.get("suggestedCategory")), SUGGEST_MAX)
                : null;

        Object tagsNode = node.get("tags");
        if (!(tagsNode instanceof List<?> rawTags)) {
            throw new IllegalArgumentException("tags 必须是数组");
        }
        List<String> tags = TagNormalizer.normalize(
                rawTags.stream().filter(t -> t instanceof String).map(t -> (String) t).toList());

        return new AiResult(categoryId, suggested, title, summary, tags);
    }

    /** 模型偶尔会把 JSON 包在 ```json ... ``` 里，剥掉围栏。 */
    private static String stripCodeFence(String s) {
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                return s.substring(firstNl + 1, lastFence).strip();
            }
        }
        return s;
    }

    private static String cleanStr(Object o) {
        if (!(o instanceof String s)) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}

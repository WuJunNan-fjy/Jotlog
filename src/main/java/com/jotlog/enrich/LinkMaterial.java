package com.jotlog.enrich;

/**
 * 喂给 AI 的单链接素材。
 *
 * excerpt 在抓取层就已截断，绝不把整页内容灌给模型。
 * status=failed 时 title/excerpt 可能为 null，prompt 会明确告诉模型
 * "该链接抓取失败，不要猜测内容"。
 */
public record LinkMaterial(
        String url,
        String domain,
        LinkKind kind,
        String title,
        String excerpt,
        String status          // done / failed / skipped
) {
}

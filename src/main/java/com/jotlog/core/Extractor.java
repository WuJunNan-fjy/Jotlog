package com.jotlog.core;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从自由文本中抽取链接并判定类型。
 *
 * 纯规则、无网络、无 Spring 依赖。这是有意的：
 * 它跑在同步路径上，必须可预测、可单测、零延迟。
 */
public final class Extractor {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
            Pattern.CASE_INSENSITIVE);

    /** URL 尾部要剥掉的中英文标点。 */
    private static final String TRAILING_PUNCT = ".,;:!?)]}'\"、，。；：！？）】》";

    private static final List<String> REPO_HOSTS = List.of(
            "github.com", "gitee.com", "gitlab.com", "codeberg.org", "gitcode.com");

    private static final List<String> VIDEO_HOSTS = List.of(
            "youtube.com", "youtu.be", "bilibili.com", "vimeo.com",
            "douyin.com", "ixigua.com", "iqiyi.com", "youku.com", "xiaohongshu.com");

    private Extractor() {
    }

    /** 一个链接及其判定结果。domain 已剥 www 前缀。 */
    public record Link(String url, String domain, EntryType type) {
    }

    /**
     * 多链接抽取结果。保持原文出现顺序、按 URL 去重。
     *
     * primary 是"主体链接"：同一条转发里有多个链接时，
     * 按 repo > video > link 的优先级取主体，同优先级取最先出现的。
     * 条目类型与 entry.url 跟随主体，但所有链接都会被登记和抓取。
     */
    public record MultiLinks(List<Link> links, Link primary) {
        public static MultiLinks empty() {
            return new MultiLinks(List.of(), null);
        }
    }

    public static MultiLinks extractAll(String text) {
        if (text == null || text.isBlank()) {
            return MultiLinks.empty();
        }
        // LinkedHashMap：保序 + 按 URL 去重
        LinkedHashMap<String, Link> unique = new LinkedHashMap<>();
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String raw = stripTrailingPunct(m.group());
            String domain = hostOf(raw);
            unique.putIfAbsent(raw, new Link(raw, domain, classify(domain)));
        }
        if (unique.isEmpty()) {
            return MultiLinks.empty();
        }
        List<Link> links = List.copyOf(unique.values());
        Link primary = null;
        for (EntryType t : List.of(EntryType.REPO, EntryType.VIDEO, EntryType.LINK)) {
            Link hit = links.stream().filter(l -> l.type() == t).findFirst().orElse(null);
            if (hit != null) {
                primary = hit;
                break;
            }
        }
        return new MultiLinks(links, primary != null ? primary : links.get(0));
    }

    /**
     * 兼容旧调用：返回主体链接的抽取结果。
     * 入库时条目类型/url 跟随主体；多链接的完整清单由增强链路另行处理。
     */
    public static IngestService.Extracted extract(String text) {
        MultiLinks all = extractAll(text);
        if (all.primary() == null) {
            return new IngestService.Extracted(null, null, EntryType.NOTE);
        }
        Link p = all.primary();
        return new IngestService.Extracted(p.url(), p.domain(), p.type());
    }

    private static String stripTrailingPunct(String raw) {
        // 去掉尾随的标点，中文输入里句号逗号很容易被吞进来
        while (raw.length() > 0 && TRAILING_PUNCT.indexOf(raw.charAt(raw.length() - 1)) >= 0) {
            raw = raw.substring(0, raw.length() - 1);
        }
        return raw;
    }

    private static EntryType classify(String domain) {
        if (domain == null) {
            return EntryType.LINK;
        }
        String d = domain.toLowerCase(Locale.ROOT);
        if (REPO_HOSTS.contains(d)) {
            return EntryType.REPO;
        }
        if (VIDEO_HOSTS.stream().anyMatch(d::contains)) {
            return EntryType.VIDEO;
        }
        return EntryType.LINK;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return null;
        }
    }
}

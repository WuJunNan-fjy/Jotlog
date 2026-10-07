package com.jotlog.core;

import java.net.URI;
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

    private static final List<String> REPO_HOSTS = List.of(
            "github.com", "gitee.com", "gitlab.com", "codeberg.org", "gitcode.com");

    private static final List<String> VIDEO_HOSTS = List.of(
            "youtube.com", "youtu.be", "bilibili.com", "vimeo.com",
            "douyin.com", "ixigua.com", "iqiyi.com", "youku.com", "xiaohongshu.com");

    private Extractor() {
    }

    public static IngestService.Extracted extract(String text) {
        if (text == null || text.isBlank()) {
            return new IngestService.Extracted(null, null, EntryType.NOTE);
        }

        Matcher m = URL_PATTERN.matcher(text);
        if (!m.find()) {
            return new IngestService.Extracted(null, null, EntryType.NOTE);
        }

        // 去掉尾随的标点，中文输入里句号逗号很容易被吞进来
        String raw = m.group();
        while (raw.length() > 0 && ".,;:!?)]}'\"、，。；：！？）】》".indexOf(raw.charAt(raw.length() - 1)) >= 0) {
            raw = raw.substring(0, raw.length() - 1);
        }

        String domain = hostOf(raw);
        EntryType type = classify(domain);
        return new IngestService.Extracted(raw, domain, type);
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
            return host.toLowerCase(Locale.ROOT).startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return null;
        }
    }
}

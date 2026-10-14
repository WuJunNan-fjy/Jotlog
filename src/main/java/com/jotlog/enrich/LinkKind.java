package com.jotlog.enrich;

import java.util.List;
import java.util.Locale;

/**
 * entry_links.kind。
 * npm 单独一类，因为它走 registry JSON API；repo 走站点 API；
 * video 只取标题（正文刻意不抓）；其余走通用网页抓取。
 */
public enum LinkKind {
    REPO, VIDEO, NPM, WEB;

    private static final List<String> REPO_HOSTS = List.of(
            "github.com", "gitee.com", "gitlab.com", "codeberg.org", "gitcode.com");

    private static final List<String> VIDEO_HOSTS = List.of(
            "youtube.com", "youtu.be", "bilibili.com", "vimeo.com",
            "douyin.com", "ixigua.com", "iqiyi.com", "youku.com", "xiaohongshu.com");

    public static LinkKind fromDomain(String domain, String url) {
        if (domain == null) {
            return WEB;
        }
        String d = domain.toLowerCase(Locale.ROOT);
        if (REPO_HOSTS.contains(d)) {
            return REPO;
        }
        if (d.equals("registry.npmjs.org") || d.equals("npmjs.com")) {
            return NPM;
        }
        if (VIDEO_HOSTS.stream().anyMatch(d::contains)) {
            return VIDEO;
        }
        return WEB;
    }
}

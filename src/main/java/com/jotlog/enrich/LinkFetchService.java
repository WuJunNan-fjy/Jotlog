package com.jotlog.enrich;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jotlog.config.AiProperties;
import com.jotlog.core.Extractor;
import com.jotlog.repository.EntryLinkRepository;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 按链接类型抓取素材。每个链接独立超时、独立失败：
 * 抓不到的链接 status=failed，AI 拿到的素材里明确标注"信息不足"，不允许编造。
 *
 * 统一用 jsoup（自动跟随重定向，正好覆盖抖音短链 v.douyin.com → 落地页）。
 * JSON / README 这类非 HTML 响应用 Response.body() 取原始字符串，
 * 不交给 HTML parser —— 否则 README 里的 a < b 会被当成标签吃掉。
 */
@Service
public class LinkFetchService {

    private static final Logger log = LoggerFactory.getLogger(LinkFetchService.class);
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0 Safari/537.36";
    private static final int EXCERPT_MAX = 1200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final EntryLinkRepository links;
    private final AiProperties props;

    public LinkFetchService(EntryLinkRepository links, AiProperties props) {
        this.links = links;
        this.props = props;
    }

    /** 抽取、登记并抓取整条消息的所有链接，返回给 AI 的素材（顺序与原文一致）。 */
    public List<LinkMaterial> fetchAll(long entryId, String rawInput) {
        var all = Extractor.extractAll(rawInput);
        return all.links().stream().map(l -> {
            LinkKind kind = LinkKind.fromDomain(l.domain(), l.url());
            String hash = sha256(l.url());
            links.registerIfAbsent(entryId, l.url(), hash, l.domain(), kind.name().toLowerCase());
            try {
                LinkMaterial m = switch (kind) {
                    case REPO -> fetchGitHub(l.url(), l.domain());
                    case NPM -> fetchNpm(l.url());
                    case VIDEO -> fetchVideo(l.url(), l.domain());
                    case WEB -> fetchWeb(l.url(), l.domain());
                };
                links.saveFetch(entryId, hash, clip(m.title(), 500), clip(m.excerpt(), EXCERPT_MAX * 2),
                        m.status());
                return m;
            } catch (Exception e) {
                log.warn("链接抓取失败 entryId={} url={} reason={}", entryId, l.url(), e.getMessage());
                links.saveFetch(entryId, hash, null, null, "failed");
                return new LinkMaterial(l.url(), l.domain(), kind, null, null, "failed");
            }
        }).toList();
    }

    private LinkMaterial fetchGitHub(String url, String domain) throws Exception {
        String path = URI.create(url).getPath();
        var seg = path == null ? List.<String>of() : List.of(path.split("/"));
        // "/owner/repo" split → ["", "owner", "repo", ...]
        if (seg.size() < 3) {
            return fetchWeb(url, domain);
        }
        String owner = seg.get(1);
        String repo = stripSuffix(seg.get(2));
        String api = "https://api.github.com/repos/" + owner + "/" + repo;

        Object node = parseJson(body(api, "application/vnd.github+json"));
        String desc = jsonString(node, "description");
        String home = jsonString(node, "homepage");

        String readme = "";
        try {
            readme = clip(body(api + "/readme", "application/vnd.github.raw"), EXCERPT_MAX);
        } catch (Exception ignore) {
            // README 403（限流）/404 不致命，仓库描述已够用
        }

        String title = repo + "（GitHub 仓库）" + (desc == null ? "" : "：" + desc);
        String excerpt = String.join("\n",
                desc == null ? "" : "简介：" + desc,
                home == null || home.isBlank() ? "" : "主页：" + home,
                readme.isBlank() ? "" : "README 摘录：" + readme).trim();
        return new LinkMaterial(url, domain, LinkKind.REPO, clip(title, 500), excerpt, "done");
    }

    private LinkMaterial fetchNpm(String url) throws Exception {
        String name;
        if (url.contains("registry.npmjs.org")) {
            String p = URI.create(url).getPath();
            name = p == null ? "" : (p.startsWith("/") ? p.substring(1) : p).trim();
        } else {
            String p = URI.create(url).getPath();
            var seg = p == null ? List.<String>of() : List.of(p.split("/"));
            // /package/name 或 /package/@scope/name
            int idx = seg.indexOf("package");
            name = idx < 0 ? "" : String.join("/", seg.subList(idx + 1, seg.size()));
        }
        if (name.isBlank()) {
            return fetchWeb(url, host(url));
        }
        Object root = parseJson(body("https://registry.npmjs.org/" + name, "application/json"));
        Object latest = root instanceof Map<?, ?> m && m.get("latest") instanceof Map<?, ?> lm
                ? lm : root;
        String desc = jsonString(latest, "description");
        String home = latest instanceof Map<?, ?> lm2 && lm2.get("homepage") instanceof String s ? s : null;
        String title = name + "（npm 包）" + (desc == null ? "" : "：" + desc);
        String excerpt = String.join("\n",
                desc == null ? "" : "简介：" + desc,
                home == null || home.isBlank() ? "" : "主页：" + home).trim();
        return new LinkMaterial(url, "npmjs", LinkKind.NPM, clip(title, 500), excerpt, "done");
    }

    private LinkMaterial fetchVideo(String url, String domain) throws Exception {
        Document doc = getHtml(url);
        String og = attr(doc, "meta[property=og:title]");
        String title = firstNonBlank(og, doc.title());
        String desc = attr(doc, "meta[property=og:description]");
        // 刻意不抓正文：视频站正文大多要 JS 渲染、拿不到，
        // 只把"页面标题 + 用户自己的转发文案"留给 AI，从源头杜绝编造画面内容。
        String status = title == null ? "failed" : "done";
        return new LinkMaterial(url, domain, LinkKind.VIDEO, clip(title, 500),
                desc == null ? null : clip(desc, EXCERPT_MAX), status);
    }

    private LinkMaterial fetchWeb(String url, String domain) throws Exception {
        Document doc = getHtml(url);
        String og = attr(doc, "meta[property=og:title]");
        String title = firstNonBlank(og, doc.title());
        String desc = firstNonBlank(attr(doc, "meta[property=og:description]"),
                attr(doc, "meta[name=description]"));
        String bodyText = clip(doc.body() == null ? "" : doc.body().text(), EXCERPT_MAX);
        String excerpt = desc != null && !desc.isBlank() ? desc + "\n" + bodyText : bodyText;
        return new LinkMaterial(url, domain, LinkKind.WEB, clip(title, 500), excerpt, "done");
    }

    /* ---------- HTTP ---------- */

    private Document getHtml(String url) throws Exception {
        return Jsoup.connect(url)
                .header("User-Agent", UA)
                .timeout(timeoutMs())
                .execute()
                .parse();
    }

    /** 非 HTML 接口：ignoreContentType 放行，直接取原始响应体。 */
    private String body(String url, String accept) throws Exception {
        Connection.Response resp = Jsoup.connect(url)
                .header("User-Agent", UA)
                .header("Accept", accept)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .timeout(timeoutMs())
                .execute();
        if (resp.statusCode() >= 400) {
            throw new java.io.IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    private int timeoutMs() {
        return Math.max(2, props.getFetchTimeoutSeconds()) * 1000;
    }

    /* ---------- 小工具 ---------- */

    private static Object parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(text, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String jsonString(Object node, String key) {
        if (node instanceof Map<?, ?> m && m.get(key) instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private static String attr(Document doc, String css) {
        var el = doc.selectFirst(css);
        if (el == null) {
            return null;
        }
        String v = el.attr("content");
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? null : h.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripSuffix(String s) {
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

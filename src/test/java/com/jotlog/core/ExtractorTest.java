package com.jotlog.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * URL 抽取与类型判定。
 *
 * 这层跑在同步路径上，必须可预测，所以必须有测试兜底。
 */
class ExtractorTest {

    @Test
    void 识别仓库链接() {
        var r = Extractor.extract("https://github.com/larksuite/oapi-sdk-go 看看这个");
        assertEquals("https://github.com/larksuite/oapi-sdk-go", r.url());
        assertEquals("github.com", r.domain());
        assertEquals(EntryType.REPO, r.type());
    }

    @Test
    void 识别视频链接() {
        assertEquals(EntryType.VIDEO, Extractor.extract("https://www.bilibili.com/video/BV1xx").type());
        assertEquals(EntryType.VIDEO, Extractor.extract("https://youtu.be/abc123").type());
    }

    @Test
    void 剥离中文尾随标点() {
        // 飞书里发链接手滑常带句号，不能把句号吃进 URL
        var r = Extractor.extract("https://example.com/page。");
        assertEquals("https://example.com/page", r.url());
    }

    @Test
    void 剥离www前缀() {
        assertEquals("example.com", Extractor.extract("https://www.example.com").domain());
    }

    @Test
    void 没有链接时判为随记() {
        var r = Extractor.extract("今天想到一句话：人生不是要赶到哪里去");
        assertNull(r.url());
        assertEquals(EntryType.NOTE, r.type());
    }

    @Test
    void 空白输入不炸() {
        var r = Extractor.extract("   ");
        assertNull(r.url());
        assertEquals(EntryType.NOTE, r.type());
    }

    @Test
    void 多个链接取第一个() {
        var r = Extractor.extract("参考 https://a.com 还有 https://b.com 第二个先不管");
        assertEquals("https://a.com", r.url());
    }

    @Test
    void extractAll_抽出全部链接并去重_主体优先级repo最高() {
        String text = """
                3.02 复制打开抖音 https://v.douyin.com/w_6uKY4ZYbk/ gOX
                GitHub：https://github.com/morluto/rea
                主页：https://rea.tools
                再发一次 GitHub https://github.com/morluto/rea
                """;
        var all = Extractor.extractAll(text);
        assertEquals(3, all.links().size());
        assertEquals(List.of(
                "https://v.douyin.com/w_6uKY4ZYbk/",
                "https://github.com/morluto/rea",
                "https://rea.tools"), all.links().stream().map(Extractor.Link::url).toList());
        assertEquals("github.com", all.primary().domain());
        assertEquals(EntryType.REPO, all.primary().type());
    }

    @Test
    void extractAll_无链接时空结果() {
        var all = Extractor.extractAll("人生不是赶路，是看风景");
        assertTrue(all.links().isEmpty());
        assertNull(all.primary());
    }

    @Test
    void extractAll_纯视频链接判video() {
        var all = Extractor.extractAll("看看这个 https://www.bilibili.com/video/BV1xx");
        assertEquals(EntryType.VIDEO, all.primary().type());
    }
}

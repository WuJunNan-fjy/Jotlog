package com.jotlog.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}

package com.jotlog.enrich;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TagNormalizerTest {

    @Test
    void 去井号去空白_拉丁小写_去重() {
        assertEquals(List.of("ai", "逆向工程"),
                TagNormalizer.normalize(List.of("  AI ", "#ai", " 逆向工程 ")));
    }

    @Test
    void 最多五个() {
        var r = TagNormalizer.normalize(List.of("a1", "a2", "a3", "a4", "a5", "a6"));
        assertEquals(5, r.size());
        assertEquals(List.of("a1", "a2", "a3", "a4", "a5"), r);
    }

    @Test
    void 超长截断到16字() {
        assertEquals(16, TagNormalizer.normalize(List.of("一二三四五六七八九十一二三四五六七")).get(0).length());
    }

    @Test
    void 空与null安全() {
        assertEquals(List.of(), TagNormalizer.normalize(null));
        assertEquals(List.of(), TagNormalizer.normalize(List.of("  ", "#")));
    }
}

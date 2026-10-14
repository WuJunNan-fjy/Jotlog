package com.jotlog.enrich;

import com.jotlog.repository.CategoryRepository.CategoryRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiResponseParserTest {

    private final List<CategoryRow> cats = List.of(
            new CategoryRow(1, "工具软件", "#000", 1, true, 0),
            new CategoryRow(2, "开源项目", "#000", 2, true, 0));

    @Test
    void 正常解析_带代码围栏_标签归一化() {
        String json = """
                ```json
                {"categoryId":2,"suggestedCategory":null,"title":"rea 反编译工具",
                 "summary":"开源的本地软件反编译工具。","tags":["#逆向","AI","ai"]}
                ```
                """;
        AiResult r = AiResponseParser.parse(json, cats);
        assertEquals(2L, r.categoryId());
        assertEquals("rea 反编译工具", r.title());
        assertEquals(List.of("逆向", "ai"), r.tags());
        assertNull(r.suggestedCategory());
    }

    @Test
    void 非法分类id被拒() {
        assertThrows(IllegalArgumentException.class, () ->
                AiResponseParser.parse(
                        "{\"categoryId\":99,\"title\":\"x\",\"summary\":\"y\",\"tags\":[]}", cats));
    }

    @Test
    void 无分类时允许建议名() {
        AiResult r = AiResponseParser.parse(
                "{\"categoryId\":null,\"suggestedCategory\":\"硬件折腾\",\"title\":\"x\",\"summary\":\"y\",\"tags\":[]}",
                cats);
        assertNull(r.categoryId());
        assertEquals("硬件折腾", r.suggestedCategory());
    }

    @Test
    void 选了分类时建议名被清空() {
        AiResult r = AiResponseParser.parse(
                "{\"categoryId\":1,\"suggestedCategory\":\"不该有的建议\",\"title\":\"x\",\"summary\":\"y\",\"tags\":[]}",
                cats);
        assertEquals(1L, r.categoryId());
        assertNull(r.suggestedCategory());
    }

    @Test
    void 非JSON被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> AiResponseParser.parse("我觉得这是一个好工具", cats));
    }

    @Test
    void tags不是数组被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> AiResponseParser.parse(
                        "{\"categoryId\":null,\"title\":\"x\",\"summary\":\"y\",\"tags\":\"逆向\"}", cats));
    }

    @Test
    void 标题摘要同时为空被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> AiResponseParser.parse(
                        "{\"categoryId\":null,\"title\":null,\"summary\":null,\"tags\":[]}", cats));
    }
}

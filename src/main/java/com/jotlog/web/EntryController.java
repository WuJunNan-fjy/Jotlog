package com.jotlog.web;

import com.jotlog.core.Extractor;
import com.jotlog.core.IngestService;
import com.jotlog.core.PolishQueue;
import com.jotlog.repository.EntryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 条目接口。
 *
 * 全部在 /api/** 下，受 {@link com.jotlog.auth.JwtInterceptor} 保护。
 * 能进到这里说明已经登录，方法里不再做身份校验 —— 单用户系统，
 * 所有条目都属于这一个人，不存在"越权看别人数据"这回事。
 */
@RestController
@RequestMapping("/api")
public class EntryController {

    /** 一页最多给多少条。前端无限滚动，一次不要拉太多。 */
    private static final int MAX_SIZE = 100;

    private final EntryRepository entries;
    private final IngestService ingest;
    private final PolishQueue polishQueue;

    public EntryController(EntryRepository entries, IngestService ingest, PolishQueue polishQueue) {
        this.entries = entries;
        this.ingest = ingest;
        this.polishQueue = polishQueue;
    }

    /**
     * 列表 / 搜索。
     *
     * 有 q 就走全文（查不到降级 LIKE），没 q 就是按时间倒序翻页。
     * archived=true 时翻的是归档箱。
     */
    @GetMapping("/entries")
    public EntryRepository.Page list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean starred,
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        int safePage = Math.max(page, 0);
        return entries.searchPage(q, type, starred, archived, safeSize, (long) safePage * safeSize);
    }

    @GetMapping("/entries/{id}")
    public ResponseEntity<EntryRepository.Row> get(@PathVariable long id) {
        EntryRepository.Row row = entries.findById(id);
        return row == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(row);
    }

    /**
     * 手动录入。网页顶部的输入框和手机 PWA 最终都打这个接口。
     *
     * 正文走 JSON body 而不是 query 参数：一篇长笔记很容易超过
     * 浏览器/代理对 URL 长度的限制（普遍 2KB-8KB），超了会被
     * 悄悄截断，用户还以为存全了 —— 数据不完整比报错严重得多。
     */
    @PostMapping("/entries")
    public ResponseEntity<?> create(@RequestBody CreateRequest req) {
        String text = req == null ? null : req.text();
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text 不能为空"));
        }
        var extracted = Extractor.extract(text);
        var entry = new com.jotlog.core.Entry(
                text,
                extracted.type(),
                extracted.url(),
                com.jotlog.core.Entry.ReplyTarget.none(),
                "web",
                null,
                List.of(),
                java.time.Instant.now());

        var result = ingest.persist(entry);
        return ResponseEntity.ok(Map.of(
                "id", result.id(),
                "type", extracted.type().name().toLowerCase(),
                "duplicate", result.duplicate()));
    }

    /**
     * 改星标、归档、备注。
     *
     * 这里改不了 raw_input，也改不了 title —— 前者是用户原话，后者是 AI 写的。
     * 用户能改的只有"我怎么对待这条记录"。
     */
    @PatchMapping("/entries/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody UpdateRequest req) {
        if (req == null || (req.starred() == null && req.archived() == null && req.note() == null)) {
            return ResponseEntity.badRequest().body(Map.of("error", "没有要修改的字段"));
        }
        entries.updateFlags(id, req.starred(), req.archived(), req.note());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/entries/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        entries.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 顶部那排数字。 */
    @GetMapping("/stats")
    public EntryRepository.Stats stats() {
        return entries.stats();
    }

    /** 增强队列状态。M1 接上真 worker 之后这个才有意义。 */
    @GetMapping("/queue/stats")
    public Map<String, Object> queueStats() {
        return polishQueue.stats();
    }

    public record UpdateRequest(Boolean starred, Boolean archived, String note) {
    }

    public record CreateRequest(String text) {
    }
}

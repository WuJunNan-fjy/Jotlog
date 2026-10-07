package com.jotlog.web;

import com.jotlog.core.Extractor;
import com.jotlog.core.IngestService;
import com.jotlog.core.PolishQueue;
import com.jotlog.repository.EntryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 极简 REST 接口。
 *
 * 现阶段只提供"录入"和"查看"，界面类的东西等M2 做 PWA。
 */
@RestController
@RequestMapping("/api")
public class EntryController {

    private final EntryRepository entries;
    private final IngestService ingest;
    private final PolishQueue polishQueue;

    public EntryController(EntryRepository entries, IngestService ingest, PolishQueue polishQueue) {
        this.entries = entries;
        this.ingest = ingest;
        this.polishQueue = polishQueue;
    }

    @GetMapping("/entries")
    public List<EntryRepository.Row> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit) {
        if (q == null || q.isBlank()) {
            return entries.recent(Math.min(limit, 200));
        }
        return entries.search(q, Math.min(limit, 200));
    }

    @GetMapping("/entries/{id}")
    public ResponseEntity<EntryRepository.Row> get(@PathVariable long id) {
        EntryRepository.Row row = entries.findById(id);
        return row == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(row);
    }

    /**
     * 手动录入。手机 PWA 和电脑快捷键最终都打这个接口。
     */
    @PostMapping("/entries")
    public ResponseEntity<?> create(@RequestParam String text) {
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text 不能为空"));
        }
        var extracted = Extractor.extract(text);
        var entry = new com.jotlog.core.Entry(
                text,
                extracted.type(),
                extracted.url(),
                com.jotlog.core.Entry.ReplyTarget.none(),
                "pwa",
                null,
                List.of(),
                java.time.Instant.now());

        var result = ingest.persist(entry);
        return ResponseEntity.ok(Map.of(
                "id", result.id(),
                "type", extracted.type().name().toLowerCase(),
                "duplicate", result.duplicate()));
    }

    @GetMapping("/queue/stats")
    public Map<String, Object> queueStats() {
        return polishQueue.stats();
    }
}

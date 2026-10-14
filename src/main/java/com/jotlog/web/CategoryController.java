package com.jotlog.web;

import com.jotlog.repository.CategoryRepository;
import com.jotlog.repository.CategoryRepository.CategoryRow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 分类接口。分类是单选的桶，预设为主，AI 只能建议不能自建。 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryRepository categories;

    public CategoryController(CategoryRepository categories) {
        this.categories = categories;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("categories", categories.list(),
                "uncategorized", categories.uncategorizedCount());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody NameRequest req) {
        String name = req == null ? null : req.name() == null ? null : req.name().trim();
        if (name == null || name.isBlank() || name.length() > 16) {
            return ResponseEntity.badRequest().body(Map.of("error", "分类名需 1-16 个字"));
        }
        if (categories.findByName(name) != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "同名分类已存在"));
        }
        long id = categories.insert(name, req.color());
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** 冷启动引导确认时批量建；已存在的名字跳过。 */
    @PostMapping("/bulk")
    public Map<String, Object> bulk(@RequestBody BulkRequest req) {
        List<String> names = req == null || req.names() == null ? List.of() : req.names();
        int created = 0;
        for (String n : names) {
            String name = n == null ? "" : n.trim();
            if (name.isEmpty() || name.length() > 16 || categories.findByName(name) != null) {
                continue;
            }
            categories.insert(name, null);
            created++;
        }
        return Map.of("created", created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> rename(@PathVariable long id, @RequestBody NameRequest req) {
        CategoryRow c = categories.findById(id);
        if (c == null) {
            return ResponseEntity.notFound().build();
        }
        String name = req.name() == null ? c.name() : req.name().trim();
        if (name.isBlank() || name.length() > 16) {
            return ResponseEntity.badRequest().body(Map.of("error", "分类名需 1-16 个字"));
        }
        categories.rename(id, name, req.color());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    public record NameRequest(String name, String color) {
    }

    public record BulkRequest(List<String> names) {
    }
}

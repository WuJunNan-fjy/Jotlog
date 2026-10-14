package com.jotlog.web;

import com.jotlog.config.AiProperties;
import com.jotlog.enrich.AiOrganizer;
import com.jotlog.enrich.BatchPolishService;
import com.jotlog.repository.CategoryRepository;
import com.jotlog.repository.CategoryRepository.CategoryRow;
import com.jotlog.repository.EntryRepository;
import com.jotlog.repository.UserSettingsRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 增强管理接口：服务状态、笔记开关、冷启动分类建议、批量补跑。
 *
 * Key 不回显：状态只给 configured(true/false)，真值只在配置文件/环境变量里。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    /** AI 不可用或建议为空时的兜底分类。 */
    private static final List<String> SEED_CATEGORIES = List.of(
            "工具软件", "开源项目", "教程文章", "视频影像", "灵感随记", "资讯动态", "资源素材");

    private final AiProperties props;
    private final ObjectProvider<AiOrganizer> ai;
    private final ObjectProvider<BatchPolishService> batch;
    private final UserSettingsRepository settings;
    private final EntryRepository entries;
    private final CategoryRepository categories;

    public AiController(AiProperties props, ObjectProvider<AiOrganizer> ai,
                        ObjectProvider<BatchPolishService> batch, UserSettingsRepository settings,
                        EntryRepository entries, CategoryRepository categories) {
        this.props = props;
        this.ai = ai;
        this.batch = batch;
        this.settings = settings;
        this.entries = entries;
        this.categories = categories;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        boolean ready = props.isReady() && ai.getIfAvailable() != null;
        var batchSvc = batch.getIfAvailable();
        return Map.of(
                "enabled", props.isEnabled(),
                "configured", props.isReady(),
                "ready", ready,
                "model", props.getModel(),
                "polishNotes", settings.getBool("ai_polish_notes", props.isPolishNotes()),
                "batch", batchSvc == null
                        ? Map.of("active", false)
                        : batchSvc.progress());
    }

    @PutMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody SettingsRequest req) {
        if (req != null && req.polishNotes() != null) {
            settings.put("ai_polish_notes", String.valueOf(req.polishNotes()));
        }
        return Map.of("ok", true,
                "polishNotes", settings.getBool("ai_polish_notes", props.isPolishNotes()));
    }

    /**
     * 冷启动建议：AI 可用就基于现有内容抽样生成；不可用或返回空直接给内置种子。
     * 只返回预览，不落库；用户勾选确认后走 /api/categories/bulk。
     */
    @PostMapping("/bootstrap-categories")
    public Map<String, Object> bootstrap() {
        List<String> suggested;
        var organizer = ai.getIfAvailable();
        if (organizer != null) {
            suggested = organizer.suggestCategories(entries.sampleRawInput(40));
            if (suggested.isEmpty()) {
                suggested = SEED_CATEGORIES;
            }
        } else {
            suggested = SEED_CATEGORIES;
        }
        List<String> existing = categories.list().stream().map(CategoryRow::name).toList();
        return Map.of("suggestions", suggested, "existing", existing);
    }

    @PostMapping("/batch-repolish")
    public ResponseEntity<?> batch(@RequestBody BatchRequest req) {
        var svc = batch.getIfAvailable();
        if (svc == null) {
            return ResponseEntity.status(503).body(Map.of("error", "AI 未配置或未启用"));
        }
        if (req != null && "stop".equals(req.action())) {
            svc.stop();
        } else {
            svc.start();
        }
        return ResponseEntity.ok(svc.progress());
    }

    public record SettingsRequest(Boolean polishNotes) {
    }

    public record BatchRequest(String action) {
    }
}

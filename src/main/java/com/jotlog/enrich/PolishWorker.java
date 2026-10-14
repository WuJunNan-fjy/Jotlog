package com.jotlog.enrich;

import com.jotlog.config.AiProperties;
import com.jotlog.core.PolishQueue;
import com.jotlog.repository.CategoryRepository;
import com.jotlog.repository.EntryRepository;
import com.jotlog.repository.TagRepository;
import com.jotlog.repository.UserSettingsRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 增强 worker。
 *
 * AI 未配置时整个 Bean 不创建（@ConditionalOnBean AiOrganizer），
 * 队列仍可正常入队，条目安静停在 pending。
 *
 * 单线程串行：个人量级足够，天然规避模型限流。
 * 失败延迟 30 秒重试一次（共 2 次尝试），仍失败置 failed 等用户手动重跑。
 */
@Service
@ConditionalOnBean(AiOrganizer.class)
public class PolishWorker {

    private static final Logger log = LoggerFactory.getLogger(PolishWorker.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_SECONDS = 30;

    private final PolishQueue queue;
    private final AiOrganizer ai;
    private final LinkFetchService fetcher;
    private final EnrichmentService enrichment;
    private final EntryRepository entries;
    private final CategoryRepository categories;
    private final TagRepository tags;
    private final UserSettingsRepository settings;
    private final AiProperties props;

    private final ScheduledExecutorService scheduler;
    private volatile boolean running = true;

    public PolishWorker(PolishQueue queue, AiOrganizer ai, LinkFetchService fetcher,
                        EnrichmentService enrichment, EntryRepository entries,
                        CategoryRepository categories, TagRepository tags,
                        UserSettingsRepository settings, AiProperties props) {
        this.queue = queue;
        this.ai = ai;
        this.fetcher = fetcher;
        this.enrichment = enrichment;
        this.entries = entries;
        this.categories = categories;
        this.tags = tags;
        this.settings = settings;
        this.props = props;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "jotlog-polish");
            t.setDaemon(true);
            return t;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
    }

    @PostConstruct
    void start() {
        scheduler.submit(this::loop);
    }

    @PreDestroy
    void stop() {
        running = false;
        scheduler.shutdownNow();
    }

    private void loop() {
        while (running) {
            try {
                PolishQueue.Task task = queue.take();
                process(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 单条异常绝不能杀掉消费线程
                log.error("增强循环异常", e);
            }
        }
    }

    /** 包级可见：便于将来加单测直接驱动一条任务。 */
    void process(PolishQueue.Task task) {
        long id = task.entryId();
        var row = entries.findById(id);
        if (row == null) {
            return;
        }

        boolean polishNotes = settings.getBool("ai_polish_notes", props.isPolishNotes());
        if ("note".equals(row.entryType()) && !polishNotes) {
            enrichment.applyNoteSkipped(id);
            return;
        }

        long started = System.currentTimeMillis();
        try {
            entries.markAiProcessing(id);
            var materials = fetcher.fetchAll(id, row.rawInput());
            var cats = categories.listAll();
            var popular = tags.popular(15);
            AiResult result = ai.organize(row.rawInput(), materials, cats, popular);
            enrichment.applyDone(id, result, ai.modelName(), (int) (System.currentTimeMillis() - started));
            log.info("AI 增强完成 id={} 耗时={}ms 分类={} 标签={}",
                    id, System.currentTimeMillis() - started, result.categoryId(), result.tags());
        } catch (Exception e) {
            log.warn("AI 增强失败 id={} attempt={} reason={}",
                    id, task.attempts() + 1, e.getMessage());
            if (task.attempts() + 1 < MAX_ATTEMPTS) {
                scheduler.schedule(
                        () -> queue.enqueueEntry(id, row.url(), row.rawInput(), task.attempts() + 1),
                        RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
            } else {
                entries.markAiFailed(id, e.getMessage() == null ? "unknown" : e.getMessage());
            }
        }
    }
}

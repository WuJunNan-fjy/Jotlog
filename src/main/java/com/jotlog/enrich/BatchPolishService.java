package com.jotlog.enrich;

import com.jotlog.config.AiProperties;
import com.jotlog.core.PolishQueue;
import com.jotlog.repository.EntryRepository;
import com.jotlog.repository.UserSettingsRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 存量条目批量补跑。
 *
 * 扫描 pending/failed 按时间顺序入队，每条间隔约 1.2 秒（温和限流）；
 * 运行状态存内存——个人单实例够用，不做分布式进度表。
 * 真正的处理进度由各条目 ai_status 体现，这里只管入队节奏。
 */
@Service
@ConditionalOnBean(AiOrganizer.class)
public class BatchPolishService {

    private static final long INTERVAL_MS = 1200;
    private static final int BATCH_LIMIT = 500;

    private final EntryRepository entries;
    private final PolishQueue queue;
    private final UserSettingsRepository settings;
    private final AiProperties props;
    private final ScheduledExecutorService exec;

    private volatile boolean active = false;
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger enqueued = new AtomicInteger();

    public BatchPolishService(EntryRepository entries, PolishQueue queue,
                              UserSettingsRepository settings, AiProperties props) {
        this.entries = entries;
        this.queue = queue;
        this.settings = settings;
        this.props = props;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jotlog-batch-polish");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        if (active) {
            return;
        }
        boolean includeNotes = settings.getBool("ai_polish_notes", props.isPolishNotes());
        List<Long> ids = entries.pendingForPolish(BATCH_LIMIT, includeNotes);
        total.set(ids.size());
        enqueued.set(0);
        active = true;
        if (ids.isEmpty()) {
            active = false;
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            exec.schedule(() -> {
                if (!active) {
                    return;
                }
                var row = entries.findById(id);
                if (row != null) {
                    queue.enqueueEntry(id, row.url(), row.rawInput(), 0);
                }
                enqueued.incrementAndGet();
            }, INTERVAL_MS * (i + 1), TimeUnit.MILLISECONDS);
        }
        // 入队节奏结束后留 30 秒余量再收尾标志位
        exec.schedule(() -> active = false, INTERVAL_MS * (ids.size() + 25L), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        active = false;
    }

    public Map<String, Object> progress() {
        return Map.of("active", active, "total", total.get(), "enqueued", enqueued.get());
    }
}

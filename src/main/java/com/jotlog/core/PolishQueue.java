package com.jotlog.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 增强队列。
 *
 * 入库后立即入队，PolishWorker 单线程串行消费（抓取素材 → AI 分类总结 → 回写）。
 * 队列满了不阻塞录入：原文已经落库，网页端随时可以手动补跑。
 */
@Service
public class PolishQueue {

    private static final Logger log = LoggerFactory.getLogger(PolishQueue.class);

    private final LinkedBlockingQueue<Task> queue = new LinkedBlockingQueue<>(1000);

    /** 需要补发卡片时用：id → 回复目标。 */
    private final Map<Long, Entry.ReplyTarget> targets = new ConcurrentHashMap<>();

    public void enqueue(long entryId, Entry entry) {
        boolean ok = queue.offer(new Task(entryId, entry.source(), entry.url(),
                entry.replyTarget(), entry.rawInput(), 0));
        if (ok) {
            targets.put(entryId, entry.replyTarget());
            log.debug("已入增强队列 id={} 剩余={}", entryId, queue.size());
        } else {
            // 队列满不是致命错误。原文已经入库，网页端可以手动补跑。
            log.warn("增强队列已满，丢弃 id={}。原文已入库，可稍后手动补跑。", entryId);
        }
    }

    /** worker 主循环用，无任务时阻塞等待。 */
    public Task take() throws InterruptedException {
        return queue.take();
    }

    /**
     * 手动重跑 / 批量补跑：为已入库条目构造任务。
     * 处理时 worker 会以数据库里的最新原文为准重新 findById。
     */
    public void enqueueEntry(long entryId, String url, String rawInput, int attempts) {
        boolean ok = queue.offer(new Task(entryId, "manual", url,
                Entry.ReplyTarget.none(), rawInput, attempts));
        if (!ok) {
            log.warn("增强队列已满，丢弃 id={}", entryId);
        }
    }

    /** 队列观测。 */
    public Map<String, Object> stats() {
        return Map.of(
                "queued", queue.size(),
                "tracked", targets.size());
    }

    /**
     * attempts：已尝试次数。首次入队为 0，失败重试由 worker 递增加 1，
     * 达到上限（2 次）后置 failed，不再入队。
     */
    public record Task(
            long entryId,
            String source,
            String url,
            Entry.ReplyTarget replyTarget,
            String rawInput,
            int attempts
    ) {
    }
}

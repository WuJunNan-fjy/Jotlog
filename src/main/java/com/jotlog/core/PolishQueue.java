package com.jotlog.core;

import com.jotlog.channel.ChannelAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 增强队列。
 *
 * 现阶段只做一件事：把待润色的条目攒起来，交给未来的 PolishWorker。
 * M0 阶段 worker 是空的，所以队列消费完就丢弃——这是有意的，
 * 目的是先验证飞书链路，而不是一上来就调AI API。
 */
@Service
public class PolishQueue {

    private static final Logger log = LoggerFactory.getLogger(PolishQueue.class);

    private final LinkedBlockingQueue<Task> queue = new LinkedBlockingQueue<>(1000);

    /** 需要补发卡片时用：id → 回复目标。 */
    private final Map<Long, Entry.ReplyTarget> targets = new ConcurrentHashMap<>();

    public void enqueue(long entryId, Entry entry) {
        boolean ok = queue.offer(new Task(entryId, entry.source(), entry.url(),
                entry.replyTarget(), entry.rawInput()));
        if (ok) {
            targets.put(entryId, entry.replyTarget());
            log.debug("已入增强队列 id={} 剩余={}", entryId, queue.size());
        } else {
            // 队列满不是致命错误。原文已经入库，网页端可以手动补跑。
            log.warn("增强队列已满，丢弃 id={}。原文已入库，可稍后手动补跑。", entryId);
        }
    }

    /** M0 阶段：只观察，不消费。M1 接上真实 worker。 */
    public Map<String, Object> stats() {
        return Map.of(
                "queued", queue.size(),
                "tracked", targets.size());
    }

    public record Task(
            long entryId,
            String source,
            String url,
            Entry.ReplyTarget replyTarget,
            String rawInput
    ) {
    }
}

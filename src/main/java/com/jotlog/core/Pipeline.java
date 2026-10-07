package com.jotlog.core;

import com.jotlog.channel.ChannelAdapter;
import com.jotlog.channel.feishu.FeishuChannel;
import com.jotlog.config.FeishuProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一管线。
 *
 * 顺序是铁律，不可调换：
 *   入库 → 回执 → 异步增强
 *
 * 任何慢操作（抓正文、AI、GitHub API）放进异步队列，
 * 绝不允许出现在同步路径上，否则撞上飞书 3 秒超时会被重推。
 */
@Service
public class Pipeline {

    private static final Logger log = LoggerFactory.getLogger(Pipeline.class);

    private final List<ChannelAdapter> channels;
    private final IngestService ingest;
    private final FeishuProperties props;
    private final PolishQueue polishQueue;

    private final ExecutorService workers =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "jotlog-worker");
                t.setDaemon(true);
                return t;
            });

    public Pipeline(List<ChannelAdapter> channels,
                    IngestService ingest,
                    FeishuProperties props,
                    PolishQueue polishQueue) {
        this.channels = channels;
        this.ingest = ingest;
        this.props = props;
        this.polishQueue = polishQueue;
    }

    @PostConstruct
    public void start() {
        for (ChannelAdapter channel : channels) {
            try {
                channel.start(this::handleAsync);
                log.info("通道已启动: {}", channel.name());
            } catch (Exception e) {
                log.error("通道启动失败: {}", channel.name(), e);
            }
        }
    }

    /**
     * 通道回调入口。
     *
     * 飞书的 SDK 在这个线程里等待我们返回，所以真正的处理
     * 必须立刻丢给工作线程，这个方法一秒都不占。
     */
    private void handleAsync(Entry entry) {
        workers.submit(() -> handle(entry));
    }

    void handle(Entry entry) {
        IngestService.IngestResult result;
        try {
            result = ingest.persist(entry);
        } catch (Exception e) {
            log.error("入库失败，source={} msgId={}", entry.source(), entry.sourceMsgId(), e);
            return;
        }

        if (result.duplicate()) {
            log.debug("重复消息，跳过回执。id={}", result.id());
            return;
        }

        // 第二步：回执。必须在增强之前，因为用户等着确认。
        if (entry.replyTarget().valid()) {
            ChannelAdapter adapter = adapterFor(entry.source());
            if (adapter != null) {
                String receipt = props.getReceiptText() == null || props.getReceiptText().isBlank()
                        ? "已记下，稍后补全"
                        : props.getReceiptText();
                try {
                    adapter.reply(entry.replyTarget(), receipt);
                } catch (Exception e) {
                    log.warn("回执失败，不影响已入库的记录", e);
                }
            }
        }

        // 第三步：异步增强。丢队列，立即返回。
        polishQueue.enqueue(result.id(), entry);
    }

    private ChannelAdapter adapterFor(String name) {
        for (ChannelAdapter c : channels) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }
}

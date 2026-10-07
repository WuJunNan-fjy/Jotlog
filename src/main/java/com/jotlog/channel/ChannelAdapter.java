package com.jotlog.channel;

import com.jotlog.core.Entry;
import com.jotlog.core.Entry.ReplyTarget;

/**
 * 通道适配器。
 *
 * 一个通道 = 一个投递入口。飞书、钉钉、PWA 各实现一份，
 * 全部转换成统一 Entry 交给管线。换通道不动管线。
 */
public interface ChannelAdapter {

    /** 通道名，落库到 entries.source。 */
    String name();

    /** 启动。返回后通道已在后台接收。 */
    void start(Handler handler) throws Exception;

    /**
     * 回复一条回执。
     *
     * 必须在 3 秒内返回，否则飞书判定超时并重推。
     * 实现方不得在这里做任何慢操作。
     */
    void reply(ReplyTarget target, String text) throws Exception;

    /**
     * 主动推送。用于 AI 润色完成后补发卡片这类场景。
     * 与 reply 不同，允许慢，允许失败重试。
     */
    void push(ReplyTarget target, String text) throws Exception;

    /** 通道收到原始输入后的回调。 */
    interface Handler {
        void onEntry(Entry entry);
    }
}

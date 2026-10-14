package com.jotlog.repository;

import java.util.List;

/** 一条记录内多个链接的登记与抓取结果落库。 */
public interface EntryLinkRepository {

    record LinkRow(long id, String url, String domain, String kind,
                   String fetchedTitle, String fetchStatus) {
    }

    /** 不存在则插（entry+url_hash 唯一），已存在不覆盖抓取结果。 */
    void registerIfAbsent(long entryId, String url, String urlHash, String domain, String kind);

    void saveFetch(long entryId, String urlHash, String title, String excerpt, String status);

    List<LinkRow> listByEntry(long entryId);

    /** 是否已有成功抓取的链接（判断重跑 AI 时素材是否可复用）。 */
    boolean hasDoneFetch(long entryId);
}

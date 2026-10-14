package com.jotlog.repository;

import java.util.List;
import java.util.Map;

/**
 * 标签持久化。标签是"多选的关键词"，归一化（去 # / 拉丁小写 / 截断）
 * 由上层 TagNormalizer 完成，仓库层只保证名字 → id 的内容寻址式复用。
 */
public interface TagRepository {

    /** 名字 → id，不存在就建。 */
    long findOrCreate(String name);

    /** 全量替换某条目的标签。tagIds 为空 = 清空。 */
    void replaceForEntry(long entryId, List<Long> tagIds);

    /** 一批条目各自的标签名，key=entryId。列表页一次性加载，避免 N+1。 */
    Map<Long, List<String>> namesForEntries(List<Long> entryIds);

    List<String> namesForEntry(long entryId);

    /** 使用频次最高的若干标签，喂给 AI 做复用提示。 */
    List<String> popular(int limit);

    /** 详情页输入联想；q 为空时返回高频标签。 */
    List<String> suggest(String q, int limit);
}

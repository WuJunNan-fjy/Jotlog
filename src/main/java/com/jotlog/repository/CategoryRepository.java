package com.jotlog.repository;

import java.util.List;

/**
 * 分类持久化。分类是"单选的桶"，预设为主：
 * AI 不允许直接建分类，只能把建议名写到 entries.suggested_category。
 */
public interface CategoryRepository {

    record CategoryRow(long id, String name, String color, int sortOrder, boolean system, long count) {
    }

    /** 分类清单，count=该分类下未归档条目数；按 sort_order 排序。 */
    List<CategoryRow> list();

    /** 与 list 相同；单独命名是给"需要全部分类（含 0 条）"的语义留出口子，当前实现一致。 */
    List<CategoryRow> listAll();

    CategoryRow findById(long id);

    CategoryRow findByName(String name);

    /** 新建分类，sort_order 接在现有最大值之后。返回新 id。 */
    long insert(String name, String color);

    void rename(long id, String name, String color);

    /** 未归档且 category_id 为空的条目数（侧边栏"待分类"角标）。 */
    long uncategorizedCount();
}

package com.jotlog.enrich;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 标签归一化：去前导 #、压缩空白、拉丁字母转小写（让 AI/ai 收敛成一个）、
 * 超长截断、去重、上限 5 个。中文名保持原样。
 */
public final class TagNormalizer {

    public static final int MAX_TAGS = 5;
    public static final int MAX_LEN = 16;

    private TagNormalizer() {
    }

    public static List<String> normalize(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return List.of();
        }
        for (String r : raw) {
            if (r == null) {
                continue;
            }
            String t = r.trim().replaceAll("^#+\\s*", "").replaceAll("\\s+", " ");
            if (t.isEmpty()) {
                continue;
            }
            t = latinLower(t);
            if (t.length() > MAX_LEN) {
                t = t.substring(0, MAX_LEN);
            }
            out.add(t);
            if (out.size() >= MAX_TAGS) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    /** 只对纯 ASCII 标签做小写化；含中文的整体不动，避免误伤。 */
    private static String latinLower(String s) {
        return s.chars().allMatch(c -> c < 0x80) ? s.toLowerCase(Locale.ROOT) : s;
    }
}

package com.polaris.app.util;

import java.util.HashSet;
import java.util.Set;

/** 简单的字符串拼接工具。 */
public final class TextUtil {

    private TextUtil() {}

    public static String join(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }

    public static Set<String> split(String joined) {
        Set<String> out = new HashSet<>();
        if (joined == null || joined.isEmpty()) return out;
        for (String s : joined.split(",")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}

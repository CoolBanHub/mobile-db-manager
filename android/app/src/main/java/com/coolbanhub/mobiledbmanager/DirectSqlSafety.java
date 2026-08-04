package com.coolbanhub.mobiledbmanager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class DirectSqlSafety {
    private static final Set<String> READ_PREFIXES = new HashSet<>(Arrays.asList(
            "select", "with", "show", "describe", "desc", "explain", "pragma", "values"));
    private static final Set<String> WRITE_KEYWORDS = new HashSet<>(Arrays.asList(
            "insert", "update", "delete", "merge", "replace", "upsert", "create", "alter",
            "drop", "truncate", "grant", "revoke", "call", "execute", "exec", "copy",
            "vacuum", "reindex", "attach", "detach", "load"));

    private DirectSqlSafety() {}

    static boolean isReadOnlySql(String sql) {
        String normalized = sql
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)--[^\\n]*$", " ")
                .toLowerCase(Locale.ROOT)
                .trim();
        String withoutTrailingDelimiter = normalized.replaceFirst(";\\s*$", "");
        if (withoutTrailingDelimiter.contains(";")) return false;
        int end = normalized.indexOf(' ');
        int newline = normalized.indexOf('\n');
        if (end < 0 || (newline >= 0 && newline < end)) end = newline;
        String first = end < 0 ? normalized : normalized.substring(0, end);
        if (!READ_PREFIXES.contains(first)) return false;
        for (String keyword : WRITE_KEYWORDS) {
            if (normalized.matches("(?s).*\\b" + keyword + "\\b.*")) return false;
        }
        return true;
    }
}

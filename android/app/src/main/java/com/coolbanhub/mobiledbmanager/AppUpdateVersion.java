package com.coolbanhub.mobiledbmanager;

final class AppUpdateVersion {
    private static final long MAJOR_WEIGHT = 1_000_000L;
    private static final long MINOR_WEIGHT = 1_000L;

    private AppUpdateVersion() {}

    static String versionFromTag(String tag) {
        if (tag == null) return "";
        String value = tag.trim();
        if (value.startsWith("release/v")) return value.substring("release/v".length());
        if (value.startsWith("v")) return value.substring(1);
        return value;
    }

    static boolean isReleaseTag(String tag) {
        return tag != null && tag.trim().startsWith("release/v");
    }

    static int compare(String left, String right) {
        int[] a = parse(versionFromTag(left));
        int[] b = parse(versionFromTag(right));
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    static long versionCode(String version) {
        int[] parts = parse(versionFromTag(version));
        if (parts.length == 0) return 0;
        long major = parts.length > 0 ? parts[0] : 0;
        long minor = parts.length > 1 ? parts[1] : 0;
        long patch = parts.length > 2 ? parts[2] : 0;
        return major * MAJOR_WEIGHT + minor * MINOR_WEIGHT + patch + 1;
    }

    private static int[] parse(String version) {
        if (version == null) return new int[0];
        String core = version.trim();
        int suffix = core.indexOf('-');
        if (suffix >= 0) core = core.substring(0, suffix);
        if (core.isEmpty()) return new int[0];

        String[] tokens = core.split("\\.");
        int[] parts = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].replaceAll("[^0-9].*$", "");
            if (token.isEmpty()) token = "0";
            try {
                parts[i] = Integer.parseInt(token);
            } catch (NumberFormatException error) {
                parts[i] = 0;
            }
        }
        return parts;
    }
}

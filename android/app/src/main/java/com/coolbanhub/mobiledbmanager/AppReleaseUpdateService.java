package com.coolbanhub.mobiledbmanager;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import com.getcapacitor.JSObject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class AppReleaseUpdateService {
    private static final String OWNER = "CoolBanHub";
    private static final String REPO = "mobile-db-manager";
    private static final String RELEASES_API =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases?per_page=30";
    private static final int TIMEOUT_MILLIS = 12_000;
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";

    private AppReleaseUpdateService() {}

    static JSObject check(Context context) throws Exception {
        PackageInfo packageInfo = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
        String currentVersion = packageInfo.versionName == null ? "" : packageInfo.versionName;
        long currentVersionCode = versionCode(packageInfo);
        ReleaseAsset latest = latestApkRelease();
        long latestVersionCode = AppUpdateVersion.versionCode(latest.version);
        boolean hasUpdate = latestVersionCode > currentVersionCode
                || (latestVersionCode == currentVersionCode
                && AppUpdateVersion.compare(currentVersion, latest.version) < 0);

        JSObject result = new JSObject();
        result.put("currentVersion", currentVersion);
        result.put("currentVersionCode", currentVersionCode);
        result.put("latestTag", latest.tagName);
        result.put("latestVersion", latest.version);
        result.put("latestVersionCode", latestVersionCode);
        result.put("releaseUrl", latest.releaseUrl);
        result.put("apkDownloadUrl", latest.downloadUrl);
        result.put("apkName", latest.name);
        result.put("apkSize", latest.size);
        result.put("publishedAt", latest.publishedAt);
        result.put("hasUpdate", hasUpdate);
        return result;
    }

    static ReleaseAsset latestApkRelease() throws Exception {
        JSONArray releases = new JSONArray(get(RELEASES_API));
        ReleaseAsset latest = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) continue;

            String tagName = release.optString("tag_name", "");
            if (!AppUpdateVersion.isReleaseTag(tagName)) continue;

            ReleaseAsset asset = apkAsset(release);
            if (asset == null) continue;

            String version = AppUpdateVersion.versionFromTag(tagName);
            asset.tagName = tagName;
            asset.version = version;
            asset.releaseUrl = release.optString("html_url", "");
            asset.publishedAt = release.optString("published_at", "");
            if (latest == null || AppUpdateVersion.compare(version, latest.version) > 0) {
                latest = asset;
            }
        }
        if (latest == null) throw new IllegalStateException("GitHub Release 中没有可用 APK");
        return latest;
    }

    private static ReleaseAsset apkAsset(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;

            String name = asset.optString("name", "");
            String contentType = asset.optString("content_type", "");
            if (!name.toLowerCase().endsWith(".apk") && !APK_MIME_TYPE.equals(contentType)) continue;

            ReleaseAsset result = new ReleaseAsset();
            result.name = name;
            result.downloadUrl = asset.optString("browser_download_url", "");
            result.size = asset.optLong("size", 0);
            if (!result.downloadUrl.isEmpty()) return result;
        }
        return null;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Mobile-DB-Manager-Android");
        int status = connection.getResponseCode();
        InputStream body = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String text = readAll(body);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("GitHub Release 查询失败: HTTP " + status);
        }
        return text;
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
        }
        return builder.toString();
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return packageInfo.getLongVersionCode();
        return packageInfo.versionCode;
    }

    static final class ReleaseAsset {
        String tagName;
        String version;
        String releaseUrl;
        String publishedAt;
        String name;
        String downloadUrl;
        long size;
    }
}

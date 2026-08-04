package com.coolbanhub.mobiledbmanager;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "AppUpdate")
public class AppUpdatePlugin extends Plugin {
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @PluginMethod
    public void check(PluginCall call) {
        executor.execute(() -> {
            try {
                call.resolve(wrap(AppReleaseUpdateService.check(getContext())));
            } catch (Throwable error) {
                call.reject("检查更新失败: " + error.getMessage(), asException(error));
            }
        });
    }

    @PluginMethod
    public void downloadLatest(PluginCall call) {
        executor.execute(() -> {
            try {
                String url = call.getString("url", "");
                String fileName = sanitizeFileName(call.getString("fileName", ""));
                if (url.isEmpty() || fileName.isEmpty()) {
                    AppReleaseUpdateService.ReleaseAsset latest =
                            AppReleaseUpdateService.latestApkRelease();
                    url = latest.downloadUrl;
                    fileName = sanitizeFileName(latest.name);
                }
                if (url.isEmpty() || fileName.isEmpty()) {
                    throw new IllegalArgumentException("最新 APK 下载地址不可用");
                }
                call.resolve(wrap(enqueueDownload(url, fileName)));
            } catch (Throwable error) {
                call.reject("下载更新失败: " + error.getMessage(), asException(error));
            }
        });
    }

    private JSObject enqueueDownload(String url, String fileName) {
        DownloadManager downloads =
                (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloads == null) return openExternalDownload(url, fileName);

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle(fileName);
        request.setDescription("Mobile DB Manager");
        request.setMimeType(APK_MIME_TYPE);
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        request.addRequestHeader("User-Agent", "Mobile-DB-Manager-Android");

        long downloadId = downloads.enqueue(request);
        JSObject result = new JSObject();
        result.put("downloadId", downloadId);
        result.put("fileName", fileName);
        result.put("openedExternal", false);
        return result;
    }

    private JSObject openExternalDownload(String url, String fileName) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);

        JSObject result = new JSObject();
        result.put("downloadId", -1);
        result.put("fileName", fileName);
        result.put("openedExternal", true);
        return result;
    }

    private static JSObject wrap(JSObject value) {
        JSObject result = new JSObject();
        result.put("value", value);
        return result;
    }

    private static Exception asException(Throwable error) {
        return error instanceof Exception ? (Exception) error : new RuntimeException(error);
    }

    private static String sanitizeFileName(String fileName) {
        String sanitized = fileName == null ? "" : fileName.trim();
        if (sanitized.isEmpty()) return "";
        return sanitized.replaceAll("[\\\\/:*?\"<>|]+", "-");
    }
}

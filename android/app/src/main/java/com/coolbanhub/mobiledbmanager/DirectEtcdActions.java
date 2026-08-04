package com.coolbanhub.mobiledbmanager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;

final class DirectEtcdActions {
    private DirectEtcdActions() {}

    static Object execute(JSONObject config, String action, PluginCall call) throws Exception {
        switch (action) {
            case "overview": {
                JSONObject status = DirectEtcdConnection.status(config);
                JSONObject count = DirectEtcdConnection.count(config);
                return new JSObject()
                        .put("version", status.optString("version", "—"))
                        .put("dbSize", status.optString("dbSize", status.optString("db_size", "0")))
                        .put("keyCount", count.optString("count", "0"));
            }
            case "list":
                return range(config, call.getString("prefix", ""), call.getInt("limit", 200));
            case "detail":
                return detail(config, DirectJson.required(call.getString("key"), "key"));
            case "put":
                return DirectEtcdConnection.put(
                        config,
                        DirectJson.required(call.getString("key"), "key"),
                        call.getString("value", ""),
                        call.getString("lease", "0"));
            case "delete":
                return DirectEtcdConnection.delete(
                        config,
                        DirectJson.required(call.getString("key"), "key"));
            default:
                throw new IllegalArgumentException("不支持的 etcd 操作：" + action);
        }
    }

    static boolean isWriteAction(String action) {
        return new HashSet<>(Arrays.asList("put", "delete")).contains(action);
    }

    static void assertWriteAllowed(JSONObject config, PluginCall call) {
        if (config.optBoolean("readOnly", false)) {
            throw new IllegalArgumentException("此连接已设为只读，不能修改 etcd 数据");
        }
        if (!call.getBoolean("confirmedWrite", false)) {
            throw new IllegalArgumentException("etcd 写入必须由数据浏览器明确确认");
        }
        if (config.optBoolean("isProduction", false)
                && !config.optString("name").equals(call.getString("productionConfirmation", ""))) {
            throw new IllegalArgumentException("生产连接写入前必须输入完整连接名称");
        }
    }

    private static JSObject range(JSONObject config, String prefix, int limit) throws Exception {
        JSONObject response = DirectEtcdConnection.range(config, prefix, limit);
        JSArray entries = new JSArray();
        org.json.JSONArray values = response.optJSONArray("kvs");
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                JSONObject item = values.getJSONObject(index);
                entries.put(entry(item));
            }
        }
        return new JSObject()
                .put("entries", entries)
                .put("count", response.optString("count", String.valueOf(entries.length())))
                .put("more", response.optBoolean("more", false));
    }

    private static JSObject detail(JSONObject config, String key) throws Exception {
        JSONObject response = DirectEtcdConnection.get(config, key);
        org.json.JSONArray values = response.optJSONArray("kvs");
        if (values == null || values.length() == 0) {
            throw new IllegalArgumentException("etcd 键不存在或已被删除");
        }
        return entry(values.getJSONObject(0));
    }

    private static JSObject entry(JSONObject item) throws Exception {
        return new JSObject()
                .put("key", DirectEtcdConnection.decode(item.optString("key")))
                .put("value", DirectEtcdConnection.decode(item.optString("value")))
                .put("createRevision", item.optString("create_revision", item.optString("createRevision", "0")))
                .put("modRevision", item.optString("mod_revision", item.optString("modRevision", "0")))
                .put("version", item.optString("version", "0"))
                .put("lease", item.optString("lease", "0"));
    }
}

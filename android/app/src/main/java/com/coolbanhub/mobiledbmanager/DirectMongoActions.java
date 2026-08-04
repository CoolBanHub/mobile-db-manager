package com.coolbanhub.mobiledbmanager;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;

final class DirectMongoActions {
    private DirectMongoActions() {}

    static Object execute(JSONObject config, String action, PluginCall call) throws Exception {
        switch (action) {
            case "databases":
                return DirectJson.stringArray(DirectMongoConnection.databases(config));
            case "collections":
                return DirectJson.stringArray(DirectMongoConnection.collections(
                        config,
                        DirectJson.required(call.getString("database"), "database")));
            case "documents": {
                DirectMongoConnection.Page page = DirectMongoConnection.documents(
                        config,
                        DirectJson.required(call.getString("database"), "database"),
                        DirectJson.required(call.getString("collection"), "collection"),
                        call.getString("filter", "{}"),
                        call.getInt("offset", 0),
                        call.getInt("limit", 25));
                return new JSObject()
                        .put("documents", DirectJson.stringArray(page.documents))
                        .put("offset", page.offset)
                        .put("limit", page.limit)
                        .put("hasMore", page.hasMore);
            }
            case "insert":
                return new JSObject().put("document", DirectMongoConnection.insert(
                        config,
                        DirectJson.required(call.getString("database"), "database"),
                        DirectJson.required(call.getString("collection"), "collection"),
                        DirectJson.required(call.getString("document"), "document")));
            case "replace":
                return new JSObject().put("modifiedCount", DirectMongoConnection.replace(
                        config,
                        DirectJson.required(call.getString("database"), "database"),
                        DirectJson.required(call.getString("collection"), "collection"),
                        DirectJson.required(call.getString("original"), "original"),
                        DirectJson.required(call.getString("document"), "document")));
            case "delete":
                return new JSObject().put("deletedCount", DirectMongoConnection.delete(
                        config,
                        DirectJson.required(call.getString("database"), "database"),
                        DirectJson.required(call.getString("collection"), "collection"),
                        DirectJson.required(call.getString("original"), "original")));
            default:
                throw new IllegalArgumentException("不支持的 MongoDB 操作：" + action);
        }
    }

    static boolean isWriteAction(String action) {
        return new HashSet<>(Arrays.asList("insert", "replace", "delete")).contains(action);
    }

    static void assertWriteAllowed(JSONObject config, PluginCall call) {
        if (config.optBoolean("readOnly", false)) {
            throw new IllegalArgumentException("此连接已设为只读，不能修改 MongoDB 文档");
        }
        if (!call.getBoolean("confirmedWrite", false)) {
            throw new IllegalArgumentException("MongoDB 写入必须由数据浏览器明确确认");
        }
        if (config.optBoolean("isProduction", false)
                && !config.optString("name").equals(call.getString("productionConfirmation", ""))) {
            throw new IllegalArgumentException("生产连接写入前必须输入完整连接名称");
        }
    }
}

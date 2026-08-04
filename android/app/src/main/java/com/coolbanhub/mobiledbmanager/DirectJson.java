package com.coolbanhub.mobiledbmanager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import org.json.JSONObject;

import java.util.List;

final class DirectJson {
    private DirectJson() {}

    static JSONObject requiredObject(PluginCall call, String name) {
        JSObject value = call.getObject(name);
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + "不能为空");
        return value;
    }

    static String optionalDatabase(JSONObject config) {
        if (config == null || !config.has("database") || config.isNull("database")) return "";
        return config.optString("database", "");
    }

    static String databaseOrNull(String database) {
        return database == null || database.isEmpty() ? null : database;
    }

    static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    static Object nullable(String value) {
        return value == null || value.isEmpty() ? JSONObject.NULL : value;
    }

    static JSArray stringArray(List<String> values) {
        JSArray result = new JSArray();
        for (String value : values) result.put(value);
        return result;
    }
}

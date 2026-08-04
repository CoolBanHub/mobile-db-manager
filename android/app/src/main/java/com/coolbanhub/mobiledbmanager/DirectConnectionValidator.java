package com.coolbanhub.mobiledbmanager;

import com.getcapacitor.JSObject;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class DirectConnectionValidator {
    private static final Set<String> SUPPORTED_DATABASES = new HashSet<>(Arrays.asList(
            "postgres", "mysql", "sqlserver", "redis", "mongodb", "etcd"));

    private DirectConnectionValidator() {}

    static JSObject summary(JSONObject config) {
        return new JSObject()
                .put("id", config.optString("id"))
                .put("name", config.optString("name"))
                .put("note", config.optString("note"))
                .put("dbType", config.optString("dbType"))
                .put("host", config.optString("host"))
                .put("port", config.optInt("port"))
                .put("database", DirectJson.nullable(DirectJson.optionalDatabase(config)))
                .put("color", DirectJson.nullable(config.optString("color", null)))
                .put("ssl", config.optBoolean("ssl"))
                .put("sslMode", config.optString("sslMode", "verify-full"))
                .put("readOnly", config.optBoolean("readOnly"))
                .put("isProduction", config.optBoolean("isProduction"))
                .put("connectTimeoutSecs", config.optInt("connectTimeoutSecs", 10))
                .put("queryTimeoutSecs", config.optInt("queryTimeoutSecs", 60));
    }

    static void validateDraft(JSONObject draft) {
        DirectJson.required(draft.optString("name"), "连接名称");
        if (draft.optString("host").trim().isEmpty() && draft.optString("connectionString").trim().isEmpty()) {
            throw new IllegalArgumentException("主机或数据库连接串不能为空");
        }
        if (draft.optInt("port") <= 0) throw new IllegalArgumentException("端口必须大于 0");
        String type = draft.optString("dbType");
        if (!SUPPORTED_DATABASES.contains(type)) {
            throw new IllegalArgumentException("当前直连版本支持 PostgreSQL、MySQL/MariaDB、SQL Server、Redis、MongoDB 和 etcd");
        }
        if (type.equals("redis")) {
            String database = DirectJson.optionalDatabase(draft).trim();
            if (!database.isEmpty()) {
                try {
                    if (Integer.parseInt(database) < 0) throw new NumberFormatException();
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("Redis 数据库必须是非负整数");
                }
            }
        }
        if (type.equals("mongodb")
                && !draft.optString("connectionString").trim().isEmpty()
                && (draft.optBoolean("sshEnabled", false) || draft.optBoolean("proxyEnabled", false))) {
            throw new IllegalArgumentException("MongoDB URI 不能与 Android SSH/HTTP 隧道同时使用；请改填主机、端口和账号");
        }
        if (draft.optBoolean("proxyEnabled", false)) {
            DirectJson.required(draft.optString("proxyHost"), "HTTP 代理主机");
            if (draft.optInt("proxyPort") <= 0) throw new IllegalArgumentException("HTTP 代理端口必须大于 0");
        }
        if (draft.optBoolean("sshEnabled", false)) {
            DirectJson.required(draft.optString("sshHost"), "SSH 主机");
            DirectJson.required(draft.optString("sshUsername"), "SSH 用户名");
            if (draft.optInt("sshPort", 22) <= 0) throw new IllegalArgumentException("SSH 端口必须大于 0");
        }
    }
}

package com.coolbanhub.mobiledbmanager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

final class DirectRedisActions {
    private DirectRedisActions() {}

    static Object execute(JSONObject config, String database, String action, PluginCall call) throws Exception {
        // 将界面动作映射为参数化命令数组，键和值不会被拼接为可执行命令文本。
        switch (action) {
            case "overview": {
                List<Object> replies = DirectRedisConnection.execute(config, database, Arrays.asList(
                        new String[]{"DBSIZE"},
                        new String[]{"INFO", "keyspace"}));
                return new JSObject()
                        .put("keyCount", replies.get(0))
                        .put("keyspace", replies.get(1));
            }
            case "scan": {
                int count = Math.min(200, Math.max(10, call.getInt("count", 100)));
                Object reply = DirectRedisConnection.execute(config, database, new String[]{
                        "SCAN",
                        call.getString("cursor", "0"),
                        "MATCH",
                        call.getString("pattern", "*"),
                        "COUNT",
                        String.valueOf(count)
                });
                List<?> page = requireRedisList(reply, "SCAN");
                return new JSObject()
                        .put("cursor", String.valueOf(page.get(0)))
                        .put("keys", redisJsonValue(page.get(1)));
            }
            case "detail":
                return redisKeyDetail(config, database, DirectJson.required(call.getString("key"), "key"));
            case "delete":
                return redisMutation(config, database, "DEL", DirectJson.required(call.getString("key"), "key"));
            case "set-string":
                return redisMutation(
                        config,
                        database,
                        "SET",
                        DirectJson.required(call.getString("key"), "key"),
                        call.getString("value", ""),
                        "KEEPTTL");
            case "hset":
                return redisMutation(
                        config,
                        database,
                        "HSET",
                        DirectJson.required(call.getString("key"), "key"),
                        DirectJson.required(call.getString("field"), "field"),
                        call.getString("value", ""));
            case "hdel":
                return redisMutation(
                        config,
                        database,
                        "HDEL",
                        DirectJson.required(call.getString("key"), "key"),
                        DirectJson.required(call.getString("field"), "field"));
            case "lset":
                return redisMutation(
                        config,
                        database,
                        "LSET",
                        DirectJson.required(call.getString("key"), "key"),
                        String.valueOf(call.getInt("index", 0)),
                        call.getString("value", ""));
            case "rpush":
                return redisMutation(
                        config,
                        database,
                        "RPUSH",
                        DirectJson.required(call.getString("key"), "key"),
                        call.getString("value", ""));
            case "sadd":
                return redisMutation(
                        config,
                        database,
                        "SADD",
                        DirectJson.required(call.getString("key"), "key"),
                        DirectJson.required(call.getString("member"), "member"));
            case "srem":
                return redisMutation(
                        config,
                        database,
                        "SREM",
                        DirectJson.required(call.getString("key"), "key"),
                        DirectJson.required(call.getString("member"), "member"));
            case "zadd":
                return redisMutation(
                        config,
                        database,
                        "ZADD",
                        DirectJson.required(call.getString("key"), "key"),
                        DirectJson.required(call.getString("score"), "score"),
                        DirectJson.required(call.getString("member"), "member"));
            case "zrem":
                return redisMutation(
                        config,
                        database,
                        "ZREM",
                        DirectJson.required(call.getString("key"), "key"),
                        DirectJson.required(call.getString("member"), "member"));
            case "expire": {
                int seconds = call.getInt("seconds", -1);
                if (seconds <= 0) {
                    throw new IllegalArgumentException("TTL 必须大于 0 秒；如需删除键请使用删除操作");
                }
                return redisMutation(
                        config,
                        database,
                        "EXPIRE",
                        DirectJson.required(call.getString("key"), "key"),
                        String.valueOf(seconds));
            }
            case "persist":
                return redisMutation(config, database, "PERSIST", DirectJson.required(call.getString("key"), "key"));
            default:
                throw new IllegalArgumentException("不支持的 Redis 操作：" + action);
        }
    }

    static boolean isWriteAction(String action) {
        return new HashSet<>(Arrays.asList(
                "delete", "set-string", "hset", "hdel", "lset", "rpush",
                "sadd", "srem", "zadd", "zrem", "expire", "persist")).contains(action);
    }

    static void assertWriteAllowed(JSONObject config, PluginCall call) {
        if (config.optBoolean("readOnly", false)) {
            throw new IllegalArgumentException("此连接已设为只读，不能修改 Redis 数据");
        }
        if (!call.getBoolean("confirmedWrite", false)) {
            throw new IllegalArgumentException("Redis 写入必须由数据浏览器明确确认");
        }
        if (config.optBoolean("isProduction", false)
                && !config.optString("name").equals(call.getString("productionConfirmation", ""))) {
            throw new IllegalArgumentException("生产连接写入前必须输入完整连接名称");
        }
    }

    private static JSObject redisKeyDetail(JSONObject config, String database, String key) throws Exception {
        // 先读取公共元数据，再按类型选择有界的预览命令，避免对大集合执行全量读取。
        List<Object> header = DirectRedisConnection.execute(config, database, Arrays.asList(
                new String[]{"TYPE", key},
                new String[]{"PTTL", key},
                new String[]{"MEMORY", "USAGE", key}));
        String type = String.valueOf(header.get(0));
        JSObject result = new JSObject()
                .put("key", key)
                .put("type", type)
                .put("ttlMs", header.get(1))
                .put("memoryBytes", header.get(2));
        switch (type) {
            case "string":
                result.put("value", redisJsonValue(
                        DirectRedisConnection.execute(config, database, new String[]{"GET", key})));
                break;
            case "hash": {
                List<Object> replies = DirectRedisConnection.execute(config, database, Arrays.asList(
                        new String[]{"HLEN", key},
                        new String[]{"HSCAN", key, "0", "COUNT", "200"}));
                result.put("length", replies.get(0));
                result.put("value", redisCollectionPage(replies.get(1), "HSCAN"));
                break;
            }
            case "list": {
                List<Object> replies = DirectRedisConnection.execute(config, database, Arrays.asList(
                        new String[]{"LLEN", key},
                        new String[]{"LRANGE", key, "0", "199"}));
                result.put("length", replies.get(0));
                result.put("value", redisJsonValue(replies.get(1)));
                break;
            }
            case "set": {
                List<Object> replies = DirectRedisConnection.execute(config, database, Arrays.asList(
                        new String[]{"SCARD", key},
                        new String[]{"SSCAN", key, "0", "COUNT", "200"}));
                result.put("length", replies.get(0));
                result.put("value", redisCollectionPage(replies.get(1), "SSCAN"));
                break;
            }
            case "zset": {
                List<Object> replies = DirectRedisConnection.execute(config, database, Arrays.asList(
                        new String[]{"ZCARD", key},
                        new String[]{"ZRANGE", key, "0", "199", "WITHSCORES"}));
                result.put("length", replies.get(0));
                result.put("value", redisJsonValue(replies.get(1)));
                break;
            }
            case "stream": {
                List<Object> replies = DirectRedisConnection.execute(config, database, Arrays.asList(
                        new String[]{"XLEN", key},
                        new String[]{"XRANGE", key, "-", "+", "COUNT", "100"}));
                result.put("length", replies.get(0));
                result.put("value", redisJsonValue(replies.get(1)));
                break;
            }
            case "none":
                result.put("value", JSONObject.NULL);
                break;
            default:
                result.put("value", "安卓端暂不支持预览 " + type + " 类型");
        }
        return result;
    }

    private static Object redisCollectionPage(Object reply, String command) {
        List<?> page = requireRedisList(reply, command);
        return redisJsonValue(page.size() > 1 ? page.get(1) : null);
    }

    private static JSObject redisMutation(JSONObject config, String database, String... command) throws Exception {
        return new JSObject().put(
                "result",
                redisJsonValue(DirectRedisConnection.execute(config, database, command)));
    }

    private static List<?> requireRedisList(Object value, String command) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(command + " 返回了意外结果");
        }
        return (List<?>) value;
    }

    private static Object redisJsonValue(Object value) {
        if (value == null) return JSONObject.NULL;
        if (value instanceof List) {
            JSArray result = new JSArray();
            for (Object item : (List<?>) value) result.put(redisJsonValue(item));
            return result;
        }
        return value;
    }
}

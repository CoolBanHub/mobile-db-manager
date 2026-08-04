package com.houtsider.dbx;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "DirectDatabase")
public class DirectDatabasePlugin extends Plugin {
    private static final int MAX_ROWS = 501;
    private static final Set<String> READ_PREFIXES = new HashSet<>(Arrays.asList(
            "select", "with", "show", "describe", "desc", "explain", "pragma", "values"));
    private static final Set<String> WRITE_KEYWORDS = new HashSet<>(Arrays.asList(
            "insert", "update", "delete", "merge", "replace", "upsert", "create", "alter",
            "drop", "truncate", "grant", "revoke", "call", "execute", "exec", "copy",
            "vacuum", "reindex", "attach", "detach", "load"));
    private static final Set<String> SUPPORTED_DATABASES = new HashSet<>(Arrays.asList(
            "postgres", "mysql", "sqlserver", "redis", "mongodb", "etcd"));

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Statement> runningStatements = new ConcurrentHashMap<>();

    private DirectConnectionStore store() {
        return new DirectConnectionStore(getContext());
    }

    @PluginMethod
    public void listConnections(PluginCall call) {
        run(call, () -> {
            JSArray result = new JSArray();
            for (JSONObject config : store().all()) result.put(summary(config));
            return result;
        });
    }

    @PluginMethod
    public void getConnection(PluginCall call) {
        run(call, () -> {
            JSONObject config = requiredConfig(call.getString("id"));
            JSObject result = summary(config);
            result.put("username", config.optString("username"));
            result.put("hasPassword", !config.optString("password").isEmpty());
            result.put("keepaliveIntervalSecs", config.optInt("keepaliveIntervalSecs", 30));
            result.put("proxyEnabled", config.optBoolean("proxyEnabled", false));
            result.put("proxyHost", config.optString("proxyHost"));
            result.put("proxyPort", config.optInt("proxyPort", 8080));
            result.put("proxyUsername", config.optString("proxyUsername"));
            result.put("hasProxyPassword", !config.optString("proxyPassword").isEmpty());
            result.put("sshEnabled", config.optBoolean("sshEnabled", false));
            result.put("sshHost", config.optString("sshHost"));
            result.put("sshPort", config.optInt("sshPort", 22));
            result.put("sshUsername", config.optString("sshUsername"));
            result.put("sshHostKeyFingerprint", config.optString("sshHostKeyFingerprint"));
            result.put("hasSshPassword", !config.optString("sshPassword").isEmpty());
            result.put("sshAuthMethod", config.optString("sshAuthMethod", "password"));
            result.put("hasSshPrivateKey", !config.optString("sshPrivateKey").isEmpty());
            result.put("hasSshPrivateKeyPassphrase", !config.optString("sshPrivateKeyPassphrase").isEmpty());
            result.put("connectionString", "");
            result.put("hasConnectionString", !config.optString("connectionString").isEmpty());
            result.put("tunnelLayerCount",
                    (config.optBoolean("sshEnabled", false) ? 1 : 0)
                            + (config.optBoolean("proxyEnabled", false) ? 1 : 0));
            return result;
        });
    }

    @PluginMethod
    public void saveConnection(PluginCall call) {
        run(call, () -> {
            JSONObject draft = requiredObject(call, "connection");
            validateDraft(withStoredSecrets(draft));
            return summary(store().save(draft));
        });
    }

    @PluginMethod
    public void deleteConnection(PluginCall call) {
        run(call, () -> {
            if (!store().remove(required(call.getString("id"), "id"))) {
                throw new IllegalArgumentException("连接不存在");
            }
            return new JSObject().put("ok", true);
        });
    }

    @PluginMethod
    public void testConnection(PluginCall call) {
        run(call, () -> {
            JSONObject draft = requiredObject(call, "connection");
            JSONObject effective = withStoredSecrets(draft);
            validateDraft(effective);
            String type = effective.optString("dbType");
            if (type.equals("redis")) {
                DirectRedisConnection.test(effective);
                return new JSObject().put("message", "手机已直接连接 Redis");
            }
            if (type.equals("mongodb")) {
                DirectMongoConnection.test(effective);
                return new JSObject().put("message", "手机已直接连接 MongoDB");
            }
            if (type.equals("etcd")) {
                DirectEtcdConnection.test(effective);
                return new JSObject().put("message", "手机已直接连接 etcd");
            }
            try (Connection connection = open(effective, optionalDatabase(effective))) {
                if (!connection.isValid(Math.max(1, draft.optInt("connectTimeoutSecs", 10)))) {
                    throw new SQLException("数据库没有通过连接有效性检查");
                }
                return new JSObject().put("message", "手机已直接连接数据库");
            }
        });
    }

    @PluginMethod
    public void metadata(PluginCall call) {
        run(call, () -> metadata(
                requiredConfig(call.getString("connectionId")),
                required(call.getString("kind"), "kind"),
                call.getString("database", ""),
                call.getString("schema", ""),
                call.getString("table", ""),
                call.getString("filter", ""),
                call.getInt("limit", 100),
                call.getInt("offset", 0)));
    }

    @PluginMethod
    public void query(PluginCall call) {
        run(call, () -> {
            JSONObject config = requiredConfig(call.getString("connectionId"));
            String sql = required(call.getString("sql"), "sql").trim();
            boolean readOnly = call.getBoolean("readOnly", true);
            boolean readOnlySql = isReadOnlySql(sql);
            if (readOnly && !readOnlySql) {
                throw new IllegalArgumentException("只读模式已阻止可能修改数据的语句");
            }
            if (!readOnly && !readOnlySql && config.optBoolean("readOnly", false)) {
                throw new IllegalArgumentException("此连接已设为只读，不能执行高级写入");
            }
            if (!readOnly && !readOnlySql && !call.getBoolean("confirmedWrite", false)) {
                throw new IllegalArgumentException("写入语句必须先勾选本次写入确认");
            }
            if (!readOnly && !readOnlySql && config.optBoolean("isProduction", false)
                    && !config.optString("name").equals(call.getString("productionConfirmation", ""))) {
                throw new IllegalArgumentException("生产连接写入前必须输入完整连接名称");
            }
            return executeQuery(
                    config,
                    call.getString("database", ""),
                    call.getString("schema", ""),
                    sql,
                    call.getString("executionId", ""),
                    call.getInt("offset", 0),
                    call.getInt("pageSize", 50),
                    readOnly);
        });
    }

    @PluginMethod
    public void redis(PluginCall call) {
        run(call, () -> {
            JSONObject config = requiredConfig(call.getString("connectionId"));
            if (!"redis".equals(config.optString("dbType"))) {
                throw new IllegalArgumentException("当前连接不是 Redis");
            }
            String action = required(call.getString("action"), "action");
            String database = call.getString("database", optionalDatabase(config));
            boolean write = isRedisWriteAction(action);
            // 所有写动作先经过统一安全门，再进入固定命令映射，WebView 不能下发任意 Redis 命令。
            if (write) assertRedisWriteAllowed(config, call);
            return executeRedisAction(config, database, action, call);
        });
    }

    @PluginMethod
    public void mongo(PluginCall call) {
        run(call, () -> {
            JSONObject config = requiredConfig(call.getString("connectionId"));
            if (!"mongodb".equals(config.optString("dbType"))) {
                throw new IllegalArgumentException("当前连接不是 MongoDB");
            }
            String action = required(call.getString("action"), "action");
            // 写入校验放在 switch 之前，新增动作时只需维护动作分类即可继承保护。
            if (isMongoWriteAction(action)) assertMongoWriteAllowed(config, call);
            switch (action) {
                case "databases":
                    return stringArray(DirectMongoConnection.databases(config));
                case "collections":
                    return stringArray(DirectMongoConnection.collections(
                            config,
                            required(call.getString("database"), "database")));
                case "documents": {
                    DirectMongoConnection.Page page = DirectMongoConnection.documents(
                            config,
                            required(call.getString("database"), "database"),
                            required(call.getString("collection"), "collection"),
                            call.getString("filter", "{}"),
                            call.getInt("offset", 0),
                            call.getInt("limit", 25));
                    return new JSObject()
                            .put("documents", stringArray(page.documents))
                            .put("offset", page.offset)
                            .put("limit", page.limit)
                            .put("hasMore", page.hasMore);
                }
                case "insert":
                    return new JSObject().put("document", DirectMongoConnection.insert(
                            config,
                            required(call.getString("database"), "database"),
                            required(call.getString("collection"), "collection"),
                            required(call.getString("document"), "document")));
                case "replace":
                    return new JSObject().put("modifiedCount", DirectMongoConnection.replace(
                            config,
                            required(call.getString("database"), "database"),
                            required(call.getString("collection"), "collection"),
                            required(call.getString("original"), "original"),
                            required(call.getString("document"), "document")));
                case "delete":
                    return new JSObject().put("deletedCount", DirectMongoConnection.delete(
                            config,
                            required(call.getString("database"), "database"),
                            required(call.getString("collection"), "collection"),
                            required(call.getString("original"), "original")));
                default:
                    throw new IllegalArgumentException("不支持的 MongoDB 操作：" + action);
            }
        });
    }

    @PluginMethod
    public void etcd(PluginCall call) {
        run(call, () -> {
            JSONObject config = requiredConfig(call.getString("connectionId"));
            if (!"etcd".equals(config.optString("dbType"))) {
                throw new IllegalArgumentException("当前连接不是 etcd");
            }
            String action = required(call.getString("action"), "action");
            // etcd 桥接仅开放列表、详情和单键写入，不暴露事务或任意 Gateway 路径。
            if (new HashSet<>(Arrays.asList("put", "delete")).contains(action)) {
                assertEtcdWriteAllowed(config, call);
            }
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
                    return etcdRange(config, call.getString("prefix", ""), call.getInt("limit", 200));
                case "detail":
                    return etcdDetail(config, required(call.getString("key"), "key"));
                case "put":
                    return DirectEtcdConnection.put(
                            config,
                            required(call.getString("key"), "key"),
                            call.getString("value", ""),
                            call.getString("lease", "0"));
                case "delete":
                    return DirectEtcdConnection.delete(
                            config,
                            required(call.getString("key"), "key"));
                default:
                    throw new IllegalArgumentException("不支持的 etcd 操作：" + action);
            }
        });
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        run(call, () -> {
            Statement statement = runningStatements.get(required(call.getString("executionId"), "executionId"));
            if (statement != null) statement.cancel();
            return new JSObject().put("cancelled", statement != null);
        });
    }

    private Object executeRedisAction(JSONObject config, String database, String action, PluginCall call)
            throws Exception {
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
                return redisKeyDetail(config, database, required(call.getString("key"), "key"));
            case "delete":
                return redisMutation(config, database, "DEL", required(call.getString("key"), "key"));
            case "set-string":
                return redisMutation(
                        config,
                        database,
                        "SET",
                        required(call.getString("key"), "key"),
                        call.getString("value", ""),
                        "KEEPTTL");
            case "hset":
                return redisMutation(
                        config,
                        database,
                        "HSET",
                        required(call.getString("key"), "key"),
                        required(call.getString("field"), "field"),
                        call.getString("value", ""));
            case "hdel":
                return redisMutation(
                        config,
                        database,
                        "HDEL",
                        required(call.getString("key"), "key"),
                        required(call.getString("field"), "field"));
            case "lset":
                return redisMutation(
                        config,
                        database,
                        "LSET",
                        required(call.getString("key"), "key"),
                        String.valueOf(call.getInt("index", 0)),
                        call.getString("value", ""));
            case "rpush":
                return redisMutation(
                        config,
                        database,
                        "RPUSH",
                        required(call.getString("key"), "key"),
                        call.getString("value", ""));
            case "sadd":
                return redisMutation(
                        config,
                        database,
                        "SADD",
                        required(call.getString("key"), "key"),
                        required(call.getString("member"), "member"));
            case "srem":
                return redisMutation(
                        config,
                        database,
                        "SREM",
                        required(call.getString("key"), "key"),
                        required(call.getString("member"), "member"));
            case "zadd":
                return redisMutation(
                        config,
                        database,
                        "ZADD",
                        required(call.getString("key"), "key"),
                        required(call.getString("score"), "score"),
                        required(call.getString("member"), "member"));
            case "zrem":
                return redisMutation(
                        config,
                        database,
                        "ZREM",
                        required(call.getString("key"), "key"),
                        required(call.getString("member"), "member"));
            case "expire": {
                int seconds = call.getInt("seconds", -1);
                if (seconds <= 0) {
                    throw new IllegalArgumentException("TTL 必须大于 0 秒；如需删除键请使用删除操作");
                }
                return redisMutation(
                        config,
                        database,
                        "EXPIRE",
                        required(call.getString("key"), "key"),
                        String.valueOf(seconds));
            }
            case "persist":
                return redisMutation(config, database, "PERSIST", required(call.getString("key"), "key"));
            default:
                throw new IllegalArgumentException("不支持的 Redis 操作：" + action);
        }
    }

    private JSObject redisKeyDetail(JSONObject config, String database, String key) throws Exception {
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

    private Object redisCollectionPage(Object reply, String command) {
        List<?> page = requireRedisList(reply, command);
        return redisJsonValue(page.size() > 1 ? page.get(1) : null);
    }

    private JSObject redisMutation(JSONObject config, String database, String... command) throws Exception {
        return new JSObject().put(
                "result",
                redisJsonValue(DirectRedisConnection.execute(config, database, command)));
    }

    private boolean isRedisWriteAction(String action) {
        return new HashSet<>(Arrays.asList(
                "delete", "set-string", "hset", "hdel", "lset", "rpush",
                "sadd", "srem", "zadd", "zrem", "expire", "persist")).contains(action);
    }

    private void assertRedisWriteAllowed(JSONObject config, PluginCall call) {
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

    private JSObject etcdRange(JSONObject config, String prefix, int limit) throws Exception {
        JSONObject response = DirectEtcdConnection.range(config, prefix, limit);
        JSArray entries = new JSArray();
        org.json.JSONArray values = response.optJSONArray("kvs");
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                JSONObject item = values.getJSONObject(index);
                entries.put(etcdEntry(item));
            }
        }
        return new JSObject()
                .put("entries", entries)
                .put("count", response.optString("count", String.valueOf(entries.length())))
                .put("more", response.optBoolean("more", false));
    }

    private JSObject etcdDetail(JSONObject config, String key) throws Exception {
        JSONObject response = DirectEtcdConnection.get(config, key);
        org.json.JSONArray values = response.optJSONArray("kvs");
        if (values == null || values.length() == 0) {
            throw new IllegalArgumentException("etcd 键不存在或已被删除");
        }
        return etcdEntry(values.getJSONObject(0));
    }

    private JSObject etcdEntry(JSONObject item) throws Exception {
        return new JSObject()
                .put("key", DirectEtcdConnection.decode(item.optString("key")))
                .put("value", DirectEtcdConnection.decode(item.optString("value")))
                .put("createRevision", item.optString("create_revision", item.optString("createRevision", "0")))
                .put("modRevision", item.optString("mod_revision", item.optString("modRevision", "0")))
                .put("version", item.optString("version", "0"))
                .put("lease", item.optString("lease", "0"));
    }

    private void assertEtcdWriteAllowed(JSONObject config, PluginCall call) {
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

    private boolean isMongoWriteAction(String action) {
        return new HashSet<>(Arrays.asList("insert", "replace", "delete")).contains(action);
    }

    private void assertMongoWriteAllowed(JSONObject config, PluginCall call) {
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

    private JSArray stringArray(List<String> values) {
        JSArray result = new JSArray();
        for (String value : values) result.put(value);
        return result;
    }

    private List<?> requireRedisList(Object value, String command) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(command + " 返回了意外结果");
        }
        return (List<?>) value;
    }

    private Object redisJsonValue(Object value) {
        if (value == null) return JSONObject.NULL;
        if (value instanceof List) {
            JSArray result = new JSArray();
            for (Object item : (List<?>) value) result.put(redisJsonValue(item));
            return result;
        }
        return value;
    }

    private Object metadata(JSONObject config, String kind, String database, String schema, String table,
                            String filter, int limit, int offset) throws Exception {
        try (Connection connection = open(config, database)) {
            DatabaseMetaData meta = connection.getMetaData();
            JSArray values = new JSArray();
            switch (kind) {
                case "databases":
                    try (ResultSet rows = meta.getCatalogs()) {
                        while (rows.next()) values.put(new JSObject().put("name", rows.getString(1)));
                    }
                    if (values.length() == 0) {
                        String current = connection.getCatalog();
                        values.put(new JSObject().put("name", current == null || current.isEmpty() ? config.optString("database", "default") : current));
                    }
                    return values;
                case "schemas":
                    try (ResultSet rows = getSchemasCompatible(meta, databaseOrNull(database))) {
                        while (rows.next()) values.put(rows.getString("TABLE_SCHEM"));
                    }
                    return values;
                case "tables":
                    int skipped = 0;
                    try (ResultSet rows = meta.getTables(databaseOrNull(database), emptyToNull(schema),
                            filter.isEmpty() ? "%" : "%" + filter + "%", new String[]{"TABLE", "VIEW"})) {
                        while (rows.next()) {
                            if (skipped++ < Math.max(0, offset)) continue;
                            if (values.length() >= Math.max(1, limit)) break;
                            values.put(new JSObject()
                                    .put("name", rows.getString("TABLE_NAME"))
                                    .put("table_type", rows.getString("TABLE_TYPE"))
                                    .put("comment", rows.getString("REMARKS"))
                                    .put("parent_schema", JSONObject.NULL)
                                    .put("parent_name", JSONObject.NULL));
                        }
                    }
                    return values;
                case "columns":
                    Set<String> primaryKeys = ConcurrentHashMap.newKeySet();
                    try (ResultSet keys = meta.getPrimaryKeys(databaseOrNull(database), emptyToNull(schema), table)) {
                        while (keys.next()) primaryKeys.add(keys.getString("COLUMN_NAME"));
                    }
                    try (ResultSet rows = meta.getColumns(databaseOrNull(database), emptyToNull(schema), table, "%")) {
                        while (rows.next()) {
                            String name = rows.getString("COLUMN_NAME");
                            values.put(new JSObject()
                                    .put("name", name)
                                    .put("data_type", rows.getString("TYPE_NAME"))
                                    .put("is_nullable", rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls)
                                    .put("column_default", nullable(rows.getString("COLUMN_DEF")))
                                    .put("is_primary_key", primaryKeys.contains(name))
                                    .put("extra", nullable(rows.getString("IS_AUTOINCREMENT")))
                                    .put("comment", nullable(rows.getString("REMARKS"))));
                        }
                    }
                    return values;
                case "indexes":
                    try (ResultSet rows = meta.getIndexInfo(databaseOrNull(database), emptyToNull(schema), table, false, false)) {
                        Map<String, JSObject> indexes = new java.util.LinkedHashMap<>();
                        while (rows.next()) {
                            String name = rows.getString("INDEX_NAME");
                            String column = rows.getString("COLUMN_NAME");
                            if (name == null || column == null) continue;
                            JSObject index = indexes.computeIfAbsent(name, key -> new JSObject()
                                    .put("name", key).put("columns", new JSArray())
                                    .put("is_unique", false).put("is_primary", false));
                            index.getJSONArray("columns").put(column);
                            index.put("is_unique", !rows.getBoolean("NON_UNIQUE"));
                        }
                        indexes.values().forEach(values::put);
                    }
                    return values;
                case "foreign-keys":
                    try (ResultSet rows = meta.getImportedKeys(databaseOrNull(database), emptyToNull(schema), table)) {
                        while (rows.next()) values.put(new JSObject()
                                .put("name", rows.getString("FK_NAME"))
                                .put("column", rows.getString("FKCOLUMN_NAME"))
                                .put("ref_schema", nullable(rows.getString("PKTABLE_SCHEM")))
                                .put("ref_table", rows.getString("PKTABLE_NAME"))
                                .put("ref_column", rows.getString("PKCOLUMN_NAME")));
                    }
                    return values;
                case "objects":
                    try (ResultSet rows = meta.getProcedures(databaseOrNull(database), emptyToNull(schema), "%")) {
                        while (rows.next()) values.put(new JSObject()
                                .put("name", rows.getString("PROCEDURE_NAME"))
                                .put("object_type", "PROCEDURE")
                                .put("schema", nullable(rows.getString("PROCEDURE_SCHEM"))));
                    }
                    return values;
                default:
                    return kind.equals("ddl") ? "" : values;
            }
        }
    }

    static ResultSet getSchemasCompatible(DatabaseMetaData metadata, String catalog) throws SQLException {
        try {
            return metadata.getSchemas(catalog, null);
        } catch (AbstractMethodError error) {
            // jTDS predates the JDBC 4 catalog-aware overload. The connection
            // is already opened against the requested SQL Server database, so
            // its original getSchemas() method returns the correct schemas.
            return metadata.getSchemas();
        }
    }

    private JSObject executeQuery(JSONObject config, String database, String schema, String sql,
                                  String executionId, int offset, int pageSize, boolean readOnly) throws Exception {
        long started = System.nanoTime();
        try (Connection connection = open(config, database)) {
            connection.setReadOnly(readOnly);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(Math.max(1, config.optInt("queryTimeoutSecs", 60)));
                statement.setMaxRows(Math.min(MAX_ROWS, Math.max(1, offset + pageSize + 1)));
                if (executionId != null && !executionId.isEmpty()) runningStatements.put(executionId, statement);
                boolean hasRows = statement.execute(sql);
                JSArray columns = new JSArray();
                JSArray rows = new JSArray();
                int affected = 0;
                boolean hasMore = false;
                if (hasRows) {
                    try (ResultSet result = statement.getResultSet()) {
                        ResultSetMetaData metadata = result.getMetaData();
                        for (int column = 1; column <= metadata.getColumnCount(); column++) {
                            columns.put(metadata.getColumnLabel(column));
                        }
                        int rowIndex = 0;
                        while (result.next()) {
                            if (rowIndex++ < offset) continue;
                            if (rows.length() >= pageSize) {
                                hasMore = true;
                                break;
                            }
                            JSArray row = new JSArray();
                            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                                row.put(jsonValue(result.getObject(column)));
                            }
                            rows.put(row);
                        }
                    }
                } else {
                    affected = Math.max(0, statement.getUpdateCount());
                }
                return new JSObject()
                        .put("columns", columns)
                        .put("rows", rows)
                        .put("affected_rows", affected)
                        .put("execution_time_ms", (System.nanoTime() - started) / 1_000_000)
                        .put("truncated", hasMore)
                        .put("has_more", hasMore);
            } finally {
                if (executionId != null) runningStatements.remove(executionId);
            }
        }
    }

    private Connection open(JSONObject config, String requestedDatabase) throws Exception {
        String type = config.optString("dbType");
        String host = config.optString("host");
        int port = config.optInt("port");
        String database = requestedDatabase == null || requestedDatabase.isEmpty()
                ? optionalDatabase(config) : requestedDatabase;
        if (database.isEmpty() && type.equals("postgres")) database = "postgres";
        if (database.isEmpty() && type.equals("sqlserver")) database = "master";
        String connectionString = config.optString("connectionString").trim();
        if (!connectionString.isEmpty()
                && (config.optBoolean("sshEnabled", false) || config.optBoolean("proxyEnabled", false))) {
            throw new IllegalArgumentException("使用 SSH/HTTP 隧道时请通过常规主机和端口连接，不要使用自定义 JDBC 连接串");
        }
        DirectTransport.Route route = DirectTransport.open(config, host, port);
        String url;
        if (!connectionString.isEmpty()) {
            url = connectionString;
        } else if (type.equals("postgres")) {
            Class.forName("org.postgresql.Driver");
            url = "jdbc:postgresql://" + route.host + ":" + route.port + "/" + database;
        } else if (type.equals("mysql")) {
            Class.forName("org.mariadb.jdbc.Driver");
            url = "jdbc:mariadb://" + route.host + ":" + route.port + "/" + database;
        } else if (type.equals("sqlserver")) {
            Class.forName("net.sourceforge.jtds.jdbc.Driver");
            url = "jdbc:jtds:sqlserver://" + route.host + ":" + route.port + "/" + database;
        } else {
            route.close();
            throw new IllegalArgumentException("当前 Android 直连版本尚未内置 " + type + " 驱动");
        }
        Properties properties = new Properties();
        properties.setProperty("user", config.optString("username"));
        properties.setProperty("password", config.optString("password"));
        int connectTimeoutSecs = Math.max(1, config.optInt("connectTimeoutSecs", 10));
        if (type.equals("mysql")) {
            properties.setProperty("connectTimeout", String.valueOf(connectTimeoutSecs * 1_000));
        } else if (type.equals("sqlserver")) {
            properties.setProperty("loginTimeout", String.valueOf(connectTimeoutSecs));
        } else {
            properties.setProperty("connectTimeout", String.valueOf(connectTimeoutSecs));
        }
        applySecurityProperties(
                properties,
                type,
                config.optBoolean("ssl", false),
                config.optString("sslMode", "verify-full"));
        DriverManager.setLoginTimeout(Math.max(1, config.optInt("connectTimeoutSecs", 10)));
        try {
            return DirectTransport.attach(DriverManager.getConnection(url, properties), route);
        } catch (Throwable error) {
            route.close();
            if (error instanceof Exception) throw (Exception) error;
            throw (Error) error;
        }
    }

    static void applySecurityProperties(Properties properties, String type, boolean ssl, String sslMode) {
        if (type.equals("sqlserver")) {
            properties.setProperty(
                    "ssl",
                    !ssl ? "off" : sslMode.equals("required") ? "require" : "authenticate");
            return;
        }
        if (ssl) {
            if (type.equals("postgres")) properties.setProperty("sslmode", sslMode);
            if (type.equals("mysql")) {
                properties.setProperty(
                        "sslMode",
                        sslMode.equals("required") ? "trust"
                                : sslMode.equals("verify-ca") ? "verify-ca" : "verify-full");
            }
            return;
        }

        if (type.equals("postgres")) properties.setProperty("sslmode", "disable");
        if (type.equals("mysql")) {
            properties.setProperty("sslMode", "disable");
            // MySQL 8 默认使用 caching_sha2_password。关闭 TLS 后，MariaDB
            // Connector/J 需要显式允许获取 RSA 公钥，否则会把自签名证书拒绝为认证错误。
            properties.setProperty("allowPublicKeyRetrieval", "true");
        }
    }

    private JSObject summary(JSONObject config) {
        return new JSObject()
                .put("id", config.optString("id"))
                .put("name", config.optString("name"))
                .put("note", config.optString("note"))
                .put("dbType", config.optString("dbType"))
                .put("host", config.optString("host"))
                .put("port", config.optInt("port"))
                .put("database", nullable(optionalDatabase(config)))
                .put("color", nullable(config.optString("color", null)))
                .put("ssl", config.optBoolean("ssl"))
                .put("sslMode", config.optString("sslMode", "verify-full"))
                .put("readOnly", config.optBoolean("readOnly"))
                .put("isProduction", config.optBoolean("isProduction"))
                .put("connectTimeoutSecs", config.optInt("connectTimeoutSecs", 10))
                .put("queryTimeoutSecs", config.optInt("queryTimeoutSecs", 60));
    }

    private void validateDraft(JSONObject draft) {
        required(draft.optString("name"), "连接名称");
        if (draft.optString("host").trim().isEmpty() && draft.optString("connectionString").trim().isEmpty()) {
            throw new IllegalArgumentException("主机或数据库连接串不能为空");
        }
        if (draft.optInt("port") <= 0) throw new IllegalArgumentException("端口必须大于 0");
        String type = draft.optString("dbType");
        if (!SUPPORTED_DATABASES.contains(type)) {
            throw new IllegalArgumentException("当前直连版本支持 PostgreSQL、MySQL/MariaDB、SQL Server、Redis、MongoDB 和 etcd");
        }
        if (type.equals("redis")) {
            String database = optionalDatabase(draft).trim();
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
            required(draft.optString("proxyHost"), "HTTP 代理主机");
            if (draft.optInt("proxyPort") <= 0) throw new IllegalArgumentException("HTTP 代理端口必须大于 0");
        }
        if (draft.optBoolean("sshEnabled", false)) {
            required(draft.optString("sshHost"), "SSH 主机");
            required(draft.optString("sshUsername"), "SSH 用户名");
            if (draft.optInt("sshPort", 22) <= 0) throw new IllegalArgumentException("SSH 端口必须大于 0");
        }
    }

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

    private Object jsonValue(Object value) throws Exception {
        if (value == null) return JSONObject.NULL;
        if (value instanceof byte[]) return "base64:" + Base64.getEncoder().encodeToString((byte[]) value);
        if (value instanceof Blob) {
            Blob blob = (Blob) value;
            return "base64:" + Base64.getEncoder().encodeToString(blob.getBytes(1, (int) Math.min(blob.length(), 1_048_576)));
        }
        if (value instanceof Clob) {
            Clob clob = (Clob) value;
            return clob.getSubString(1, (int) Math.min(clob.length(), 1_048_576));
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) return value;
        if (value instanceof java.util.Date || value instanceof TemporalAccessor) return value.toString();
        if (value instanceof BigDecimal) return value.toString();
        return String.valueOf(value);
    }

    private JSONObject requiredConfig(String id) throws Exception {
        JSONObject config = store().get(required(id, "connectionId"));
        if (config == null) throw new IllegalArgumentException("连接不存在");
        return config;
    }

    private JSONObject withStoredSecrets(JSONObject incoming) throws Exception {
        String id = incoming.optString("id", "").trim();
        if (id.isEmpty()) return incoming;
        JSONObject existing = store().get(id);
        if (existing == null) return incoming;
        JSONObject effective = new JSONObject(incoming.toString());
        for (String name : new String[]{
                "password", "proxyPassword", "sshPassword", "sshPrivateKey",
                "sshPrivateKeyPassphrase", "connectionString"}) {
            if (effective.optString(name, "").isEmpty() && !existing.optString(name, "").isEmpty()) {
                effective.put(name, existing.get(name));
            }
        }
        return effective;
    }

    private JSONObject requiredObject(PluginCall call, String name) {
        JSObject value = call.getObject(name);
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + "不能为空");
        return value;
    }

    private String databaseOrNull(String database) {
        return database == null || database.isEmpty() ? null : database;
    }

    private String optionalDatabase(JSONObject config) {
        if (config == null || !config.has("database") || config.isNull("database")) return "";
        return config.optString("database", "");
    }

    private String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private Object nullable(String value) {
        return value == null || value.isEmpty() ? JSONObject.NULL : value;
    }

    private interface Task {
        Object execute() throws Exception;
    }

    private void run(PluginCall call, Task task) {
        executor.execute(() -> {
            try {
                Object value = task.execute();
                JSObject result = new JSObject();
                result.put("value", value);
                call.resolve(result);
            } catch (Throwable error) {
                String message = friendlyMessage(error);
                Exception reportable = error instanceof Exception
                        ? (Exception) error : new RuntimeException(message, error);
                call.reject(message, reportable);
            }
        });
    }

    private String friendlyMessage(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            String message = cause.getMessage() == null ? "" : cause.getMessage();
            if (cause instanceof javax.net.ssl.SSLHandshakeException
                    || message.contains("trust anchors")
                    || message.contains("PKIX path")) {
                return "SSL 证书验证失败：服务器使用了 Android 不信任的证书。"
                        + "本地或自签名环境请在 SSL 页选择“仅加密”，或关闭 SSL；"
                        + "生产环境请配置受信任证书。";
            }
            cause = cause.getCause();
        }
        if (error instanceof NoClassDefFoundError) {
            return "数据库驱动与当前 Android 运行时不兼容：" + error.getMessage();
        }
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String rootMessage = root.getMessage();
        if (root != error && rootMessage != null && !rootMessage.isEmpty() && !message.contains(rootMessage)) {
            return message + "；根因：" + rootMessage;
        }
        return message;
    }
}

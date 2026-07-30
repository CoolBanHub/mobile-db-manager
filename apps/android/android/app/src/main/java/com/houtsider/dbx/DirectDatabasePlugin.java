package com.houtsider.dbx;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
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
            "postgres", "mysql", "sqlserver", "redis", "mongodb"));

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
            result.put("idleTimeoutSecs", config.optInt("idleTimeoutSecs", 60));
            result.put("keepaliveIntervalSecs", config.optInt("keepaliveIntervalSecs", 30));
            result.put("caCertPath", config.optString("caCertPath"));
            result.put("clientCertPath", config.optString("clientCertPath"));
            result.put("clientKeyPath", "");
            result.put("proxyEnabled", config.optBoolean("proxyEnabled", false));
            result.put("proxyType", config.optString("proxyType", "http"));
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
            result.put("oracleConnectionType", config.optString("oracleConnectionType", "service_name"));
            result.put("sysdba", false);
            result.put("urlParams", config.optString("urlParams"));
            result.put("initScript", config.optString("initScript"));
            result.put("visibleDatabases", config.optJSONArray("visibleDatabases") == null ? new JSONArray() : config.optJSONArray("visibleDatabases"));
            result.put("visibleSchemas", config.optJSONObject("visibleSchemas") == null ? new JSONObject() : config.optJSONObject("visibleSchemas"));
            result.put("productionDatabases", config.optJSONArray("productionDatabases") == null ? new JSONArray() : config.optJSONArray("productionDatabases"));
            result.put("redisConnectionMode", config.optString("redisConnectionMode", "standalone"));
            result.put("redisSentinelMaster", config.optString("redisSentinelMaster"));
            result.put("redisSentinelNodes", config.optString("redisSentinelNodes"));
            result.put("redisSentinelUsername", config.optString("redisSentinelUsername"));
            result.put("hasRedisSentinelPassword", !config.optString("redisSentinelPassword").isEmpty());
            result.put("redisSentinelTls", config.optBoolean("redisSentinelTls", false));
            result.put("redisClusterNodes", config.optString("redisClusterNodes"));
            result.put("jdbcDriverClass", "");
            result.put("jdbcDriverPaths", new JSONArray());
            result.put("driverProfile", config.optString("driverProfile", "android-native"));
            result.put("driverLabel", config.optString(
                    "driverLabel",
                    config.optString("dbType").equals("redis")
                            ? "Android native Redis"
                            : config.optString("dbType").equals("mongodb")
                                    ? "Android bundled MongoDB"
                                    : "Android bundled JDBC"));
            result.put("tunnelLayerCount",
                    (config.optBoolean("sshEnabled", false) ? 1 : 0)
                            + (config.optBoolean("proxyEnabled", false) ? 1 : 0));
            result.put("tunnelProfileCount", 0);
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
            try (Connection connection = open(effective, optionalDatabase(effective))) {
                if (!connection.isValid(Math.max(1, draft.optInt("connectTimeoutSecs", 10)))) {
                    throw new SQLException("数据库没有通过连接有效性检查");
                }
                return new JSObject().put("message", "手机已直接连接数据库");
            }
        });
    }

    @PluginMethod
    public void listDatabases(PluginCall call) {
        run(call, () -> {
            JSONObject draft = requiredObject(call, "connection");
            JSONObject effective = withStoredSecrets(draft);
            validateDraft(effective);
            return metadata(effective, "databases", "", "", "", "", 500, 0);
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
    public void cancel(PluginCall call) {
        run(call, () -> {
            Statement statement = runningStatements.get(required(call.getString("executionId"), "executionId"));
            if (statement != null) statement.cancel();
            return new JSObject().put("cancelled", statement != null);
        });
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
                    try (ResultSet rows = meta.getSchemas(databaseOrNull(database), null)) {
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
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            url = "jdbc:sqlserver://" + route.host + ":" + route.port + ";databaseName=" + database;
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
        String sslMode = config.optString("sslMode", "verify-full");
        if (config.optBoolean("ssl", false)) {
            if (type.equals("postgres")) properties.setProperty("sslmode", sslMode);
            if (type.equals("mysql")) {
                properties.setProperty("sslMode",
                        sslMode.equals("required") ? "trust"
                                : sslMode.equals("verify-ca") ? "verify-ca" : "verify-full");
            }
            if (type.equals("sqlserver")) {
                properties.setProperty("encrypt", "true");
                properties.setProperty("trustServerCertificate", String.valueOf(sslMode.equals("required")));
            }
        } else {
            if (type.equals("postgres")) properties.setProperty("sslmode", "disable");
            if (type.equals("mysql")) properties.setProperty("sslMode", "disable");
            if (type.equals("sqlserver")) properties.setProperty("encrypt", "false");
        }
        DriverManager.setLoginTimeout(Math.max(1, config.optInt("connectTimeoutSecs", 10)));
        try {
            return DirectTransport.attach(DriverManager.getConnection(url, properties), route);
        } catch (Throwable error) {
            route.close();
            if (error instanceof Exception) throw (Exception) error;
            throw (Error) error;
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
                .put("queryTimeoutSecs", config.optInt("queryTimeoutSecs", 60))
                .put("hasProxy", config.optBoolean("proxyEnabled", false))
                .put("hasCaCertificate", !config.optString("caCertPath").isEmpty());
    }

    private void validateDraft(JSONObject draft) {
        required(draft.optString("name"), "连接名称");
        if (draft.optString("host").trim().isEmpty() && draft.optString("connectionString").trim().isEmpty()) {
            throw new IllegalArgumentException("主机或数据库连接串不能为空");
        }
        if (draft.optInt("port") <= 0) throw new IllegalArgumentException("端口必须大于 0");
        String type = draft.optString("dbType");
        if (!SUPPORTED_DATABASES.contains(type)) {
            throw new IllegalArgumentException("当前直连版本支持 PostgreSQL、MySQL/MariaDB、SQL Server、Redis 和 MongoDB");
        }
        if (type.equals("redis")
                && !"standalone".equals(draft.optString("redisConnectionMode", "standalone"))) {
            throw new IllegalArgumentException("Android 直连当前仅支持 Redis Standalone 模式");
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
            if (!"http".equals(draft.optString("proxyType", "http"))) {
                throw new IllegalArgumentException("Android 直连当前仅支持 HTTP CONNECT 代理");
            }
            required(draft.optString("proxyHost"), "HTTP 代理主机");
            if (draft.optInt("proxyPort") <= 0) throw new IllegalArgumentException("HTTP 代理端口必须大于 0");
        }
        if (draft.optBoolean("sshEnabled", false)) {
            required(draft.optString("sshHost"), "SSH 主机");
            required(draft.optString("sshUsername"), "SSH 用户名");
            if (draft.optInt("sshPort", 22) <= 0) throw new IllegalArgumentException("SSH 端口必须大于 0");
        }
        if (!draft.optString("caCertPath").isEmpty()
                || !draft.optString("clientCertPath").isEmpty()
                || !draft.optString("clientKeyPath").isEmpty()) {
            throw new IllegalArgumentException("Android 直连暂未实现自定义 CA 或客户端证书导入");
        }
        if (!draft.optString("initScript").isEmpty() || !draft.optString("urlParams").isEmpty()) {
            throw new IllegalArgumentException("Android 直连暂未实现初始化脚本和 URL 参数；可改用完整 JDBC 连接串");
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
                "sshPrivateKeyPassphrase", "connectionString", "clientKeyPath",
                "redisSentinelPassword"}) {
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
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}

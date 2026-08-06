package com.coolbanhub.mobiledbmanager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "DirectDatabase")
public class DirectDatabasePlugin extends Plugin {
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final DirectJdbcQueryRunner queryRunner = new DirectJdbcQueryRunner();

    private DirectConnectionStore store() {
        return new DirectConnectionStore(getContext());
    }

    private DirectSshProfileStore sshProfiles() {
        return new DirectSshProfileStore(getContext());
    }

    @PluginMethod
    public void listSshProfiles(PluginCall call) {
        run(call, () -> {
            JSArray result = new JSArray();
            for (JSONObject profile : sshProfiles().all()) result.put(DirectSshProfileStore.summary(profile));
            return result;
        });
    }

    @PluginMethod
    public void getSshProfile(PluginCall call) {
        run(call, () -> {
            JSONObject profile = sshProfiles().get(DirectJson.required(call.getString("id"), "id"));
            if (profile == null) throw new IllegalArgumentException("SSH 配置不存在");
            return DirectSshProfileStore.summary(profile);
        });
    }

    @PluginMethod
    public void saveSshProfile(PluginCall call) {
        run(call, () -> DirectSshProfileStore.summary(
                sshProfiles().save(DirectJson.requiredObject(call, "profile"))));
    }

    @PluginMethod
    public void deleteSshProfile(PluginCall call) {
        run(call, () -> {
            String id = DirectJson.required(call.getString("id"), "id");
            for (JSONObject connection : store().all()) {
                if (id.equals(connection.optString("sshProfileId"))) {
                    throw new IllegalArgumentException("该 SSH 配置正被连接“" + connection.optString("name") + "”使用");
                }
            }
            if (!sshProfiles().remove(id)) throw new IllegalArgumentException("SSH 配置不存在");
            return new JSObject().put("ok", true);
        });
    }

    @PluginMethod
    public void listConnections(PluginCall call) {
        run(call, () -> {
            JSArray result = new JSArray();
            for (JSONObject config : store().all()) result.put(DirectConnectionValidator.summary(config));
            return result;
        });
    }

    @PluginMethod
    public void getConnection(PluginCall call) {
        run(call, () -> {
            JSONObject config = requiredConfig(call.getString("id"));
            JSObject result = DirectConnectionValidator.summary(config);
            result.put("username", config.optString("username"));
            result.put("hasPassword", !config.optString("password").isEmpty());
            result.put("keepaliveIntervalSecs", config.optInt("keepaliveIntervalSecs", 30));
            result.put("proxyEnabled", config.optBoolean("proxyEnabled", false));
            result.put("proxyHost", config.optString("proxyHost"));
            result.put("proxyPort", config.optInt("proxyPort", 8080));
            result.put("proxyUsername", config.optString("proxyUsername"));
            result.put("hasProxyPassword", !config.optString("proxyPassword").isEmpty());
            result.put("sshEnabled", config.optBoolean("sshEnabled", false));
            result.put("sshProfileId", config.optString("sshProfileId"));
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
            JSONObject draft = DirectJson.requiredObject(call, "connection");
            DirectConnectionValidator.validateDraft(effectiveConnection(draft));
            return DirectConnectionValidator.summary(store().save(draft));
        });
    }

    @PluginMethod
    public void deleteConnection(PluginCall call) {
        run(call, () -> {
            if (!store().remove(DirectJson.required(call.getString("id"), "id"))) {
                throw new IllegalArgumentException("连接不存在");
            }
            return new JSObject().put("ok", true);
        });
    }

    @PluginMethod
    public void testConnection(PluginCall call) {
        run(call, () -> {
            JSONObject draft = DirectJson.requiredObject(call, "connection");
            JSONObject effective = effectiveConnection(draft);
            DirectConnectionValidator.validateDraft(effective);
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
            try (Connection connection = DirectJdbcConnectionFactory.open(effective, DirectJson.optionalDatabase(effective))) {
                if (!DirectJdbcConnectionFactory.isValid(
                        connection,
                        type,
                        effective.optInt("connectTimeoutSecs", 10))) {
                    throw new SQLException("数据库没有通过连接有效性检查");
                }
                return new JSObject().put("message", "手机已直接连接数据库");
            }
        });
    }

    @PluginMethod
    public void metadata(PluginCall call) {
        run(call, () -> DirectJdbcMetadata.load(
                requiredConfig(call.getString("connectionId")),
                DirectJson.required(call.getString("kind"), "kind"),
                call.getString("database", ""),
                call.getString("schema", ""),
                call.getString("table", ""),
                call.getString("filter", ""),
                call.getInt("limit", 100),
                call.getInt("offset", 0)));
    }

    @PluginMethod
    public void diagnostics(PluginCall call) {
        run(call, () -> {
            JSONObject config = requiredConfig(call.getString("connectionId"));
            String action = DirectJson.required(call.getString("action"), "action");
            String database = call.getString("database", DirectJson.optionalDatabase(config));
            if ("sessions".equals(action) || "locks".equals(action)) {
                return DirectJdbcDiagnostics.list(config, database, action);
            }
            if (config.optBoolean("readOnly", false)) {
                throw new IllegalArgumentException("此连接已设为只读，不能取消或终止数据库会话");
            }
            if (!call.getBoolean("confirmedAction", false)) {
                throw new IllegalArgumentException("会话操作必须经过明确确认");
            }
            if (config.optBoolean("isProduction", false)
                    && !config.optString("name").equals(call.getString("productionConfirmation", ""))) {
                throw new IllegalArgumentException("生产连接操作前必须输入完整连接名称");
            }
            return DirectJdbcDiagnostics.interrupt(
                    config,
                    database,
                    action,
                    DirectJson.required(call.getString("sessionId"), "sessionId"));
        });
    }

    @PluginMethod
    public void tableTransaction(PluginCall call) {
        run(call, () -> {
            JSONObject config = requiredConfig(call.getString("connectionId"));
            if (config.optBoolean("readOnly", false)) {
                throw new IllegalArgumentException("此连接已设为只读，不能提交表数据事务");
            }
            if (!call.getBoolean("confirmedWrite", false)) {
                throw new IllegalArgumentException("事务提交前必须明确确认全部变更");
            }
            if (config.optBoolean("isProduction", false)
                    && !config.optString("name").equals(call.getString("productionConfirmation", ""))) {
                throw new IllegalArgumentException("生产连接提交前必须输入完整连接名称");
            }
            JSArray changes = call.getArray("changes");
            if (changes == null) throw new IllegalArgumentException("changes不能为空");
            return DirectJdbcTableTransaction.execute(
                    config,
                    call.getString("database", DirectJson.optionalDatabase(config)),
                    call.getString("schema", ""),
                    DirectJson.required(call.getString("table"), "table"),
                    changes);
        });
    }

    @PluginMethod
    public void query(PluginCall call) {
        run(call, () -> {
            JSONObject config = requiredConfig(call.getString("connectionId"));
            String sql = DirectJson.required(call.getString("sql"), "sql").trim();
            boolean readOnly = call.getBoolean("readOnly", true);
            boolean readOnlySql = DirectSqlSafety.isReadOnlySql(sql);
            assertQueryAllowed(
                    config.optBoolean("readOnly", false),
                    config.optBoolean("isProduction", false),
                    config.optString("name"),
                    readOnly,
                    readOnlySql,
                    call.getBoolean("confirmedWrite", false),
                    call.getString("productionConfirmation", ""));
            return queryRunner.execute(
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
            String action = DirectJson.required(call.getString("action"), "action");
            String database = call.getString("database", DirectJson.optionalDatabase(config));
            if (DirectRedisActions.isWriteAction(action)) DirectRedisActions.assertWriteAllowed(config, call);
            return DirectRedisActions.execute(config, database, action, call);
        });
    }

    @PluginMethod
    public void mongo(PluginCall call) {
        run(call, () -> {
            JSONObject config = requiredConfig(call.getString("connectionId"));
            if (!"mongodb".equals(config.optString("dbType"))) {
                throw new IllegalArgumentException("当前连接不是 MongoDB");
            }
            String action = DirectJson.required(call.getString("action"), "action");
            if (DirectMongoActions.isWriteAction(action)) DirectMongoActions.assertWriteAllowed(config, call);
            return DirectMongoActions.execute(config, action, call);
        });
    }

    @PluginMethod
    public void etcd(PluginCall call) {
        run(call, () -> {
            JSONObject config = requiredConfig(call.getString("connectionId"));
            if (!"etcd".equals(config.optString("dbType"))) {
                throw new IllegalArgumentException("当前连接不是 etcd");
            }
            String action = DirectJson.required(call.getString("action"), "action");
            if (DirectEtcdActions.isWriteAction(action)) DirectEtcdActions.assertWriteAllowed(config, call);
            return DirectEtcdActions.execute(config, action, call);
        });
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        run(call, () -> queryRunner.cancel(call.getString("executionId")));
    }

    static boolean isReadOnlySql(String sql) {
        return DirectSqlSafety.isReadOnlySql(sql);
    }

    /**
     * Treat every advanced-mode request as potentially writable. SQL keyword
     * classification is only an additional safe-mode rejection mechanism; it
     * must never decide whether read-only or Production safeguards apply.
     */
    static void assertQueryAllowed(
            boolean connectionReadOnly,
            boolean production,
            String connectionName,
            boolean requestedReadOnly,
            boolean classifiedReadOnly,
            boolean confirmedWrite,
            String productionConfirmation) {
        if (requestedReadOnly) {
            if (!classifiedReadOnly) {
                throw new IllegalArgumentException("只读模式已阻止可能修改数据的语句");
            }
            return;
        }
        if (connectionReadOnly) {
            throw new IllegalArgumentException("此连接已设为只读，不能执行高级 SQL");
        }
        if (!confirmedWrite) {
            throw new IllegalArgumentException("高级 SQL 必须先勾选本次执行确认");
        }
        if (production
                && !connectionName.equals(productionConfirmation == null ? "" : productionConfirmation)) {
            throw new IllegalArgumentException("生产连接执行高级 SQL 前必须输入完整连接名称");
        }
    }

    static void applySecurityProperties(Properties properties, String type, boolean ssl, String sslMode) {
        DirectJdbcConnectionFactory.applySecurityProperties(properties, type, ssl, sslMode);
    }

    private JSONObject requiredConfig(String id) throws Exception {
        JSONObject config = store().get(DirectJson.required(id, "connectionId"));
        if (config == null) throw new IllegalArgumentException("连接不存在");
        return sshProfiles().applyToConnection(config);
    }

    private JSONObject effectiveConnection(JSONObject incoming) throws Exception {
        return sshProfiles().applyToConnection(withStoredSecrets(incoming));
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
                String message = DirectErrors.friendlyMessage(error);
                Exception reportable = error instanceof Exception
                        ? (Exception) error : new RuntimeException(message, error);
                call.reject(message, reportable);
            }
        });
    }
}

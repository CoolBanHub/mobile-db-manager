package com.coolbanhub.mobiledbmanager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/**
 * Fixed, read-only database diagnostics used by the mobile session monitor.
 *
 * <p>The WebView selects an action name but never supplies administration SQL.
 * Keeping vendor SQL here makes privilege failures diagnosable and prevents a
 * compromised WebView from turning the diagnostics bridge into an arbitrary
 * database command endpoint.</p>
 */
final class DirectJdbcDiagnostics {
    private static final int MAX_SESSIONS = 200;

    private DirectJdbcDiagnostics() {}

    static Object list(JSONObject config, String database, String kind) throws Exception {
        String type = relationalType(config);
        try (Connection connection = DirectJdbcConnectionFactory.open(config, database)) {
            connection.setReadOnly(true);
            String sql;
            if ("sessions".equals(kind)) {
                sql = sessionsSql(type);
            } else if ("locks".equals(kind)) {
                boolean mariaDb = "mysql".equals(type)
                        && connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mariadb");
                sql = locksSql(type, mariaDb);
            } else {
                throw new IllegalArgumentException("不支持的诊断动作：" + kind);
            }
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(Math.max(1, config.optInt("queryTimeoutSecs", 60)));
                statement.setMaxRows(MAX_SESSIONS);
                try (ResultSet rows = statement.executeQuery(sql)) {
                    return "sessions".equals(kind) ? readSessions(rows) : readLocks(rows);
                }
            }
        }
    }

    static JSObject interrupt(JSONObject config, String database, String action, String sessionId) throws Exception {
        String type = relationalType(config);
        long numericSessionId = parseSessionId(sessionId);
        if (!"cancel".equals(action) && !"terminate".equals(action)) {
            throw new IllegalArgumentException("不支持的会话操作：" + action);
        }
        if ("sqlserver".equals(type) && "cancel".equals(action)) {
            throw new IllegalArgumentException("SQL Server 不支持仅取消当前 SQL，请使用终止会话");
        }

        try (Connection connection = DirectJdbcConnectionFactory.open(config, database)) {
            boolean affected;
            if ("postgres".equals(type)) {
                String function = "cancel".equals(action) ? "pg_cancel_backend" : "pg_terminate_backend";
                try (PreparedStatement statement = connection.prepareStatement("SELECT " + function + "(?)")) {
                    statement.setLong(1, numericSessionId);
                    statement.setQueryTimeout(Math.max(1, config.optInt("queryTimeoutSecs", 60)));
                    try (ResultSet rows = statement.executeQuery()) {
                        affected = rows.next() && rows.getBoolean(1);
                    }
                }
            } else {
                // MySQL and SQL Server do not accept a bind parameter in KILL.
                // numericSessionId has already been parsed as a positive long.
                String sql;
                if ("mysql".equals(type)) {
                    sql = "cancel".equals(action)
                            ? "KILL QUERY " + numericSessionId
                            : "KILL CONNECTION " + numericSessionId;
                } else {
                    // SQL Server KILL always ends the session; the UI therefore
                    // exposes only the terminate action for this database type.
                    sql = "KILL " + numericSessionId;
                }
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(Math.max(1, config.optInt("queryTimeoutSecs", 60)));
                    statement.execute(sql);
                    affected = true;
                }
            }
            if (!affected) throw new IllegalArgumentException("目标会话不存在、已经结束或当前账号无权操作");
            return new JSObject()
                    .put("ok", true)
                    .put("message", "cancel".equals(action) ? "已请求取消正在执行的 SQL" : "已请求终止数据库会话");
        }
    }

    static String sessionsSql(String type) {
        if ("postgres".equals(type)) {
            return "SELECT CAST(pid AS text) AS session_id, COALESCE(usename, '') AS user_name, "
                    + "COALESCE(datname, '') AS database_name, COALESCE(client_addr::text, 'local') AS client, "
                    + "COALESCE(state, '') AS state, COALESCE(application_name, '') AS command, "
                    + "COALESCE(query, '') AS query_text, CAST(query_start AS text) AS query_started_at, "
                    + "CAST(xact_start AS text) AS transaction_started_at, "
                    + "CASE WHEN query_start IS NULL THEN 0 ELSE CAST(EXTRACT(EPOCH FROM (clock_timestamp() - query_start)) * 1000 AS bigint) END AS duration_ms, "
                    + "CASE WHEN xact_start IS NULL THEN 0 ELSE CAST(EXTRACT(EPOCH FROM (clock_timestamp() - xact_start)) * 1000 AS bigint) END AS transaction_duration_ms, "
                    + "COALESCE(wait_event_type, '') AS wait_type, COALESCE(wait_event, '') AS wait_event "
                    + "FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND datname IS NOT NULL "
                    + "ORDER BY xact_start NULLS LAST, query_start NULLS LAST";
        }
        if ("mysql".equals(type)) {
            return "SELECT CAST(ID AS CHAR) AS session_id, COALESCE(USER, '') AS user_name, "
                    + "COALESCE(DB, '') AS database_name, COALESCE(HOST, '') AS client, "
                    + "COALESCE(STATE, '') AS state, COALESCE(COMMAND, '') AS command, "
                    + "COALESCE(INFO, '') AS query_text, '' AS query_started_at, '' AS transaction_started_at, "
                    + "CAST(COALESCE(TIME, 0) * 1000 AS SIGNED) AS duration_ms, 0 AS transaction_duration_ms, "
                    + "CASE WHEN STATE IS NULL THEN '' ELSE STATE END AS wait_type, '' AS wait_event "
                    + "FROM information_schema.PROCESSLIST WHERE ID <> CONNECTION_ID() ORDER BY TIME DESC";
        }
        return "SELECT CAST(s.session_id AS varchar(20)) AS session_id, COALESCE(s.login_name, '') AS user_name, "
                + "COALESCE(DB_NAME(r.database_id), '') AS database_name, COALESCE(s.host_name, '') AS client, "
                + "COALESCE(r.status, s.status, '') AS state, COALESCE(r.command, '') AS command, "
                + "COALESCE(t.text, '') AS query_text, COALESCE(CONVERT(varchar(33), r.start_time, 126), '') AS query_started_at, "
                + "COALESCE(CONVERT(varchar(33), tx.transaction_begin_time, 126), '') AS transaction_started_at, "
                + "COALESCE(r.total_elapsed_time, 0) AS duration_ms, "
                + "CASE WHEN tx.transaction_begin_time IS NULL THEN 0 ELSE DATEDIFF_BIG(millisecond, tx.transaction_begin_time, GETDATE()) END AS transaction_duration_ms, "
                + "COALESCE(r.wait_type, '') AS wait_type, COALESCE(r.wait_resource, '') AS wait_event "
                + "FROM sys.dm_exec_sessions s LEFT JOIN sys.dm_exec_requests r ON r.session_id = s.session_id "
                + "LEFT JOIN sys.dm_exec_connections c ON c.session_id = s.session_id "
                + "OUTER APPLY sys.dm_exec_sql_text(COALESCE(r.sql_handle, c.most_recent_sql_handle)) t "
                + "OUTER APPLY (SELECT MIN(active_tx.transaction_begin_time) AS transaction_begin_time "
                + "FROM sys.dm_tran_session_transactions session_tx "
                + "JOIN sys.dm_tran_active_transactions active_tx ON active_tx.transaction_id = session_tx.transaction_id "
                + "WHERE session_tx.session_id = s.session_id) tx "
                + "WHERE s.is_user_process = 1 AND s.session_id <> @@SPID "
                + "ORDER BY COALESCE(r.total_elapsed_time, 0) DESC, s.session_id";
    }

    static String locksSql(String type, boolean mariaDb) {
        if ("postgres".equals(type)) {
            return "SELECT CAST(waiting.pid AS text) AS waiting_session_id, CAST(blocking.pid AS text) AS blocking_session_id, "
                    + "COALESCE(waiting.datname, '') AS database_name, COALESCE(waiting.wait_event, '') AS object_name, "
                    + "COALESCE(waiting.wait_event_type, 'Lock') AS wait_type, "
                    + "CASE WHEN waiting.query_start IS NULL THEN 0 ELSE CAST(EXTRACT(EPOCH FROM (clock_timestamp() - waiting.query_start)) * 1000 AS bigint) END AS duration_ms, "
                    + "COALESCE(waiting.query, '') AS waiting_query, COALESCE(blocking.query, '') AS blocking_query "
                    + "FROM pg_stat_activity waiting CROSS JOIN LATERAL unnest(pg_blocking_pids(waiting.pid)) AS blocker(pid) "
                    + "JOIN pg_stat_activity blocking ON blocking.pid = blocker.pid ORDER BY duration_ms DESC";
        }
        if ("mysql".equals(type) && mariaDb) {
            return "SELECT CAST(requesting.trx_mysql_thread_id AS CHAR) AS waiting_session_id, "
                    + "CAST(blocking.trx_mysql_thread_id AS CHAR) AS blocking_session_id, "
                    + "'' AS database_name, COALESCE(w.requested_lock_id, '') AS object_name, "
                    + "'InnoDB lock' AS wait_type, CAST(TIMESTAMPDIFF(MICROSECOND, requesting.trx_wait_started, NOW()) / 1000 AS SIGNED) AS duration_ms, "
                    + "COALESCE(requesting.trx_query, '') AS waiting_query, COALESCE(blocking.trx_query, '') AS blocking_query "
                    + "FROM information_schema.INNODB_LOCK_WAITS w "
                    + "JOIN information_schema.INNODB_TRX requesting ON requesting.trx_id = w.requesting_trx_id "
                    + "JOIN information_schema.INNODB_TRX blocking ON blocking.trx_id = w.blocking_trx_id "
                    + "ORDER BY duration_ms DESC";
        }
        if ("mysql".equals(type)) {
            return "SELECT CAST(requesting_thread.PROCESSLIST_ID AS CHAR) AS waiting_session_id, "
                    + "CAST(blocking_thread.PROCESSLIST_ID AS CHAR) AS blocking_session_id, "
                    + "COALESCE(requesting_lock.OBJECT_SCHEMA, '') AS database_name, "
                    + "COALESCE(CONCAT(requesting_lock.OBJECT_SCHEMA, '.', requesting_lock.OBJECT_NAME), '') AS object_name, "
                    + "COALESCE(requesting_lock.LOCK_TYPE, 'InnoDB lock') AS wait_type, "
                    + "CAST(COALESCE(requesting_thread.PROCESSLIST_TIME, 0) * 1000 AS SIGNED) AS duration_ms, "
                    + "COALESCE(requesting_sql.SQL_TEXT, requesting_thread.PROCESSLIST_INFO, '') AS waiting_query, "
                    + "COALESCE(blocking_sql.SQL_TEXT, blocking_thread.PROCESSLIST_INFO, '') AS blocking_query "
                    + "FROM performance_schema.data_lock_waits w "
                    + "JOIN performance_schema.data_locks requesting_lock ON requesting_lock.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
                    + "JOIN performance_schema.data_locks blocking_lock ON blocking_lock.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID "
                    + "LEFT JOIN performance_schema.threads requesting_thread ON requesting_thread.THREAD_ID = requesting_lock.THREAD_ID "
                    + "LEFT JOIN performance_schema.threads blocking_thread ON blocking_thread.THREAD_ID = blocking_lock.THREAD_ID "
                    + "LEFT JOIN performance_schema.events_statements_current requesting_sql ON requesting_sql.THREAD_ID = requesting_thread.THREAD_ID "
                    + "LEFT JOIN performance_schema.events_statements_current blocking_sql ON blocking_sql.THREAD_ID = blocking_thread.THREAD_ID";
        }
        return "SELECT CAST(waiting.session_id AS varchar(20)) AS waiting_session_id, "
                + "CAST(waiting.blocking_session_id AS varchar(20)) AS blocking_session_id, "
                + "COALESCE(DB_NAME(waiting.database_id), '') AS database_name, COALESCE(waiting.wait_resource, '') AS object_name, "
                + "COALESCE(waiting.wait_type, 'LOCK') AS wait_type, COALESCE(waiting.wait_time, 0) AS duration_ms, "
                + "COALESCE(waiting_text.text, '') AS waiting_query, COALESCE(blocking_text.text, '') AS blocking_query "
                + "FROM sys.dm_exec_requests waiting "
                + "LEFT JOIN sys.dm_exec_requests blocking ON blocking.session_id = waiting.blocking_session_id "
                + "LEFT JOIN sys.dm_exec_connections blocking_connection ON blocking_connection.session_id = waiting.blocking_session_id "
                + "OUTER APPLY sys.dm_exec_sql_text(waiting.sql_handle) waiting_text "
                + "OUTER APPLY sys.dm_exec_sql_text(COALESCE(blocking.sql_handle, blocking_connection.most_recent_sql_handle)) blocking_text "
                + "WHERE waiting.blocking_session_id > 0 ORDER BY waiting.wait_time DESC";
    }

    static long parseSessionId(String value) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value.trim());
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("会话 ID 必须是正整数");
        }
    }

    private static String relationalType(JSONObject config) {
        String type = config.optString("dbType");
        if (!"postgres".equals(type) && !"mysql".equals(type) && !"sqlserver".equals(type)) {
            throw new IllegalArgumentException("会话诊断仅支持 PostgreSQL、MySQL/MariaDB 和 SQL Server");
        }
        return type;
    }

    private static JSArray readSessions(ResultSet rows) throws Exception {
        JSArray result = new JSArray();
        while (rows.next()) {
            result.put(new JSObject()
                    .put("sessionId", text(rows, "session_id"))
                    .put("user", text(rows, "user_name"))
                    .put("database", text(rows, "database_name"))
                    .put("client", text(rows, "client"))
                    .put("state", text(rows, "state"))
                    .put("command", text(rows, "command"))
                    .put("query", text(rows, "query_text"))
                    .put("queryStartedAt", text(rows, "query_started_at"))
                    .put("transactionStartedAt", text(rows, "transaction_started_at"))
                    .put("durationMs", number(rows, "duration_ms"))
                    .put("transactionDurationMs", number(rows, "transaction_duration_ms"))
                    .put("waitType", text(rows, "wait_type"))
                    .put("waitEvent", text(rows, "wait_event")));
        }
        return result;
    }

    private static JSArray readLocks(ResultSet rows) throws Exception {
        JSArray result = new JSArray();
        while (rows.next()) {
            result.put(new JSObject()
                    .put("waitingSessionId", text(rows, "waiting_session_id"))
                    .put("blockingSessionId", text(rows, "blocking_session_id"))
                    .put("database", text(rows, "database_name"))
                    .put("objectName", text(rows, "object_name"))
                    .put("waitType", text(rows, "wait_type"))
                    .put("durationMs", number(rows, "duration_ms"))
                    .put("waitingQuery", text(rows, "waiting_query"))
                    .put("blockingQuery", text(rows, "blocking_query")));
        }
        return result;
    }

    private static String text(ResultSet rows, String column) throws Exception {
        String value = rows.getString(column);
        return value == null ? "" : value;
    }

    private static long number(ResultSet rows, String column) throws Exception {
        long value = rows.getLong(column);
        return rows.wasNull() ? 0 : Math.max(0, value);
    }
}

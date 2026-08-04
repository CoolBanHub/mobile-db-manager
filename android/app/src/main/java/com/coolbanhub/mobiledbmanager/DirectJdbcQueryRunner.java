package com.coolbanhub.mobiledbmanager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class DirectJdbcQueryRunner {
    private static final int MAX_ROWS = 501;

    private final Map<String, Statement> runningStatements = new ConcurrentHashMap<>();

    JSObject execute(JSONObject config, String database, String schema, String sql,
                     String executionId, int offset, int pageSize, boolean readOnly) throws Exception {
        long started = System.nanoTime();
        try (Connection connection = DirectJdbcConnectionFactory.open(config, database)) {
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

    JSObject cancel(String executionId) throws Exception {
        Statement statement = runningStatements.get(DirectJson.required(executionId, "executionId"));
        if (statement != null) statement.cancel();
        return new JSObject().put("cancelled", statement != null);
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
}

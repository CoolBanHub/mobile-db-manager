package com.coolbanhub.mobiledbmanager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Executes structured table mutations as one JDBC transaction. */
final class DirectJdbcTableTransaction {
    private static final int MAX_CHANGES = 100;

    private DirectJdbcTableTransaction() {}

    static JSObject execute(JSONObject config, String database, String schema, String table, JSArray changes)
            throws Exception {
        if (changes.length() < 1) throw new IllegalArgumentException("没有待提交的表数据变更");
        if (changes.length() > MAX_CHANGES) throw new IllegalArgumentException("一次最多提交 100 项表数据变更");
        String type = config.optString("dbType");
        if (!"postgres".equals(type) && !"mysql".equals(type) && !"sqlserver".equals(type)) {
            throw new IllegalArgumentException("安全事务编辑仅支持 PostgreSQL、MySQL/MariaDB 和 SQL Server");
        }

        try (Connection connection = DirectJdbcConnectionFactory.open(config, database)) {
            TableDefinition definition = loadDefinition(connection.getMetaData(), database, schema, table);
            connection.setReadOnly(false);
            connection.setAutoCommit(false);
            int affectedRows = 0;
            try {
                for (int index = 0; index < changes.length(); index++) {
                    JSONObject change = changes.optJSONObject(index);
                    if (change == null) throw new IllegalArgumentException("第 " + (index + 1) + " 项变更格式无效");
                    int affected = executeChange(connection, type, definition, change);
                    if (affected != 1) {
                        throw new SQLException("第 " + (index + 1) + " 项变更影响了 " + affected
                                + " 行；目标行可能已变化，事务已回滚");
                    }
                    affectedRows += affected;
                }
                connection.commit();
                return new JSObject()
                        .put("committed", true)
                        .put("operationCount", changes.length())
                        .put("affectedRows", affectedRows);
            } catch (Throwable error) {
                try {
                    connection.rollback();
                } catch (Throwable rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                if (error instanceof Exception) throw (Exception) error;
                throw (Error) error;
            }
        }
    }

    private static int executeChange(Connection connection, String type, TableDefinition table, JSONObject change)
            throws Exception {
        String kind = change.optString("kind");
        if ("insert".equals(kind)) return insert(connection, type, table, requiredValues(change, "values"));
        if ("update".equals(kind)) {
            return update(
                    connection,
                    type,
                    table,
                    requiredValues(change, "values"),
                    requiredPrimaryKey(change, table));
        }
        if ("delete".equals(kind)) return delete(connection, type, table, requiredPrimaryKey(change, table));
        throw new IllegalArgumentException("不支持的事务变更类型：" + kind);
    }

    private static int insert(Connection connection, String type, TableDefinition table, JSONObject values)
            throws Exception {
        List<String> columns = checkedColumns(values, table, false);
        String sql;
        if (columns.isEmpty()) {
            sql = "mysql".equals(type)
                    ? "INSERT INTO " + table.qualifiedName + " () VALUES ()"
                    : "INSERT INTO " + table.qualifiedName + " DEFAULT VALUES";
        } else {
            sql = "INSERT INTO " + table.qualifiedName + " (" + quoted(columns, type) + ") VALUES ("
                    + placeholders(columns.size()) + ")";
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindObject(statement, 1, values, columns, table);
            return statement.executeUpdate();
        }
    }

    private static int update(Connection connection, String type, TableDefinition table,
                              JSONObject values, JSONObject primaryKey) throws Exception {
        List<String> columns = checkedColumns(values, table, true);
        if (columns.isEmpty()) throw new IllegalArgumentException("更新项没有字段变更");
        List<String> keys = new ArrayList<>(table.primaryKeys);
        StringBuilder sql = new StringBuilder("UPDATE ").append(table.qualifiedName).append(" SET ");
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) sql.append(", ");
            sql.append(quote(columns.get(index), type)).append(" = ?");
        }
        sql.append(" WHERE ").append(keyPredicates(keys, type));
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int next = bindObject(statement, 1, values, columns, table);
            bindObject(statement, next, primaryKey, keys, table);
            return statement.executeUpdate();
        }
    }

    private static int delete(Connection connection, String type, TableDefinition table, JSONObject primaryKey)
            throws Exception {
        List<String> keys = new ArrayList<>(table.primaryKeys);
        String sql = "DELETE FROM " + table.qualifiedName + " WHERE " + keyPredicates(keys, type);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindObject(statement, 1, primaryKey, keys, table);
            return statement.executeUpdate();
        }
    }

    private static JSONObject requiredValues(JSONObject change, String name) {
        JSONObject values = change.optJSONObject(name);
        if (values == null) throw new IllegalArgumentException(name + "不能为空");
        return values;
    }

    private static JSONObject requiredPrimaryKey(JSONObject change, TableDefinition table) {
        if (table.primaryKeys.isEmpty()) throw new IllegalArgumentException("表没有主键，不能安全定位目标行");
        JSONObject values = requiredValues(change, "primaryKey");
        for (String key : table.primaryKeys) {
            if (!values.has(key)) throw new IllegalArgumentException("缺少主键字段：" + key);
        }
        if (values.length() != table.primaryKeys.size()) throw new IllegalArgumentException("主键字段与数据库定义不匹配");
        return values;
    }

    private static List<String> checkedColumns(JSONObject values, TableDefinition table, boolean rejectPrimaryKeys) {
        List<String> result = new ArrayList<>();
        JSONArray names = values.names();
        if (names == null) return result;
        for (int index = 0; index < names.length(); index++) {
            String name = names.optString(index);
            if (!table.columns.containsKey(name)) throw new IllegalArgumentException("字段不存在：" + name);
            if (rejectPrimaryKeys && table.primaryKeys.contains(name)) {
                throw new IllegalArgumentException("安全事务模式不允许修改主键字段：" + name);
            }
            result.add(name);
        }
        return result;
    }

    private static int bindObject(PreparedStatement statement, int start, JSONObject values,
                                  List<String> columns, TableDefinition table) throws Exception {
        int parameter = start;
        for (String column : columns) {
            Object value = values.isNull(column) ? null : values.get(column);
            ColumnDefinition definition = table.columns.get(column);
            if (value == null) {
                statement.setNull(parameter++, definition.jdbcType);
            } else {
                statement.setObject(parameter++, jdbcValue(value, definition.jdbcType), definition.jdbcType);
            }
        }
        return parameter;
    }

    static Object jdbcValue(Object value, int jdbcType) {
        if (!(value instanceof String)) return value;
        String text = (String) value;
        try {
            switch (jdbcType) {
                case Types.TINYINT:
                case Types.SMALLINT:
                case Types.INTEGER:
                case Types.BIGINT:
                case Types.NUMERIC:
                case Types.DECIMAL:
                    return new BigDecimal(text.trim());
                case Types.FLOAT:
                case Types.REAL:
                case Types.DOUBLE:
                    return Double.parseDouble(text.trim());
                case Types.BOOLEAN:
                case Types.BIT:
                    String normalized = text.trim().toLowerCase(Locale.ROOT);
                    if ("true".equals(normalized) || "1".equals(normalized)) return true;
                    if ("false".equals(normalized) || "0".equals(normalized)) return false;
                    throw new IllegalArgumentException("布尔值必须是 true、false、1 或 0");
                default:
                    return text;
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("字段值与数据库数值类型不匹配：" + text);
        }
    }

    private static TableDefinition loadDefinition(DatabaseMetaData metadata, String database, String schema, String table)
            throws Exception {
        Map<String, ColumnDefinition> columns = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getColumns(
                DirectJson.databaseOrNull(database), DirectJson.emptyToNull(schema), table, "%")) {
            while (rows.next()) {
                String name = rows.getString("COLUMN_NAME");
                columns.put(name, new ColumnDefinition(rows.getInt("DATA_TYPE")));
            }
        }
        if (columns.isEmpty()) throw new IllegalArgumentException("表不存在或没有可见字段");
        Set<String> primaryKeys = new LinkedHashSet<>();
        try (ResultSet rows = metadata.getPrimaryKeys(
                DirectJson.databaseOrNull(database), DirectJson.emptyToNull(schema), table)) {
            while (rows.next()) primaryKeys.add(rows.getString("COLUMN_NAME"));
        }
        String type = databaseType(metadata);
        String qualifiedName = schema == null || schema.isEmpty()
                ? quote(table, type)
                : quote(schema, type) + "." + quote(table, type);
        return new TableDefinition(qualifiedName, columns, primaryKeys);
    }

    private static String databaseType(DatabaseMetaData metadata) throws SQLException {
        String product = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (product.contains("mysql") || product.contains("mariadb")) return "mysql";
        if (product.contains("microsoft") || product.contains("sql server")) return "sqlserver";
        return "postgres";
    }

    static String quote(String identifier, String type) {
        if (identifier == null || identifier.isEmpty() || identifier.length() > 256 || identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("数据库对象名称无效");
        }
        if ("mysql".equals(type)) return "`" + identifier.replace("`", "``") + "`";
        if ("sqlserver".equals(type)) return "[" + identifier.replace("]", "]]") + "]";
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    static String placeholders(int count) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < count; index++) values.add("?");
        return String.join(", ", values);
    }

    private static String quoted(List<String> columns, String type) {
        List<String> values = new ArrayList<>();
        for (String column : columns) values.add(quote(column, type));
        return String.join(", ", values);
    }

    private static String keyPredicates(List<String> keys, String type) {
        List<String> predicates = new ArrayList<>();
        for (String key : keys) predicates.add(quote(key, type) + " = ?");
        return String.join(" AND ", predicates);
    }

    private static final class ColumnDefinition {
        final int jdbcType;

        ColumnDefinition(int jdbcType) {
            this.jdbcType = jdbcType;
        }
    }

    private static final class TableDefinition {
        final String qualifiedName;
        final Map<String, ColumnDefinition> columns;
        final Set<String> primaryKeys;

        TableDefinition(String qualifiedName, Map<String, ColumnDefinition> columns, Set<String> primaryKeys) {
            this.qualifiedName = qualifiedName;
            this.columns = columns;
            this.primaryKeys = primaryKeys;
        }
    }
}

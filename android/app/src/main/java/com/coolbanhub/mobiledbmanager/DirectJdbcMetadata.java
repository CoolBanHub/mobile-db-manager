package com.coolbanhub.mobiledbmanager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class DirectJdbcMetadata {
    private DirectJdbcMetadata() {}

    static Object load(JSONObject config, String kind, String database, String schema, String table,
                       String filter, int limit, int offset) throws Exception {
        try (Connection connection = DirectJdbcConnectionFactory.open(config, database)) {
            DatabaseMetaData meta = connection.getMetaData();
            JSArray values = new JSArray();
            switch (kind) {
                case "databases":
                    try (ResultSet rows = meta.getCatalogs()) {
                        while (rows.next()) values.put(new JSObject().put("name", rows.getString(1)));
                    }
                    if (values.length() == 0) {
                        String current = connection.getCatalog();
                        values.put(new JSObject().put(
                                "name",
                                current == null || current.isEmpty()
                                        ? config.optString("database", "default") : current));
                    }
                    return values;
                case "schemas":
                    try (ResultSet rows = getSchemasCompatible(meta, DirectJson.databaseOrNull(database))) {
                        while (rows.next()) values.put(rows.getString("TABLE_SCHEM"));
                    }
                    return values;
                case "tables":
                    int skipped = 0;
                    try (ResultSet rows = meta.getTables(
                            DirectJson.databaseOrNull(database),
                            DirectJson.emptyToNull(schema),
                            filter.isEmpty() ? "%" : "%" + filter + "%",
                            new String[]{"TABLE", "VIEW"})) {
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
                    try (ResultSet keys = meta.getPrimaryKeys(
                            DirectJson.databaseOrNull(database),
                            DirectJson.emptyToNull(schema),
                            table)) {
                        while (keys.next()) primaryKeys.add(keys.getString("COLUMN_NAME"));
                    }
                    try (ResultSet rows = meta.getColumns(
                            DirectJson.databaseOrNull(database),
                            DirectJson.emptyToNull(schema),
                            table,
                            "%")) {
                        while (rows.next()) {
                            String name = rows.getString("COLUMN_NAME");
                            values.put(new JSObject()
                                    .put("name", name)
                                    .put("data_type", rows.getString("TYPE_NAME"))
                                    .put("is_nullable", rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls)
                                    .put("column_default", DirectJson.nullable(rows.getString("COLUMN_DEF")))
                                    .put("is_primary_key", primaryKeys.contains(name))
                                    .put("extra", DirectJson.nullable(rows.getString("IS_AUTOINCREMENT")))
                                    .put("comment", DirectJson.nullable(rows.getString("REMARKS"))));
                        }
                    }
                    return values;
                case "indexes":
                    try (ResultSet rows = meta.getIndexInfo(
                            DirectJson.databaseOrNull(database),
                            DirectJson.emptyToNull(schema),
                            table,
                            false,
                            false)) {
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
                    try (ResultSet rows = meta.getImportedKeys(
                            DirectJson.databaseOrNull(database),
                            DirectJson.emptyToNull(schema),
                            table)) {
                        while (rows.next()) values.put(new JSObject()
                                .put("name", rows.getString("FK_NAME"))
                                .put("column", rows.getString("FKCOLUMN_NAME"))
                                .put("ref_schema", DirectJson.nullable(rows.getString("PKTABLE_SCHEM")))
                                .put("ref_table", rows.getString("PKTABLE_NAME"))
                                .put("ref_column", rows.getString("PKCOLUMN_NAME")));
                    }
                    return values;
                case "objects":
                    try (ResultSet rows = meta.getProcedures(
                            DirectJson.databaseOrNull(database),
                            DirectJson.emptyToNull(schema),
                            "%")) {
                        while (rows.next()) values.put(new JSObject()
                                .put("name", rows.getString("PROCEDURE_NAME"))
                                .put("object_type", "PROCEDURE")
                                .put("schema", DirectJson.nullable(rows.getString("PROCEDURE_SCHEM"))));
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
}

package com.coolbanhub.mobiledbmanager;

import static org.junit.Assert.assertEquals;

import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

@RunWith(AndroidJUnit4.class)
public class SqlServerInstrumentedTest {
    @Test
    public void connectsAndQueriesWithSslDisabled() throws Exception {
        connectAndQuery(false, "required");
    }

    @Test
    public void connectsAndQueriesWithRequiredSsl() throws Exception {
        connectAndQuery(true, "required");
    }

    @Test
    public void loadsSqlServerSchemaMetadata() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String password = arguments.getString("sqlPassword", "");
        Assume.assumeTrue("sqlPassword instrumentation argument is required", !password.isEmpty());

        Class.forName("net.sourceforge.jtds.jdbc.Driver");
        Properties properties = connectionProperties(arguments, password, false, "required");
        try (Connection connection = DriverManager.getConnection(jdbcUrl(arguments), properties)) {
            DatabaseMetaData metadata = connection.getMetaData();
            boolean foundSchema = false;
            try (ResultSet schemas = DirectDatabasePlugin.getSchemasCompatible(metadata, "master")) {
                while (schemas.next()) {
                    foundSchema = true;
                    String schema = schemas.getString("TABLE_SCHEM");
                    try (ResultSet tables = metadata.getTables("master", schema, "%", new String[]{"TABLE", "VIEW"})) {
                        if (tables.next()) {
                            String table = tables.getString("TABLE_NAME");
                            try (ResultSet columns = metadata.getColumns("master", schema, table, "%")) {
                                columns.next();
                            }
                        }
                    }
                }
            }
            org.junit.Assert.assertTrue(foundSchema);
        }
    }

    private void connectAndQuery(boolean ssl, String sslMode) throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String password = arguments.getString("sqlPassword", "");
        // 未显式提供临时容器密码时跳过，避免在仓库或测试包中硬编码秘密。
        Assume.assumeTrue("sqlPassword instrumentation argument is required", !password.isEmpty());

        Class.forName("net.sourceforge.jtds.jdbc.Driver");
        Properties properties = connectionProperties(arguments, password, ssl, sslMode);

        try (Connection connection = DriverManager.getConnection(jdbcUrl(arguments), properties);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            org.junit.Assert.assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    private Properties connectionProperties(
            Bundle arguments, String password, boolean ssl, String sslMode) {
        Properties properties = new Properties();
        properties.setProperty("user", arguments.getString("sqlUser", "sa"));
        properties.setProperty("password", password);
        properties.setProperty("loginTimeout", "15");
        DirectDatabasePlugin.applySecurityProperties(properties, "sqlserver", ssl, sslMode);
        return properties;
    }

    private String jdbcUrl(Bundle arguments) {
        String host = arguments.getString("sqlHost", "10.0.2.2");
        String port = arguments.getString("sqlPort", "11434");
        return "jdbc:jtds:sqlserver://" + host + ":" + port + "/master";
    }
}

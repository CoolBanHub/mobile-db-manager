package com.coolbanhub.mobiledbmanager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class DirectDatabasePluginTest {
    @Test
    public void validatesReusableSshPasswordProfiles() {
        DirectSshProfileStore.validateFields(
                "开发跳板机", "bastion.example.com", 22, "deploy",
                "password", "secret", "");
    }

    @Test
    public void rejectsReusableSshProfilesWithoutRequiredCredential() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> DirectSshProfileStore.validateFields(
                        "生产跳板机", "bastion.example.com", 22, "deploy",
                        "private-key", "", ""));
        assertTrue(error.getMessage().contains("私钥"));
    }

    @Test
    public void acceptsSingleReadOnlyStatements() {
        assertTrue(DirectDatabasePlugin.isReadOnlySql("SELECT * FROM users"));
        assertTrue(DirectDatabasePlugin.isReadOnlySql("-- comment\nWITH x AS (SELECT 1) SELECT * FROM x;"));
        assertTrue(DirectDatabasePlugin.isReadOnlySql("EXPLAIN SELECT * FROM users"));
    }

    @Test
    public void blocksWritesAndStatementStacking() {
        assertFalse(DirectDatabasePlugin.isReadOnlySql("UPDATE users SET admin = true"));
        assertFalse(DirectDatabasePlugin.isReadOnlySql("SELECT 1; DELETE FROM users"));
        assertFalse(DirectDatabasePlugin.isReadOnlySql("WITH x AS (DELETE FROM users RETURNING *) SELECT * FROM x"));
        assertFalse(DirectDatabasePlugin.isReadOnlySql("/* hidden */ DROP TABLE users"));
    }

    @Test
    public void advancedSqlAlwaysRequiresWritableConnectionAndExplicitConfirmation() {
        // SELECT-like statements can have side effects, so their classification
        // must not bypass the advanced-mode safety gate.
        assertTrue(DirectDatabasePlugin.isReadOnlySql("SELECT nextval('order_seq')"));
        assertTrue(DirectDatabasePlugin.isReadOnlySql("SELECT * INTO users_backup FROM users"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DirectDatabasePlugin.assertQueryAllowed(false, false, "开发库", false, true, false, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> DirectDatabasePlugin.assertQueryAllowed(true, false, "只读库", false, true, true, ""));

        DirectDatabasePlugin.assertQueryAllowed(false, false, "开发库", false, true, true, "");
    }

    @Test
    public void advancedSqlRequiresExactProductionConnectionNameEvenForSelect() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DirectDatabasePlugin.assertQueryAllowed(false, true, "核心生产库", false, true, true, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> DirectDatabasePlugin.assertQueryAllowed(false, true, "核心生产库", false, true, true, "核心生产"));
        DirectDatabasePlugin.assertQueryAllowed(false, true, "核心生产库", false, true, true, "核心生产库");
    }

    @Test
    public void encodesRedisCommandsUsingUtf8ByteLengths() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Object reply = DirectRedisConnection.command(
                new ByteArrayInputStream("+OK\r\n".getBytes(StandardCharsets.UTF_8)),
                output,
                "AUTH",
                "密码");

        assertEquals("OK", reply);
        assertEquals(
                "*2\r\n$4\r\nAUTH\r\n$6\r\n密码\r\n",
                output.toString(StandardCharsets.UTF_8.name()));
    }

    @Test
    public void surfacesRedisErrorReplies() {
        IOException error = assertThrows(IOException.class, () ->
                DirectRedisConnection.command(
                        new ByteArrayInputStream("-NOAUTH Authentication required\r\n".getBytes(StandardCharsets.UTF_8)),
                        new ByteArrayOutputStream(),
                        "PING"));
        assertTrue(error.getMessage().contains("NOAUTH"));
    }

    @Test
    public void parsesNestedRedisArraysUsedByScanAndCollections() throws Exception {
        Object reply = DirectRedisConnection.command(
                new ByteArrayInputStream(
                        "*2\r\n$2\r\n17\r\n*2\r\n$8\r\n用户:1\r\n$8\r\n用户:2\r\n"
                                .getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(),
                "SCAN",
                "0");

        List<?> page = (List<?>) reply;
        assertEquals("17", page.get(0));
        assertEquals(Arrays.asList("用户:1", "用户:2"), page.get(1));
    }

    @Test
    public void parsesNullRedisBulkValues() throws Exception {
        Object reply = DirectRedisConnection.command(
                new ByteArrayInputStream("$-1\r\n".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(),
                "GET",
                "missing");

        assertEquals(null, reply);
    }

    @Test
    public void allowsMysqlEightAuthenticationWhenSslIsDisabled() {
        Properties properties = new Properties();

        DirectDatabasePlugin.applySecurityProperties(properties, "mysql", false, "verify-full");

        assertEquals("disable", properties.getProperty("sslMode"));
        assertEquals("true", properties.getProperty("allowPublicKeyRetrieval"));
    }

    @Test
    public void keepsMysqlPublicKeyRetrievalDisabledWhenSslIsEnabled() {
        Properties properties = new Properties();

        DirectDatabasePlugin.applySecurityProperties(properties, "mysql", true, "required");

        assertEquals("trust", properties.getProperty("sslMode"));
        assertFalse(properties.containsKey("allowPublicKeyRetrieval"));
    }

    @Test
    public void disablesSqlServerSslWhenToggleIsOff() {
        Properties properties = new Properties();

        DirectDatabasePlugin.applySecurityProperties(properties, "sqlserver", false, "verify-full");

        assertEquals("off", properties.getProperty("ssl"));
    }

    @Test
    public void requiresSqlServerEncryptionWithoutCertificateValidation() {
        Properties properties = new Properties();

        DirectDatabasePlugin.applySecurityProperties(properties, "sqlserver", true, "required");

        assertEquals("require", properties.getProperty("ssl"));
    }

    @Test
    public void authenticatesSqlServerCertificateWhenRequested() {
        Properties properties = new Properties();

        DirectDatabasePlugin.applySecurityProperties(properties, "sqlserver", true, "verify-full");

        assertEquals("authenticate", properties.getProperty("ssl"));
    }

    @Test
    public void validatesJtdsSqlServerConnectionsWithAQuery() throws Exception {
        AtomicBoolean isValidCalled = new AtomicBoolean(false);
        AtomicBoolean queryCalled = new AtomicBoolean(false);
        ResultSet rows = (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("next")) return true;
                    if (method.getName().equals("getInt")) return 1;
                    return null;
                });
        Statement statement = (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setQueryTimeout")) {
                        assertEquals(7, args[0]);
                        return null;
                    }
                    if (method.getName().equals("executeQuery")) {
                        assertEquals("SELECT 1", args[0]);
                        queryCalled.set(true);
                        return rows;
                    }
                    return null;
                });
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isValid")) {
                        isValidCalled.set(true);
                        throw new AbstractMethodError();
                    }
                    if (method.getName().equals("createStatement")) return statement;
                    return null;
                });

        assertTrue(DirectJdbcConnectionFactory.isValid(connection, "sqlserver", 7));
        assertFalse(isValidCalled.get());
        assertTrue(queryCalled.get());
    }

    @Test
    public void keepsStandardJdbcValidationForPostgres() throws Exception {
        AtomicBoolean isValidCalled = new AtomicBoolean(false);
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isValid")) {
                        isValidCalled.set(true);
                        assertEquals(5, args[0]);
                        return true;
                    }
                    if (method.getName().equals("createStatement")) {
                        throw new AssertionError("PostgreSQL should use Connection.isValid");
                    }
                    return null;
                });

        assertTrue(DirectJdbcConnectionFactory.isValid(connection, "postgres", 5));
        assertTrue(isValidCalled.get());
    }

    @Test
    public void diagnosticsUseVendorSpecificFixedQueries() {
        assertTrue(DirectJdbcDiagnostics.sessionsSql("postgres").contains("pg_stat_activity"));
        assertTrue(DirectJdbcDiagnostics.sessionsSql("mysql").contains("information_schema.PROCESSLIST"));
        assertTrue(DirectJdbcDiagnostics.sessionsSql("sqlserver").contains("sys.dm_exec_sessions"));
        assertTrue(DirectJdbcDiagnostics.locksSql("postgres", false).contains("pg_blocking_pids"));
        assertTrue(DirectJdbcDiagnostics.locksSql("mysql", true).contains("INNODB_LOCK_WAITS"));
        assertTrue(DirectJdbcDiagnostics.locksSql("mysql", false).contains("performance_schema.data_lock_waits"));
        assertTrue(DirectJdbcDiagnostics.locksSql("sqlserver", false).contains("blocking_session_id"));
    }

    @Test
    public void diagnosticsOnlyAcceptPositiveNumericSessionIds() {
        assertEquals(42L, DirectJdbcDiagnostics.parseSessionId("42"));
        assertThrows(IllegalArgumentException.class, () -> DirectJdbcDiagnostics.parseSessionId("0"));
        assertThrows(IllegalArgumentException.class, () -> DirectJdbcDiagnostics.parseSessionId("1; DROP TABLE users"));
    }

    @Test
    public void tableTransactionsConvertTypedFormValuesBeforeBinding() {
        assertEquals(new BigDecimal("42"), DirectJdbcTableTransaction.jdbcValue("42", Types.INTEGER));
        assertEquals(new BigDecimal("12.50"), DirectJdbcTableTransaction.jdbcValue("12.50", Types.DECIMAL));
        assertEquals(true, DirectJdbcTableTransaction.jdbcValue("1", Types.BOOLEAN));
        assertEquals(false, DirectJdbcTableTransaction.jdbcValue("false", Types.BIT));
        assertEquals("2026-08-05", DirectJdbcTableTransaction.jdbcValue("2026-08-05", Types.DATE));
        assertThrows(
                IllegalArgumentException.class,
                () -> DirectJdbcTableTransaction.jdbcValue("not-a-number", Types.INTEGER));
        assertThrows(
                IllegalArgumentException.class,
                () -> DirectJdbcTableTransaction.jdbcValue("yes", Types.BOOLEAN));
    }

    @Test
    public void tableTransactionsUsePlaceholdersAndEscapeIdentifiers() {
        assertEquals("?, ?, ?", DirectJdbcTableTransaction.placeholders(3));
        assertEquals("\"users\"\"archive\"", DirectJdbcTableTransaction.quote("users\"archive", "postgres"));
        assertEquals("`users``archive`", DirectJdbcTableTransaction.quote("users`archive", "mysql"));
        assertEquals("[users]]archive]", DirectJdbcTableTransaction.quote("users]archive", "sqlserver"));
        assertThrows(IllegalArgumentException.class, () -> DirectJdbcTableTransaction.quote("", "postgres"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DirectJdbcTableTransaction.quote("users\0archive", "postgres"));
    }
}

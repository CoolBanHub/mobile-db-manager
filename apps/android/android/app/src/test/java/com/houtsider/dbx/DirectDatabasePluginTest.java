package com.houtsider.dbx;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class DirectDatabasePluginTest {
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
}

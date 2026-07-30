package com.houtsider.dbx;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}

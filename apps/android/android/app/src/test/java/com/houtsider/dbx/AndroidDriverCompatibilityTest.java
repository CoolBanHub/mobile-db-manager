package com.houtsider.dbx;

import static org.junit.Assert.assertEquals;

import com.microsoft.sqlserver.jdbc.MaxResultBufferParser;
import com.mongodb.internal.management.jmx.JMXMBeanServer;

import org.postgresql.util.PGPropertyMaxResultBufferParser;
import org.junit.Test;

public class AndroidDriverCompatibilityTest {
    @Test
    public void postgresResultBufferParserUsesAndroidRuntimeMemory() throws Exception {
        assertEquals(-1L, PGPropertyMaxResultBufferParser.parseProperty(null));
        assertEquals(1_000L, PGPropertyMaxResultBufferParser.parseProperty("1K"));
        assertEquals(
                (long) (Runtime.getRuntime().maxMemory() * 0.1),
                PGPropertyMaxResultBufferParser.parseProperty("10percent"));
    }

    @Test
    public void sqlServerResultBufferParserUsesAndroidRuntimeMemory() throws Exception {
        assertEquals(-1L, MaxResultBufferParser.validateMaxResultBuffer(null));
        assertEquals(1_000L, MaxResultBufferParser.validateMaxResultBuffer("1K"));
        assertEquals(
                (long) (Runtime.getRuntime().maxMemory() * 0.1),
                MaxResultBufferParser.validateMaxResultBuffer("10percent"));
    }

    @Test
    public void mongoJmxReplacementIsSafeWithoutJavaManagementModule() {
        JMXMBeanServer server = new JMXMBeanServer();
        server.registerMBean(new Object(), "dbx:type=Android");
        server.unregisterMBean("dbx:type=Android");
    }
}

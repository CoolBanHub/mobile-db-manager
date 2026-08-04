package com.coolbanhub.mobiledbmanager;

import static org.junit.Assert.assertEquals;

import com.mongodb.management.JMXMBeanServer;

import org.bson.Document;
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
    public void mongoJmxReplacementIsSafeWithoutJavaManagementModule() {
        JMXMBeanServer server = new JMXMBeanServer();
        server.registerMBean(new Object(), "dbx:type=Android");
        server.unregisterMBean("dbx:type=Android");
    }

    @Test
    public void mongoDocumentsUseCanonicalExtendedJsonForLosslessEditing() {
        String json = DirectMongoConnection.extendedJson(
                new Document("_id", 7L).append("count", 5L));

        org.junit.Assert.assertTrue(json.contains("\"$numberLong\""));
        Document roundTrip = Document.parse(json);
        org.junit.Assert.assertTrue(roundTrip.get("_id") instanceof Long);
        org.junit.Assert.assertTrue(roundTrip.get("count") instanceof Long);
    }

    @Test
    public void mongoScramAuthenticatorCanResolveItsSaslBaseClass() throws Exception {
        Class<?> saslClient = Class.forName("javax.security.sasl.SaslClient");
        Class<?> base = Class.forName(
                "com.mongodb.internal.connection.SaslAuthenticator$SaslClientImpl");
        Class<?> scram = Class.forName(
                "com.mongodb.internal.connection.ScramShaAuthenticator$ScramShaSaslClient");

        org.junit.Assert.assertTrue(saslClient.isAssignableFrom(base));
        org.junit.Assert.assertTrue(base.isAssignableFrom(scram));
    }
}

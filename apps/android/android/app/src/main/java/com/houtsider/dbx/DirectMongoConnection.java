package com.houtsider.dbx;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import org.bson.Document;
import org.json.JSONObject;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Opens a short-lived MongoDB client for native connection checks.
 *
 * <p>The client runs entirely in the Android process. Saved credentials and
 * connection strings are never returned to the WebView.
 */
final class DirectMongoConnection {
    private DirectMongoConnection() {}

    static void test(JSONObject config) throws Exception {
        String uri = config.optString("connectionString").trim();
        boolean routed = config.optBoolean("sshEnabled", false) || config.optBoolean("proxyEnabled", false);
        if (!uri.isEmpty() && routed) {
            throw new IllegalArgumentException("MongoDB URI 不能与 Android SSH/HTTP 隧道同时使用；请改填主机、端口和账号");
        }

        int timeoutMillis = Math.max(1, config.optInt("connectTimeoutSecs", 10)) * 1_000;
        if (!uri.isEmpty()) {
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(uri))
                    .applyToClusterSettings(builder ->
                            builder.serverSelectionTimeout(timeoutMillis, TimeUnit.MILLISECONDS))
                    .applyToSocketSettings(builder ->
                            builder.connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                                    .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS))
                    .build();
            ping(settings, database(config));
            return;
        }

        String host = config.optString("host");
        int port = config.optInt("port", 27017);
        String sslMode = config.optString("sslMode", "verify-full");
        if (routed && config.optBoolean("ssl", false) && "verify-full".equals(sslMode)) {
            throw new IllegalArgumentException("MongoDB 通过 Android 隧道连接时无法校验原始主机名；请使用“校验证书”或“仅加密”模式");
        }
        DirectTransport.Route route = DirectTransport.open(config, host, port);
        try {
            MongoClientSettings.Builder builder = MongoClientSettings.builder()
                    .applyToClusterSettings(settings ->
                            settings.hosts(Collections.singletonList(new ServerAddress(route.host, route.port)))
                                    .serverSelectionTimeout(timeoutMillis, TimeUnit.MILLISECONDS))
                    .applyToSocketSettings(settings ->
                            settings.connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                                    .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS))
                    .applyToSslSettings(settings -> {
                        settings.enabled(config.optBoolean("ssl", false));
                        settings.invalidHostNameAllowed(!"verify-full".equals(sslMode));
                        if ("required".equals(sslMode)) {
                            settings.context(insecureSslContext());
                        }
                    });
            String username = config.optString("username");
            if (!username.isEmpty()) {
                builder.credential(MongoCredential.createCredential(
                        username,
                        database(config),
                        config.optString("password").toCharArray()));
            }
            ping(builder.build(), database(config));
        } finally {
            route.close();
        }
    }

    private static void ping(MongoClientSettings settings, String database) {
        try (MongoClient client = MongoClients.create(settings)) {
            client.getDatabase(database).runCommand(new Document("ping", 1));
        }
    }

    private static String database(JSONObject config) {
        String database = config.isNull("database") ? "" : config.optString("database");
        return database.trim().isEmpty() ? "admin" : database.trim();
    }

    /**
     * Implements the explicitly selected "encryption only" mode. Production
     * profiles should use verify-ca or verify-full with the Android trust store.
     */
    private static SSLContext insecureSslContext() {
        try {
            TrustManager[] managers = new TrustManager[]{new X509TrustManager() {
                @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
                @Override public void checkClientTrusted(
                        java.security.cert.X509Certificate[] chain,
                        String authType) {}
                @Override public void checkServerTrusted(
                        java.security.cert.X509Certificate[] chain,
                        String authType) {}
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers, new java.security.SecureRandom());
            return context;
        } catch (Exception error) {
            throw new IllegalStateException("无法初始化 MongoDB TLS 上下文", error);
        }
    }
}

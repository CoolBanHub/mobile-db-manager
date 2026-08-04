package com.houtsider.dbx;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;

import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 在 Android 原生进程中创建短生命周期 MongoDB 客户端。
 *
 * <p>保存的凭据和连接 URI 不会返回 WebView；每次操作结束后立即关闭客户端及连接池。
 */
final class DirectMongoConnection {
    private static final JsonWriterSettings EXTENDED_JSON = JsonWriterSettings.builder()
            .outputMode(JsonMode.EXTENDED)
            .build();

    private DirectMongoConnection() {}

    static void test(JSONObject config) throws Exception {
        withClient(config, client -> {
            client.getDatabase(targetDatabase(config, connectionString(config)))
                    .runCommand(new Document("ping", 1));
            return null;
        });
    }

    static List<String> databases(JSONObject config) throws Exception {
        return withClient(config, client -> {
            List<String> names = new ArrayList<>();
            for (String name : client.listDatabaseNames()) names.add(name);
            return names;
        });
    }

    static List<String> collections(JSONObject config, String database) throws Exception {
        return withClient(config, client -> {
            List<String> names = new ArrayList<>();
            for (String name : client.getDatabase(required(database, "数据库")).listCollectionNames()) {
                names.add(name);
            }
            return names;
        });
    }

    static Page documents(
            JSONObject config,
            String database,
            String collection,
            String filterJson,
            int offset,
            int limit) throws Exception {
        Document filter = parseDocument(filterJson, "查询条件");
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(100, Math.max(1, limit));
        return withClient(config, client -> {
            MongoCollection<Document> target = collection(client, database, collection);
            List<String> documents = new ArrayList<>();
            boolean hasMore = false;
            // 多取一条只用于判断下一页是否存在，不把额外文档返回给 WebView。
            try (MongoCursor<Document> cursor = target.find(filter)
                    .skip(safeOffset)
                    .limit(safeLimit + 1)
                    .iterator()) {
                while (cursor.hasNext()) {
                    if (documents.size() >= safeLimit) {
                        hasMore = true;
                        break;
                    }
                    documents.add(extendedJson(cursor.next()));
                }
            }
            return new Page(documents, safeOffset, safeLimit, hasMore);
        });
    }

    static String insert(
            JSONObject config,
            String database,
            String collection,
            String documentJson) throws Exception {
        Document document = parseDocument(documentJson, "新文档");
        return withClient(config, client -> {
            collection(client, database, collection).insertOne(document);
            return extendedJson(document);
        });
    }

    static long replace(
            JSONObject config,
            String database,
            String collection,
            String originalJson,
            String replacementJson) throws Exception {
        Document original = parseDocument(originalJson, "原始文档");
        Document replacement = parseDocument(replacementJson, "修改后的文档");
        Object identity = requiredIdentity(original);
        // 全文替换仍锁定原始 _id，防止编辑器把一次修改变成插入或误更新其他文档。
        if (!replacement.containsKey("_id")) {
            replacement.put("_id", identity);
        } else if (!identity.equals(replacement.get("_id"))) {
            throw new IllegalArgumentException("MongoDB 文档的 _id 不允许修改");
        }
        return withClient(config, client -> {
            UpdateResult result = collection(client, database, collection)
                    .replaceOne(new Document("_id", identity), replacement);
            if (result.getMatchedCount() != 1) {
                throw new IllegalArgumentException("原文档已不存在或 _id 已变化，请刷新后重试");
            }
            return result.getModifiedCount();
        });
    }

    static long delete(
            JSONObject config,
            String database,
            String collection,
            String originalJson) throws Exception {
        Object identity = requiredIdentity(parseDocument(originalJson, "待删除文档"));
        return withClient(config, client -> {
            DeleteResult result = collection(client, database, collection)
                    .deleteOne(new Document("_id", identity));
            if (result.getDeletedCount() != 1) {
                throw new IllegalArgumentException("原文档已不存在，请刷新后重试");
            }
            return result.getDeletedCount();
        });
    }

    private static <T> T withClient(JSONObject config, ClientTask<T> task) throws Exception {
        String uri = config.optString("connectionString").trim();
        boolean routed = config.optBoolean("sshEnabled", false) || config.optBoolean("proxyEnabled", false);
        if (!uri.isEmpty() && routed) {
            throw new IllegalArgumentException("MongoDB URI 不能与 Android SSH/HTTP 隧道同时使用；请改填主机、端口和账号");
        }

        int timeoutMillis = Math.max(1, config.optInt("connectTimeoutSecs", 10)) * 1_000;
        if (!uri.isEmpty()) {
            // URI 模式交给官方驱动解析副本集、SRV 和 authSource 等高级参数。
            ConnectionString connectionString = new ConnectionString(uri);
            MongoClientSettings.Builder builder = MongoClientSettings.builder()
                    .applyConnectionString(connectionString)
                    .applyToClusterSettings(settings ->
                            settings.serverSelectionTimeout(timeoutMillis, TimeUnit.MILLISECONDS))
                    .applyToSocketSettings(settings ->
                            settings.connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                                    .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS));
            applyFormCredentialWhenUriHasNone(builder, connectionString, config);
            try (MongoClient client = MongoClients.create(builder.build())) {
                return task.execute(client);
            }
        }

        String host = config.optString("host");
        int port = config.optInt("port", 27017);
        String sslMode = config.optString("sslMode", "verify-full");
        if (routed && config.optBoolean("ssl", false) && "verify-full".equals(sslMode)) {
            throw new IllegalArgumentException("MongoDB 通过 Android 隧道连接时无法校验原始主机名；请使用“校验证书”或“仅加密”模式");
        }
        DirectTransport.Route route = DirectTransport.open(config, host, port);
        try {
            // 表单模式先建立 SSH/代理路由，再让 MongoDB 驱动连接本地路由端点。
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
                        authenticationDatabase(),
                        config.optString("password").toCharArray()));
            }
            try (MongoClient client = MongoClients.create(builder.build())) {
                return task.execute(client);
            }
        } finally {
            route.close();
        }
    }

    private static MongoCollection<Document> collection(
            MongoClient client,
            String database,
            String collection) {
        return client.getDatabase(required(database, "数据库"))
                .getCollection(required(collection, "集合"));
    }

    private static Document parseDocument(String json, String label) {
        try {
            return Document.parse(json == null || json.trim().isEmpty() ? "{}" : json.trim());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(label + "不是有效的 MongoDB Extended JSON：" + error.getMessage());
        }
    }

    static String extendedJson(Document document) {
        return document.toJson(EXTENDED_JSON);
    }

    private static Object requiredIdentity(Document document) {
        if (!document.containsKey("_id") || document.get("_id") == null) {
            throw new IllegalArgumentException("文档缺少 _id，不能安全定位写入目标");
        }
        return document.get("_id");
    }

    private static String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.trim();
    }

    private static ConnectionString connectionString(JSONObject config) {
        String uri = config.optString("connectionString").trim();
        return uri.isEmpty() ? null : new ConnectionString(uri);
    }

    private static void applyFormCredentialWhenUriHasNone(
            MongoClientSettings.Builder builder,
            ConnectionString connectionString,
            JSONObject config) {
        if (connectionString.getCredential() != null) return;
        String username = config.optString("username");
        if (username.isEmpty()) return;
        builder.credential(MongoCredential.createCredential(
                username,
                authenticationDatabase(),
                config.optString("password").toCharArray()));
    }

    private static String targetDatabase(JSONObject config, ConnectionString connectionString) {
        if (connectionString != null
                && connectionString.getDatabase() != null
                && !connectionString.getDatabase().trim().isEmpty()) {
            return connectionString.getDatabase().trim();
        }
        String database = config.isNull("database") ? "" : config.optString("database");
        return database.trim().isEmpty() ? "admin" : database.trim();
    }

    private static String authenticationDatabase() {
        // 表单账号按部署管理员处理；其他认证库应通过带 authSource 的 MongoDB URI 指定。
        return "admin";
    }

    static final class Page {
        final List<String> documents;
        final int offset;
        final int limit;
        final boolean hasMore;

        Page(List<String> documents, int offset, int limit, boolean hasMore) {
            this.documents = documents;
            this.offset = offset;
            this.limit = limit;
            this.hasMore = hasMore;
        }
    }

    private interface ClientTask<T> {
        T execute(MongoClient client) throws Exception;
    }

    /** 仅供用户明确选择“仅加密”时使用；生产连接应优先使用系统信任库校验证书。 */
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

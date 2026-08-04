package com.houtsider.dbx;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Android 直连使用的最小 etcd v3 JSON Gateway 客户端。 */
final class DirectEtcdConnection {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private DirectEtcdConnection() {}

    static JSONObject test(JSONObject config) throws Exception {
        return execute(config, "/v3/maintenance/status", new JSONObject());
    }

    static JSONObject status(JSONObject config) throws Exception {
        return test(config);
    }

    static JSONObject range(JSONObject config, String prefix, int limit) throws Exception {
        // etcd 的前缀查询等价于半开区间 [prefix, prefixEnd)，空前缀使用全键空间哨兵。
        byte[] start = prefix.isEmpty() ? new byte[]{0} : prefix.getBytes(StandardCharsets.UTF_8);
        byte[] end = prefix.isEmpty() ? new byte[]{0} : prefixEnd(start);
        JSONObject body = new JSONObject()
                .put("key", encode(start))
                .put("range_end", encode(end))
                .put("limit", String.valueOf(Math.min(500, Math.max(1, limit))))
                .put("sort_order", "ASCEND")
                .put("sort_target", "KEY");
        return execute(config, "/v3/kv/range", body);
    }

    static JSONObject count(JSONObject config) throws Exception {
        return execute(config, "/v3/kv/range", new JSONObject()
                .put("key", encode(new byte[]{0}))
                .put("range_end", encode(new byte[]{0}))
                .put("count_only", true));
    }

    static JSONObject get(JSONObject config, String key) throws Exception {
        return execute(config, "/v3/kv/range", new JSONObject()
                .put("key", encode(key.getBytes(StandardCharsets.UTF_8)))
                .put("limit", "1"));
    }

    static JSONObject put(JSONObject config, String key, String value) throws Exception {
        return put(config, key, value, "0");
    }

    static JSONObject put(JSONObject config, String key, String value, String lease) throws Exception {
        JSONObject request = new JSONObject()
                .put("key", encode(key.getBytes(StandardCharsets.UTF_8)))
                .put("value", encode(value.getBytes(StandardCharsets.UTF_8)));
        if (lease != null && !lease.isEmpty() && !"0".equals(lease)) request.put("lease", lease);
        return execute(config, "/v3/kv/put", request);
    }

    static JSONObject delete(JSONObject config, String key) throws Exception {
        return execute(config, "/v3/kv/deleterange", new JSONObject()
                .put("key", encode(key.getBytes(StandardCharsets.UTF_8))));
    }

    static String decode(String value) throws Exception {
        byte[] bytes = Base64.decode(value, Base64.DEFAULT);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            // 非 UTF-8 值保留原始 Base64，并由前端禁止文本覆盖，避免无损数据被破坏。
            return "base64:" + Base64.encodeToString(bytes, Base64.NO_WRAP);
        }
    }

    private static JSONObject execute(JSONObject config, String path, JSONObject body) throws Exception {
        String originalHost = config.optString("host");
        int originalPort = config.optInt("port", 2379);
        DirectTransport.Route route = DirectTransport.open(config, originalHost, originalPort);
        try {
            String token = "";
            String username = config.optString("username");
            String password = config.optString("password");
            if (!username.isEmpty() || !password.isEmpty()) {
                // Gateway 使用短期 token；每次原生操作现取现用，不写入 WebView 或本地存储。
                if (username.isEmpty() || password.isEmpty()) {
                    throw new IllegalArgumentException("etcd 认证需要同时填写用户名和密码");
                }
                JSONObject authentication = request(
                        config,
                        route,
                        originalHost,
                        "/v3/auth/authenticate",
                        new JSONObject().put("name", username).put("password", password),
                        "");
                token = authentication.optString("token");
                if (token.isEmpty()) throw new IllegalArgumentException("etcd 认证响应中没有 token");
            }
            return request(config, route, originalHost, path, body, token);
        } finally {
            route.close();
        }
    }

    private static JSONObject request(
            JSONObject config,
            DirectTransport.Route route,
            String originalHost,
            String path,
            JSONObject body,
            String token) throws Exception {
        boolean ssl = config.optBoolean("ssl", false);
        URL url = new URL((ssl ? "https" : "http") + "://" + route.host + ":" + route.port + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        int timeout = Math.max(1, config.optInt("connectTimeoutSecs", 10)) * 1_000;
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(Math.max(timeout, config.optInt("queryTimeoutSecs", 60) * 1_000));
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", token);
        connection.setDoOutput(true);
        if (connection instanceof HttpsURLConnection) {
            configureTls((HttpsURLConnection) connection, config, originalHost);
        }
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(payload.length);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int status = connection.getResponseCode();
            String response = read(connection, status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status >= 400) {
                String detail = response;
                try {
                    JSONObject error = new JSONObject(response);
                    detail = error.optString("message", error.optString("error", response));
                } catch (Exception ignored) {}
                throw new java.io.IOException("etcd HTTP " + status + "：" + detail);
            }
            return response.isEmpty() ? new JSONObject() : new JSONObject(response);
        } finally {
            connection.disconnect();
        }
    }

    private static void configureTls(
            HttpsURLConnection connection,
            JSONObject config,
            String originalHost) throws Exception {
        String mode = config.optString("sslMode", "verify-full");
        // required=仅加密，verify-ca=校验证书链，verify-full=同时校验用户填写的原始主机名。
        if ("required".equals(mode)) {
            connection.setSSLSocketFactory(insecureSslContext().getSocketFactory());
            connection.setHostnameVerifier((host, session) -> true);
            return;
        }
        if ("verify-ca".equals(mode)) {
            connection.setHostnameVerifier((host, session) -> true);
            return;
        }
        HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
        connection.setHostnameVerifier((host, session) -> verifier.verify(originalHost, session));
    }

    private static SSLContext insecureSslContext() throws Exception {
        TrustManager[] managers = new TrustManager[]{new X509TrustManager() {
            @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
            @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers, new java.security.SecureRandom());
        return context;
    }

    private static String read(HttpURLConnection connection, InputStream stream) throws Exception {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                // Gateway 响应先进入内存，必须在分配失控前主动终止读取。
                if (total > MAX_RESPONSE_BYTES) throw new java.io.IOException("etcd 响应超过安卓端 4 MB 上限");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    private static byte[] prefixEnd(byte[] prefix) {
        // 从末尾寻找可递增字节，构造严格大于所有同前缀键的最小上界。
        byte[] end = prefix.clone();
        for (int index = end.length - 1; index >= 0; index--) {
            if ((end[index] & 0xff) < 0xff) {
                end[index]++;
                return java.util.Arrays.copyOf(end, index + 1);
            }
        }
        return new byte[]{0};
    }
}

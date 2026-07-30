package com.houtsider.dbx;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Implements the minimal RESP client required to authenticate, select a
 * logical database, and verify a standalone Redis connection with PING.
 *
 * <p>The socket and credentials stay inside the Android native process.
 */
final class DirectRedisConnection {
    private DirectRedisConnection() {}

    static void test(JSONObject config) throws Exception {
        String host = config.optString("host");
        int port = config.optInt("port", 6379);
        int timeoutMillis = Math.max(1, config.optInt("connectTimeoutSecs", 10)) * 1_000;
        DirectTransport.Route route = DirectTransport.open(config, host, port);
        try (Socket socket = openSocket(config, route, host, timeoutMillis)) {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = new BufferedOutputStream(socket.getOutputStream());
            String username = config.optString("username");
            String password = config.optString("password");
            if (!password.isEmpty()) {
                expectOk(input, output, username.isEmpty()
                        ? new String[]{"AUTH", password}
                        : new String[]{"AUTH", username, password});
            }
            String database = optionalDatabase(config);
            if (!database.isEmpty() && !"0".equals(database)) {
                try {
                    int number = Integer.parseInt(database);
                    if (number < 0) throw new NumberFormatException();
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("Redis 数据库必须是非负整数");
                }
                expectOk(input, output, new String[]{"SELECT", database});
            }
            Object reply = command(input, output, "PING");
            if (!"PONG".equalsIgnoreCase(String.valueOf(reply))) {
                throw new IOException("Redis PING 返回了意外结果：" + reply);
            }
        } finally {
            route.close();
        }
    }

    private static Socket openSocket(
            JSONObject config,
            DirectTransport.Route route,
            String originalHost,
            int timeoutMillis) throws Exception {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(route.host, route.port), timeoutMillis);
        plain.setSoTimeout(timeoutMillis);
        if (!config.optBoolean("ssl", false)) return plain;

        String sslMode = config.optString("sslMode", "verify-full");
        SSLSocketFactory factory = "required".equals(sslMode)
                ? insecureSslContext().getSocketFactory()
                : (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket socket = (SSLSocket) factory.createSocket(plain, originalHost, config.optInt("port", 6379), true);
        if ("verify-full".equals(sslMode)) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
        }
        socket.startHandshake();
        return socket;
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

    private static void expectOk(InputStream input, OutputStream output, String[] arguments) throws IOException {
        Object reply = command(input, output, arguments);
        if (!"OK".equalsIgnoreCase(String.valueOf(reply))) {
            throw new IOException("Redis 命令返回了意外结果：" + reply);
        }
    }

    static Object command(InputStream input, OutputStream output, String... arguments) throws IOException {
        output.write(('*' + String.valueOf(arguments.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String argument : arguments) {
            byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
            output.write(('$' + String.valueOf(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.flush();
        return readReply(input);
    }

    private static Object readReply(InputStream input) throws IOException {
        int prefix = input.read();
        if (prefix < 0) throw new EOFException("Redis 在返回响应前关闭了连接");
        String line = readLine(input);
        if (prefix == '+') return line;
        if (prefix == '-') throw new IOException("Redis：" + line);
        if (prefix == ':') return Long.parseLong(line);
        if (prefix == '$') {
            int length = Integer.parseInt(line);
            if (length < 0) return null;
            byte[] bytes = new byte[length];
            readFully(input, bytes);
            requireCrlf(input);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        throw new IOException("无法识别 Redis 响应类型：" + (char) prefix);
    }

    private static String readLine(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream value = new java.io.ByteArrayOutputStream();
        int previous = -1;
        while (value.size() < 64 * 1024) {
            int current = input.read();
            if (current < 0) throw new EOFException("Redis 响应不完整");
            if (previous == '\r' && current == '\n') {
                byte[] bytes = value.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.UTF_8);
            }
            value.write(current);
            previous = current;
        }
        throw new IOException("Redis 响应行过长");
    }

    private static void readFully(InputStream input, byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int read = input.read(bytes, offset, bytes.length - offset);
            if (read < 0) throw new EOFException("Redis 响应不完整");
            offset += read;
        }
    }

    private static void requireCrlf(InputStream input) throws IOException {
        if (input.read() != '\r' || input.read() != '\n') {
            throw new IOException("Redis 响应格式无效");
        }
    }

    private static String optionalDatabase(JSONObject config) {
        if (!config.has("database") || config.isNull("database")) return "";
        return config.optString("database", "");
    }
}

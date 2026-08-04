package com.houtsider.dbx;

import android.util.Base64;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.ProxyHTTP;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds process-local TCP routes for direct JDBC connections. A route is
 * attached to the returned JDBC connection and is torn down when close() is
 * called, so tunnel credentials and sockets never enter the WebView.
 */
final class DirectTransport {
    private DirectTransport() {}

    static final class Route implements Closeable {
        final String host;
        final int port;
        private final Closeable cleanup;

        Route(String host, int port, Closeable cleanup) {
            this.host = host;
            this.port = port;
            this.cleanup = cleanup;
        }

        @Override
        public void close() throws IOException {
            cleanup.close();
        }
    }

    static Route open(JSONObject config, String targetHost, int targetPort) throws Exception {
        if (config.optBoolean("sshEnabled", false)) {
            return openSsh(config, targetHost, targetPort);
        }
        if (config.optBoolean("proxyEnabled", false)) {
            HttpConnectTunnel tunnel = new HttpConnectTunnel(
                    config.optString("proxyHost"),
                    config.optInt("proxyPort"),
                    config.optString("proxyUsername"),
                    config.optString("proxyPassword"),
                    targetHost,
                    targetPort,
                    timeoutMillis(config));
            tunnel.start();
            return new Route("127.0.0.1", tunnel.localPort(), tunnel);
        }
        return new Route(targetHost, targetPort, () -> {});
    }

    private static Route openSsh(JSONObject config, String targetHost, int targetPort) throws Exception {
        JSch jsch = new JSch();
        String authMethod = config.optString("sshAuthMethod", "password");
        if ("private-key".equals(authMethod)) {
            String privateKey = config.optString("sshPrivateKey");
            if (privateKey.isEmpty()) throw new IllegalArgumentException("SSH 私钥不能为空");
            String passphrase = config.optString("sshPrivateKeyPassphrase");
            jsch.addIdentity(
                    "dbx-mobile",
                    privateKey.getBytes(StandardCharsets.UTF_8),
                    null,
                    passphrase.isEmpty() ? null : passphrase.getBytes(StandardCharsets.UTF_8));
        }

        Session session = jsch.getSession(
                config.optString("sshUsername"),
                config.optString("sshHost"),
                config.optInt("sshPort", 22));
        if ("password".equals(authMethod)) {
            session.setPassword(config.optString("sshPassword"));
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        } else {
            session.setConfig("PreferredAuthentications", "publickey");
        }
        String hostKeyFingerprint = config.optString("sshHostKeyFingerprint").trim();
        if (hostKeyFingerprint.isEmpty()) {
            // Android has no conventional ~/.ssh/known_hosts. Users can pin a
            // SHA256 fingerprint in the SSH tab for strict verification.
            session.setConfig("StrictHostKeyChecking", "no");
        } else {
            session.setHostKeyRepository(new PinnedHostKeyRepository(hostKeyFingerprint));
            session.setConfig("StrictHostKeyChecking", "yes");
        }
        session.setServerAliveInterval(Math.max(5_000, config.optInt("keepaliveIntervalSecs", 30) * 1_000));

        if (config.optBoolean("proxyEnabled", false)) {
            ProxyHTTP proxy = new ProxyHTTP(config.optString("proxyHost"), config.optInt("proxyPort"));
            if (!config.optString("proxyUsername").isEmpty()) {
                proxy.setUserPasswd(config.optString("proxyUsername"), config.optString("proxyPassword"));
            }
            session.setProxy(proxy);
        }

        try {
            session.connect(timeoutMillis(config));
            int localPort = session.setPortForwardingL("127.0.0.1", 0, targetHost, targetPort);
            return new Route("127.0.0.1", localPort, session::disconnect);
        } catch (Exception error) {
            session.disconnect();
            throw error;
        }
    }

    static Connection attach(Connection connection, Route route) {
        AtomicBoolean closed = new AtomicBoolean(false);
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> invoke(connection, route, closed, method, args));
    }

    private static Object invoke(
            Connection connection,
            Route route,
            AtomicBoolean closed,
            Method method,
            Object[] args) throws Throwable {
        boolean closes = "close".equals(method.getName()) || "abort".equals(method.getName());
        try {
            return method.invoke(connection, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        } finally {
            if (closes && closed.compareAndSet(false, true)) route.close();
        }
    }

    private static int timeoutMillis(JSONObject config) {
        return Math.max(1, config.optInt("connectTimeoutSecs", 10)) * 1_000;
    }

    private static final class PinnedHostKeyRepository implements HostKeyRepository {
        private final byte[] expected;

        PinnedHostKeyRepository(String fingerprint) {
            String normalized = fingerprint.startsWith("SHA256:")
                    ? fingerprint.substring("SHA256:".length()) : fingerprint;
            this.expected = normalized.replace("=", "").getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public int check(String host, byte[] key) {
            try {
                String actual = Base64.encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(key),
                        Base64.NO_WRAP).replace("=", "");
                return MessageDigest.isEqual(expected, actual.getBytes(StandardCharsets.US_ASCII))
                        ? OK : CHANGED;
            } catch (Exception error) {
                return CHANGED;
            }
        }

        @Override public void add(HostKey hostKey, UserInfo userInfo) {}
        @Override public void remove(String host, String type) {}
        @Override public void remove(String host, String type, byte[] key) {}
        @Override public String getKnownHostsRepositoryID() { return "DBX pinned SHA256 fingerprint"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }

    private static final class HttpConnectTunnel implements Closeable {
        private final String proxyHost;
        private final int proxyPort;
        private final String proxyUsername;
        private final String proxyPassword;
        private final String targetHost;
        private final int targetPort;
        private final int timeoutMillis;
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private ServerSocket listener;

        HttpConnectTunnel(
                String proxyHost,
                int proxyPort,
                String proxyUsername,
                String proxyPassword,
                String targetHost,
                int targetPort,
                int timeoutMillis) {
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
            this.proxyUsername = proxyUsername;
            this.proxyPassword = proxyPassword;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.timeoutMillis = timeoutMillis;
        }

        void start() throws IOException {
            listener = new ServerSocket();
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread acceptor = new Thread(this::acceptLoop, "dbx-http-connect");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int localPort() {
            return listener.getLocalPort();
        }

        private void acceptLoop() {
            while (!closed.get()) {
                try {
                    Socket inbound = listener.accept();
                    sockets.add(inbound);
                    Thread worker = new Thread(() -> bridge(inbound), "dbx-http-connect-bridge");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException error) {
                    if (!closed.get()) closeQuietly();
                }
            }
        }

        private void bridge(Socket inbound) {
            Socket outbound = new Socket();
            sockets.add(outbound);
            try {
                outbound.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMillis);
                outbound.setSoTimeout(timeoutMillis);
                negotiate(outbound);
                outbound.setSoTimeout(0);

                Thread upstream = new Thread(
                        () -> pumpAndClose(inbound, outbound),
                        "dbx-http-connect-upstream");
                upstream.setDaemon(true);
                upstream.start();
                pumpAndClose(outbound, inbound);
            } catch (IOException ignored) {
                closeSocket(inbound);
                closeSocket(outbound);
            }
        }

        private void negotiate(Socket socket) throws IOException {
            String target = targetHost + ":" + targetPort;
            StringBuilder request = new StringBuilder()
                    .append("CONNECT ").append(target).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(target).append("\r\n")
                    .append("Proxy-Connection: Keep-Alive\r\n");
            if (!proxyUsername.isEmpty()) {
                String credentials = proxyUsername + ":" + proxyPassword;
                request.append("Proxy-Authorization: Basic ")
                        .append(Base64.encodeToString(credentials.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP))
                        .append("\r\n");
            }
            request.append("\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();

            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int state = 0;
            while (header.size() < 32_768 && state < 4) {
                int value = socket.getInputStream().read();
                if (value < 0) throw new IOException("HTTP 代理在握手完成前关闭连接");
                header.write(value);
                state = (state == 0 && value == '\r') ? 1
                        : (state == 1 && value == '\n') ? 2
                        : (state == 2 && value == '\r') ? 3
                        : (state == 3 && value == '\n') ? 4 : 0;
            }
            String firstLine = header.toString(StandardCharsets.ISO_8859_1.name()).split("\r\n", 2)[0];
            String[] parts = firstLine.split(" ");
            if (parts.length < 2 || !parts[1].startsWith("2")) {
                throw new IOException("HTTP 代理 CONNECT 失败：" + firstLine);
            }
        }

        private void pumpAndClose(Socket source, Socket destination) {
            try {
                pump(source.getInputStream(), destination.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                closeSocket(source);
                closeSocket(destination);
            }
        }

        private void pump(InputStream input, OutputStream output) throws IOException {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                output.flush();
            }
        }

        private void closeSocket(Socket socket) {
            sockets.remove(socket);
            try {
                socket.close();
            } catch (IOException ignored) {}
        }

        private void closeQuietly() {
            try {
                close();
            } catch (IOException ignored) {}
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            if (listener != null) listener.close();
            for (Socket socket : sockets.toArray(new Socket[0])) closeSocket(socket);
        }
    }
}

package com.houtsider.dbx;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@CapacitorPlugin(name = "NativeHttp")
public class NativeHttpPlugin extends Plugin {
    private final ConcurrentHashMap<String, HttpURLConnection> requests = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PluginMethod
    public void request(PluginCall call) {
        String requestId = call.getString("requestId");
        String url = call.getString("url");
        if (requestId == null || requestId.isBlank() || url == null || url.isBlank()) {
            call.reject("requestId and url are required");
            return;
        }
        executor.execute(() -> executeRequest(call, requestId, url));
    }

    private void executeRequest(PluginCall call, String requestId, String urlValue) {
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(urlValue).toURL();
            Proxy proxy = buildProxy(call.getString("proxyUrl", ""));
            connection = (HttpURLConnection) (proxy == null ? url.openConnection() : url.openConnection(proxy));
            requests.put(requestId, connection);

            int timeoutMs = Math.max(3_000, Math.min(120_000, call.getInt("timeoutMs", 8_000)));
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestMethod(call.getString("method", "GET").toUpperCase());
            // API requests may carry a Bearer token. Do not let HttpURLConnection
            // forward it implicitly when a server redirects to another origin.
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);

            if (connection instanceof HttpsURLConnection httpsConnection) {
                configureTls(httpsConnection, call.getBoolean("allowInvalidCertificate", false));
            } else if (!call.getString("certificatePin", "").isBlank()) {
                throw new IllegalArgumentException("证书指纹固定仅支持 HTTPS 服务器");
            }

            JSObject headers = call.getObject("headers", new JSObject());
            Iterator<String> headerNames = headers.keys();
            while (headerNames.hasNext()) {
                String name = headerNames.next();
                Object value = headers.opt(name);
                if (value != null && value != JSONObject.NULL) {
                    connection.setRequestProperty(name, String.valueOf(value));
                }
            }

            String body = call.getString("body");
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }

            int status = connection.getResponseCode();
            if (connection instanceof HttpsURLConnection httpsConnection) {
                verifyCertificatePin(httpsConnection, call.getString("certificatePin", ""));
            }

            JSObject response = new JSObject();
            response.put("status", status);
            response.put("headers", responseHeaders(connection));
            response.put("body", readResponseBody(connection, status));
            call.resolve(response);
        } catch (Exception error) {
            call.reject(networkErrorMessage(error), error);
        } finally {
            requests.remove(requestId);
            if (connection != null) connection.disconnect();
        }
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        String requestId = call.getString("requestId");
        HttpURLConnection connection = requestId == null ? null : requests.remove(requestId);
        if (connection != null) connection.disconnect();
        call.resolve();
    }

    private Proxy buildProxy(String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) return null;
        URI proxyUri = URI.create(proxyUrl);
        String scheme = proxyUri.getScheme() == null ? "" : proxyUri.getScheme().toLowerCase();
        Proxy.Type type = scheme.equals("socks5") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        if (!scheme.equals("http") && !scheme.equals("socks5")) {
            throw new IllegalArgumentException("不支持的代理协议");
        }
        int port = proxyUri.getPort();
        if (port <= 0) port = type == Proxy.Type.SOCKS ? 1080 : 8080;
        return new Proxy(type, InetSocketAddress.createUnresolved(proxyUri.getHost(), port));
    }

    private void configureTls(HttpsURLConnection connection, boolean allowInvalidCertificate) throws Exception {
        if (!allowInvalidCertificate) return;
        TrustManager[] trustAll = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, new SecureRandom());
        connection.setSSLSocketFactory(context.getSocketFactory());
        HostnameVerifier allowAnyHostname = (hostname, session) -> true;
        connection.setHostnameVerifier(allowAnyHostname);
    }

    private void verifyCertificatePin(HttpsURLConnection connection, String configuredPin) throws Exception {
        if (configuredPin == null || configuredPin.isBlank()) return;
        Certificate[] certificates = connection.getServerCertificates();
        if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate certificate)) {
            throw new SSLPeerUnverifiedException("服务器没有提供可固定的 X.509 证书");
        }

        byte[] expected;
        byte[] actual;
        if (configuredPin.regionMatches(true, 0, "sha256/", 0, 7)) {
            expected = android.util.Base64.decode(configuredPin.substring(7), android.util.Base64.DEFAULT);
            actual = MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded());
        } else {
            expected = hexBytes(configuredPin);
            actual = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new SSLPeerUnverifiedException("服务器证书指纹与固定值不匹配");
        }
    }

    private byte[] hexBytes(String value) {
        String normalized = value.replaceAll("[^0-9A-Fa-f]", "");
        if (normalized.length() != 64) throw new IllegalArgumentException("无效的 SHA-256 证书指纹");
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(normalized.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }

    private JSObject responseHeaders(HttpURLConnection connection) {
        JSObject headers = new JSObject();
        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                headers.put(entry.getKey(), String.join(", ", entry.getValue()));
            }
        }
        return headers;
    }

    private String readResponseBody(HttpURLConnection connection, int status) throws Exception {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = stream.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private String networkErrorMessage(Exception error) {
        if (error instanceof SSLPeerUnverifiedException) return error.getMessage();
        String message = error.getMessage();
        return message == null || message.isBlank() ? "原生网络请求失败" : message;
    }

    @Override
    protected void handleOnDestroy() {
        for (HttpURLConnection connection : requests.values()) connection.disconnect();
        requests.clear();
        executor.shutdownNow();
        super.handleOnDestroy();
    }
}

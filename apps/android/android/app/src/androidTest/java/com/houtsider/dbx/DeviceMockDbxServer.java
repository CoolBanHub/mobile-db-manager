package com.houtsider.dbx;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DeviceMockDbxServer implements Closeable {
    interface Dispatcher {
        Response dispatch(Request request) throws Exception;
    }

    static final class Request {
        private final String method;
        private final String path;
        private final String body;

        Request(String method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }

        String method() { return method; }
        String path() { return path; }
        String body() { return body; }
    }

    static final class Response {
        private final int status;
        private final String body;
        private final long delayMillis;

        Response(int status, String body, long delayMillis) {
            this.status = status;
            this.body = body;
            this.delayMillis = delayMillis;
        }

        int status() { return status; }
        String body() { return body; }
        long delayMillis() { return delayMillis; }

        static Response json(String body) {
            return new Response(200, body, 0);
        }

        static Response json(String body, long delayMillis) {
            return new Response(200, body, delayMillis);
        }
    }

    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Dispatcher dispatcher;
    private volatile boolean running = true;

    DeviceMockDbxServer(Dispatcher dispatcher) throws IOException {
        this.dispatcher = dispatcher;
        serverSocket = new ServerSocket(0);
        executor.execute(this::acceptLoop);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort();
    }

    List<Request> requests() {
        return requests;
    }

    boolean received(String method, String path) {
        return requests.stream().anyMatch(request -> request.method().equals(method) && request.path().equals(path));
    }

    Request last(String method, String path) {
        return requests.stream()
                .filter(request -> request.method().equals(method) && request.path().equals(path))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    Request lastStartingWith(String method, String pathPrefix) {
        return requests.stream()
                .filter(request -> request.method().equals(method) && request.path().startsWith(pathPrefix))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(() -> handle(socket));
            } catch (IOException error) {
                if (running) throw new RuntimeException(error);
            }
        }
    }

    private void handle(Socket socket) {
        try (socket; BufferedInputStream input = new BufferedInputStream(socket.getInputStream())) {
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.isBlank()) return;
            String[] requestParts = requestLine.split(" ", 3);
            Map<String, String> headers = new ConcurrentHashMap<>();
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                int separator = line.indexOf(':');
                if (separator > 0) {
                    headers.put(
                            line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                            line.substring(separator + 1).trim());
                }
            }
            int contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            byte[] body = input.readNBytes(contentLength);
            Request request = new Request(
                    requestParts[0],
                    requestParts[1],
                    new String(body, StandardCharsets.UTF_8));
            requests.add(request);
            Response response = dispatcher.dispatch(request);
            if (response.delayMillis() > 0) Thread.sleep(response.delayMillis());
            writeResponse(socket.getOutputStream(), response);
        } catch (Exception ignored) {
            // Query-cancellation tests intentionally disconnect an in-flight socket.
        }
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int current;
        while ((current = input.read()) != -1) {
            if (current == '\n') break;
            if (current != '\r') bytes.write(current);
        }
        if (current == -1 && bytes.size() == 0) return null;
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void writeResponse(OutputStream output, Response response) throws IOException {
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        String reason = response.status() >= 400 ? "Error" : "OK";
        String headers = "HTTP/1.1 " + response.status() + " " + reason + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(body);
        output.flush();
    }

    @Override
    public void close() throws IOException {
        running = false;
        serverSocket.close();
        executor.shutdownNow();
    }
}

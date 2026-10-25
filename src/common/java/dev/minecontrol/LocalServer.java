package dev.minecontrol;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
public final class LocalServer implements AutoCloseable {
    private final Config config;
    private final Controller controller;
    private volatile boolean running;
    private ServerSocket listener;
    private volatile Socket connection;
    private Thread worker;
    public LocalServer(Config config, Controller controller) { this.config = config; this.controller = controller; }
    public void start() throws IOException {
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.port), 8);
        running = true;
        worker = new Thread(this::serve, "MineControl TCP");
        worker.setDaemon(true);
        worker.start();
    }
    private void serve() {
        while (running) {
            try (Socket socket = listener.accept()) {
                connection = socket;
                socket.setSoTimeout(config.requestTimeoutMs);
                socket.setTcpNoDelay(true);
                var input = socket.getInputStream();
                var output = socket.getOutputStream();
                while (running) {
                    String line;
                    try { line = readLine(input, config.maxRequestBytes); }
                    catch (RequestTooLarge e) { write(output, error(JsonNull.INSTANCE, "REQUEST_TOO_LARGE", e.getMessage())); break; }
                    if (line == null) break;
                    JsonElement id = JsonNull.INSTANCE;
                    JsonObject response;
                    try {
                        JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                        if (!request.has("id") || !request.get("id").isJsonPrimitive() || request.get("id").getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("id must be a string or number");
                        id = request.get("id");
                        if (!request.has("command") || !request.get("command").isJsonPrimitive() || !request.get("command").getAsJsonPrimitive().isString()) throw new IllegalArgumentException("command must be a string");
                        var future = controller.submit(request);
                        try { response = future.get(config.requestTimeoutMs, TimeUnit.MILLISECONDS); }
                        catch (TimeoutException e) {
                            future.cancel(false);
                            response = error(id, "TIMEOUT", "Game did not finish in time; inspect status before retrying");
                        }
                    } catch (JsonParseException | IllegalArgumentException | IllegalStateException e) {
                        response = error(id, "INVALID_REQUEST", e.getMessage());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); return;
                    } catch (ExecutionException e) {
                        response = error(id, "INTERNAL_ERROR", "Command failed; see client log");
                        MineControlClient.LOG.error("Command failed", e.getCause());
                    }
                    write(output, response);
                }
            } catch (SocketTimeoutException ignored) {
                // Idle clients cannot monopolize this sequential server forever.
            } catch (IOException e) {
                if (running) MineControlClient.LOG.debug("Control connection closed", e);
            } finally { connection = null; }
        }
    }
    static String readLine(InputStream input, int limit) throws IOException {
        var buffer = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return buffer.toString(StandardCharsets.UTF_8);
            if (buffer.size() >= limit) throw new RequestTooLarge("Request exceeds " + limit + " bytes");
            buffer.write(value);
        }
        if (buffer.size() != 0) throw new EOFException("Request must end with newline");
        return null;
    }
    private static void write(OutputStream output, JsonObject value) throws IOException {
        output.write((value.toString() + "\n").getBytes(StandardCharsets.UTF_8)); output.flush();
    }
    public static JsonObject error(JsonElement id, String code, String message) {
        var result = new JsonObject(); result.add("id", id); result.addProperty("ok", false);
        var error = new JsonObject(); error.addProperty("code", code); error.addProperty("message", message);
        result.add("error", error); return result;
    }
    @Override public void close() {
        running = false;
        try { if (listener != null) listener.close(); } catch (IOException ignored) {}
        try { if (connection != null) connection.close(); } catch (IOException ignored) {}
        if (worker != null) worker.interrupt();
    }
    private static final class RequestTooLarge extends IOException { RequestTooLarge(String message) { super(message); } }
}

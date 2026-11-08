package dev.minecontrol.sdk;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Regression: wait through an empty scan, send exactly once after detection, and exit. */
public final class PlayerGuardTest {
    public static void run() throws Exception {
        try (var listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(500);
            var clicks = new java.util.concurrent.atomic.AtomicInteger();
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread server = new Thread(() -> {
                long tick = 0;
                int observations = 0;
                while (!listener.isClosed()) {
                    try (var socket = listener.accept()) {
                        var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        JsonObject request = JsonParser.parseString(reader.readLine()).getAsJsonObject();
                        String command = request.get("command").getAsString();
                        tick += 5;
                        JsonObject result = new JsonObject();
                        result.addProperty("tick", tick);
                        if (command.equals("ping")) result.addProperty("protocol", 1);
                        if (command.equals("status")) result.addProperty("world_loaded", true);
                        JsonElement payload = result;
                        if (command.equals("players.nearby")) {
                            if (request.get("radius").getAsDouble() != 10) throw new AssertionError("Wrong radius");
                            observations++;
                            payload = JsonParser.parseString(observations == 1 ? "[]"
                                    : "[{\"uuid\":\"test-player\",\"name\":\"Other\",\"distance\":10}]");
                        }
                        if (command.equals("chat.command")) {
                            if (observations < 2 || !request.get("text").getAsString().equals("/res tp liuhome"))
                                throw new AssertionError("Premature or wrong command");
                            clicks.incrementAndGet();
                        }
                        var response = new JsonObject();
                        response.add("id", request.get("id")); response.addProperty("ok", true); response.add("result", payload);
                        socket.getOutputStream().write((response + "\n").getBytes(StandardCharsets.UTF_8));
                    } catch (SocketTimeoutException ignored) {}
                    catch (Throwable e) { if (!listener.isClosed()) failure.set(e); }
                }
            }, "PlayerGuard regression server");
            server.setDaemon(true); server.start();
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Process program = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-cp", System.getProperty("java.class.path"), "PlayerGuard", Integer.toString(listener.getLocalPort()))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if (!program.waitFor(8, TimeUnit.SECONDS)) throw new AssertionError("Guard did not exit after command");
                if (program.exitValue() != 0 || clicks.get() != 1) throw new AssertionError("Expected one command, got " + clicks.get());
                if (failure.get() != null) throw new AssertionError("Fake server failed", failure.get());
            } finally {
                program.destroy();
                if (!program.waitFor(3, TimeUnit.SECONDS)) program.destroyForcibly().waitFor();
            }
            listener.close(); server.join(1000);
        }
        System.out.println("PlayerGuard empty scan, 10-block boundary, exact command and single-send regression passed");
    }
}

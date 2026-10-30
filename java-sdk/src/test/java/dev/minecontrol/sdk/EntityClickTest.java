package dev.minecontrol.sdk;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Regression: an unchanged entity must generate multiple clicks without any GUI transitions. */
public final class EntityClickTest {
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
                        if (command.equals("observe")) {
                            result.addProperty("active", true);
                            observations++;
                            result.add("target", JsonParser.parseString(observations == 1
                                    ? "{\"kind\":\"block\",\"id\":\"minecraft:stone\",\"nbt\":null}"
                                    : observations == 2 ? "null"
                                    : "{\"kind\":\"entity\",\"uuid\":\"same-entity\",\"state\":null,\"nbt\":null}"));
                        }
                        if (command.equals("mouse.click")) clicks.incrementAndGet();
                        var response = new JsonObject();
                        response.add("id", request.get("id")); response.addProperty("ok", true); response.add("result", result);
                        socket.getOutputStream().write((response + "\n").getBytes(StandardCharsets.UTF_8));
                    } catch (SocketTimeoutException ignored) {}
                    catch (Throwable e) { if (!listener.isClosed()) failure.set(e); }
                }
            }, "EntityClick regression server");
            server.setDaemon(true); server.start();
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Process program = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-cp", System.getProperty("java.class.path"), "EntityClick", Integer.toString(listener.getLocalPort()))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (clicks.get() < 3 && program.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
                if (clicks.get() < 3) throw new AssertionError("Unchanged target did not trigger repeated clicks: " + clicks.get());
                if (failure.get() != null) throw new AssertionError("Fake server failed", failure.get());
            } finally {
                program.destroy();
                if (!program.waitFor(3, TimeUnit.SECONDS)) program.destroyForcibly().waitFor();
            }
            listener.close(); server.join(1000);
        }
        System.out.println("EntityClick null/block/entity transitions and repeated-target regression passed");
    }
}

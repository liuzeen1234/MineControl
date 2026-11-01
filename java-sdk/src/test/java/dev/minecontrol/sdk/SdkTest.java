package dev.minecontrol.sdk;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static dev.minecontrol.sdk.Snapshots.*;

/** Wire-level regression tests, with no Minecraft or testing framework dependency. */
public final class SdkTest {
    public static void main(String[] args) throws Exception {
        EntityClickTest.run();
        PlayerGuardTest.run();
        Class.forName("GuardedEntityClickFlowTest").getMethod("run").invoke(null);
        try (var server = new FakeServer()) {
            try (var mc = MineControl.connect(server.port())) {
                check(mc.status().worldLoaded(), "status decoding");
                check(mc.observe().mainHand().remainingDurability() == 42, "typed observation");
                check(mc.observe().target().serializationWarnings().equals("warning"), "target warnings");
                var observation = mc.observe();
                check(observation.target().nbt() == null && observation.mainHand().nbt() == null
                        && observation.mainHand().components() == null, "explicit null NBT and components");
                check(observation.target().state().get("axis").getAsString().equals("x"), "non-null object retained");
                check(mc.region(new BlockPos(0, 1, 2), new BlockPos(3, 4, 5)).from().get(1) == 1, "region arrays");
                mc.ui().type(7, "中文\ntext");
                check(server.last.get("text").getAsString().equals("中文\ntext"), "UTF-8 and newline framing");
                check(mc.ui().list().get(0).id() == 7, "widget decoding");
                mc.key().hold("w", 20);
                check(server.last.get("ticks").getAsInt() == 20, "hold parameters");
                long before = mc.status().tick();
                mc.waitTicks(4);
                check(mc.status().tick() >= before + 4, "client tick wait");
                mc.mouse().holdAndWait(MineControl.MouseButton.LEFT, 3);
                check(server.tick >= server.lastHoldTick + 3, "hold wait uses operation tick");
                server.freeze = true;
                try { mc.waitTicks(1, Duration.ofMillis(25)); throw new AssertionError("wait timeout"); }
                catch (SocketTimeoutException expected) {}
                server.freeze = false;
                Thread.currentThread().interrupt();
                try { mc.waitTicks(1); throw new AssertionError("interrupt ignored"); }
                catch (InterruptedException expected) {}
                try { mc.call("reject", new JsonObject()); throw new AssertionError("error accepted"); }
                catch (CommandException expected) { check(expected.code().equals("NO_WORLD"), "error code"); }
                expectIo(mc, "bad-id");
                expectIo(mc, "partial");
                expectIo(mc, "bad-json");
                expectIo(mc, "oversize");
            }
            check(server.resets == 1, "input cleanup on close");
            try (var mc = MineControl.connect(server.port())) { mc.status(); }
            check(server.resets == 1, "read-only close must not reset another controller");
            try {
                try (var mc = MineControl.connect(server.port())) {
                    mc.key().down("w");
                    throw new IllegalStateException("user program failed");
                }
            } catch (IllegalStateException expected) {}
            check(server.resets == 2, "cleanup when program throws");
            var mc = MineControl.connect(server.port());
            mc.key().down("w");
            Thread.currentThread().interrupt();
            mc.close();
            check(Thread.interrupted() && server.resets == 3, "interrupted cleanup preserves flag");
            mc.close();
            try { mc.status(); throw new AssertionError("closed call"); } catch (IOException expected) {}
            check(server.failure == null, "fake server failed: " + server.failure);
        }
        System.out.println("Java SDK wire/types/ticks/errors/cleanup checks passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void expectIo(MineControl mc, String command) throws Exception {
        try { mc.call(command, new JsonObject()); throw new AssertionError(command); }
        catch (IOException expected) {}
    }
    private static final class FakeServer implements AutoCloseable {
        final ServerSocket listener;
        final Thread worker;
        volatile JsonObject last;
        volatile Throwable failure;
        volatile boolean freeze;
        volatile long tick, lastHoldTick;
        volatile int resets;
        FakeServer() throws IOException {
            listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(this::serve);
            worker.start();
        }
        int port() { return listener.getLocalPort(); }
        void serve() {
            while (!listener.isClosed()) {
                try (var socket = listener.accept()) {
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    last = JsonParser.parseString(reader.readLine()).getAsJsonObject();
                    String command = last.get("command").getAsString();
                    if (!freeze) tick++;
                    var reply = new JsonObject();
                    reply.add("id", last.get("id")); reply.addProperty("ok", true);
                    JsonElement result = JsonParser.parseString("{\"tick\":" + tick + ",\"world_loaded\":true}");
                    switch (command) {
                        case "ping" -> result = JsonParser.parseString("{\"pong\":true,\"protocol\":1}");
                        case "observe" -> result = JsonParser.parseString("{\"active\":true,\"main_hand\":{\"remaining_durability\":42,\"nbt\":null,\"components\":null},\"off_hand\":null,\"target\":{\"kind\":\"block\",\"id\":\"minecraft:stone\",\"state\":{\"axis\":\"x\"},\"nbt\":null,\"serialization_warnings\":\"warning\"}}");
                        case "region.query" -> result = JsonParser.parseString("{\"from\":[0,1,2],\"to\":[3,4,5]}");
                        case "ui.list" -> result = JsonParser.parseString("[{\"id\":7,\"label\":\"输入\"}]");
                        case "key.hold", "mouse.hold" -> lastHoldTick = tick;
                        case "input.reset" -> resets++;
                        case "reject" -> {
                            reply.addProperty("ok", false);
                            reply.add("error", JsonParser.parseString("{\"code\":\"NO_WORLD\",\"message\":\"no world\"}"));
                        }
                        case "bad-id" -> reply.addProperty("id", "wrong");
                        case "partial", "bad-json", "oversize" -> {
                            String raw = command.equals("partial") ? "{}" : command.equals("bad-json") ? "oops\n" : "x".repeat(1_048_578) + "\n";
                            try { socket.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8)); }
                            catch (SocketException ignored) { /* Client rejects oversized frames early. */ }
                            continue;
                        }
                    }
                    reply.add("result", result);
                    socket.getOutputStream().write((reply + "\r\n").getBytes(StandardCharsets.UTF_8));
                } catch (Throwable e) { if (!listener.isClosed()) failure = e; }
            }
        }
        @Override public void close() throws Exception { listener.close(); worker.join(2000); }
    }
}

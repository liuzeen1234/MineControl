import com.google.gson.*;
import dev.minecontrol.sdk.MineControl;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.*;

/** Exercise real macro decisions with a fake tick stream and interrupted/loading worlds. */
public final class GuardedEntityClickFlowTest {
    public static void run() throws Exception {
        for (int mode = 0; mode < 6; mode++) scenario(mode);
        System.out.println("Combined macro: out/work commands, continuous 20-tick reset, no repeated out spam, resumed clicks/guard, transfer and stop passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void scenario(int mode) throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicInteger clicks = new AtomicInteger(), retreats = new AtomicInteger(), homes = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        int initialClicks = mode == 2 ? 2 : 0;
        try (var listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(500);
            Thread server = new Thread(() -> {
                long tick = 0, clearSince = -1;
                int observations = 0, loadingPolls = 0, outScans = 0;
                boolean detected = false, released = false;
                while (!listener.isClosed()) {
                    try (var socket = listener.accept()) {
                        var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        JsonObject request = JsonParser.parseString(reader.readLine()).getAsJsonObject();
                        String command = request.get("command").getAsString();
                        tick++;
                        JsonObject result = new JsonObject();
                        result.addProperty("tick", tick);
                        if (command.equals("ping")) result.addProperty("protocol", 1);
                        if (command.equals("status")) result.addProperty("world_loaded",
                                !((mode == 3 || mode == 4) && retreats.get() > 0) && clicks.get() < initialClicks + 1 && loadingPolls-- <= 0);
                        JsonElement payload = result;
                        if (command.equals("observe")) {
                            observations++;
                            result.addProperty("active", true);
                            result.add("target", JsonParser.parseString("{\"kind\":\"entity\",\"uuid\":\"same-target\"}"));
                        }
                        if (command.equals("players.nearby")) {
                            check(request.get("radius").getAsDouble() == 10, "Wrong radius");
                            if (retreats.get() > homes.get()) {
                                outScans++;
                                // Briefly clear, then another player appears: the 20 ticks must restart.
                                detected = outScans <= 2 || outScans == 5;
                            } else {
                                detected = homes.get() == 1 || (homes.get() == 0 &&
                                        (mode == 0 || mode >= 3 || (mode == 1 && observations >= 1)
                                        || (mode == 2 && clicks.get() >= 2 && observations >= 3)));
                            }
                            if (detected) clearSince = -1;
                            else if (clearSince < 0) clearSince = tick;
                            payload = JsonParser.parseString(detected
                                    ? "[{\"uuid\":\"other\",\"name\":\"Other\",\"distance\":10}]" : "[]");
                        }
                        if (command.equals("input.reset") && detected) released = true;
                        if (command.equals("chat.command")) {
                            String text = request.get("text").getAsString();
                            if (text.equals("/home out")) {
                                check(detected && released, "Retreat without detection or release");
                                check(retreats.get() == homes.get(), "Repeated out while already avoiding");
                                retreats.incrementAndGet();
                                outScans = 0;
                                clearSince = -1;
                                if (mode == 5) loadingPolls = 4;
                                released = false;
                            } else if (text.equals("/home work")) {
                                check(!detected && clearSince >= 0 && tick - clearSince >= 20, "Returned before 20 continuous clear ticks");
                                check(outScans > 5, "Did not reset clear timer after player reappeared");
                                homes.incrementAndGet();
                                check(retreats.get() == homes.get(), "Repeated command");
                                if (mode == 5) loadingPolls = 4;
                            } else throw new AssertionError("Wrong command: " + text);
                        }
                        if (command.equals("mouse.click")) {
                            check(!detected, "Clicked after detecting player");
                            if (retreats.get() > 0) {
                                check(homes.get() == 2 && retreats.get() == 2, "Did not repeat retreat before resuming");

                            }
                            clicks.incrementAndGet();
                        }
                        var response = new JsonObject();
                        response.add("id", request.get("id")); response.addProperty("ok", true); response.add("result", payload);
                        socket.getOutputStream().write((response + "\n").getBytes(StandardCharsets.UTF_8));
                    } catch (SocketTimeoutException ignored) {
                    } catch (Throwable e) { if (!listener.isClosed()) failure.compareAndSet(null, e); }
                }
            }, "Guarded macro flow regression");
            server.setDaemon(true);
            server.start();
            var timer = new GuardedEntityClick.Timer() {
                public long nanoTime() { return clock.get(); }
                public void sleep(long millis) throws InterruptedException {
                    if (mode == 3) throw new InterruptedException("test stop during world load");
                    clock.addAndGet(Duration.ofMillis(millis).toNanos());
                }
            };
            try (var mc = MineControl.connect(listener.getLocalPort(), Duration.ofSeconds(2))) {
                try {
                    GuardedEntityClick.run(mc, timer);
                    check(mode != 3, "World load ignored interruption");
                } catch (InterruptedException expected) { check(mode == 3, "Unexpected interruption"); }
            }
            check(failure.get() == null, "Fake server failed: " + failure.get());
            if (mode == 3 || mode == 4) {
                check(retreats.get() == 1 && homes.get() == 0 && clicks.get() == 0, "Stop/unload continued macro");
            } else {
                check(retreats.get() == 2 && homes.get() == 2 && clicks.get() == initialClicks + 1, "Wrong final cycle counts");

            }
            listener.close();
            server.join(1000);
        }
    }
}

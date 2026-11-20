import com.google.gson.*;
import dev.minecontrol.sdk.*;
import java.util.*;

final class CooperationSupport {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    static final String EVENTS = "cooperation.events", COMMAND = "cooperation.command", SAMPLE = "cooperation.sample";
    static JsonObject data(String type) { var o = new JsonObject(); o.addProperty("type", type); return o; }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void forbidden(Runnable operation) {
        try { operation.run(); throw new AssertionError("Read-only role was allowed to control input"); }
        catch (CommandException e) { require(e.code().equals("FORBIDDEN"), e.toString()); }
    }
    static long deadline() { return System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60); }
    static void alive(long deadline) { require(System.nanoTime() < deadline, "Cooperation deadline exceeded"); }
}

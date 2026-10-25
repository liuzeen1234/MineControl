package dev.minecontrol;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
public final class MacroTest {
    private static JsonArray script(String text) { return JsonParser.parseString(text).getAsJsonArray(); }
    private static void require(boolean b) { if (!b) throw new AssertionError(); }
    public static void run() {
        int[] releases = {0}; var engine = new MacroEngine(() -> releases[0]++);
        var state = new JsonObject(); state.addProperty("world_loaded", true); state.addProperty("tick", 10);
        var commands = new ArrayList<String>();
        java.util.function.Function<JsonObject, CompletableFuture<JsonObject>> exec = r -> {
            commands.add(r.get("command").getAsString()); var reply = new JsonObject(); reply.addProperty("ok", true);
            return CompletableFuture.completedFuture(reply);
        };
        engine.start(script("""
            [{"repeat":3,"do":[{"if":{"field":"world_loaded","value":true},"then":[{"command":"ping"}],"else":[{"command":"status"}]},{"wait":2}]}]
            """));
        for (int t=0;t<100;t++) engine.tick(t, () -> state, exec);
        require(commands.equals(List.of("ping", "ping", "ping")) && releases[0]==1);
        require(engine.status().get("state").getAsString().equals("completed"));
        commands.clear();
        engine.start(script("[{\"wait\":3},{\"command\":\"ping\"}]"));
        engine.tick(0, () -> state, exec);
        engine.tick(1, () -> state, exec);
        engine.tick(2, () -> state, exec); require(commands.isEmpty());
        engine.tick(3, () -> state, exec); require(commands.equals(List.of("ping")));
        engine.tick(4, () -> state, exec);
        commands.clear();
        engine.start(script("[{\"repeat\":0,\"do\":[{\"command\":\"ping\"}]},{\"if\":{\"field\":\"tick\",\"op\":\"<\",\"value\":0},\"then\":[{\"command\":\"ping\"}],\"else\":[{\"command\":\"status\"}]}]"));
        for(int t=0;t<20;t++) engine.tick(t, () -> state, exec);
        require(commands.equals(List.of("status"))); commands.clear();
        engine.start(script("""
            [{"while":{"field":"tick","op":">=","value":10},"do":[{"repeat":2,"do":[{"command":"status"},{"break":true}]},{"break":true}]}]
            """));
        for(int t=0;t<30;t++) engine.tick(t, () -> state, exec);
        require(commands.equals(List.of("status")));
        engine.start(script("[{\"while\":{\"field\":\"world_loaded\",\"value\":true},\"do\":[]}]"));
        for(int t=0;t<100;t++) engine.tick(t, () -> state, exec);
        require(engine.status().get("state").getAsString().equals("running"));
        try { engine.start(script("[]")); throw new AssertionError(); } catch (IllegalStateException expected) {}
        engine.stop(); require(engine.status().get("state").getAsString().equals("stopped"));
        for(String invalid: List.of("[{\"break\":true}]", "[{\"command\":\"macro.run\"}]", "[{\"repeat\":-1,\"do\":[]}]", "[{\"wait\":0}]", "[{\"repeat\":2}]")) {
            try { engine.start(script(invalid)); throw new AssertionError(invalid); } catch (IllegalArgumentException expected) {}
        }
        engine.start(script("[{\"if\":{\"field\":\"missing\",\"value\":true},\"then\":[]}]"));
        engine.tick(0, () -> state, exec); require(engine.status().get("state").getAsString().equals("failed"));
        var pending = new CompletableFuture<JsonObject>();
        engine.start(script("[{\"command\":\"wait.screen\",\"screen\":\"none\"}]"));
        engine.tick(0, () -> state, r -> pending); engine.stop(); require(pending.isCancelled());
        engine.start(script("[{\"command\":\"ping\"}]"));
        engine.tick(0, () -> state, r -> CompletableFuture.completedFuture(JsonParser.parseString("{\"ok\":false,\"error\":{\"code\":\"TEST\"}}").getAsJsonObject()));
        engine.tick(1, () -> state, exec); require(engine.status().get("state").getAsString().equals("failed"));
        state.add("observe", JsonParser.parseString("{\"target\":null,\"main_hand\":{\"nbt\":{\"data\":{\"minecraft:custom_data\":{\"a.b\":[7]}}}}}"));
        commands.clear();
        engine.start(script("""
            [{"if":{"all":[
              {"field":["observe","main_hand","nbt","data","minecraft:custom_data","a.b","0"],"value":7},
              {"not":{"field":"observe.target.missing","op":"exists","value":true}},
              {"any":[{"field":"observe.target","value":null},{"field":"tick","value":-1}]},
              {"field":"observe.target.absent","op":"!=","value":3}
            ]},"then":[{"command":"ping"}],"else":[{"command":"observe"}]}]
            """));
        for (int t=0;t<10;t++) engine.tick(t, () -> state, exec);
        require(commands.equals(List.of("observe")));
        commands.clear();
        engine.start(script("""
            [{"if":{"field":["observe","main_hand","nbt","data","minecraft:custom_data","a.b","0"],"op":">=","value":7},"then":[{"command":"ping"}]}]
            """));
        for (int t=0;t<10;t++) engine.tick(t, () -> state, exec);
        require(commands.equals(List.of("ping")));
        for (String invalid : List.of("[{\"if\":{\"all\":[]},\"then\":[]}]", "[{\"if\":{\"field\":[],\"value\":0},\"then\":[]}]", "[{\"if\":{\"field\":\"tick\",\"op\":\"exists\",\"value\":0},\"then\":[]}]")) {
            try { engine.start(script(invalid)); throw new AssertionError(invalid); } catch (IllegalArgumentException expected) {}
        }
        var oversized = new JsonObject(); oversized.addProperty("snbt", "x".repeat(900001));
        try { ObservationData.bounded(oversized); throw new AssertionError(); } catch (IllegalStateException expected) {}
        System.out.println("Macro loop/condition/wait/stop/error checks passed");
    }
}

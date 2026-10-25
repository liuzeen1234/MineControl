package dev.minecontrol;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;

/** Lossless SNBT plus a convenient JSON view; never silently truncate observations. */
public final class ObservationData {
    private ObservationData() {}
    public static JsonObject raw(JsonElement json, String snbt) {
        var result = new JsonObject();
        result.addProperty("source", "client_snapshot");
        result.addProperty("completeness", "unknown");
        result.add("data", json); result.addProperty("snbt", snbt);
        return result;
    }
    public static JsonObject bounded(JsonObject result) {
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > 900000)
            throw new IllegalStateException("Observation exceeds 900000 UTF-8 bytes; no data returned");
        return result;
    }
}

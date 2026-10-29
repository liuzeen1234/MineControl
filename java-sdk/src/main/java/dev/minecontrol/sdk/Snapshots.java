package dev.minecontrol.sdk;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/** Client snapshots; nullable fields represent data unavailable in the current game state. */
public final class Snapshots {
    private Snapshots() {}
    public record Status(long tick, String screen,
            @SerializedName("world_loaded") boolean worldLoaded,
            @SerializedName("window_focused") boolean windowFocused,
            @SerializedName("background_control") boolean backgroundControl,
            @SerializedName("cursor_locked") boolean cursorLocked,
            @SerializedName("held_keys") List<Integer> heldKeys,
            @SerializedName("held_buttons") List<Integer> heldButtons,
            @SerializedName("player_x") Double playerX,
            @SerializedName("player_y") Double playerY,
            @SerializedName("player_z") Double playerZ,
            Double yaw, Double pitch, @SerializedName("on_ground") Boolean onGround,
            @SerializedName("gui_width") int guiWidth, @SerializedName("gui_height") int guiHeight,
            @SerializedName("cursor_x") double cursorX, @SerializedName("cursor_y") double cursorY) {}
    public record BlockPos(int x, int y, int z) {}
    public record Position(double x, double y, double z, BlockPos block,
            double yaw, double pitch, String dimension, long tick) {}
    public record Player(double x, double y, double z, double health, int food,
            boolean alive, @SerializedName("game_mode") String gameMode) {}
    public record Item(String id, int count, int damage,
            @SerializedName("max_damage") int maxDamage,
            @SerializedName("remaining_durability") int remainingDurability,
            String format, JsonObject nbt, JsonObject components) {}
    public record Target(String kind, String id, BlockPos pos, JsonObject state,
            String face, double distance, String uuid,
            @SerializedName("entity_id") Integer entityId, JsonObject nbt,
            @SerializedName("serialization_warnings") String serializationWarnings) {}
    public record Observation(long tick, String source, String dimension,
            @SerializedName("world_loaded") boolean worldLoaded, boolean active, Player player,
            @SerializedName("main_hand") Item mainHand,
            @SerializedName("off_hand") Item offHand, Target target) {}
    public record Slot(int slot, Item stack) {}
    public record Inventory(long tick, String source, List<Slot> slots, Map<String, Integer> counts) {}
    public record Region(String format, String source, String dimension, long tick,
            List<Integer> from, List<Integer> to, int volume,
            @SerializedName("block_count") int blockCount,
            @SerializedName("air_omitted") boolean airOmitted,
            @SerializedName("block_entity_data_included") boolean blockEntityDataIncluded,
            String blueprint) {}
    public record Widget(int id, String type, String label, boolean enabled, boolean visible,
            int x, int y, int width, int height, Boolean editable, String text, Boolean value) {}
}

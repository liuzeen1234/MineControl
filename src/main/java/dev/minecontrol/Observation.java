package dev.minecontrol;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.nbt.*;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.*;

/** Only reads the client world, including in singleplayer. */
public final class Observation {
    private static JsonObject raw(NbtElement tag) {
        return ObservationData.raw(NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag), tag.toString());
    }
    private static JsonObject item(MinecraftClient client, ItemStack stack) {
        var r = new JsonObject();
        r.addProperty("id", Registries.ITEM.getId(stack.getItem()).toString());
        r.addProperty("count", stack.getCount());
        r.addProperty("damage", stack.getDamage());
        r.addProperty("max_damage", stack.getMaxDamage());
        r.addProperty("remaining_durability", stack.getMaxDamage() - stack.getDamage());
        r.addProperty("format", "nbt");
        r.add("nbt", raw(stack.isEmpty() ? new NbtCompound() : stack.writeNbt(new NbtCompound())));
        return r;
    }
    public static JsonObject capture(MinecraftClient client, long tick) {
        var r = new JsonObject();
        r.addProperty("tick", tick); r.addProperty("source", "client_snapshot");
        r.addProperty("world_loaded", client.world != null);
        r.addProperty("active", client.currentScreen == null && client.player != null && client.world != null);
        r.add("player", JsonNull.INSTANCE);
        r.add("main_hand", JsonNull.INSTANCE); r.add("off_hand", JsonNull.INSTANCE); r.add("target", JsonNull.INSTANCE);
        if (client.world == null || client.player == null) return r;
        var player = new JsonObject(); player.addProperty("x", client.player.getX()); player.addProperty("y", client.player.getY()); player.addProperty("z", client.player.getZ());
        r.add("player", player);
        r.add("main_hand", item(client, client.player.getMainHandStack()));
        r.add("off_hand", item(client, client.player.getOffHandStack()));
        r.addProperty("dimension", client.world.getRegistryKey().getValue().toString());
        var hit = client.crosshairTarget;
        if (hit == null || hit.getType() == HitResult.Type.MISS) return ObservationData.bounded(r);
        var target = new JsonObject();
        target.addProperty("distance", client.player.getEyePos().distanceTo(hit.getPos()));
        if (hit instanceof BlockHitResult block) {
            var pos = block.getBlockPos(); var state = client.world.getBlockState(pos);
            target.addProperty("kind", "block");
            target.addProperty("id", Registries.BLOCK.getId(state.getBlock()).toString());
            var position = new JsonObject(); position.addProperty("x", pos.getX()); position.addProperty("y", pos.getY()); position.addProperty("z", pos.getZ()); target.add("pos", position);
            target.addProperty("face", block.getSide().getName());
            var properties = new JsonObject(); state.getEntries().forEach((key, value) -> properties.addProperty(key.getName(), value.toString())); target.add("state", properties);
            var be = client.world.getBlockEntity(pos);
            target.add("nbt", be == null ? JsonNull.INSTANCE : raw(be.createNbtWithIdentifyingData()));
        } else if (hit instanceof EntityHitResult entityHit) {
            var entity = entityHit.getEntity();
            target.addProperty("kind", "entity"); target.addProperty("id", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
            target.addProperty("uuid", entity.getUuid().toString()); target.addProperty("entity_id", entity.getId());
            var output = new NbtCompound();
            entity.writeNbt(output); target.add("nbt", raw(output));
        }
        r.add("target", target); return ObservationData.bounded(r);
    }
}

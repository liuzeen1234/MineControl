package dev.minecontrol;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;

/** Only reads the client world, including in singleplayer. */
public final class Observation {
    private static JsonObject raw(Tag tag) {
        return ObservationData.raw(NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag), tag.toString());
    }
    private static JsonObject item(Minecraft client, ItemStack stack) {
        var r = new JsonObject();
        r.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        r.addProperty("count", stack.getCount());
        r.addProperty("damage", stack.getDamageValue());
        r.addProperty("max_damage", stack.getMaxDamage());
        r.addProperty("remaining_durability", stack.getMaxDamage() - stack.getDamageValue());
        r.addProperty("format", "components");
        r.add("components", raw(net.minecraft.core.component.DataComponentMap.CODEC.encodeStart(client.level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack.getComponents()).getOrThrow()));
        r.add("nbt", raw(stack.isEmpty() ? new CompoundTag() : ItemStack.CODEC.encodeStart(client.level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow()));
        return r;
    }
    public static JsonObject capture(Minecraft client, long tick) {
        var r = new JsonObject();
        r.addProperty("tick", tick); r.addProperty("source", "client_snapshot");
        r.addProperty("world_loaded", client.level != null);
        r.addProperty("active", client.gui.screen() == null && client.player != null && client.level != null);
        r.add("player", JsonNull.INSTANCE);
        r.add("main_hand", JsonNull.INSTANCE); r.add("off_hand", JsonNull.INSTANCE); r.add("target", JsonNull.INSTANCE);
        if (client.level == null || client.player == null) return r;
        var player = new JsonObject(); player.addProperty("x", client.player.getX()); player.addProperty("y", client.player.getY()); player.addProperty("z", client.player.getZ());
        r.add("player", player);
        r.add("main_hand", item(client, client.player.getMainHandItem()));
        r.add("off_hand", item(client, client.player.getOffhandItem()));
        r.addProperty("dimension", client.level.dimension().identifier().toString());
        var hit = client.hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) return ObservationData.bounded(r);
        var target = new JsonObject();
        target.addProperty("distance", client.player.getEyePosition().distanceTo(hit.getLocation()));
        if (hit instanceof BlockHitResult block) {
            var pos = block.getBlockPos(); var state = client.level.getBlockState(pos);
            target.addProperty("kind", "block");
            target.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            var position = new JsonObject(); position.addProperty("x", pos.getX()); position.addProperty("y", pos.getY()); position.addProperty("z", pos.getZ()); target.add("pos", position);
            target.addProperty("face", block.getDirection().getName());
            var properties = new JsonObject(); state.getValues().forEach(value -> properties.addProperty(value.property().getName(), value.valueName())); target.add("state", properties);
            var be = client.level.getBlockEntity(pos);
            target.add("nbt", be == null ? JsonNull.INSTANCE : raw(be.saveWithFullMetadata(client.level.registryAccess())));
        } else if (hit instanceof EntityHitResult entityHit) {
            var entity = entityHit.getEntity();
            target.addProperty("kind", "entity"); target.addProperty("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            target.addProperty("uuid", entity.getUUID().toString()); target.addProperty("entity_id", entity.getId());
            var problems = new ProblemReporter.Collector();
            var output = TagValueOutput.createWithContext(problems, client.level.registryAccess());
            entity.saveWithoutId(output); target.add("nbt", raw(output.buildResult()));
            if (!problems.isEmpty()) target.addProperty("serialization_warnings", problems.getReport());
        }
        r.add("target", target); return ObservationData.bounded(r);
    }
}

package dev.minecontrol;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.core.registries.BuiltInRegistries;

/** Reports received damage packets, never infers an attacker from nearby entities. */
public final class DamageObservation {
    private static JsonObject last;
    private static Object level;
    private static long received;
    public static void record(Minecraft client, ClientboundDamageEventPacket packet) {
        if(client.player==null || client.level==null || packet.entityId()!=client.player.getId()) return;
        var source=packet.getSource(client.level);
        var data=new JsonObject();
        data.addProperty("source","server_damage_packet");
        data.addProperty("type",source.typeHolder().unwrapKey().map(k->k.identifier().toString()).orElse(source.getMsgId()));
        var attacker=source.getEntity();var direct=source.getDirectEntity();
        data.addProperty("attacker",attacker==null?"unknown":BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString());
        data.addProperty("attacker_name",attacker==null?"unknown":attacker.getName().getString());
        data.addProperty("direct_entity",direct==null?"unknown":BuiltInRegistries.ENTITY_TYPE.getKey(direct.getType()).toString());
        data.addProperty("cause_entity_id",packet.sourceCauseId());
        data.addProperty("direct_entity_id",packet.sourceDirectId());
        last=data;level=client.level;received=System.nanoTime();
    }
    public static JsonObject snapshot(Minecraft client) {
        var data=new JsonObject();
        long age=(System.nanoTime()-received)/1_000_000;
        if(last==null || level!=client.level || age>5000) {
            data.addProperty("source","unknown");data.addProperty("type","unknown");return data;
        }
        data=last.deepCopy();data.addProperty("age_ms",age);return data;
    }
}

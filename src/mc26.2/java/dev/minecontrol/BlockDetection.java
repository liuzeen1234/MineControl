package dev.minecontrol;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

/** Bounded, client-thread-only scan. Never loads a chunk or controls player input. */
final class BlockDetection {
    static final List<String> TYPES = List.of("spawner", "end_portal_frame", "iron_door", "iron_bars", "iron_chain", "rail", "reinforced_deepslate", "vault", "budding_amethyst", "crying_obsidian", "wet_sponge");
    static final List<String> NAMES = List.of("刷怪笼", "末地框架", "铁门", "铁栏杆", "锁链", "普通铁轨", "强化深板岩", "试炼宝库", "紫水晶母岩", "哭泣的黑曜石", "潮湿的海绵");
    private Object level;
    private final ArrayDeque<int[]> pending = new ArrayDeque<>();
    private int section;
    private String scanId, scanWorld, scanDimension;
    static int type(BlockState state) {
        int type = state.is(Blocks.SPAWNER) ? 0 : state.is(Blocks.END_PORTAL_FRAME) ? 1
                : state.is(Blocks.IRON_DOOR) ? 2 : state.is(Blocks.IRON_BARS) ? 3
                : state.is(Blocks.IRON_CHAIN) ? 4 : state.is(Blocks.RAIL) ? 5
                : state.is(Blocks.REINFORCED_DEEPSLATE) ? 6 : state.is(Blocks.VAULT) ? 7 : state.is(Blocks.BUDDING_AMETHYST) ? 8 : state.is(Blocks.CRYING_OBSIDIAN) ? 9 : state.is(Blocks.WET_SPONGE) ? 10 : -1;
        if (type == 2 && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) return -1;
        return type;
    }
    static String world(Minecraft mc) {
        if (mc.getSingleplayerServer() != null)
            return "local:" + mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if (mc.getCurrentServer() == null) throw new IllegalStateException("Cannot identify current server");
        return "server:" + mc.getCurrentServer().ip;
    }
    JsonObject scan(Minecraft mc, JsonObject request) {
        if (request.has("begin") && request.get("begin").getAsBoolean()) {
            level = mc.level; pending.clear(); section = 0;
            scanId = UUID.randomUUID().toString(); scanWorld = world(mc);
            scanDimension = mc.level.dimension().identifier().toString();
            int radius = mc.options.renderDistance().get();
            int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
            var chunks = new ArrayList<int[]>();
            for (int x = cx-radius; x <= cx+radius; x++) for (int z = cz-radius; z <= cz+radius; z++)
                if (mc.level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false) != null) chunks.add(new int[]{x,z});
            chunks.sort(Comparator.comparingInt(p -> Math.max(Math.abs(p[0]-cx), Math.abs(p[1]-cz))));
            pending.addAll(chunks);
        } else if (scanId == null || !request.has("scan_id") || !scanId.equals(request.get("scan_id").getAsString())) {
            throw new IllegalArgumentException("Scan expired; begin a new scan");
        }
        if (level != mc.level || !scanWorld.equals(world(mc))) throw new IllegalStateException("World changed during scan");
        var hits = new JsonArray();
        long deadline = System.nanoTime() + 3_000_000;
        for (int work = 0; work < 512 && !pending.isEmpty() && System.nanoTime() < deadline; work++) {
            int[] p = pending.peek();
            var chunk = mc.level.getChunkSource().getChunk(p[0], p[1], ChunkStatus.FULL, false);
            if (chunk == null) { pending.remove(); section = 0; continue; }
            var sections = chunk.getSections();
            int sy = section++;
            if (section >= sections.length) { pending.remove(); section = 0; }
            var blocks = sections[sy];
            if (blocks.hasOnlyAir() || !blocks.maybeHas(s -> type(s) >= 0)) continue;
            for (int y=0; y<16; y++) for (int z=0; z<16; z++) for (int x=0; x<16; x++) {
                int t = type(blocks.getBlockState(x,y,z));
                if (t < 0) continue;
                var hit = new JsonObject(); hit.addProperty("type", TYPES.get(t));
                hit.addProperty("x", p[0]*16+x); hit.addProperty("y", chunk.getMinY()+sy*16+y); hit.addProperty("z", p[1]*16+z); hits.add(hit);
            }
            break;
        }
        var result = new JsonObject(); result.addProperty("world", scanWorld);
        result.addProperty("dimension", scanDimension); result.addProperty("scan_id", scanId);
        result.addProperty("complete", pending.isEmpty()); result.add("hits", hits); return result;
    }
    static JsonObject mark(Minecraft mc, JsonObject request) throws Exception {
        if (!world(mc).equals(request.get("world").getAsString()) || !mc.level.dimension().identifier().toString().equals(request.get("dimension").getAsString()))
            throw new IllegalArgumentException("World changed before waypoint creation");
        String type = request.get("type").getAsString(); int index = TYPES.indexOf(type);
        if (index < 0) throw new IllegalArgumentException("Unsupported detection type");
        int x=request.get("x").getAsInt(), y=request.get("y").getAsInt(), z=request.get("z").getAsInt();
        Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
        Object session = sessionClass.getMethod("getCurrentSession").invoke(null);
        if (session == null) throw new IllegalStateException("Xaero session not ready");
        Object minimap = invoke(invoke(session, "getMinimapProcessor"), "getSession");
        Object world = invoke(invoke(minimap, "getWorldManager"), "getAutoWorld");
        if (world == null || !mc.level.dimension().equals(invoke(world,"getDimId"))) throw new IllegalStateException("Xaero world not ready");
        Object set = invoke(world, "getCurrentWaypointSet");
        String name = "检测·" + NAMES.get(index);
        boolean exists = false;
        for (Object waypoint : (Iterable<?>) invoke(set, "getWaypoints")) {
            if (name.equals(invoke(waypoint,"getName")) && (int)invoke(waypoint,"getX")==x && (int)invoke(waypoint,"getY")==y && (int)invoke(waypoint,"getZ")==z) { exists=true; break; }
        }
        if (!exists) {
            Class<?> waypoint = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Object point = waypoint.getConstructor(int.class,int.class,int.class,String.class,String.class,int.class)
                    .newInstance(x,y,z,name,"检", new int[]{12,5,15,7,6,14,3,11,13,10,4}[index]);
            waypoint.getMethod("setYIncluded",boolean.class).invoke(point,true);
            set.getClass().getMethod("add",waypoint).invoke(set,point);
        }
        Object io = invoke(minimap,"getWorldManagerIO");
        io.getClass().getMethod("saveWorld",world.getClass()).invoke(io,world);
        var result = new JsonObject(); result.addProperty("marked",true);result.addProperty("operation",exists?"already_exists":"created"); return result;
    }
    private static Object invoke(Object object, String method) throws Exception { return object.getClass().getMethod(method).invoke(object); }
}

package dev.minecontrol;

import com.google.gson.*;
import java.nio.file.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.*;

/** Explicit file-triggered setup/inspection, only for the isolated block detector world. */
public final class BlockDetectionClientTest implements ClientModInitializer {
    @Override public void onInitializeClient() {
        Path config=FabricLoader.getInstance().getConfigDir();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Path trigger=config.resolve("block-test.request.json");
            if (!Files.exists(trigger) || mc.player==null) return;
            JsonObject report=new JsonObject();
            try {
                var request=JsonParser.parseString(Files.readString(trigger)).getAsJsonObject(); Files.delete(trigger);
                report.add("id",request.get("id"));
                String action=request.get("action").getAsString();
                if (action.equals("setup")) {
                    var server=mc.getSingleplayerServer(); String player=mc.player.getName().getString();
                    server.execute(() -> {
                        var source=server.createCommandSourceStack();
                        var commands=server.getCommands();
                        commands.performPrefixedCommand(source,"gamemode spectator "+player);
                        commands.performPrefixedCommand(source,"tp "+player+" 0 110 0");
                        String[] types={"spawner","end_portal_frame","iron_door[half=lower]","iron_bars","iron_chain","rail","reinforced_deepslate","vault","budding_amethyst","crying_obsidian","wet_sponge"};
                        for(int i=0;i<types.length;i++) {
                            int x=i*3;
                            commands.performPrefixedCommand(source,"setblock "+x+" 99 0 stone");
                            commands.performPrefixedCommand(source,"setblock "+x+" 100 0 "+types[i]);
                        }
                        commands.performPrefixedCommand(source,"setblock 6 101 0 iron_door[half=upper]");
                        commands.performPrefixedCommand(source,"setblock 0 170 0 spawner");
                        commands.performPrefixedCommand(source,"setblock 0 171 0 spawner");
                        commands.performPrefixedCommand(source,"setblock 85 99 0 stone");
                        commands.performPrefixedCommand(source,"setblock 86 99 0 stone");
                        commands.performPrefixedCommand(source,"setblock 85 100 0 rail");
                        commands.performPrefixedCommand(source,"setblock 86 100 0 rail");
                        for (String type: new String[]{"powered_rail","detector_rail","activator_rail"}) {
                            int x=30+java.util.List.of("powered_rail","detector_rail","activator_rail").indexOf(type)*3;
                            commands.performPrefixedCommand(source,"setblock "+x+" 99 0 stone");
                            commands.performPrefixedCommand(source,"setblock "+x+" 100 0 "+type);
                        }
                    });
                    if (BlockDetection.type(Blocks.VAULT.defaultBlockState()) != 7
                            || BlockDetection.type(Blocks.VAULT.defaultBlockState().setValue(net.minecraft.world.level.block.VaultBlock.OMINOUS,true)) != 7)
                        throw new AssertionError("normal and ominous vault detection");
                    if(BlockDetection.type(Blocks.IRON_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER))!=-1) throw new AssertionError("upper door");
                    for(var b: java.util.List.of(Blocks.POWERED_RAIL,Blocks.DETECTOR_RAIL,Blocks.ACTIVATOR_RAIL,Blocks.TRIAL_SPAWNER))
                        if(BlockDetection.type(b.defaultBlockState())!=-1) throw new AssertionError("excluded block");
                } else if(action.equals("start")) {
                    MineControlClient.javaMacros().refresh().thenRun(() -> MineControlClient.javaMacros().start("block-detector.jar"));
                } else if(action.equals("stop")) MineControlClient.javaMacros().stop("block-detector.jar");
                else if(action.equals("inspect")) {
                    Object session=Class.forName("xaero.common.XaeroMinimapSession").getMethod("getCurrentSession").invoke(null);
                    Object minimap=invoke(invoke(session,"getMinimapProcessor"),"getSession");
                    Object world=invoke(invoke(minimap,"getWorldManager"),"getCurrentWorld");
                    Object set=invoke(world,"getCurrentWaypointSet"); var points=new JsonArray();
                    for(Object point:(Iterable<?>)invoke(set,"getWaypoints")) {
                        var p=new JsonObject(); for(String field:java.util.List.of("Name","X","Y","Z")) p.addProperty(field,String.valueOf(invoke(point,"get"+field))); points.add(p);
                    }
                    report.add("waypoints",points);
                    report.addProperty("captured",MineControlClient.inputCaptured());
                    report.addProperty("state",MineControlClient.javaMacros().fileState("block-detector.jar"));
                }
                report.addProperty("ok",true);
            } catch(Throwable e) { report.addProperty("ok",false); report.addProperty("error",e.toString()); }
            try { Files.writeString(config.resolve("block-test.response.json"),report.toString()); }
            catch(Exception e) { MineControlClient.LOG.error("Block test report",e); }
        });
    }
    private static Object invoke(Object o,String method)throws Exception{return o.getClass().getMethod(method).invoke(o);}
}

package dev.minecontrol;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.vault.VaultState;

/** Verifies ordinary and ominous vaults share one detection/waypoint type in every state. */
public final class BlockDetectionTypeTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        int vault = BlockDetection.TYPES.indexOf("vault");
        if (vault < 0 || !BlockDetection.NAMES.get(vault).equals("试炼宝库")) throw new AssertionError("vault metadata");
        for (boolean ominous : new boolean[]{false,true}) {
            for (VaultState state : VaultState.values()) {
                var block = Blocks.VAULT.defaultBlockState().setValue(VaultBlock.OMINOUS,ominous).setValue(VaultBlock.STATE,state);
                if (BlockDetection.type(block) != vault) throw new AssertionError("vault " + ominous + ": " + state);
            }
        }
        if (BlockDetection.type(Blocks.CHEST.defaultBlockState()) != -1) throw new AssertionError("ordinary chest included");
        int wetSponge = BlockDetection.TYPES.indexOf("wet_sponge");
        if (wetSponge < 0 || BlockDetection.type(Blocks.WET_SPONGE.defaultBlockState()) != wetSponge
                || !BlockDetection.NAMES.get(wetSponge).equals("潮湿的海绵")) throw new AssertionError("wet sponge");
        if (BlockDetection.type(Blocks.SPONGE.defaultBlockState()) != -1) throw new AssertionError("dry sponge included");
        int cryingObsidian = BlockDetection.TYPES.indexOf("crying_obsidian");
        if (cryingObsidian < 0 || BlockDetection.type(Blocks.CRYING_OBSIDIAN.defaultBlockState()) != cryingObsidian
                || !BlockDetection.NAMES.get(cryingObsidian).equals("哭泣的黑曜石")) throw new AssertionError("crying obsidian");
        if (BlockDetection.type(Blocks.OBSIDIAN.defaultBlockState()) != -1) throw new AssertionError("ordinary obsidian included");
        int amethyst = BlockDetection.TYPES.indexOf("budding_amethyst");
        if (amethyst < 0 || BlockDetection.type(Blocks.BUDDING_AMETHYST.defaultBlockState()) != amethyst
                || !BlockDetection.NAMES.get(amethyst).equals("紫水晶母岩")) throw new AssertionError("budding amethyst");
        for (var excluded : new net.minecraft.world.level.block.Block[]{Blocks.AMETHYST_BLOCK, Blocks.AMETHYST_CLUSTER,
                Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD})
            if (BlockDetection.type(excluded.defaultBlockState()) != -1) throw new AssertionError("amethyst variant included");
        System.out.println("Budding amethyst detected; ordinary amethyst blocks, buds and clusters excluded");
        System.out.println("Normal/ominous vaults detected in every state; ordinary chests excluded");
    }
}

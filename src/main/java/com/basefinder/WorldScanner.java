package com.basefinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;

/** Amethyst Scanner: finds budding amethyst blocks (amethyst geodes) in loaded chunks. Off until you enable it. */
public final class WorldScanner {
    private WorldScanner() {}

    /** Snapshot for the renderer, replaced as a whole list. */
    public static volatile List<BlockPos> amethyst = List.of();
    private static int ticks = 0;

    public static void tick(Minecraft mc) {
        if (!Config.on("am.enabled") || mc.level == null || mc.player == null) {
            if (!amethyst.isEmpty()) amethyst = List.of();
            return;
        }
        if (++ticks % 60 != 0) return;
        scan(mc);
    }

    public static void scan(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        int r = Config.v("am.radius");
        int minY = level.getMinY();
        List<BlockPos> out = new ArrayList<>();

        for (int cx = pcx - r; cx <= pcx + r; cx++) {
            for (int cz = pcz - r; cz <= pcz + r; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection s = sections[i];
                    if (s == null || s.hasOnlyAir()) continue;
                    if (!s.maybeHas(st -> st.is(Blocks.BUDDING_AMETHYST))) continue; // skips most sections fast
                    int baseY = minY + i * 16;
                    for (int x = 0; x < 16; x++)
                        for (int y = 0; y < 16; y++)
                            for (int z = 0; z < 16; z++)
                                if (s.getBlockState(x, y, z).is(Blocks.BUDDING_AMETHYST)) {
                                    out.add(new BlockPos(cx * 16 + x, baseY + y, cz * 16 + z));
                                    if (out.size() >= 400) { amethyst = out; return; }
                                }
                }
            }
        }
        amethyst = out;
    }
}

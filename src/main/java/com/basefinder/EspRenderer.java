package com.basefinder;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

/** Client-side outlines for block entities in chunks already loaded by the client. */
public final class EspRenderer {
    private EspRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(EspRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        var client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.world == null || client.player == null || context.matrixStack() == null || context.consumers() == null) return;
        if (!BaseFinderClient.espChests && !BaseFinderClient.espStorage && !BaseFinderClient.espSpawners) return;

        Vec3d camera = context.camera().getPos();
        var matrices = context.matrixStack();
        VertexConsumer lines = context.consumers().getBuffer(RenderLayer.getLines());
        BlockPos playerPos = client.player.getBlockPos();
        int pcx = playerPos.getX() >> 4;
        int pcz = playerPos.getZ() >> 4;
        int view = Math.max(2, Math.min(16, client.options.getClampedViewDistance()));
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int cx = pcx - view; cx <= pcx + view; cx++) {
            for (int cz = pcz - view; cz <= pcz + view; cz++) {
                WorldChunk chunk = client.world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    pos.set(entry.getKey());
                    Block block = entry.getValue().getCachedState().getBlock();
                    float r, g, b;
                    if (block == Blocks.SPAWNER && BaseFinderClient.espSpawners) {
                        r = 1.0f; g = 0.25f; b = 0.25f;
                    } else if ((block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) && BaseFinderClient.espChests) {
                        r = 1.0f; g = 0.75f; b = 0.15f;
                    } else if (BaseFinderClient.espStorage && (block instanceof ShulkerBoxBlock || block == Blocks.BARREL || block == Blocks.ENDER_CHEST)) {
                        r = 0.7f; g = 0.35f; b = 1.0f;
                    } else continue;

                    Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1).offset(-camera.x, -camera.y, -camera.z);
                    WorldRenderer.drawBox(matrices, lines, box, r, g, b, 0.95f);
                }
            }
        }
    }
}

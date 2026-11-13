package com.basefinder;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Through-walls ESP. Follows the official Fabric 1.21.11 "Rendering in the World" example:
 * a custom render pipeline with NO_DEPTH_TEST, so boxes show through stone/dirt/deepslate.
 */
public final class EspRenderer {
    private EspRenderer() {}

    private static final String ID = "basefinder";

    private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(ID, "pipeline/filled_box_through_walls"))
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .build());

    private static final ByteBufferBuilder allocator = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static BufferBuilder buffer;

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static MappableRingBuffer vertexBuffer;

    private static final int MAX_BOXES = 1500;

    // r, g, b per target type (see ChunkScanner.T_*)
    private static final float[][] COLORS = {
            {1.00f, 0.75f, 0.15f}, // chest   - gold
            {0.85f, 0.50f, 0.20f}, // barrel  - orange
            {0.75f, 0.35f, 1.00f}, // shulker - purple
            {0.20f, 0.85f, 0.85f}, // ender   - teal
            {1.00f, 0.20f, 0.20f}, // spawner - red
            {0.30f, 0.60f, 1.00f}  // beacon  - blue
    };

    // info / warning / critical (see SoundDetector)
    private static final float[][] MARKER_COLORS = {
            {0.44f, 0.66f, 1.00f}, {1.00f, 0.75f, 0.30f}, {1.00f, 0.30f, 0.30f}
    };

    public static void init() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(EspRenderer::onRender);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
    }

    private static void onRender(WorldRenderContext context) {
        try {
            extract(context);
            if (buffer != null) {
                drawThroughWalls(Minecraft.getInstance(), FILLED_THROUGH_WALLS);
            }
        } catch (Exception ex) {
            buffer = null; // never crash the game because of the ESP
        }
    }

    // ---------------- extraction: add every box to the buffer ----------------
    private static void extract(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        List<ChunkScanner.Target> targets = ChunkScanner.targets;
        List<ChunkScanner.Entry> chunks = ChunkScanner.highlight;
        boolean bf = Config.on("bf.enabled");
        boolean boxes = bf && (Config.espStorage || Config.espSpawners) && !targets.isEmpty();
        boolean zones = bf && Config.chunkHighlight && !chunks.isEmpty();
        List<SoundDetector.Marker> marks = SoundDetector.markers();
        List<net.minecraft.core.BlockPos> geodes = Config.on("am.enabled") ? WorldScanner.amethyst : List.of();
        List<WorldScanner.Hit> structs = Config.on("sf.enabled") ? WorldScanner.structures : List.of();
        boolean tracer = bf && Config.on("tr.enabled") && !ChunkScanner.clusters.isEmpty();
        if (!boxes && !zones && marks.isEmpty() && geodes.isEmpty() && structs.isEmpty() && !tracer) return;

        PoseStack matrices = context.matrices();
        Vec3 camera = context.worldState().cameraRenderState.pos;

        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        Matrix4fc pose = matrices.last().pose();

        float alpha = Config.espAlpha / 100f;
        double range = Config.espRange;
        double range2 = range * range;
        int count = 0;

        if (boxes) {
            for (ChunkScanner.Target t : targets) {
                if (count >= MAX_BOXES) break;
                boolean spawner = t.type() == ChunkScanner.T_SPAWNER;
                if (spawner ? !Config.espSpawners : !Config.espStorage) continue;
                double dx = t.x() + 0.5 - camera.x, dy = t.y() + 0.5 - camera.y, dz = t.z() + 0.5 - camera.z;
                if (dx * dx + dy * dy + dz * dz > range2) continue;
                float[] c = COLORS[t.type()];
                ensureBuffer();
                float e = 0.03f; // tiny grow so faces don't z-fight with the block
                renderFilledBox(pose, buffer,
                        t.x() - e, t.y() - e, t.z() - e,
                        t.x() + 1f + e, t.y() + 1f + e, t.z() + 1f + e,
                        c[0], c[1], c[2], Math.min(1f, alpha + 0.2f));
                count++;
            }
        }

        if (zones) {
            double zr = (range + 48) * (range + 48);
            for (ChunkScanner.Entry e : chunks) {
                if (count >= MAX_BOXES) break;
                double dx = (e.cx * 16 + 8) - camera.x, dz = (e.cz * 16 + 8) - camera.z;
                if (dx * dx + dz * dz > zr) continue;
                ensureBuffer();
                renderFilledBox(pose, buffer,
                        e.cx * 16f, e.minY - 1f, e.cz * 16f,
                        e.cx * 16f + 16f, e.maxY + 2f, e.cz * 16f + 16f,
                        0.71f, 0.36f, 1.0f, alpha * 0.35f);
                count++;
            }
        }

        // Amethyst Scanner: purple boxes on budding amethyst
        if (!geodes.isEmpty()) {
            for (net.minecraft.core.BlockPos bp : geodes) {
                if (count >= MAX_BOXES) break;
                double dx = bp.getX() + 0.5 - camera.x, dy = bp.getY() + 0.5 - camera.y, dz = bp.getZ() + 0.5 - camera.z;
                if (dx * dx + dy * dy + dz * dz > range2) continue;
                ensureBuffer();
                float e = 0.03f;
                renderFilledBox(pose, buffer,
                        bp.getX() - e, bp.getY() - e, bp.getZ() - e,
                        bp.getX() + 1f + e, bp.getY() + 1f + e, bp.getZ() + 1f + e,
                        0.80f, 0.45f, 1.0f, Math.min(1f, alpha + 0.2f));
                count++;
            }
        }

        // Structure Finder boxes
        if (!structs.isEmpty()) {
            float[][] sc = {{1.0f, 0.60f, 0.20f}, {0.30f, 0.90f, 0.90f}, {0.30f, 0.90f, 0.60f}};
            double sr2 = (range + 64) * (range + 64);
            for (WorldScanner.Hit h : structs) {
                if (count >= MAX_BOXES) break;
                var bp = h.pos();
                double dx = bp.getX() + 0.5 - camera.x, dy = bp.getY() + 0.5 - camera.y, dz = bp.getZ() + 0.5 - camera.z;
                if (dx * dx + dy * dy + dz * dz > sr2) continue;
                float[] c = sc[h.kind()];
                ensureBuffer();
                float e = 0.03f;
                renderFilledBox(pose, buffer, bp.getX() - e, bp.getY() - e, bp.getZ() - e,
                        bp.getX() + 1f + e, bp.getY() + 1f + e, bp.getZ() + 1f + e,
                        c[0], c[1], c[2], Math.min(1f, alpha + 0.2f));
                count++;
            }
        }

        // Stash Tracer: dotted line toward the nearest stash
        if (tracer) {
            ChunkScanner.Cluster best = null;
            double bd = Double.MAX_VALUE;
            for (ChunkScanner.Cluster c : ChunkScanner.clusters) {
                double dx = c.x - camera.x, dz = c.z - camera.z;
                double d = dx * dx + dz * dz;
                if (d < bd) { bd = d; best = c; }
            }
            if (best != null) {
                Vec3 look = mc.player.getLookAngle();
                double sx = camera.x + look.x * 1.5, sy = camera.y - 0.35 + look.y * 1.5, sz = camera.z + look.z * 1.5;
                double tx = best.x + 0.5, ty = best.y + 0.5, tz = best.z + 0.5;
                double len = Math.sqrt((tx - sx) * (tx - sx) + (ty - sy) * (ty - sy) + (tz - sz) * (tz - sz));
                int dots = Config.v("tr.dots");
                double step = 2.0;
                for (int i = 0; i < dots && i * step < len && count < MAX_BOXES; i++) {
                    double t = (i * step) / len;
                    float px = (float) (sx + (tx - sx) * t), py = (float) (sy + (ty - sy) * t), pz = (float) (sz + (tz - sz) * t);
                    ensureBuffer();
                    float h = 0.07f;
                    renderFilledBox(pose, buffer, px - h, py - h, pz - h, px + h, py + h, pz + h,
                            1.0f, 0.36f, 0.54f, 0.9f);
                    count++;
                }
            }
        }

        // Sound Detector markers: pulsing boxes where a sound came from
        if (!marks.isEmpty()) {
            long nowMs = System.currentTimeMillis();
            for (SoundDetector.Marker mk : marks) {
                double dx = mk.x() - camera.x, dy = mk.y() - camera.y, dz = mk.z() - camera.z;
                if (dx * dx + dy * dy + dz * dz > 192 * 192) continue;
                float remain = Math.max(0f, Math.min(1f, (mk.expires() - nowMs) / 1500f));
                float pulse = 0.28f + 0.07f * (float) Math.sin(nowMs / 160.0);
                float[] mc2 = MARKER_COLORS[Math.max(0, Math.min(2, mk.sev()))];
                ensureBuffer();
                renderFilledBox(pose, buffer,
                        (float) mk.x() - pulse, (float) mk.y() - pulse, (float) mk.z() - pulse,
                        (float) mk.x() + pulse, (float) mk.y() + pulse, (float) mk.z() + pulse,
                        mc2[0], mc2[1], mc2[2], 0.75f * remain);
                float halo = pulse + 0.35f;
                renderFilledBox(pose, buffer,
                        (float) mk.x() - halo, (float) mk.y() - halo, (float) mk.z() - halo,
                        (float) mk.x() + halo, (float) mk.y() + halo, (float) mk.z() + halo,
                        mc2[0], mc2[1], mc2[2], 0.18f * remain);
                count += 2;
            }
        }

        matrices.popPose();
    }

    private static void ensureBuffer() {
        if (buffer == null) {
            buffer = new BufferBuilder(allocator, FILLED_THROUGH_WALLS.getVertexFormatMode(),
                    FILLED_THROUGH_WALLS.getVertexFormat());
        }
    }

    private static void renderFilledBox(Matrix4fc m, BufferBuilder b, float minX, float minY, float minZ,
                                        float maxX, float maxY, float maxZ, float r, float g, float bl, float a) {
        // Front
        b.addVertex(m, minX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, maxZ).setColor(r, g, bl, a);
        // Back
        b.addVertex(m, maxX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, minZ).setColor(r, g, bl, a);
        // Left
        b.addVertex(m, minX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, minZ).setColor(r, g, bl, a);
        // Right
        b.addVertex(m, maxX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, maxZ).setColor(r, g, bl, a);
        // Top
        b.addVertex(m, minX, maxY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, maxY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, maxY, minZ).setColor(r, g, bl, a);
        // Bottom
        b.addVertex(m, minX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, minY, minZ).setColor(r, g, bl, a);
        b.addVertex(m, maxX, minY, maxZ).setColor(r, g, bl, a);
        b.addVertex(m, minX, minY, maxZ).setColor(r, g, bl, a);
    }

    // ---------------- drawing ----------------
    private static void drawThroughWalls(Minecraft client, RenderPipeline pipeline) {
        MeshData builtBuffer = buffer.buildOrThrow();
        MeshData.DrawState drawParameters = builtBuffer.drawState();
        VertexFormat format = drawParameters.format();

        GpuBuffer vertices = upload(drawParameters, format, builtBuffer);
        draw(client, pipeline, builtBuffer, drawParameters, vertices, format);

        vertexBuffer.rotate();
        buffer = null;
    }

    private static GpuBuffer upload(MeshData.DrawState drawParameters, VertexFormat format, MeshData builtBuffer) {
        int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();

        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) vertexBuffer.close();
            vertexBuffer = new MappableRingBuffer(() -> ID + " esp pipeline",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, vertexBufferSize);
        }

        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(
                vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
        }
        return vertexBuffer.currentBuffer();
    }

    private static void draw(Minecraft client, RenderPipeline pipeline, MeshData builtBuffer,
                             MeshData.DrawState drawParameters, GpuBuffer vertices, VertexFormat format) {
        GpuBuffer indices;
        VertexFormat.IndexType indexType;

        if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            builtBuffer.sortQuads(allocator, RenderSystem.getProjectionType().vertexSorting());
            indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
            indexType = builtBuffer.drawState().indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
            indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount());
            indexType = shapeIndexBuffer.type();
        }

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> ID + " esp rendering",
                        client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                        client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);
            renderPass.drawIndexed(0, 0, drawParameters.indexCount(), 1);
        }

        builtBuffer.close();
    }

    public static void close() {
        try {
            allocator.close();
            if (vertexBuffer != null) {
                vertexBuffer.close();
                vertexBuffer = null;
            }
        } catch (Exception ignored) {
        }
    }
}

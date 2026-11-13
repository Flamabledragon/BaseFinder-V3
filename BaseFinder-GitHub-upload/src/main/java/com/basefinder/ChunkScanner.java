package com.basefinder;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans loaded chunks, scores them by how "stash-like" they are, remembers every
 * scored chunk (so flying around builds up a map), and groups neighbours into clusters.
 */
public class ChunkScanner {

    public static class Entry {
        public int cx, cz;
        public double score;
        public int containers, shulkers, spawners, other, stacked;
        public int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
    }

    public static class Cluster {
        public final List<Entry> chunks = new ArrayList<>();
        public double score;
        public int containers, shulkers, spawners, stacked;
        public int x, y, z;
        public double dist;
        private int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

        void add(Entry e) {
            chunks.add(e);
            score += e.score;
            containers += e.containers;
            shulkers += e.shulkers;
            spawners += e.spawners;
            stacked += e.stacked;
            minY = Math.min(minY, e.minY);
            maxY = Math.max(maxY, e.maxY);
        }

        void finish(double px, double pz) {
            double sx = 0, sz = 0;
            for (Entry e : chunks) { sx += e.cx * 16 + 8; sz += e.cz * 16 + 8; }
            x = (int) (sx / chunks.size());
            z = (int) (sz / chunks.size());
            y = (minY + maxY) / 2;
            double dx = x - px, dz = z - pz;
            dist = Math.sqrt(dx * dx + dz * dz);
        }
    }

    // ---- Settings (GUI changes these) ----
    public static boolean findStorage = true;
    public static boolean findSpawners = true;
    public static boolean autoAlert = true;
    public static int radius = 32;     // chunks around the player
    public static int threshold = 8;   // min cluster score to count as a stash

    // ---- State ----
    public static List<Cluster> clusters = new ArrayList<>();
    private static final Map<Long, Entry> entries = new HashMap<>();
    private static final Set<Long> alerted = new HashSet<>();
    private static String currentKey = "";
    private static int ticks = 0;

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    public static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        if (++ticks % 40 == 0) scan(mc);
    }

    public static void clear() {
        entries.clear();
        alerted.clear();
        clusters = new ArrayList<>();
    }

    public static void scan(MinecraftClient mc) {
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) return;

        var server = mc.getCurrentServerEntry();
        String key = (server != null ? server.address : "singleplayer") + "|" + world.getRegistryKey().getValue();
        if (!key.equals(currentKey)) {
            clear();
            currentKey = key;
        }

        int pcx = mc.player.getBlockPos().getX() >> 4;
        int pcz = mc.player.getBlockPos().getZ() >> 4;

        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;
                Entry e = analyze(chunk, cx, cz);
                if (e == null) entries.remove(key(cx, cz));
                else entries.put(key(cx, cz), e);
            }
        }

        rebuildClusters(mc.player.getX(), mc.player.getZ());
        checkAlerts(mc);
    }

    private static Entry analyze(WorldChunk chunk, int cx, int cz) {
        Entry e = new Entry();
        e.cx = cx;
        e.cz = cz;
        Map<Integer, Integer> columns = new HashMap<>();

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockState s = be.getCachedState();
            Block b = s.getBlock();
            BlockPos p = be.getPos();
            double w;
            boolean container = false;

            if (b == Blocks.SPAWNER) {
                if (!findSpawners) continue;
                e.spawners++;
                w = 3;
            } else if (!findStorage) {
                continue;
            } else if (b instanceof ShulkerBoxBlock) {
                e.shulkers++;
                e.containers++;
                container = true;
                w = 3;
            } else if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST) {
                if (s.get(ChestBlock.CHEST_TYPE) == ChestType.LEFT) continue; // count double chests once
                e.containers++;
                container = true;
                w = 1;
            } else if (b == Blocks.BARREL) {
                e.containers++;
                container = true;
                w = 1;
            } else if (b == Blocks.ENDER_CHEST) {
                e.containers++;
                container = true;
                w = 1.5;
            } else if (b == Blocks.BEACON) {
                e.other++;
                w = 3;
            } else if (b == Blocks.HOPPER || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE
                    || b == Blocks.SMOKER || b == Blocks.DISPENSER || b == Blocks.DROPPER) {
                e.other++;
                w = 0.5;
            } else {
                continue;
            }

            e.score += w;
            e.minY = Math.min(e.minY, p.getY());
            e.maxY = Math.max(e.maxY, p.getY());
            if (container) {
                int col = ((p.getX() & 15) << 4) | (p.getZ() & 15);
                columns.merge(col, 1, Integer::sum);
            }
        }

        // Stacked storage (several containers on top of each other) is a strong stash sign
        for (int n : columns.values()) if (n > 1) e.stacked += n - 1;
        e.score += 0.5 * e.stacked;

        return e.score > 0 ? e : null;
    }

    private static boolean qualifies(Entry e) {
        return e.score >= 2 || e.spawners > 0;
    }

    private static void rebuildClusters(double px, double pz) {
        Set<Long> seen = new HashSet<>();
        List<Cluster> out = new ArrayList<>();

        for (Map.Entry<Long, Entry> me : entries.entrySet()) {
            Entry start = me.getValue();
            if (!qualifies(start) || seen.contains(me.getKey())) continue;

            Cluster c = new Cluster();
            Deque<Entry> queue = new ArrayDeque<>();
            queue.add(start);
            seen.add(me.getKey());

            while (!queue.isEmpty()) {
                Entry e = queue.poll();
                c.add(e);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long nk = key(e.cx + dx, e.cz + dz);
                        Entry n = entries.get(nk);
                        if (n != null && qualifies(n) && seen.add(nk)) queue.add(n);
                    }
                }
            }

            c.finish(px, pz);
            if (c.score >= threshold || (findSpawners && c.spawners > 0)) out.add(c);
        }

        out.sort(Comparator.comparingDouble((Cluster c) -> -c.score));
        clusters = out;
    }

    private static void checkAlerts(MinecraftClient mc) {
        boolean found = false;
        for (Cluster c : clusters) {
            boolean isNew = true;
            for (Entry e : c.chunks) {
                if (alerted.contains(key(e.cx, e.cz))) { isNew = false; break; }
            }
            if (!isNew) continue;
            for (Entry e : c.chunks) alerted.add(key(e.cx, e.cz));
            found = true;
        }
        if (found && autoAlert) save();
    }

    /** Writes all current results to .minecraft/config/basefinder/<server>.txt. Returns the path or null. */
    public static String save() {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("basefinder");
            Files.createDirectories(dir);
            Path file = dir.resolve(currentKey.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt");
            StringBuilder sb = new StringBuilder("# BaseFinder results: " + currentKey + "\n");
            for (Cluster c : clusters) {
                sb.append("X ").append(c.x).append(" Y ").append(c.y).append(" Z ").append(c.z)
                        .append(" | score ").append((int) c.score)
                        .append(" | containers ").append(c.containers)
                        .append(" | shulkers ").append(c.shulkers)
                        .append(" | spawners ").append(c.spawners)
                        .append(" | stacked ").append(c.stacked)
                        .append(" | chunks ").append(c.chunks.size()).append("\n");
            }
            Files.writeString(file, sb.toString());
            return file.toString();
        } catch (Exception ex) {
            return null;
        }
    }
}

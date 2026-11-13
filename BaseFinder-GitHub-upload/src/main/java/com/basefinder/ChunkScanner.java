package com.basefinder;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;

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
 * Scans loaded chunks, scores them by how stash-like they are, remembers every scored chunk
 * (also across restarts, per server + dimension) and groups neighbouring chunks into clusters.
 */
public class ChunkScanner {

    public static final int T_CHEST = 0, T_BARREL = 1, T_SHULKER = 2, T_ENDER = 3, T_SPAWNER = 4, T_BEACON = 5;

    /** One block worth drawing an ESP box on. */
    public record Target(int x, int y, int z, int type) {}

    public static class Entry {
        public int cx, cz;
        public double score;
        public int containers, shulkers, spawners, other, stacked;
        public int machines, signs; // hoppers/furnaces/dispensers, signs
        public boolean bell; // village marker
        public int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        public final List<Target> targets = new ArrayList<>();
    }

    public static class Cluster {
        public final List<Entry> chunks = new ArrayList<>();
        public double score;
        public int containers, shulkers, spawners, stacked, machines, signs;
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
            machines += e.machines;
            signs += e.signs;
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

        /** What this looks like: STASH, FARM, BASE or SPAWNER. */
        public String kind() {
            if (containers == 0 && spawners > 0) return "SPAWNER";
            if (machines >= 10 && machines > containers * 2) return "FARM";
            if (signs >= 2 || (machines >= 4 && containers >= 4)) return "BASE";
            return "STASH";
        }

        /** Rough size label. */
        public String tier() {
            return score >= 100 ? "MEGA" : score >= 40 ? "LARGE" : score >= 15 ? "MEDIUM" : "SMALL";
        }
    }

    // ---- State ----
    public static volatile List<Cluster> clusters = new ArrayList<>();
    /** Snapshots for the renderer (replaced as whole lists, never edited in place). */
    public static volatile List<Target> targets = List.of();
    public static volatile List<Entry> highlight = List.of();
    /** Bumped every time the result list is rebuilt, so the GUI knows when to refresh. */
    public static volatile int version = 0;

    private static final Map<Long, Entry> entries = new HashMap<>();
    private static final Set<Long> alerted = new HashSet<>();
    /** Chunks the user hid with the HIDE button. */
    private static final Set<Long> ignored = new HashSet<>();
    private static String currentKey = "";
    private static int ticks = 0;

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        if (!Config.on("bf.enabled")) return;
        ticks++;
        if (ticks % 40 == 0) scan(mc);
        if (ticks % 2400 == 0) saveState(); // autosave every 2 minutes
    }

    /** Wipes the in-memory map (does not touch files). */
    public static void clear() {
        entries.clear();
        naturalCache.clear();
        alerted.clear();
        ignored.clear();
        clusters = new ArrayList<>();
        targets = List.of();
        highlight = List.of();
        version++;
    }

    /** Wipes memory AND the saved map of the current server/dimension. */
    public static void resetAll() {
        clear();
        try {
            if (!currentKey.isEmpty()) Files.deleteIfExists(stateFile());
        } catch (Exception ignoredEx) {
        }
    }

    /** Hides a result (all its chunks) until reset. */
    public static void hide(Cluster c) {
        for (Entry e : c.chunks) ignored.add(key(e.cx, e.cz));
        scan(Minecraft.getInstance());
    }

    public static void scan(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;

        var server = mc.getCurrentServer();
        String key = (server != null ? server.ip : "singleplayer") + "|" + level.dimension();
        if (!key.equals(currentKey)) {
            saveState();       // keep what we learned in the old world
            clear();
            currentKey = key;
            loadState();       // restore the map of the new one
        }

        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        int radius = Config.radius;

        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                Entry e = analyze(chunk, cx, cz);
                if (e == null) entries.remove(key(cx, cz));
                else entries.put(key(cx, cz), e);
            }
        }

        rebuildClusters(mc.player.getX(), mc.player.getZ());

        List<Target> all = new ArrayList<>();
        if (Config.espOnlyStashes) {
            for (Entry e : highlight) all.addAll(e.targets);
        } else {
            for (Map.Entry<Long, Entry> me : entries.entrySet()) {
                if (!ignored.contains(me.getKey())) all.addAll(me.getValue().targets);
            }
        }
        targets = all;

        version++;
        checkAlerts(mc);
    }

    private static Entry analyze(LevelChunk chunk, int cx, int cz) {
        Entry e = new Entry();
        e.cx = cx;
        e.cz = cz;
        Map<Integer, Integer> columns = new HashMap<>();

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockState s = be.getBlockState();
            Block b = s.getBlock();
            BlockPos p = be.getBlockPos();
            boolean isStorage = b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL
                    || b instanceof ShulkerBoxBlock;
            if (isStorage && Config.findStorage && Config.on("bf.nonat") && isNatural(chunk, cx, cz, p)) continue;
            double w;
            int type = -1;
            boolean container = false;
            boolean countsOnce = true;

            if (b == Blocks.BELL) {
                e.bell = true; // villages have bells, stashes almost never do
                continue;
            } else if (b == Blocks.SPAWNER) {
                if (!Config.findSpawners) continue;
                if (Config.on("bf.nomine") && nearCobweb(chunk, cx, cz, p)) continue; // cave spider spawner of a mineshaft
                type = T_SPAWNER;
                e.spawners++;
                w = 3;
            } else if (!Config.findStorage) {
                continue;
            } else if (b instanceof ShulkerBoxBlock) {
                type = T_SHULKER;
                e.shulkers++;
                e.containers++;
                container = true;
                w = 3;
            } else if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST) {
                type = T_CHEST;
                if (s.getValue(ChestBlock.TYPE) == ChestType.LEFT) countsOnce = false; // other half is counted
                else e.containers++;
                container = true;
                w = 1;
            } else if (b == Blocks.BARREL) {
                type = T_BARREL;
                e.containers++;
                container = true;
                w = 1;
            } else if (b == Blocks.ENDER_CHEST) {
                type = T_ENDER;
                e.containers++;
                container = true;
                w = 1.5;
            } else if (b == Blocks.BEACON) {
                type = T_BEACON;
                e.other++;
                w = 3;
            } else if (b == Blocks.HOPPER || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE
                    || b == Blocks.SMOKER || b == Blocks.DISPENSER || b == Blocks.DROPPER) {
                e.other++;
                e.machines++;
                w = 0.5;
            } else if (be instanceof SignBlockEntity || b == Blocks.BREWING_STAND
                    || b == Blocks.ENCHANTING_TABLE || b == Blocks.JUKEBOX) {
                e.other++; // small sign of player activity
                if (be instanceof SignBlockEntity) e.signs++;
                w = 0.25;
            } else {
                continue;
            }

            if (type >= 0) e.targets.add(new Target(p.getX(), p.getY(), p.getZ(), type));
            if (!countsOnce) continue;

            e.score += w;
            e.minY = Math.min(e.minY, p.getY());
            e.maxY = Math.max(e.maxY, p.getY());
            if (container) {
                int col = ((p.getX() & 15) << 4) | (p.getZ() & 15);
                columns.merge(col, 1, Integer::sum);
            }
        }

        // hoppers, dispensers and furnaces in bulk are farms, not loot: stop them inflating the score
        if (e.machines > 6) e.score -= 0.5 * (e.machines - 6);

        // Stacked storage (several containers on top of each other) is a strong stash sign
        for (int n : columns.values()) if (n > 1) e.stacked += n - 1;
        e.score += 0.5 * e.stacked;

        // keep bell-only chunks so the village filter still knows about them
        return (e.score > 0 || e.bell) ? e : null;
    }

    private static final Map<Long, Boolean> naturalCache = new HashMap<>();

    /** Blocks that mark generated structures (end city, bastion, fortress, dungeon, stronghold, ancient city). */
    private static final Set<Block> NATURAL = Set.of(
            Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.PURPUR_STAIRS, Blocks.END_STONE_BRICKS,
            Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICK_STAIRS,
            Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.GILDED_BLACKSTONE,
            Blocks.CHISELED_POLISHED_BLACKSTONE,
            Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.COBWEB,
            Blocks.END_PORTAL_FRAME,
            Blocks.SCULK, Blocks.SCULK_SENSOR, Blocks.DEEPSLATE_TILES, Blocks.REINFORCED_DEEPSLATE);

    /** True when a container sits inside a generated structure (6+ signature blocks nearby, same chunk only). */
    private static boolean isNatural(LevelChunk chunk, int cx, int cz, BlockPos p) {
        long k = p.asLong();
        Boolean cached = naturalCache.get(k);
        if (cached != null) return cached;
        int hits = 0;
        outer:
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                int x = p.getX() + dx, z = p.getZ() + dz;
                if ((x >> 4) != cx || (z >> 4) != cz) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    if (NATURAL.contains(chunk.getBlockState(new BlockPos(x, p.getY() + dy, z)).getBlock())) {
                        if (++hits >= 6) break outer;
                    }
                }
            }
        boolean r = hits >= 6;
        naturalCache.put(k, r);
        return r;
    }

    /** Mineshaft spawners sit in cobwebs. Only looks inside the same chunk. */
    private static boolean nearCobweb(LevelChunk chunk, int cx, int cz, BlockPos p) {
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++) {
                int x = p.getX() + dx, z = p.getZ() + dz;
                if ((x >> 4) != cx || (z >> 4) != cz) continue;
                for (int dy = -2; dy <= 2; dy++)
                    if (chunk.getBlockState(new BlockPos(x, p.getY() + dy, z)).is(Blocks.COBWEB)) return true;
            }
        return false;
    }

    public static int mapped() { return entries.size(); }

    private static boolean usable(long k, Entry e, Set<Long> villageZone) {
        if (e.score <= 0) return false;
        if (!(e.score >= 2 || e.spawners > 0)) return false;
        return !ignored.contains(k) && !villageZone.contains(k);
    }

    private static void rebuildClusters(double px, double pz) {
        // Chunks within 3 of a village bell are treated as village
        Set<Long> villageZone = new HashSet<>();
        if (Config.ignoreVillages) {
            for (Entry e : entries.values()) {
                if (!e.bell) continue;
                for (int dx = -3; dx <= 3; dx++)
                    for (int dz = -3; dz <= 3; dz++) villageZone.add(key(e.cx + dx, e.cz + dz));
            }
        }

        Set<Long> seen = new HashSet<>();
        List<Cluster> out = new ArrayList<>();

        for (Map.Entry<Long, Entry> me : entries.entrySet()) {
            Entry start = me.getValue();
            if (!usable(me.getKey(), start, villageZone) || seen.contains(me.getKey())) continue;

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
                        if (n != null && usable(nk, n, villageZone) && seen.add(nk)) queue.add(n);
                    }
                }
            }

            c.finish(px, pz);
            if (Config.on("bf.nofarm") && c.kind().equals("FARM")) continue;
            // A result must look like storage: enough containers AND enough score. A spawner alone is not a stash.
            boolean spawnerOk = Config.findSpawners && Config.on("bf.spawneronly") && c.spawners > 0;
            boolean stash = c.containers >= Config.v("bf.mincont") && c.score >= Config.threshold;
            if (stash || spawnerOk) out.add(c);
        }

        if (Config.sortByDistance) out.sort(Comparator.comparingDouble((Cluster c) -> c.dist));
        else out.sort(Comparator.comparingDouble((Cluster c) -> -c.score));
        clusters = out;

        List<Entry> hl = new ArrayList<>();
        for (Cluster c : out) hl.addAll(c.chunks);
        highlight = hl;
    }

    private static void checkAlerts(Minecraft mc) {
        boolean found = false;
        for (Cluster c : clusters) {
            boolean isNew = true;
            for (Entry e : c.chunks) {
                if (alerted.contains(key(e.cx, e.cz))) { isNew = false; break; }
            }
            if (!isNew) continue;
            for (Entry e : c.chunks) alerted.add(key(e.cx, e.cz));
            found = true;
            if (Config.autoAlert && mc.player != null) {
                mc.player.displayClientMessage(Component.literal("[BaseFinder] " + c.tier() + " " + c.kind().toLowerCase() + " at "
                        + Config.xyz(c.x, c.y, c.z, "st.chat") + " (score " + (int) c.score + ", " + c.containers
                        + " containers, " + c.spawners + " spawners, " + (int) c.dist + "m away)"), false);
            }
        }
        if (found) {
            save();
            saveState();
        }
    }

    // ------------------------------------------------------------------ files
    private static Path dir() throws Exception {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("basefinder");
        Files.createDirectories(dir);
        return dir;
    }

    private static String safeName() {
        return currentKey.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Path stateFile() throws Exception {
        return dir().resolve(safeName() + ".dat");
    }

    /** Writes all current results to .minecraft/config/basefinder/<server>.txt. Returns the path or null. */
    public static String save() {
        try {
            Path file = dir().resolve(safeName() + ".txt");
            StringBuilder sb = new StringBuilder("# BaseFinder results: " + currentKey + "\n");
            for (Cluster c : clusters) {
                sb.append("X ").append(c.x).append(" Y ").append(c.y).append(" Z ").append(c.z)
                        .append(" | ").append(c.tier()).append(" ").append(c.kind())
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

    /** Saves the whole remembered chunk map so it survives restarts. */
    public static void saveState() {
        if (currentKey.isEmpty() || !Config.on("ut.memory")) return;
        try {
            Path f = stateFile();
            if (entries.isEmpty() && ignored.isEmpty()) {
                Files.deleteIfExists(f);
                return;
            }
            StringBuilder sb = new StringBuilder("# BaseFinder state v1\n");
            for (long k : ignored) sb.append("I ").append(k).append('\n');
            for (Entry e : entries.values()) {
                sb.append("E ").append(e.cx).append(' ').append(e.cz).append(' ').append(e.score)
                        .append(' ').append(e.containers).append(' ').append(e.shulkers)
                        .append(' ').append(e.spawners).append(' ').append(e.other)
                        .append(' ').append(e.stacked).append(' ').append(e.minY).append(' ').append(e.maxY)
                        .append(' ').append(e.bell ? 1 : 0).append(' ');
                sb.append(e.machines).append(' ').append(e.signs).append(' ');
                if (e.targets.isEmpty()) sb.append('-');
                else {
                    boolean first = true;
                    for (Target t : e.targets) {
                        if (!first) sb.append(';');
                        first = false;
                        sb.append(t.x()).append(',').append(t.y()).append(',').append(t.z()).append(',').append(t.type());
                    }
                }
                sb.append('\n');
            }
            Files.writeString(f, sb.toString());
        } catch (Exception ex) {
        }
    }

    private static void loadState() {
        if (!Config.on("ut.memory")) return;
        try {
            Path f = stateFile();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f)) {
                try {
                    if (line.startsWith("I ")) {
                        ignored.add(Long.parseLong(line.substring(2).trim()));
                    } else if (line.startsWith("E ")) {
                        String[] p = line.split(" ");
                        Entry e = new Entry();
                        e.cx = Integer.parseInt(p[1]);
                        e.cz = Integer.parseInt(p[2]);
                        e.score = Double.parseDouble(p[3]);
                        e.containers = Integer.parseInt(p[4]);
                        e.shulkers = Integer.parseInt(p[5]);
                        e.spawners = Integer.parseInt(p[6]);
                        e.other = Integer.parseInt(p[7]);
                        e.stacked = Integer.parseInt(p[8]);
                        e.minY = Integer.parseInt(p[9]);
                        e.maxY = Integer.parseInt(p[10]);
                        e.bell = p[11].equals("1");
                        boolean newFmt = p.length > 14 && p[12].matches("\\d+") && p[13].matches("\\d+");
                        int tIdx = newFmt ? 14 : 12;
                        if (newFmt) {
                            e.machines = Integer.parseInt(p[12]);
                            e.signs = Integer.parseInt(p[13]);
                        }
                        if (p.length > tIdx && !p[tIdx].equals("-")) {
                            for (String t : p[tIdx].split(";")) {
                                String[] q = t.split(",");
                                e.targets.add(new Target(Integer.parseInt(q[0]), Integer.parseInt(q[1]),
                                        Integer.parseInt(q[2]), Integer.parseInt(q[3])));
                            }
                        }
                        entries.put(key(e.cx, e.cz), e);
                        alerted.add(key(e.cx, e.cz)); // old finds should not alert again
                    }
                } catch (Exception badLine) {
                    // skip corrupt line
                }
            }
        } catch (Exception ex) {
        }
    }
}

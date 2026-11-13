package com.basefinder;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** All settings in one place. Saved to .minecraft/config/basefinder.properties */
public final class Config {
    private Config() {}

    // Scanner modules
    public static boolean findStorage = true;
    public static boolean findSpawners = true;
    public static boolean autoAlert = true;

    // ESP modules
    public static boolean espStorage = true;
    public static boolean espSpawners = true;
    public static boolean chunkHighlight = true;

    // Noise filters (v2.1)
    public static boolean espOnlyStashes = true;  // ESP boxes only inside detected stashes
    public static boolean ignoreVillages = true;  // skip chunks around village bells
    public static boolean loneSpawners = false;   // false = a single spawner alone is not a result

    // Results
    public static boolean sortByDistance = false;

    // Numbers
    public static int radius = 32;      // scan radius in chunks
    public static int threshold = 8;    // min score for a stash
    public static int espRange = 128;   // blocks
    public static int espAlpha = 50;    // percent


    // ---- Generic settings store (v2.2): modules/GUI settings live here as ints (booleans are 0/1) ----
    private static final Map<String, Integer> DEFAULTS = new HashMap<>();
    private static final Map<String, Integer> VALUES = new HashMap<>();
    /** Module ids marked with the star in the GUI. */
    public static final Set<String> favorites = new LinkedHashSet<>();

    private static void d(String k, int v) { DEFAULTS.put(k, v); }

    static {
        d("bf.enabled", 0); // everything starts OFF, you turn on what you want
        // Sound Detector
        d("sd.enabled", 0); d("sd.chat", 1); d("sd.markers", 1); d("sd.duration", 17); d("sd.cards", 3);
        d("sd.cooldown", 50); d("sd.menus", 1); d("sd.hud", 1); d("sd.scale", 80); d("sd.range", 48);
        d("sd.minsev", 0); d("sd.pos", 0);
        d("sd.cat.players", 1); d("sd.cat.cont", 1); d("sd.cat.mach", 1); d("sd.cat.blocks", 1); d("sd.cat.boom", 1);
        // HUD
        d("hud.master", 1); d("hud.coords", 0); d("hud.nearest", 0);
        // Streaming
        d("st.on", 0); d("st.chat", 1); d("st.hud", 1); d("st.gui", 1);
        // World / extra modules (all off by default)
        d("am.enabled", 0); d("am.radius", 6);
        d("bf.nomine", 1); d("bf.nonat", 1); d("bf.nofarm", 1);
        d("fv.enabled", 0);
        d("sf.enabled", 0); d("sf.radius", 8); d("sf.trial", 1); d("sf.shrieker", 1);
        d("hud.totems", 0); d("hud.mapstats", 0);
        d("tr.enabled", 0); d("tr.dots", 30);
        d("hud.light", 0); d("hud.players", 0); d("hud.prange", 128); d("hud.pcount", 5);
        // Utility
        d("ut.memory", 1);
        // Appearance
        d("ap.theme", 1); d("ap.cube", 1); d("ap.spin", 50); d("ap.opacity", 94); d("ap.style", 0);

        favorites.add("basefinder");
        favorites.add("sound");
    }

    public static int v(String k) {
        Integer x = VALUES.get(k);
        return x != null ? x : DEFAULTS.getOrDefault(k, 0);
    }

    public static void set(String k, int val) { VALUES.put(k, val); }
    public static boolean on(String k) { return v(k) != 0; }

    /** "X 1 Y 2 Z 3", or hidden when Streamer Mode covers this place (flag = st.chat / st.hud / st.gui). */
    public static String xyz(int x, int y, int z, String flag) {
        return (on("st.on") && on(flag)) ? "X ? Y ? Z ?" : "X " + x + " Y " + y + " Z " + z;
    }

    /** "1, 2, 3", or "hidden". */
    public static String xyzPlain(int x, int y, int z, String flag) {
        return (on("st.on") && on(flag)) ? "hidden" : x + ", " + y + ", " + z;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("basefinder.properties");
    }

    private static boolean bool(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int num(Properties p, String k, int def, int lo, int hi) {
        try {
            return clamp(Integer.parseInt(p.getProperty(k, String.valueOf(def)).trim()), lo, hi);
        } catch (Exception ex) {
            return def; // one bad value no longer wipes the rest of the file
        }
    }

    public static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(f)) { p.load(in); }
            findStorage = bool(p, "findStorage", true);
            findSpawners = bool(p, "findSpawners", true);
            autoAlert = bool(p, "autoAlert", true);
            espStorage = bool(p, "espStorage", true);
            espSpawners = bool(p, "espSpawners", true);
            chunkHighlight = bool(p, "chunkHighlight", true);
            espOnlyStashes = bool(p, "espOnlyStashes", true);
            ignoreVillages = bool(p, "ignoreVillages", true);
            loneSpawners = bool(p, "loneSpawners", false);
            sortByDistance = bool(p, "sortByDistance", false);
            radius = num(p, "radius", 32, 4, 64);
            threshold = num(p, "threshold", 8, 2, 100);
            espRange = num(p, "espRange", 128, 32, 256);
            espAlpha = num(p, "espAlpha", 50, 10, 100);
            for (String k : DEFAULTS.keySet()) {
                String raw = p.getProperty("v." + k);
                if (raw == null) continue;
                try { VALUES.put(k, Integer.parseInt(raw.trim())); } catch (Exception ignoredNum) { }
            }
            String fav = p.getProperty("favorites");
            if (fav != null) {
                favorites.clear();
                for (String id : fav.split(",")) if (!id.isBlank()) favorites.add(id.trim());
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        try {
            Properties p = new Properties();
            p.setProperty("findStorage", String.valueOf(findStorage));
            p.setProperty("findSpawners", String.valueOf(findSpawners));
            p.setProperty("autoAlert", String.valueOf(autoAlert));
            p.setProperty("espStorage", String.valueOf(espStorage));
            p.setProperty("espSpawners", String.valueOf(espSpawners));
            p.setProperty("chunkHighlight", String.valueOf(chunkHighlight));
            p.setProperty("espOnlyStashes", String.valueOf(espOnlyStashes));
            p.setProperty("ignoreVillages", String.valueOf(ignoreVillages));
            p.setProperty("loneSpawners", String.valueOf(loneSpawners));
            p.setProperty("sortByDistance", String.valueOf(sortByDistance));
            p.setProperty("radius", String.valueOf(radius));
            p.setProperty("threshold", String.valueOf(threshold));
            p.setProperty("espRange", String.valueOf(espRange));
            p.setProperty("espAlpha", String.valueOf(espAlpha));
            for (String k : DEFAULTS.keySet()) p.setProperty("v." + k, String.valueOf(v(k)));
            p.setProperty("favorites", String.join(",", favorites));
            try (OutputStream out = Files.newOutputStream(file())) { p.store(out, "BaseFinder settings"); }
        } catch (Exception ignored) {
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

package com.basefinder;

import net.fabricmc.loader.api.FabricLoader;

import java.io.OutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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

    // Numbers
    public static int radius = 32;      // scan radius in chunks
    public static int threshold = 8;    // min score for a stash
    public static int espRange = 128;   // blocks
    public static int espAlpha = 50;    // percent

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("basefinder.properties");
    }

    public static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(f)) { p.load(in); }
            findStorage = Boolean.parseBoolean(p.getProperty("findStorage", "true"));
            findSpawners = Boolean.parseBoolean(p.getProperty("findSpawners", "true"));
            autoAlert = Boolean.parseBoolean(p.getProperty("autoAlert", "true"));
            espStorage = Boolean.parseBoolean(p.getProperty("espStorage", "true"));
            espSpawners = Boolean.parseBoolean(p.getProperty("espSpawners", "true"));
            chunkHighlight = Boolean.parseBoolean(p.getProperty("chunkHighlight", "true"));
            radius = clamp(Integer.parseInt(p.getProperty("radius", "32")), 4, 64);
            threshold = clamp(Integer.parseInt(p.getProperty("threshold", "8")), 2, 100);
            espRange = clamp(Integer.parseInt(p.getProperty("espRange", "128")), 32, 256);
            espAlpha = clamp(Integer.parseInt(p.getProperty("espAlpha", "50")), 10, 100);
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
            p.setProperty("radius", String.valueOf(radius));
            p.setProperty("threshold", String.valueOf(threshold));
            p.setProperty("espRange", String.valueOf(espRange));
            p.setProperty("espAlpha", String.valueOf(espAlpha));
            try (OutputStream out = Files.newOutputStream(file())) { p.store(out, "BaseFinder settings"); }
        } catch (Exception ignored) {
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

package com.basefinder;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Registry of every module and every setting shown in the GUI. */
public final class Modules {
    private Modules() {}

    public enum Cat {
        VISUAL("Visual"), DETECTION("Detection"), WORLD("World"), UTILITY("Utility"), STREAMING("Streaming");
        public final String label;
        Cat(String label) { this.label = label; }
    }

    public enum Type { TOGGLE, SLIDER, CHOICE, BUTTON, HEADER }

    public static final String[] THEME_NAMES = {"Ice", "Teal", "Sage", "Sand", "Rose", "Lavender"};
    public static final int[] THEMES = {0xFF8FB3FF, 0xFF5FA8A0, 0xFF7FB069, 0xFFC9A66B, 0xFFB07A8C, 0xFF9179B8};

    // ---- deferred work, polled by the GUI tick ----
    private static int scanWait = -1, saveWait = -1;
    public static void scanSoon() { scanWait = 6; }
    public static void saveSoon() { saveWait = 10; }
    public static boolean pollScan() {
        if (scanWait < 0) return false;
        if (--scanWait <= 0) { scanWait = -1; return true; }
        return false;
    }
    public static boolean pollSave() {
        if (saveWait < 0) return false;
        if (--saveWait <= 0) { saveWait = -1; return true; }
        return false;
    }

    // ------------------------------------------------------------------ Setting
    public static final class Setting {
        public final Type type;
        public final String label;
        public IntSupplier get = () -> 0;
        public IntConsumer set = v -> { };
        public int min, max = 1, step = 1;
        public Function<Integer, String> fmt = String::valueOf;
        public String[] opts = new String[0];
        public Runnable action = () -> { };
        public boolean rescan;

        Setting(Type type, String label) {
            this.type = type;
            this.label = label;
        }

        public int height() {
            switch (type) {
                case SLIDER: return 28;
                case BUTTON: return 22;
                case HEADER: return 16;
                default: return 20;
            }
        }

        public void change(int v) {
            set.accept(v);
            if (rescan) scanSoon();
            saveSoon();
        }

        public void click() {
            switch (type) {
                case TOGGLE: change(get.getAsInt() == 0 ? 1 : 0); break;
                case CHOICE: change((get.getAsInt() + 1) % Math.max(1, opts.length)); break;
                case BUTTON: action.run(); break;
                default: break;
            }
        }

        public String display() {
            int v = get.getAsInt();
            if (type == Type.CHOICE) return opts[Math.max(0, Math.min(opts.length - 1, v))];
            return fmt.apply(v);
        }
    }

    public static Setting header(String t) { return new Setting(Type.HEADER, t); }

    public static Setting tog(String label, BooleanSupplier g, Consumer<Boolean> s, boolean rescan) {
        Setting x = new Setting(Type.TOGGLE, label);
        x.get = () -> g.getAsBoolean() ? 1 : 0;
        x.set = v -> s.accept(v != 0);
        x.rescan = rescan;
        return x;
    }

    public static Setting key(String label, String key) {
        Setting x = new Setting(Type.TOGGLE, label);
        x.get = () -> Config.v(key);
        x.set = v -> Config.set(key, v);
        return x;
    }

    public static Setting slider(String label, String key, int min, int max, int step, Function<Integer, String> fmt) {
        Setting x = new Setting(Type.SLIDER, label);
        x.get = () -> Config.v(key);
        x.set = v -> Config.set(key, v);
        x.min = min; x.max = max; x.step = step; x.fmt = fmt;
        return x;
    }

    public static Setting sliderF(String label, IntSupplier g, IntConsumer s, int min, int max, int step,
                                  Function<Integer, String> fmt, boolean rescan) {
        Setting x = new Setting(Type.SLIDER, label);
        x.get = g; x.set = s;
        x.min = min; x.max = max; x.step = step; x.fmt = fmt; x.rescan = rescan;
        return x;
    }

    public static Setting choice(String label, String key, String... opts) {
        Setting x = new Setting(Type.CHOICE, label);
        x.get = () -> Config.v(key);
        x.set = v -> Config.set(key, v);
        x.opts = opts;
        return x;
    }

    public static Setting button(String label, Runnable r) {
        Setting x = new Setting(Type.BUTTON, label);
        x.action = r;
        return x;
    }

    // ------------------------------------------------------------------ Module
    public static final class Module {
        public final String id, name, desc;
        public final Cat cat;
        public final Setting main;
        public final List<Setting> settings;

        Module(String id, String name, Cat cat, String desc, Setting main, List<Setting> settings) {
            this.id = id;
            this.name = name;
            this.cat = cat;
            this.desc = desc;
            this.main = main;
            this.settings = settings;
        }

        public boolean on() { return main.get.getAsInt() != 0; }
        public void flip() { main.click(); }
    }

    private static void chat(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(msg), false);
    }

    private static Setting espRange() {
        return sliderF("ESP range", () -> Config.espRange, v -> Config.espRange = v, 32, 256, 32, v -> v + " blocks", false);
    }

    private static Setting espOpacity() {
        return sliderF("Opacity", () -> Config.espAlpha, v -> Config.espAlpha = v, 10, 100, 10, v -> v + "%", false);
    }

    public static final List<Module> ALL = new ArrayList<>();
    public static final List<Setting> HUD_SETTINGS = new ArrayList<>();
    public static final List<Setting> APPEARANCE_SETTINGS = new ArrayList<>();

    private static void add(String id, String name, Cat cat, String desc, Setting main, Setting... s) {
        ALL.add(new Module(id, name, cat, desc, main, List.of(s)));
    }

    public static Module byId(String id) {
        for (Module m : ALL) if (m.id.equals(id)) return m;
        return ALL.get(0);
    }

    static {
        add("basefinder", "Base Finder", Cat.DETECTION,
                "Scans loaded chunks for stashes: containers, spawners and player activity.",
                key("Enabled", "bf.enabled"),
                header("Detect"),
                tog("Scan containers", () -> Config.findStorage, v -> Config.findStorage = v, true),
                tog("Scan spawners", () -> Config.findSpawners, v -> Config.findSpawners = v, true),
                tog("Show spawner-only results", () -> Config.on("bf.spawneronly"), v -> Config.set("bf.spawneronly", v ? 1 : 0), true),
                tog("Ignore mineshafts", () -> Config.on("bf.nomine"), v -> Config.set("bf.nomine", v ? 1 : 0), true),
                tog("Ignore natural structures", () -> Config.on("bf.nonat"), v -> Config.set("bf.nonat", v ? 1 : 0), true),
                tog("Ignore farms", () -> Config.on("bf.nofarm"), v -> Config.set("bf.nofarm", v ? 1 : 0), true),
                tog("Ignore villages", () -> Config.ignoreVillages, v -> Config.ignoreVillages = v, true),
                tog("Chat alerts", () -> Config.autoAlert, v -> Config.autoAlert = v, false),
                header("Tuning"),
                sliderF("Scan radius", () -> Config.radius, v -> Config.radius = v, 4, 64, 4, v -> v + " chunks", true),
                sliderF("Min containers", () -> Config.v("bf.mincont"), v -> Config.set("bf.mincont", v), 1, 100, 1,
                        String::valueOf, true),
                sliderF("Min stash score", () -> Config.threshold, v -> Config.threshold = v, 2, 100, 2,
                        String::valueOf, true),
                header("Actions"),
                button("Scan now", () -> ChunkScanner.scan(Minecraft.getInstance())),
                button("Save results to file", () -> {
                    String p = ChunkScanner.save();
                    ChunkScanner.saveState();
                    chat(p != null ? "[BaseFinder] Saved to " + p : "[BaseFinder] Could not save file");
                }));

        add("sound", "Sound Detector", Cat.DETECTION,
                "Warn about nearby interactions, players and machinery.",
                key("Enabled", "sd.enabled"),
                key("Chat notices", "sd.chat"),
                key("World markers", "sd.markers"),
                slider("Card duration", "sd.duration", 3, 60, 1, v -> v + " sec"),
                slider("Visible cards", "sd.cards", 1, 6, 1, String::valueOf),
                slider("Source cooldown", "sd.cooldown", 5, 300, 5, v -> String.format("%.1f sec", v / 10.0)),
                slider("Detection range", "sd.range", 16, 128, 8, v -> v + " blocks"),
                choice("Minimum level", "sd.minsev", "Info", "Warning", "Critical"),
                header("Detect"),
                key("Players and doors", "sd.cat.players"),
                key("Containers", "sd.cat.cont"),
                key("Machinery", "sd.cat.mach"),
                key("Digging and building", "sd.cat.blocks"),
                key("Explosions", "sd.cat.boom"),
                header("Display"),
                key("Show while in menus", "sd.menus"),
                key("Respect global HUD toggle", "sd.hud"),
                choice("Card position", "sd.pos", "Top right", "Top left", "Bottom right", "Bottom left"),
                header("Preview and appearance"),
                button("Preview warning / move alert", () -> SoundDetector.preview(SoundDetector.WARN)),
                button("Preview critical alert", () -> SoundDetector.preview(SoundDetector.CRIT)),
                slider("Card scale", "sd.scale", 40, 150, 10, v -> v + "%"));

        add("storageesp", "Storage ESP", Cat.VISUAL,
                "Through-walls boxes on chests, barrels, shulkers, ender chests and beacons.",
                tog("Enabled", () -> Config.espStorage, v -> Config.espStorage = v, false),
                tog("Only inside stashes", () -> Config.espOnlyStashes, v -> Config.espOnlyStashes = v, true),
                espRange(), espOpacity());

        add("spawneresp", "Spawner ESP", Cat.VISUAL,
                "Red through-walls boxes on monster spawners.",
                tog("Enabled", () -> Config.espSpawners, v -> Config.espSpawners = v, false),
                espRange(), espOpacity());

        add("chunkhl", "Chunk Highlight", Cat.VISUAL,
                "Colours every chunk that belongs to a detected stash.",
                tog("Enabled", () -> Config.chunkHighlight, v -> Config.chunkHighlight = v, false),
                espOpacity());

        add("coordshud", "Coordinates HUD", Cat.VISUAL,
                "Shows your XYZ and facing in the top-left corner.",
                key("Enabled", "hud.coords"),
                header("Hidden by Streamer Mode."));

        add("neareststash", "Nearest Stash HUD", Cat.VISUAL,
                "Shows distance and direction to the closest stash.",
                key("Enabled", "hud.nearest"),
                header("Hidden coordinates in Streamer Mode."));

        add("amethyst", "Amethyst Scanner", Cat.WORLD,
                "Finds budding amethyst (geodes) in loaded chunks and boxes it through walls.",
                key("Enabled", "am.enabled"),
                slider("Scan radius", "am.radius", 2, 12, 1, v -> v + " chunks"),
                espOpacity());

        add("tracers", "Stash Tracer", Cat.VISUAL,
                "Dotted line from you to the nearest detected stash.",
                key("Enabled", "tr.enabled"),
                slider("Line length", "tr.dots", 10, 60, 5, v -> v + " dots"),
                header("Needs Base Finder enabled."));

        add("lightdebug", "Light Level HUD", Cat.VISUAL,
                "Shows block and sky light at your feet and whether mobs can spawn there.",
                key("Enabled", "hud.light"));

        add("playerradar", "Player Radar", Cat.DETECTION,
                "Lists nearby players with distance and direction on the HUD.",
                key("Enabled", "hud.players"),
                slider("Range", "hud.prange", 32, 256, 32, v -> v + " blocks"),
                slider("Max names", "hud.pcount", 1, 10, 1, String::valueOf));

        add("fullbright", "Fullbright", Cat.VISUAL,
                "See in the dark. Client-side visual only, nothing is sent to the server.",
                key("Enabled", "fv.enabled"));

        add("structures", "Structure Finder", Cat.WORLD,
                "Boxes trial spawners, vaults and sculk shriekers (trial chambers, ancient cities).",
                key("Enabled", "sf.enabled"),
                slider("Scan radius", "sf.radius", 2, 16, 1, v -> v + " chunks"),
                key("Trial spawners and vaults", "sf.trial"),
                key("Sculk shriekers", "sf.shrieker"));

        add("stashlist", "Stash List HUD", Cat.VISUAL,
                "Shows the top 3 detected stashes on screen, no need to open the menu.",
                key("Enabled", "hud.stashlist"));

        add("totemhud", "Totem Counter", Cat.VISUAL,
                "Shows how many totems of undying you carry.",
                key("Enabled", "hud.totems"));

        add("mapstats", "Map Stats HUD", Cat.VISUAL,
                "Shows how many chunks Base Finder has mapped and how many stashes it holds.",
                key("Enabled", "hud.mapstats"));

        add("memory", "Map Memory", Cat.UTILITY,
                "Remembers scanned chunks per server and dimension across restarts.",
                key("Enabled", "ut.memory"),
                header("Actions"),
                button("Save map now", () -> { ChunkScanner.saveState(); chat("[BaseFinder] Map saved"); }),
                button("Reset map", () -> { ChunkScanner.resetAll(); chat("[BaseFinder] Map reset"); }));

        add("streamer", "Streamer Mode", Cat.STREAMING,
                "Hides coordinates so you can record or stream safely.",
                key("Enabled", "st.on"),
                header("Hide coordinates in"),
                key("Chat messages", "st.chat"),
                key("HUD lines", "st.hud"),
                key("GUI results", "st.gui"));

        // ---- HUD tab ----
        HUD_SETTINGS.add(key("Global HUD toggle", "hud.master"));
        HUD_SETTINGS.add(key("Coordinates HUD", "hud.coords"));
        HUD_SETTINGS.add(key("Nearest stash HUD", "hud.nearest"));
        HUD_SETTINGS.add(header("Alert cards"));
        HUD_SETTINGS.add(slider("Card scale", "sd.scale", 40, 150, 10, v -> v + "%"));
        HUD_SETTINGS.add(choice("Card position", "sd.pos", "Top right", "Top left", "Bottom right", "Bottom left"));
        HUD_SETTINGS.add(key("Show while in menus", "sd.menus"));
        HUD_SETTINGS.add(key("Respect global HUD toggle", "sd.hud"));

        // ---- Appearance tab ----
        APPEARANCE_SETTINGS.add(header("Theme"));
        APPEARANCE_SETTINGS.add(choice("Accent colour", "ap.theme", THEME_NAMES));
        APPEARANCE_SETTINGS.add(slider("Panel opacity", "ap.opacity", 60, 100, 2, v -> v + "%"));
    }
}

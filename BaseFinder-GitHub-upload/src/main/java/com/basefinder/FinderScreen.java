package com.basefinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Client-style ClickGUI. Everything is drawn by hand (dark purple theme, sidebar, module cards
 * with toggle switches). Clicks are handled by invisible vanilla buttons laid over the drawn parts.
 */
public class FinderScreen extends Screen {
    private enum Tab {
        MODULES("Modules"), RESULTS("Results"), SETTINGS("Settings");
        final String label;
        Tab(String label) { this.label = label; }
    }

    private record Mod(String name, String desc, BooleanSupplier on, Runnable toggle) {}
    private record Row(String label, Supplier<String> value, Runnable dec, Runnable inc) {}

    // Colours (ARGB)
    private static final int ACCENT = 0xFFB65CFF;
    private static final int PANEL = 0xF2120C1E;
    private static final int PANEL_EDGE = 0xFF3B2A5A;
    private static final int SIDEBAR = 0xFF1A1228;
    private static final int CARD = 0xFF1B1428;
    private static final int CARD_HOVER = 0xFF261B3A;
    private static final int TEXT = 0xFFE8DFF5;
    private static final int DIM = 0xFF8E84A3;
    private static final int OFF = 0xFF3A3346;

    private static final int W = 424, H = 252;
    private static Tab tab = Tab.MODULES;

    private int px, py;
    private List<Mod> mods;
    private List<Row> rows;

    public FinderScreen() {
        super(Component.literal("Base Finder"));
    }

    // ---------------------------------------------------------------- setup
    @Override
    protected void init() {
        px = (this.width - W) / 2;
        py = (this.height - H) / 2;
        int cx = px + 120;

        // Sidebar tabs
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            final Tab t = tabs[i];
            hit(px + 8, py + 56 + i * 26, 104, 22, () -> { tab = t; this.rebuildWidgets(); });
        }

        mods = List.of(
                new Mod("StorageFinder", "Scan containers", () -> Config.findStorage,
                        () -> { Config.findStorage = !Config.findStorage; rescan(); }),
                new Mod("SpawnerFinder", "Scan spawners", () -> Config.findSpawners,
                        () -> { Config.findSpawners = !Config.findSpawners; rescan(); }),
                new Mod("ChunkFinder", "Chunk highlight", () -> Config.chunkHighlight,
                        () -> { Config.chunkHighlight = !Config.chunkHighlight; Config.save(); }),
                new Mod("Storage ESP", "Boxes on storage", () -> Config.espStorage,
                        () -> { Config.espStorage = !Config.espStorage; Config.save(); }),
                new Mod("Spawner ESP", "Boxes on spawners", () -> Config.espSpawners,
                        () -> { Config.espSpawners = !Config.espSpawners; Config.save(); }),
                new Mod("Alerts", "Chat on new find", () -> Config.autoAlert,
                        () -> { Config.autoAlert = !Config.autoAlert; Config.save(); })
        );

        rows = List.of(
                new Row("Scan radius", () -> Config.radius + " chunks",
                        () -> { Config.radius = Math.max(4, Config.radius - 4); rescan(); },
                        () -> { Config.radius = Math.min(64, Config.radius + 4); rescan(); }),
                new Row("Min stash score", () -> String.valueOf(Config.threshold),
                        () -> { Config.threshold = Math.max(2, Config.threshold - 2); rescan(); },
                        () -> { Config.threshold = Math.min(100, Config.threshold + 2); rescan(); }),
                new Row("ESP range", () -> Config.espRange + " blocks",
                        () -> { Config.espRange = Math.max(32, Config.espRange - 32); Config.save(); },
                        () -> { Config.espRange = Math.min(256, Config.espRange + 32); Config.save(); }),
                new Row("ESP opacity", () -> Config.espAlpha + "%",
                        () -> { Config.espAlpha = Math.max(10, Config.espAlpha - 10); Config.save(); },
                        () -> { Config.espAlpha = Math.min(100, Config.espAlpha + 10); Config.save(); })
        );

        if (tab == Tab.MODULES) {
            for (int i = 0; i < mods.size(); i++) {
                final Mod m = mods.get(i);
                hit(cx + (i % 2) * 148, py + 46 + (i / 2) * 52, 140, 44, m.toggle());
            }
        } else if (tab == Tab.SETTINGS) {
            int right = px + W - 14;
            for (int i = 0; i < rows.size(); i++) {
                final Row r = rows.get(i);
                int y = py + 46 + i * 36;
                hit(right - 128, y + 5, 20, 20, r.dec());
                hit(right - 26, y + 5, 20, 20, r.inc());
            }
        } else {
            hit(cx, py + 44, 92, 22, this::rescan);
            hit(cx + 98, py + 44, 92, 22, () -> {
                String path = ChunkScanner.save();
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal(path != null
                            ? "[BaseFinder] Saved to " + path : "[BaseFinder] Could not save file"), false);
                }
            });
            hit(cx + 196, py + 44, 92, 22, () -> { ChunkScanner.clear(); this.rebuildWidgets(); });

            List<ChunkScanner.Cluster> res = ChunkScanner.clusters;
            for (int i = 0; i < Math.min(7, res.size()); i++) {
                final ChunkScanner.Cluster c = res.get(i);
                hit(px + W - 14 - 44, py + 74 + i * 24 + 3, 44, 16, () ->
                        Minecraft.getInstance().keyboardHandler.setClipboard(c.x + " " + c.y + " " + c.z));
            }
        }
    }

    /** Invisible clickable area (a vanilla button with alpha 0). */
    private void hit(int x, int y, int w, int h, Runnable action) {
        Button b = Button.builder(Component.empty(), btn -> action.run()).bounds(x, y, w, h).build();
        b.setAlpha(0f);
        this.addRenderableWidget(b);
    }

    private void rescan() {
        ChunkScanner.scan(Minecraft.getInstance());
        Config.save();
        this.rebuildWidgets();
    }

    // ---------------------------------------------------------------- drawing
    private void rect(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, color);
    }

    private void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(this.font, s, x, y, color);
    }

    private boolean over(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick); // background + (invisible) buttons
        int cx = px + 120, cw = W - 128;

        // Panel
        rect(g, px - 1, py - 1, W + 2, H + 2, PANEL_EDGE);
        rect(g, px, py, W, H, PANEL);

        // Sidebar
        rect(g, px + 4, py + 4, 112, H - 8, SIDEBAR);
        rect(g, px + 14, py + 14, 14, 14, ACCENT);
        rect(g, px + 18, py + 18, 6, 6, SIDEBAR);
        text(g, "BASEFINDER", px + 34, py + 13, TEXT);
        text(g, "CLIENT", px + 34, py + 24, DIM);
        text(g, "MENU", px + 14, py + 44, DIM);

        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int ty = py + 56 + i * 26;
            boolean active = tabs[i] == tab;
            boolean hover = over(mx, my, px + 8, ty, 104, 22);
            if (active) rect(g, px + 8, ty, 104, 22, 0xFF2A1B42);
            else if (hover) rect(g, px + 8, ty, 104, 22, 0xFF221736);
            if (active) rect(g, px + 8, ty, 2, 22, ACCENT);
            text(g, tabs[i].label, px + 20, ty + 7, active ? TEXT : DIM);
        }

        // Status box
        rect(g, px + 8, py + H - 44, 104, 34, 0xFF140E20);
        rect(g, px + 14, py + H - 32, 5, 5, ACCENT);
        text(g, "Base Finder", px + 24, py + H - 38, TEXT);
        text(g, ChunkScanner.clusters.size() + " stash(es) found", px + 24, py + H - 26, DIM);

        // Header
        rect(g, cx, py + 8, cw, 26, SIDEBAR);
        rect(g, cx, py + 33, cw, 1, ACCENT);
        text(g, tab.label, cx + 10, py + 17, TEXT);
        String hint = "Esc to close";
        text(g, hint, cx + cw - this.font.width(hint) - 10, py + 17, DIM);

        if (tab == Tab.MODULES) drawModules(g, mx, my, cx);
        else if (tab == Tab.SETTINGS) drawSettings(g, mx, my, cx, cw);
        else drawResults(g, mx, my, cx, cw);
    }

    private void drawModules(GuiGraphics g, int mx, int my, int cx) {
        for (int i = 0; i < mods.size(); i++) {
            Mod m = mods.get(i);
            int x = cx + (i % 2) * 148, y = py + 46 + (i / 2) * 52;
            boolean on = m.on().getAsBoolean();
            rect(g, x, y, 140, 44, over(mx, my, x, y, 140, 44) ? CARD_HOVER : CARD);
            if (on) rect(g, x, y, 2, 44, ACCENT);
            text(g, m.name(), x + 10, y + 9, TEXT);
            text(g, m.desc(), x + 10, y + 25, DIM);
            toggle(g, x + 140 - 36, y + 9, on);
        }
        text(g, "Click a card to turn it on or off.", cx, py + H - 22, DIM);
    }

    private void toggle(GuiGraphics g, int x, int y, boolean on) {
        rect(g, x, y, 26, 12, on ? ACCENT : OFF);
        rect(g, on ? x + 16 : x + 2, y + 2, 8, 8, 0xFFFFFFFF);
    }

    private void drawSettings(GuiGraphics g, int mx, int my, int cx, int cw) {
        int right = px + W - 14;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int y = py + 46 + i * 36;
            rect(g, cx, y, cw, 30, CARD);
            text(g, r.label(), cx + 10, y + 11, TEXT);

            boolean hd = over(mx, my, right - 128, y + 5, 20, 20);
            boolean hi = over(mx, my, right - 26, y + 5, 20, 20);
            rect(g, right - 128, y + 5, 20, 20, hd ? ACCENT : OFF);
            rect(g, right - 26, y + 5, 20, 20, hi ? ACCENT : OFF);
            text(g, "-", right - 128 + 8, y + 11, 0xFFFFFFFF);
            text(g, "+", right - 26 + 7, y + 11, 0xFFFFFFFF);

            String v = r.value().get();
            int mid = right - 108 + (82 - this.font.width(v)) / 2;
            text(g, v, mid, y + 11, TEXT);
        }
        text(g, "ESP draws through walls: gold chests, orange barrels, purple shulkers, red spawners.",
                cx, py + H - 22, DIM);
    }

    private void drawResults(GuiGraphics g, int mx, int my, int cx, int cw) {
        String[] labels = {"Scan now", "Save file", "Clear"};
        for (int i = 0; i < 3; i++) {
            int x = cx + i * 98, y = py + 44;
            rect(g, x, y, 92, 22, over(mx, my, x, y, 92, 22) ? ACCENT : CARD);
            text(g, labels[i], x + (92 - this.font.width(labels[i])) / 2, y + 7, TEXT);
        }

        List<ChunkScanner.Cluster> res = ChunkScanner.clusters;
        if (res.isEmpty()) {
            text(g, "Nothing found yet. Fly around, or lower the min stash score.", cx, py + 84, DIM);
            return;
        }
        for (int i = 0; i < Math.min(7, res.size()); i++) {
            ChunkScanner.Cluster c = res.get(i);
            int y = py + 74 + i * 24;
            rect(g, cx, y, cw, 21, CARD);
            rect(g, cx, y, 2, 21, ACCENT);
            text(g, "#" + (i + 1) + "  " + c.x + ", " + c.y + ", " + c.z, cx + 8, y + 6, TEXT);
            text(g, (int) c.score + " pts  " + (int) c.dist + "m", cx + 130, y + 6, DIM);

            int bx = px + W - 14 - 44;
            boolean hv = over(mx, my, bx, y + 3, 44, 16);
            rect(g, bx, y + 3, 44, 16, hv ? ACCENT : OFF);
            text(g, "COPY", bx + (44 - this.font.width("COPY")) / 2, y + 7, 0xFFFFFFFF);
        }
        if (res.size() > 7) {
            text(g, "+" + (res.size() - 7) + " more in the saved file", cx, py + H - 22, DIM);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

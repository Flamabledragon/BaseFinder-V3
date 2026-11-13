package com.basefinder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.List;

/** Compact, client-style module panel. */
public class FinderScreen extends Screen {
    private enum Tab { MODULES, ESP, COORDS, SETTINGS }
    private Tab tab = Tab.MODULES;
    private int panelX, panelY;

    public FinderScreen() { super(Text.literal("BaseFinder ClickGUI")); }
    private int x(int n) { return panelX + n; }
    private int y(int n) { return panelY + n; }

    @Override protected void init() {
        panelX = Math.max(8, (width - 500) / 2);
        panelY = Math.max(8, (height - 330) / 2);
        addTab("Modules", Tab.MODULES, 12);
        addTab("ESP", Tab.ESP, 105);
        addTab("Coords", Tab.COORDS, 198);
        addTab("Settings", Tab.SETTINGS, 291);
        buildTab();
    }

    private void addTab(String name, Tab value, int offset) {
        addDrawableChild(ButtonWidget.builder(Text.literal(name), b -> { tab = value; clearAndInit(); })
                .dimensions(x(offset), y(42), 86, 20).build());
    }

    private void toggle(String name, boolean enabled, int bx, int by, Runnable action) {
        addDrawableChild(ButtonWidget.builder(Text.literal(name + "  [" + (enabled ? "ON" : "OFF") + "]"), b -> action.run())
                .dimensions(x(bx), y(by), 220, 22).build());
    }

    private void buildTab() {
        if (tab == Tab.MODULES) {
            toggle("Storage scan", ChunkScanner.findStorage, 18, 92, () -> { ChunkScanner.findStorage = !ChunkScanner.findStorage; rescan(); });
            toggle("Spawner scan", ChunkScanner.findSpawners, 254, 92, () -> { ChunkScanner.findSpawners = !ChunkScanner.findSpawners; rescan(); });
            toggle("Chest ESP", BaseFinderClient.espChests, 18, 126, () -> { BaseFinderClient.espChests = !BaseFinderClient.espChests; clearAndInit(); });
            toggle("Storage ESP", BaseFinderClient.espStorage, 254, 126, () -> { BaseFinderClient.espStorage = !BaseFinderClient.espStorage; clearAndInit(); });
            toggle("Spawner ESP", BaseFinderClient.espSpawners, 18, 160, () -> { BaseFinderClient.espSpawners = !BaseFinderClient.espSpawners; clearAndInit(); });
            toggle("Auto alerts", ChunkScanner.autoAlert, 254, 160, () -> { ChunkScanner.autoAlert = !ChunkScanner.autoAlert; clearAndInit(); });
            addDrawableChild(ButtonWidget.builder(Text.literal("Scan loaded area"), b -> rescan()).dimensions(x(18), y(210), 220, 22).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Clear results"), b -> { ChunkScanner.clear(); clearAndInit(); }).dimensions(x(254), y(210), 220, 22).build());
        } else if (tab == Tab.ESP) {
            toggle("Chest outlines", BaseFinderClient.espChests, 18, 92, () -> { BaseFinderClient.espChests = !BaseFinderClient.espChests; clearAndInit(); });
            toggle("Other storage outlines", BaseFinderClient.espStorage, 254, 92, () -> { BaseFinderClient.espStorage = !BaseFinderClient.espStorage; clearAndInit(); });
            toggle("Spawner outlines", BaseFinderClient.espSpawners, 18, 126, () -> { BaseFinderClient.espSpawners = !BaseFinderClient.espSpawners; clearAndInit(); });
        } else if (tab == Tab.COORDS) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Rescan loaded chunks"), b -> rescan()).dimensions(x(18), y(82), 220, 22).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Copy top coordinates"), b -> {
                if (!ChunkScanner.clusters.isEmpty()) { var c = ChunkScanner.clusters.get(0); MinecraftClient.getInstance().keyboard.setClipboard(c.x + " " + c.y + " " + c.z); }
            }).dimensions(x(254), y(82), 220, 22).build());
            List<ChunkScanner.Cluster> results = ChunkScanner.clusters;
            for (int i = 0; i < Math.min(6, results.size()); i++) {
                ChunkScanner.Cluster c = results.get(i);
                addDrawableChild(ButtonWidget.builder(Text.literal("Copy X " + c.x + " Y " + c.y + " Z " + c.z), b ->
                        MinecraftClient.getInstance().keyboard.setClipboard(c.x + " " + c.y + " " + c.z))
                        .dimensions(x(18 + (i % 2) * 236), y(120 + (i / 2) * 30), 220, 22).build());
            }
        } else {
            addDrawableChild(ButtonWidget.builder(Text.literal("Radius -"), b -> { ChunkScanner.radius = Math.max(4, ChunkScanner.radius - 4); rescan(); }).dimensions(x(18), y(92), 100, 22).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Radius +"), b -> { ChunkScanner.radius = Math.min(64, ChunkScanner.radius + 4); rescan(); }).dimensions(x(126), y(92), 100, 22).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Threshold -"), b -> { ChunkScanner.threshold = Math.max(2, ChunkScanner.threshold - 2); rescan(); }).dimensions(x(254), y(92), 100, 22).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Threshold +"), b -> { ChunkScanner.threshold = Math.min(100, ChunkScanner.threshold + 2); rescan(); }).dimensions(x(362), y(92), 100, 22).build());
        }
    }

    private void rescan() { ChunkScanner.scan(MinecraftClient.getInstance()); clearAndInit(); }

    @Override public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fill(panelX, panelY, panelX + 500, panelY + 330, 0xF0141420);
        ctx.fill(panelX, panelY, panelX + 500, panelY + 3, 0xFFB45CFF);
        ctx.fill(panelX + 1, panelY + 1, panelX + 499, panelY + 34, 0xFF202031);
        ctx.drawTextWithShadow(textRenderer, "BASEFINDER", x(14), y(12), 0xFFE4C4FF);
        ctx.drawTextWithShadow(textRenderer, "CLIENT MODULES", x(390), y(12), 0xFF9D91B4);
        ctx.fill(x(10), y(70), x(490), y(71), 0xFF454052);
        String subtitle = switch (tab) { case MODULES -> "Toggle modules and scanning"; case ESP -> "World outlines · loaded chunks only"; case COORDS -> "Candidate locations found while exploring"; case SETTINGS -> "Scanner configuration"; };
        ctx.drawTextWithShadow(textRenderer, subtitle, x(18), y(76), 0xFFB7ADC8);
        if (tab == Tab.COORDS && ChunkScanner.clusters.isEmpty()) ctx.drawTextWithShadow(textRenderer, "No candidates yet — fly around, then rescan.", x(18), y(120), 0xFFB7ADC8);
        if (tab == Tab.COORDS && ChunkScanner.clusters.size() > 6) ctx.drawTextWithShadow(textRenderer, "+" + (ChunkScanner.clusters.size() - 6) + " more results", x(18), y(222), 0xFFB7ADC8);
        if (tab == Tab.SETTINGS) ctx.drawTextWithShadow(textRenderer, "Radius: " + ChunkScanner.radius + " chunks     Minimum score: " + ChunkScanner.threshold, x(18), y(140), 0xFFFFFFFF);
        if (tab == Tab.MODULES) ctx.drawTextWithShadow(textRenderer, "Right Shift opens this panel", x(18), y(260), 0xFF8E849F);
        if (tab == Tab.ESP) ctx.drawTextWithShadow(textRenderer, "Gold = chests · Purple = storage · Red = spawners", x(18), y(190), 0xFFB7ADC8);
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override public boolean shouldPause() { return false; }
}

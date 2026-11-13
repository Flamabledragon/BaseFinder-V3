package com.basefinder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;


/** A compact, purple BaseFinder control panel. */
public class FinderScreen extends Screen {
    private static final int PURPLE = 0xFFC65CFF;
    private static final int PANEL = 0xF0181720;
    private static final int CARD = 0xFF24212D;
    private static final int CARD_HOVER = 0xFF342642;
    private static final int MUTED = 0xFFAAA3B5;
    private static final int GREEN = 0xFF75E0A0;
    private static final int RED = 0xFFFF7777;
    private String status = "Ready — scans use loaded client-side chunks only";

    public FinderScreen() {
        super(Text.literal("BaseFinder"));
    }

    private int panelLeft() { return (width - 560) / 2; }
    private int panelTop() { return (height - 330) / 2; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int x = panelLeft(), y = panelTop();
        ctx.fill(0, 0, width, height, 0x9908060D);
        ctx.fill(x, y, x + 560, y + 330, PANEL);
        ctx.fill(x, y, x + 560, y + 3, PURPLE);
        ctx.drawTextWithShadow(textRenderer, "BASEFINDER", x + 20, y + 16, PURPLE);
        ctx.drawTextWithShadow(textRenderer, "CLIENT MODULES  /  PRIVATE TEST BUILD", x + 20, y + 34, MUTED);
        ctx.drawTextWithShadow(textRenderer, "RIGHT SHIFT", x + 475, y + 20, MUTED);

        card(ctx, x + 18, y + 62, 254, 56, "Storage Finder", ChunkScanner.findStorage, "Chest • barrel • shulker");
        card(ctx, x + 288, y + 62, 254, 56, "Spawner Finder", ChunkScanner.findSpawners, "Loaded spawner block entities");
        card(ctx, x + 18, y + 128, 254, 56, "Local auto-save", ChunkScanner.autoAlert, "Save newly found groups locally");
        card(ctx, x + 288, y + 128, 254, 56, "Chat output", false, "Disabled — no chat messages");

        ctx.drawTextWithShadow(textRenderer, "SCAN SETTINGS", x + 20, y + 201, PURPLE);
        button(ctx, x + 18, y + 220, 120, 30, "RADIUS  -", mouseX, mouseY);
        button(ctx, x + 146, y + 220, 120, 30, "RADIUS  +", mouseX, mouseY);
        button(ctx, x + 288, y + 220, 120, 30, "SCORE  -", mouseX, mouseY);
        button(ctx, x + 416, y + 220, 126, 30, "SCORE  +", mouseX, mouseY);
        ctx.drawTextWithShadow(textRenderer, "Radius: " + ChunkScanner.radius + " chunks", x + 20, y + 257, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, "Minimum score: " + ChunkScanner.threshold, x + 288, y + 257, 0xFFFFFFFF);

        button(ctx, x + 18, y + 282, 160, 30, "SCAN NOW", mouseX, mouseY);
        button(ctx, x + 188, y + 282, 160, 30, "SAVE LOCAL FILE", mouseX, mouseY);
        button(ctx, x + 358, y + 282, 184, 30, "CLEAR RESULTS", mouseX, mouseY);
        ctx.drawTextWithShadow(textRenderer, status, x + 20, y + 319, MUTED);
        super.render(ctx, mouseX, mouseY, delta);
    }


    private void card(DrawContext ctx, int x, int y, int w, int h, String title, boolean enabled, String subtitle) {
        ctx.fill(x, y, x + w, y + h, CARD);
        ctx.fill(x, y, x + 3, y + h, enabled ? PURPLE : 0xFF55515D);
        ctx.drawTextWithShadow(textRenderer, title, x + 12, y + 10, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, subtitle, x + 12, y + 29, MUTED);
        int color = enabled ? GREEN : RED;
        ctx.fill(x + w - 52, y + 10, x + w - 12, y + 29, enabled ? 0xFF173B2A : 0xFF3B2026);
        ctx.drawCenteredTextWithShadow(textRenderer, enabled ? "ON" : "OFF", x + w - 32, y + 16, color);
    }

    private void button(DrawContext ctx, int x, int y, int w, int h, String label, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        ctx.fill(x, y, x + w, y + h, hover ? CARD_HOVER : CARD);
        ctx.fill(x, y, x + w, y + 1, hover ? PURPLE : 0xFF51465C);
        ctx.drawCenteredTextWithShadow(textRenderer, label, x + w / 2, y + 11, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int x = panelLeft(), y = panelTop();
        if (inside(mouseX, mouseY, x + 18, y + 62, 254, 56)) {
            ChunkScanner.findStorage = !ChunkScanner.findStorage; rescan(); return true;
        }
        if (inside(mouseX, mouseY, x + 288, y + 62, 254, 56)) {
            ChunkScanner.findSpawners = !ChunkScanner.findSpawners; rescan(); return true;
        }
        if (inside(mouseX, mouseY, x + 18, y + 128, 254, 56)) {
            ChunkScanner.autoAlert = !ChunkScanner.autoAlert; status = "Local auto-save " + (ChunkScanner.autoAlert ? "enabled" : "disabled"); return true;
        }
        if (inside(mouseX, mouseY, x + 18, y + 220, 120, 30)) { ChunkScanner.radius = Math.max(4, ChunkScanner.radius - 4); rescan(); return true; }
        if (inside(mouseX, mouseY, x + 146, y + 220, 120, 30)) { ChunkScanner.radius = Math.min(64, ChunkScanner.radius + 4); rescan(); return true; }
        if (inside(mouseX, mouseY, x + 288, y + 220, 120, 30)) { ChunkScanner.threshold = Math.max(2, ChunkScanner.threshold - 2); rescan(); return true; }
        if (inside(mouseX, mouseY, x + 416, y + 220, 126, 30)) { ChunkScanner.threshold = Math.min(100, ChunkScanner.threshold + 2); rescan(); return true; }
        if (inside(mouseX, mouseY, x + 18, y + 282, 160, 30)) { rescan(); status = "Scan complete — " + ChunkScanner.clusters.size() + " candidate groups"; return true; }
        if (inside(mouseX, mouseY, x + 188, y + 282, 160, 30)) {
            String path = ChunkScanner.save(); status = path == null ? "Could not save local results" : "Saved locally: " + path; return true;
        }
        if (inside(mouseX, mouseY, x + 358, y + 282, 184, 30)) { ChunkScanner.clear(); status = "Results cleared"; return true; }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void rescan() {
        ChunkScanner.scan(MinecraftClient.getInstance());
        status = "Scan complete — " + ChunkScanner.clusters.size() + " candidate groups";
    }


    @Override
    public boolean shouldPause() { return false; }
}

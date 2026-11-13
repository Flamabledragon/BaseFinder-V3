package com.basefinder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

public class FinderScreen extends Screen {
    private static final int ROWS = 8;
    private static final int LIST_Y = 108;

    public FinderScreen() {
        super(Text.literal("Base Finder"));
    }

    private int left() { return this.width / 2 - 170; }

    @Override
    protected void init() {
        int left = left();

        addDrawableChild(ButtonWidget.builder(label("Storage", ChunkScanner.findStorage), b -> {
            ChunkScanner.findStorage = !ChunkScanner.findStorage; rescan();
        }).dimensions(left, 28, 110, 20).build());
        addDrawableChild(ButtonWidget.builder(label("Spawners", ChunkScanner.findSpawners), b -> {
            ChunkScanner.findSpawners = !ChunkScanner.findSpawners; rescan();
        }).dimensions(left + 115, 28, 110, 20).build());
        addDrawableChild(ButtonWidget.builder(label("Alerts", ChunkScanner.autoAlert), b -> {
            ChunkScanner.autoAlert = !ChunkScanner.autoAlert; this.clearAndInit();
        }).dimensions(left + 230, 28, 110, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Radius -"), b -> {
            ChunkScanner.radius = Math.max(4, ChunkScanner.radius - 4); rescan();
        }).dimensions(left, 52, 82, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Radius +"), b -> {
            ChunkScanner.radius = Math.min(64, ChunkScanner.radius + 4); rescan();
        }).dimensions(left + 86, 52, 82, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Need -"), b -> {
            ChunkScanner.threshold = Math.max(2, ChunkScanner.threshold - 2); rescan();
        }).dimensions(left + 172, 52, 82, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Need +"), b -> {
            ChunkScanner.threshold = Math.min(100, ChunkScanner.threshold + 2); rescan();
        }).dimensions(left + 258, 52, 82, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Scan now"), b -> rescan())
                .dimensions(left, 76, 110, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Save to file"), b -> {
            String path = ChunkScanner.save();
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal(path != null
                        ? "[BaseFinder] Saved to " + path : "[BaseFinder] Could not save file"), false);
            }
        }).dimensions(left + 115, 76, 110, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear"), b -> {
            ChunkScanner.clear(); this.clearAndInit();
        }).dimensions(left + 230, 76, 110, 20).build());

        List<ChunkScanner.Cluster> res = ChunkScanner.clusters;
        for (int i = 0; i < Math.min(ROWS, res.size()); i++) {
            ChunkScanner.Cluster c = res.get(i);
            addDrawableChild(ButtonWidget.builder(Text.literal("Copy"), b ->
                    MinecraftClient.getInstance().keyboard.setClipboard(c.x + " " + c.y + " " + c.z)
            ).dimensions(left + 290, LIST_Y + i * 22, 50, 18).build());
        }
    }

    private Text label(String name, boolean on) {
        return Text.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    private void rescan() {
        ChunkScanner.scan(MinecraftClient.getInstance());
        this.clearAndInit();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int left = left();

        ctx.drawTextWithShadow(this.textRenderer,
                "Base Finder  |  radius " + ChunkScanner.radius + "  |  need score " + ChunkScanner.threshold,
                left, 12, 0xFFC447FF);

        List<ChunkScanner.Cluster> res = ChunkScanner.clusters;
        if (res.isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer, "Nothing found yet. Fly around, or lower \"Need\".",
                    left, LIST_Y + 4, 0xFFAAAAAA);
        }
        for (int i = 0; i < Math.min(ROWS, res.size()); i++) {
            ChunkScanner.Cluster c = res.get(i);
            String line = "X " + c.x + " Y " + c.y + " Z " + c.z + " | " + (int) c.score + " pts | "
                    + c.containers + "c " + c.spawners + "sp " + c.stacked + "stk | " + (int) c.dist + "m";
            ctx.drawTextWithShadow(this.textRenderer, line, left, LIST_Y + i * 22 + 5, 0xFFFFFFFF);
        }
        if (res.size() > ROWS) {
            ctx.drawTextWithShadow(this.textRenderer, "+" + (res.size() - ROWS) + " more (see saved file)",
                    left, LIST_Y + ROWS * 22 + 4, 0xFFAAAAAA);
        }
        ctx.drawTextWithShadow(this.textRenderer, "c=containers sp=spawners stk=stacked. Best first.",
                left, this.height - 14, 0xFF888888);
    }

    @Override
    public boolean shouldPause() { return false; }
}

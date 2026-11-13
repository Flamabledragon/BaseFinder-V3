package com.basefinder;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** In-game overlay: coordinates line, nearest-stash line and the Sound Detector cards. */
public final class Hud {
    private Hud() {}

    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("basefinder", "hud"),
                (graphics, tickCounter) -> render(graphics));
    }

    private static void render(GuiGraphics g) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;
            if (mc.screen instanceof FinderScreen) return; // the GUI draws its own preview
            if (mc.options.hideGui && Config.on("sd.hud")) return;

            boolean master = Config.on("hud.master");
            int offset = 0;
            if (master) offset = lines(g, mc);

            boolean cardsOk = (!Config.on("sd.hud") || master) && (mc.screen == null || Config.on("sd.menus"));
            if (cardsOk) SoundDetector.renderCards(g, mc, offset);
        } catch (Exception ignored) {
        }
    }

    /** Draws the info lines in the top-left corner, returns their height. */
    private static int lines(GuiGraphics g, Minecraft mc) {
        List<String> out = new ArrayList<>();
        boolean hideHud = Config.on("st.on") && Config.on("st.hud");

        if (Config.on("hud.coords")) {
            double yaw = mc.player.getYRot();
            String facing = SoundDetector.compass(-Math.sin(Math.toRadians(yaw)), Math.cos(Math.toRadians(yaw)));
            out.add(hideHud ? "XYZ: hidden" : String.format("XYZ: %d %d %d   %s",
                    (int) Math.floor(mc.player.getX()), (int) Math.floor(mc.player.getY()),
                    (int) Math.floor(mc.player.getZ()), facing));
        }

        if (Config.on("hud.nearest") && Config.on("bf.enabled")) {
            ChunkScanner.Cluster best = null;
            double bd = Double.MAX_VALUE;
            for (ChunkScanner.Cluster c : ChunkScanner.clusters) {
                double dx = c.x - mc.player.getX(), dz = c.z - mc.player.getZ();
                double d = dx * dx + dz * dz;
                if (d < bd) { bd = d; best = c; }
            }
            if (best != null) {
                double dx = best.x - mc.player.getX(), dz = best.z - mc.player.getZ();
                out.add("Stash: " + best.tier() + "  " + (int) Math.sqrt(bd) + "m " + SoundDetector.compass(dx, dz)
                        + "  " + relative(mc, dx, dz)
                        + (hideHud ? "" : "  (" + best.x + " " + best.y + " " + best.z + ")"));
            } else {
                out.add("Stash: none found yet");
            }
        }

        int y = 6;
        for (String line : out) {
            int w = mc.font.width(line);
            g.fill(4, y - 2, 4 + w + 4, y + 10, 0x90000000);
            g.drawString(mc.font, line, 6, y, 0xFFEDEFF2);
            y += 13;
        }
        return out.isEmpty() ? 0 : y - 2;
    }

    /** ahead / behind / left / right of where the player is looking. */
    private static String relative(Minecraft mc, double dx, double dz) {
        double target = Math.toDegrees(Math.atan2(dx, -dz));          // 0 = north, 90 = east
        double look = mc.player.getYRot() + 180.0;                    // yaw 0 = south
        double rel = ((target - look) % 360.0 + 540.0) % 360.0 - 180.0;
        if (Math.abs(rel) < 30) return "ahead";
        if (Math.abs(rel) > 150) return "behind";
        return rel > 0 ? "right" : "left";
    }
}

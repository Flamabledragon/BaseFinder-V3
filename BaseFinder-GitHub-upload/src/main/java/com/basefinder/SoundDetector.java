package com.basefinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Sound Detector: listens to every sound the client plays (the same hook the vanilla subtitles use),
 * works out what made it (chest, door, piston, explosion, a player nearby ...) and raises an alert card,
 * an optional chat line and an optional through-walls marker at the source.
 */
public final class SoundDetector {
    private SoundDetector() {}

    public static final int INFO = 0, WARN = 1, CRIT = 2;
    private static final int C_PLAYERS = 0, C_CONT = 1, C_MACH = 2, C_BLOCKS = 3, C_BOOM = 4;

    private static final int[] SEV_RGB = {0x6FA8FF, 0xFFC04D, 0xFF4D4D};
    private static final String[] SEV_TAG = {"INFO", "WARN", "CRIT"};

    public static class Card {
        public String title, sub, key;
        public int sev, count = 1;
        public long born;
    }

    public record Marker(double x, double y, double z, int sev, long expires) {}

    private record Raw(String path, double x, double y, double z) {}
    private record Hit(int cat, String label, int sev) {}

    private static final ConcurrentLinkedQueue<Raw> queue = new ConcurrentLinkedQueue<>();
    private static final List<Card> cards = new ArrayList<>();
    private static volatile List<Marker> markers = List.of();
    private static boolean hooked = false;
    private static Method idMethod;

    public static List<Marker> markers() { return markers; }

    // ------------------------------------------------------------ hook
    private static void hook(Minecraft mc) {
        if (hooked) return;
        hooked = true;
        try {
            mc.getSoundManager().addListener((sound, set, range) -> onSound(sound));
        } catch (Throwable ignored) {
        }
    }

    private static void onSound(SoundInstance s) {
        if (!Config.on("sd.enabled")) return;
        try {
            if (s.isRelative()) return; // music / UI sounds
            String p = pathOf(s);
            if (p.isEmpty()) return;
            queue.add(new Raw(p, s.getX(), s.getY(), s.getZ()));
            while (queue.size() > 256) queue.poll();
        } catch (Throwable ignored) {
        }
    }

    /** Finds the zero-arg method of SoundInstance that returns the sound's Identifier (name differs between versions). */
    private static String pathOf(SoundInstance s) throws Exception {
        if (idMethod == null) {
            for (Method m : SoundInstance.class.getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == Identifier.class) {
                    idMethod = m;
                    break;
                }
            }
        }
        if (idMethod == null) return "";
        Object id = idMethod.invoke(s);
        return id == null ? "" : ((Identifier) id).getPath();
    }

    // ------------------------------------------------------------ tick
    public static void tick(Minecraft mc) {
        hook(mc);
        long now = System.currentTimeMillis();
        long life = Config.v("sd.duration") * 1000L;

        cards.removeIf(c -> now - c.born > life);
        List<Marker> cur = markers;
        if (!cur.isEmpty()) {
            List<Marker> keep = new ArrayList<>();
            for (Marker m : cur) if (m.expires() > now) keep.add(m);
            if (keep.size() != cur.size()) markers = keep;
        }

        Raw r;
        int guard = 0;
        while ((r = queue.poll()) != null && guard++ < 128) {
            if (mc.level != null && mc.player != null && Config.on("sd.enabled")) handle(mc, r, now);
        }
    }

    private static void handle(Minecraft mc, Raw r, long now) {
        double dx = r.x() - mc.player.getX(), dy = r.y() - mc.player.getY(), dz = r.z() - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > Config.v("sd.range")) return;

        // is another player standing at the source?
        String who = null;
        double best = 4.5 * 4.5;
        for (var pl : mc.level.players()) {
            if (pl == mc.player) continue;
            double d = pl.distanceToSqr(r.x(), r.y(), r.z());
            if (d < best) {
                best = d;
                who = pl.getName().getString();
            }
        }

        Hit h = classify(r.path(), who != null);
        if (h == null) return;
        if (!catEnabled(h.cat())) return;
        if (h.sev() < Config.v("sd.minsev")) return;
        if (h.cat() != C_BOOM && dist < 6.0) return; // that is you

        String key = h.label() + "|" + (((int) Math.floor(r.x())) >> 3) + "," + (((int) Math.floor(r.y())) >> 3)
                + "," + (((int) Math.floor(r.z())) >> 3);
        long cd = Config.v("sd.cooldown") * 100L;
        for (Card c : cards) {
            if (c.key.equals(key) && now - c.born < cd) {
                c.count++;
                return;
            }
        }

        String sub = (who != null ? who + " - " : "") + (int) dist + "m " + compass(dx, dz);
        add(mc, h.label(), sub, h.sev(), key, r.x(), r.y(), r.z(), now);
    }

    private static void add(Minecraft mc, String title, String sub, int sev, String key,
                            double x, double y, double z, long now) {
        Card c = new Card();
        c.title = title;
        c.sub = sub;
        c.sev = sev;
        c.key = key;
        c.born = now;
        cards.add(0, c);
        while (cards.size() > 24) cards.remove(cards.size() - 1);

        if (Config.on("sd.markers")) {
            List<Marker> m = new ArrayList<>(markers);
            m.add(new Marker(x, y, z, sev, now + Config.v("sd.duration") * 1000L));
            while (m.size() > 40) m.remove(0);
            markers = m;
        }
        if (Config.on("sd.chat") && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[SoundDetector] " + title + " (" + sub + ") at "
                    + Config.xyz((int) x, (int) y, (int) z, "st.chat")), false);
        }
    }

    /** Fake event for the two preview buttons in the GUI. */
    public static void preview(int sev) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double yaw = Math.toRadians(mc.player.getYRot());
        double x = mc.player.getX() - Math.sin(yaw) * 12, z = mc.player.getZ() + Math.cos(yaw) * 12;
        double y = mc.player.getY() + 1;
        String title = sev == CRIT ? "Explosion nearby" : "Chest opened";
        add(mc, title, "Preview - 12m ahead", sev, "preview" + System.nanoTime(), x, y, z, System.currentTimeMillis());
    }

    // ------------------------------------------------------------ classification
    private static boolean catEnabled(int cat) {
        switch (cat) {
            case C_PLAYERS: return Config.on("sd.cat.players");
            case C_CONT: return Config.on("sd.cat.cont");
            case C_MACH: return Config.on("sd.cat.mach");
            case C_BLOCKS: return Config.on("sd.cat.blocks");
            default: return Config.on("sd.cat.boom");
        }
    }

    private static Hit classify(String p, boolean nearPlayer) {
        // containers
        if (p.equals("block.chest.open")) return new Hit(C_CONT, "Chest opened", WARN);
        if (p.equals("block.barrel.open")) return new Hit(C_CONT, "Barrel opened", WARN);
        if (p.equals("block.shulker_box.open")) return new Hit(C_CONT, "Shulker opened", WARN);
        if (p.equals("block.ender_chest.open")) return new Hit(C_CONT, "Ender chest opened", WARN);

        // doors and gates (someone walking around)
        if (p.startsWith("block.") && p.endsWith(".open")) {
            if (p.contains("trapdoor")) return new Hit(C_PLAYERS, "Trapdoor opened", INFO);
            if (p.contains("door")) return new Hit(C_PLAYERS, "Door opened", WARN);
            if (p.contains("fence_gate")) return new Hit(C_PLAYERS, "Gate opened", INFO);
        }

        // explosions
        if (p.equals("entity.generic.explode")) return new Hit(C_BOOM, "Explosion", CRIT);
        if (p.equals("entity.tnt.primed")) return new Hit(C_BOOM, "TNT primed", CRIT);

        // players
        if (p.startsWith("entity.player.attack")) return new Hit(C_PLAYERS, "Player combat", WARN);
        if (p.equals("entity.player.hurt")) return new Hit(C_PLAYERS, "Player hurt", WARN);
        if (p.equals("entity.player.death")) return new Hit(C_PLAYERS, "Player died", CRIT);
        if (p.equals("item.totem.use")) return new Hit(C_PLAYERS, "Totem popped", CRIT);
        if (p.equals("item.elytra.flying")) return new Hit(C_PLAYERS, "Elytra flying", WARN);
        if (p.equals("entity.ender_pearl.throw")) return new Hit(C_PLAYERS, "Pearl thrown", WARN);
        if (p.equals("entity.arrow.shoot") || p.equals("item.crossbow.shoot"))
            return new Hit(C_PLAYERS, "Projectile fired", WARN);
        if (p.equals("entity.firework_rocket.launch")) return new Hit(C_PLAYERS, "Firework launched", INFO);
        if (nearPlayer) {
            if (p.endsWith(".step")) return new Hit(C_PLAYERS, "Footsteps", INFO);
            if (p.startsWith("item.armor.equip")) return new Hit(C_PLAYERS, "Armor equipped", INFO);
            if (p.equals("entity.generic.eat") || p.equals("entity.generic.drink"))
                return new Hit(C_PLAYERS, "Player eating", INFO);
        }

        // machinery
        if (p.startsWith("block.piston.")) return new Hit(C_MACH, "Piston", INFO);
        if (p.startsWith("block.dispenser.")) return new Hit(C_MACH, "Dispenser fired", WARN);
        if (p.endsWith("pressure_plate.click_on") || p.endsWith("button.click_on") || p.equals("block.lever.click"))
            return new Hit(C_MACH, "Redstone input", INFO);
        if (p.equals("entity.minecart.riding")) return new Hit(C_MACH, "Minecart moving", INFO);
        if (p.endsWith("furnace.fire_crackle") || p.equals("block.smoker.smoke"))
            return new Hit(C_MACH, "Furnace running", INFO);
        if (p.equals("block.anvil.use")) return new Hit(C_MACH, "Anvil used", WARN);
        if (p.equals("block.brewing_stand.brew")) return new Hit(C_MACH, "Brewing", INFO);
        if (p.equals("block.end_portal.spawn") || p.equals("block.end_portal_frame.fill"))
            return new Hit(C_MACH, "End portal", WARN);

        // block break / place (digging and building)
        if (p.startsWith("block.")) {
            if (p.endsWith(".break")) return new Hit(C_BLOCKS, "Block broken", INFO);
            if (p.endsWith(".place")) return new Hit(C_BLOCKS, "Block placed", INFO);
        }
        return null;
    }

    public static String compass(double dx, double dz) {
        double b = Math.toDegrees(Math.atan2(dx, -dz));
        if (b < 0) b += 360;
        String[] n = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return n[((int) Math.round(b / 45)) % 8];
    }

    // ------------------------------------------------------------ drawing
    private static int argb(float alpha, int rgb) {
        int a = Math.max(8, Math.min(255, (int) (alpha * 255)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    /** Draws the alert cards. topLeftOffset moves them down when the HUD has text in the top-left corner. */
    public static void renderCards(GuiGraphics g, Minecraft mc, int topLeftOffset) {
        if (cards.isEmpty()) return;
        var font = mc.font;
        int vis = Config.v("sd.cards");
        float s = Config.v("sd.scale") / 100f;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int pos = Config.v("sd.pos"); // 0 top-right, 1 top-left, 2 bottom-right, 3 bottom-left
        int cw = 150, ch = 30;
        float stepY = (ch + 4) * s;
        long now = System.currentTimeMillis();
        float life = Config.v("sd.duration");

        int shown = 0;
        for (Card c : cards) {
            if (shown >= vis) break;
            float age = (now - c.born) / 1000f;
            float in = Math.max(0f, Math.min(1f, age / 0.2f));
            float out = Math.max(0f, Math.min(1f, (life - age) / 0.5f));
            float alpha = Math.min(in, out);
            if (alpha <= 0f) continue;

            boolean right = pos == 0 || pos == 2;
            boolean top = pos == 0 || pos == 1;
            float x = right ? sw - cw * s - 6 : 6;
            float y = top ? 6 + topLeftOffset * (right ? 0 : 1) + shown * stepY
                    : sh - ch * s - 34 - shown * stepY;
            float slide = (1f - in) * 24f * s * (right ? 1 : -1);

            int rgb = SEV_RGB[c.sev];
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(x + slide, y);
            pose.scale(s, s);

            g.fill(0, 0, cw, ch, argb(alpha * 0.92f, 0x14171C));
            g.fill(0, 0, 3, ch, argb(alpha, rgb));
            String title = c.title + (c.count > 1 ? "  x" + c.count : "");
            g.drawString(font, title, 9, 5, argb(alpha, 0xEDEFF2));
            g.drawString(font, c.sub, 9, 17, argb(alpha, 0x8A9099));
            String tag = SEV_TAG[c.sev];
            g.drawString(font, tag, cw - font.width(tag) - 6, 5, argb(alpha, rgb));
            float remain = Math.max(0f, Math.min(1f, (life - age) / life));
            g.fill(3, ch - 2, 3 + (int) ((cw - 3) * remain), ch, argb(alpha, rgb));

            pose.popMatrix();
            shown++;
        }
    }
}

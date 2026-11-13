package com.basefinder;

import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** Fullbright: gives yourself a client-side night vision effect. Nothing is sent to the server. */
public final class Extras {
    private Extras() {}

    private static boolean applied = false;

    public static void tick(Minecraft mc) {
        if (mc.player == null) { applied = false; return; }
        if (Config.on("fv.enabled")) {
            MobEffectInstance cur = mc.player.getEffect(MobEffects.NIGHT_VISION);
            if (cur == null || cur.getDuration() < 400) {
                mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 800, 0, false, false, false));
            }
            applied = true;
        } else if (applied) {
            mc.player.removeEffect(MobEffects.NIGHT_VISION);
            applied = false;
        }
    }
}

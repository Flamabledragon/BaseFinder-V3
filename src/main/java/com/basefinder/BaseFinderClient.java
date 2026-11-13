package com.basefinder;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class BaseFinderClient implements ClientModInitializer {
    private static KeyMapping openGui;

    @Override
    public void onInitializeClient() {
        Config.load();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("basefinder", "main"));
        openGui = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.basefinder.open_gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                category));

        EspRenderer.init();
        Hud.register();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ChunkScanner.saveState());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ChunkScanner.tick(client);
            SoundDetector.tick(client);
            WorldScanner.tick(client);
            while (openGui.consumeClick()) {
                if (client.level != null && client.screen == null) {
                    if (Config.on("bf.enabled")) ChunkScanner.scan(client);
                    client.setScreen(new FinderScreen());
                }
            }
        });
    }
}

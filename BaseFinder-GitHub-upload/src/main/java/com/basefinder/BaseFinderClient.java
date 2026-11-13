package com.basefinder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class BaseFinderClient implements ClientModInitializer {
    private static KeyBinding openGui;

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("basefinder", "main"));
        openGui = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.basefinder.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ChunkScanner.tick(client);
            while (openGui.wasPressed()) {
                if (client.world != null && client.currentScreen == null) {
                    ChunkScanner.scan(client);
                    client.setScreen(new FinderScreen());
                }
            }
        });
    }
}

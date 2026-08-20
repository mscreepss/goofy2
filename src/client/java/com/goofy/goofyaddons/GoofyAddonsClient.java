package com.goofy.goofyaddons;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.event.ChatHook;
import com.goofy.goofyaddons.failsafes.FailsafeManager;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.features.economy.EconomyTracker;
import com.goofy.goofyaddons.keybinds.GoofyKeybinds;
import com.goofy.goofyaddons.render.hud.EconomyHud;
import com.goofy.goofyaddons.render.hud.GoofyOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class GoofyAddonsClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        GoofyConfig.load();
        ChatHook.register();
        GoofyKeybinds.register();
        EconomyTracker.register();
        EconomyHud.register();
        GoofyOverlay.register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            FailsafeManager.INSTANCE.onTick();
            FeatureManager.INSTANCE.onTick();

            while (GoofyKeybinds.reloadConfigKey.consumeClick()) {
                GoofyConfig.load();
            }

            while (GoofyKeybinds.startKey.consumeClick()) {
                FeatureManager.INSTANCE.start("BazaarFlipper");
            }
            while (GoofyKeybinds.stopKey.consumeClick()) {
                FeatureManager.INSTANCE.stop();
            }
            while (GoofyKeybinds.toggleOverlayKey.consumeClick()) {
                GoofyOverlay.toggle();
            }
        });
    }
}

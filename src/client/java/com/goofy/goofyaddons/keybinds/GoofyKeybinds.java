package com.goofy.goofyaddons.keybinds;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class GoofyKeybinds {

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("goofyaddons", "category")
    );

    public static KeyMapping startKey;
    public static KeyMapping stopKey;
    public static KeyMapping reloadConfigKey;
    public static KeyMapping toggleOverlayKey;
    public static KeyMapping toggleEconomyModeKey;

    public static void register() {
        startKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.goofyaddons.start",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                CATEGORY
        ));

        stopKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.goofyaddons.stop",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                CATEGORY
        ));

        toggleOverlayKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.goofyaddons.toggle_overlay",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                CATEGORY
        ));

        // Ekonomi HUD'unda All-time <-> Session geçişi.
        // V tuşu TR-Q ve QWERTY düzeninde aynı fiziksel konumda olduğu için
        // reloadConfigKey'deki gibi bir düzen düzeltmesine gerek yok.
        toggleEconomyModeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.goofyaddons.toggle_economy_mode",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                CATEGORY
        ));

        // GLFW_KEY_* sabitleri fiziksel/konum tabanlıdır: ABD (QWERTY) düzeninde o karakterin
        // bulunduğu FİZİKSEL tuş konumunu temsil eder, aktif klavye düzeninden bağımsızdır.
        // TR-Q klavyede "/" karakteri Shift+7 ile yazılır (fiziksel olarak "7" konumu), bu yüzden
        // GLFW_KEY_SLASH değil GLFW_KEY_7 kullanılıyor.
        reloadConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.goofyaddons.reload_config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_7,
                CATEGORY
        ));
    }
}

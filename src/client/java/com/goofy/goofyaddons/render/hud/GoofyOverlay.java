package com.goofy.goofyaddons.render.hud;

import com.goofy.goofyaddons.GoofyAddons;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.features.bookflipper.BazaarFlipper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Persistent HUD overlay showing live BazaarFlipper macro status (state, active book,
 * per-book task progress). Same pattern as EconomyHud: registered once via
 * HudElementRegistry so it renders every frame, on top of the world and around
 * container screens - it is NOT a Screen you open/close.
 */
public class GoofyOverlay {

    private static final Identifier HEADER = Identifier.fromNamespaceAndPath(GoofyAddons.MOD_ID, "textures/gui/header.png");

    private static final int PANEL_X = 6;
    private static final int PANEL_GAP = 6; // EconomyHud panelinin altındaki boşluk
    private static final int PANEL_WIDTH = 220;
    private static final int HEADER_WIDTH = 160;
    private static final int HEADER_HEIGHT = HEADER_WIDTH * 192 / 1195;
    private static final int LINE_HEIGHT = 12;
    private static final int PADDING = 6;

    public static boolean visible = true;

    private GoofyOverlay() {
    }

    /**
     * ESKİ KOD: PANEL_Y sabit 58'di ve EconomyHud'un 3 satırlık yüksekliğine göre
     * elle hesaplanmıştı. EconomyHud'a uptime + mod satırı eklenince paneller üst
     * üste biniyordu; artık konum ekonomi panelinin gerçek yüksekliğinden türetiliyor.
     */
    private static int panelY() {
        return EconomyHud.PANEL_Y + EconomyHud.getPanelHeight() + PANEL_GAP;
    }

    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(GoofyAddons.MOD_ID, "goofy_overlay"),
                GoofyOverlay::render
        );
    }

    public static void toggle() {
        visible = !visible;
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        if (!visible) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;

        boolean running = FeatureManager.INSTANCE.isMacroRunning();
        String stateName = "Idle";
        String activeBook = "-";
        List<String> taskLines = List.of();

        if (running && FeatureManager.INSTANCE.getCurrentFeature() instanceof BazaarFlipper flipper) {
            stateName = flipper.getStateName();
            activeBook = flipper.getActiveBookName();
            taskLines = flipper.getTaskSummary();
        }

        int panelY = panelY();
        int contentLines = 2 + Math.max(taskLines.size(), 1);
        int panelHeight = HEADER_HEIGHT + PADDING * 3 + (LINE_HEIGHT * contentLines);

        graphics.fill(PANEL_X, panelY, PANEL_X + PANEL_WIDTH, panelY + panelHeight, 0x90252525);
        graphics.outline(PANEL_X, panelY, PANEL_WIDTH, panelHeight, 0xFF000000);

        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                HEADER,
                PANEL_X + (PANEL_WIDTH - HEADER_WIDTH) / 2,
                panelY + PADDING,
                0, 0,
                HEADER_WIDTH, HEADER_HEIGHT,
                1195, 192,
                1195, 192
        );

        int textX = PANEL_X + PADDING;
        int textY = panelY + PADDING * 2 + HEADER_HEIGHT;

        int statusColor = running ? 0xFF55FF55 : 0xFFFF5555;
        String statusText = "Macro: " + (running ? "Running" : "Idle") + (running ? " [" + stateName + "]" : "");
        if (running && FeatureManager.INSTANCE.isPaused()) {
            statusText = "Macro: Paused [" + stateName + "]";
            statusColor = 0xFFFFAA00;
        }
        graphics.text(minecraft.font, statusText, textX, textY, statusColor, false);
        textY += LINE_HEIGHT;

        graphics.text(minecraft.font, "Active Book: " + activeBook, textX, textY, 0xFFAAAAAA, false);
        textY += LINE_HEIGHT;

        if (taskLines.isEmpty()) {
            graphics.text(minecraft.font, "No active tasks", textX, textY, 0xFF888888, false);
        } else {
            for (String line : taskLines) {
                graphics.text(minecraft.font, line, textX, textY, 0xFFDDDDDD, false);
                textY += LINE_HEIGHT;
            }
        }
    }
}

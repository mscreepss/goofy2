package com.goofy.goofyaddons.render.hud;

import com.goofy.goofyaddons.GoofyAddons;
import com.goofy.goofyaddons.features.economy.EconomyTracker;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Persistent HUD overlay (not a Screen you open/close) showing running bazaar economy stats:
 * total spend, total earn and clean profit. Registered once on client init and rendered every
 * frame via HudElementRegistry, so it stays on screen at all times.
 */
public class EconomyHud {

    private static final NumberFormat FORMAT = NumberFormat.getNumberInstance(Locale.US);

    static {
        FORMAT.setMaximumFractionDigits(0);
    }

    private static final int PANEL_X = 6;
    private static final int PANEL_Y = 6;
    private static final int PANEL_WIDTH = 190;
    private static final int LINE_HEIGHT = 12;
    private static final int PADDING = 6;

    private EconomyHud() {
    }

    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(GoofyAddons.MOD_ID, "economy_hud"),
                EconomyHud::render
        );
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;

        double spend = EconomyTracker.getTotalSpend();
        double earn = EconomyTracker.getTotalEarn();
        double profit = EconomyTracker.getCleanProfit();

        int panelHeight = (LINE_HEIGHT * 3) + (PADDING * 2) - 2;

        graphics.fill(PANEL_X, PANEL_Y, PANEL_X + PANEL_WIDTH, PANEL_Y + panelHeight, 0x90252525);
        graphics.outline(PANEL_X, PANEL_Y, PANEL_WIDTH, panelHeight, 0xFF000000);

        int textX = PANEL_X + PADDING;
        int textY = PANEL_Y + PADDING;

        int profitColor = profit >= 0 ? 0xFF55FFFF : 0xFFFF5555;

        graphics.text(minecraft.font, "Total Spend: " + FORMAT.format(spend), textX, textY, 0xFFFF5555, false);
        graphics.text(minecraft.font, "Total Earn: " + FORMAT.format(earn), textX, textY + LINE_HEIGHT, 0xFF55FF55, false);
        graphics.text(minecraft.font, "Clean Profit: " + FORMAT.format(profit), textX, textY + LINE_HEIGHT * 2, profitColor, false);
    }
}

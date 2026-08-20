package com.goofy.goofyaddons.render.hud;

import com.goofy.goofyaddons.GoofyAddons;
import com.goofy.goofyaddons.features.FeatureManager;
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
 * spend, earn, clean profit ve makro uptime'ı. Registered once on client init and rendered
 * every frame via HudElementRegistry, so it stays on screen at all times.
 *
 * Gösterilen değerler aktif moda göre değişir (V tuşu):
 *  - All-time : mod kurulduğundan beri toplam (diske yazılır)
 *  - Session  : sadece bu oyun oturumu
 */
public class EconomyHud {

    private static final NumberFormat FORMAT = NumberFormat.getNumberInstance(Locale.US);

    static {
        FORMAT.setMaximumFractionDigits(0);
    }

    public static final int PANEL_X = 6;
    public static final int PANEL_Y = 6;
    private static final int PANEL_WIDTH = 200;
    private static final int LINE_HEIGHT = 12;
    private static final int PADDING = 6;
    private static final int LINE_COUNT = 5; // mod basligi + spend + earn + profit + uptime

    private static final int COLOR_ALL_TIME = 0xFFFFAA00;
    private static final int COLOR_SESSION = 0xFF55FFFF;
    private static final int COLOR_HINT = 0xFF777777;
    private static final int COLOR_SPEND = 0xFFFF5555;
    private static final int COLOR_EARN = 0xFF55FF55;
    private static final int COLOR_UPTIME_ACTIVE = 0xFFDDDDDD;
    private static final int COLOR_UPTIME_IDLE = 0xFF888888;

    private EconomyHud() {
    }

    /** GoofyOverlay kendi konumunu bu panelin altına hizalamak için kullanır. */
    public static int getPanelHeight() {
        return (LINE_HEIGHT * LINE_COUNT) + (PADDING * 2) - 2;
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

        double spend = EconomyTracker.getSpend();
        double earn = EconomyTracker.getEarn();
        double profit = EconomyTracker.getProfit();
        long uptimeMs = EconomyTracker.getUptimeMs();

        int panelHeight = getPanelHeight();

        graphics.fill(PANEL_X, PANEL_Y, PANEL_X + PANEL_WIDTH, PANEL_Y + panelHeight, 0x90252525);
        graphics.outline(PANEL_X, PANEL_Y, PANEL_WIDTH, panelHeight, 0xFF000000);

        int textX = PANEL_X + PADDING;
        int textY = PANEL_Y + PADDING;

        // 1) Aktif mod + V ipucu
        String modeLabel = "Economy: " + EconomyTracker.getModeLabel();
        int modeColor = EconomyTracker.getMode() == EconomyTracker.Mode.SESSION ? COLOR_SESSION : COLOR_ALL_TIME;
        graphics.text(minecraft.font, modeLabel, textX, textY, modeColor, false);
        graphics.text(minecraft.font, " [V]", textX + minecraft.font.width(modeLabel), textY, COLOR_HINT, false);
        textY += LINE_HEIGHT;

        // 2-4) Para
        graphics.text(minecraft.font, "Spend: " + FORMAT.format(spend), textX, textY, COLOR_SPEND, false);
        textY += LINE_HEIGHT;

        graphics.text(minecraft.font, "Earn: " + FORMAT.format(earn), textX, textY, COLOR_EARN, false);
        textY += LINE_HEIGHT;

        int profitColor = profit >= 0 ? COLOR_SESSION : COLOR_SPEND;
        graphics.text(minecraft.font, "Profit: " + FORMAT.format(profit), textX, textY, profitColor, false);
        textY += LINE_HEIGHT;

        // 5) Uptime - sayacın şu an ilerleyip ilerlemediğini de gösterir
        boolean counting = EconomyTracker.isCountingUptime();
        String suffix = counting
                ? ""
                : (FeatureManager.INSTANCE.isMacroRunning() ? " (paused)" : " (stopped)");
        graphics.text(minecraft.font,
                "Uptime: " + EconomyTracker.formatUptime(uptimeMs) + suffix,
                textX, textY,
                counting ? COLOR_UPTIME_ACTIVE : COLOR_UPTIME_IDLE,
                false);
    }
}

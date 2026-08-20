package com.goofy.goofyaddons.features.economy;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.event.ChatHook;
import com.goofy.goofyaddons.features.FeatureManager;
import net.minecraft.client.Minecraft;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bazaar harcama/kazanç ve makro çalışma süresi (uptime) takibi.
 *
 * İKİ MOD:
 *  - ALL_TIME : mod kurulduğundan beri toplam (GoofyConfig içinde diske yazılır)
 *  - SESSION  : sadece bu oyun oturumu (oyun kapanınca sıfırlanır)
 * V tuşu ile modlar arasında geçilir (GoofyKeybinds.toggleEconomyModeKey).
 *
 * UPTIME KURALI: sayaç SADECE makro gerçekten çalışırken ilerler. Makro
 * durdurulduğunda (K), duraklatıldığında (ScheduledReboot -> pause), oyun
 * duraklatıldığında ya da dünyaya bağlı değilken geçen süre EKLENMEZ. Süre tick
 * sayısıyla değil duvar saatiyle (System.currentTimeMillis) ölçülür; iki tick
 * arasında anormal büyük bir boşluk varsa (donma, alt-tab, sunucu lag'i) o boşluk
 * uptime'a yazılmaz.
 *
 * Spend example:
 *   [Bazaar] Claimed 2x Wisdom I worth 279,780 coins bought for 139,890 each!
 *
 * Earn example:
 *   [Bazaar] Claimed 381,453 coins from selling 1x Rejuvenate V at 385,793 each!
 */
public class EconomyTracker {

    public enum Mode {
        ALL_TIME("All-time"),
        SESSION("Session");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    // Group 1 = total worth of the claimed buy order (this is what actually left the purse).
    private static final Pattern BUY_CLAIM_PATTERN = Pattern.compile(
            "Claimed \\d+x .+ worth ([\\d,]+(?:\\.\\d+)?) coins bought for [\\d,]+(?:\\.\\d+)? each!"
    );

    // Group 1 = total coins actually received from the sell order (post-tax amount).
    private static final Pattern SELL_CLAIM_PATTERN = Pattern.compile(
            "Claimed ([\\d,]+(?:\\.\\d+)?) coins from selling \\d+x .+ at [\\d,]+(?:\\.\\d+)? each!"
    );

    /** İki tick arası bu değerden büyük bir boşluk oluştuysa (donma/duraklama) uptime'a yazılmaz. */
    private static final long MAX_TICK_GAP_MS = 5_000;

    /** Uptime'ı her tick diske yazmak yerine bu aralıkla kaydet. */
    private static final long SAVE_INTERVAL_MS = 30_000;

    private static Mode mode = Mode.ALL_TIME;

    private static double sessionSpend = 0;
    private static double sessionEarn = 0;
    private static long sessionUptimeMs = 0;

    private static long lastTickMs = 0;
    private static long unsavedUptimeMs = 0;

    private EconomyTracker() {
    }

    public static void register() {
        ChatHook.onMessage("Claimed", EconomyTracker::onChatMessage);
    }

    /**
     * Her client tick'inde çağrılır (GoofyAddonsClient). Uptime sayacını yalnızca
     * makro aktifken ilerletir.
     */
    public static void onTick() {
        if (GoofyConfig.INSTANCE == null) return;

        long now = System.currentTimeMillis();
        long delta = (lastTickMs == 0) ? 0 : now - lastTickMs;
        lastTickMs = now;

        if (!isCountingUptime()) {
            // Aktif değiliz: geçen süre EKLENMEZ. Birikmiş süre varsa diske yaz ki
            // makro durdurulduğunda/oyun kapandığında son saniyeler kaybolmasın.
            flush();
            return;
        }

        if (delta <= 0 || delta > MAX_TICK_GAP_MS) return;

        sessionUptimeMs += delta;
        GoofyConfig.INSTANCE.totalUptimeMs += delta;
        unsavedUptimeMs += delta;

        if (unsavedUptimeMs >= SAVE_INTERVAL_MS) flush();
    }

    /**
     * Uptime sayacı şu an ilerliyor mu? (HUD bunu "paused/stopped" göstergesi için
     * de kullanıyor.)
     */
    public static boolean isCountingUptime() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return false;
        if (minecraft.isPaused()) return false;
        return FeatureManager.INSTANCE.isMacroActive();
    }

    private static void flush() {
        if (unsavedUptimeMs <= 0) return;
        unsavedUptimeMs = 0;
        GoofyConfig.save();
    }

    private static void onChatMessage(String message) {
        Matcher buyMatcher = BUY_CLAIM_PATTERN.matcher(message);
        if (buyMatcher.find()) {
            double amount = parseAmount(buyMatcher.group(1));
            GoofyConfig.INSTANCE.totalSpend += amount;
            sessionSpend += amount;
            GoofyConfig.save();
            return;
        }

        Matcher sellMatcher = SELL_CLAIM_PATTERN.matcher(message);
        if (sellMatcher.find()) {
            double amount = parseAmount(sellMatcher.group(1));
            GoofyConfig.INSTANCE.totalEarn += amount;
            sessionEarn += amount;
            GoofyConfig.save();
        }
    }

    private static double parseAmount(String raw) {
        return Double.parseDouble(raw.replace(",", ""));
    }

    // ---------------------------------------------------------------- mod kontrolü

    public static Mode getMode() {
        return mode;
    }

    public static String getModeLabel() {
        return mode.getLabel();
    }

    public static void toggleMode() {
        mode = (mode == Mode.ALL_TIME) ? Mode.SESSION : Mode.ALL_TIME;
    }

    // ------------------------------------------------- aktif moda göre okunan değerler

    public static double getSpend() {
        return mode == Mode.SESSION ? sessionSpend : getTotalSpend();
    }

    public static double getEarn() {
        return mode == Mode.SESSION ? sessionEarn : getTotalEarn();
    }

    public static double getProfit() {
        return getEarn() - getSpend();
    }

    public static long getUptimeMs() {
        return mode == Mode.SESSION ? sessionUptimeMs : getTotalUptimeMs();
    }

    // ------------------------------------------------------------ ham (moddan bağımsız)

    public static double getTotalSpend() {
        return GoofyConfig.INSTANCE == null ? 0 : GoofyConfig.INSTANCE.totalSpend;
    }

    public static double getTotalEarn() {
        return GoofyConfig.INSTANCE == null ? 0 : GoofyConfig.INSTANCE.totalEarn;
    }

    public static double getCleanProfit() {
        return getTotalEarn() - getTotalSpend();
    }

    public static long getTotalUptimeMs() {
        return GoofyConfig.INSTANCE == null ? 0 : GoofyConfig.INSTANCE.totalUptimeMs;
    }

    public static double getSessionSpend() {
        return sessionSpend;
    }

    public static double getSessionEarn() {
        return sessionEarn;
    }

    public static long getSessionUptimeMs() {
        return sessionUptimeMs;
    }

    // ------------------------------------------------------------------- yardımcılar

    /** 8047000 -> "2h 14m 07s", 134000 -> "2m 14s", 7000 -> "7s" */
    public static String formatUptime(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) return String.format("%dh %02dm %02ds", hours, minutes, seconds);
        if (minutes > 0) return String.format("%dm %02ds", minutes, seconds);
        return String.format("%ds", seconds);
    }

    /** All-time sayaçlarını (harcama/kazanç/uptime) sıfırlar ve diske yazar. */
    public static void reset() {
        if (GoofyConfig.INSTANCE == null) return;
        GoofyConfig.INSTANCE.totalSpend = 0;
        GoofyConfig.INSTANCE.totalEarn = 0;
        GoofyConfig.INSTANCE.totalUptimeMs = 0;
        unsavedUptimeMs = 0;
        GoofyConfig.save();
    }

    /** Sadece bu oturumun sayaçlarını sıfırlar. */
    public static void resetSession() {
        sessionSpend = 0;
        sessionEarn = 0;
        sessionUptimeMs = 0;
    }
}

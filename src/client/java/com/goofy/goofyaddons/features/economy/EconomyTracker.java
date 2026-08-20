package com.goofy.goofyaddons.features.economy;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.event.ChatHook;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks total bazaar spend / earn based on "Claimed" chat messages.
 * Values live in GoofyConfig (goofyaddons.json) and are saved to disk on every
 * update, so they persist across game restarts.
 *
 * Spend example:
 *   [Bazaar] Claimed 2x Wisdom I worth 279,780 coins bought for 139,890 each!
 *
 * Earn example:
 *   [Bazaar] Claimed 381,453 coins from selling 1x Rejuvenate V at 385,793 each!
 */
public class EconomyTracker {

    // Group 1 = total worth of the claimed buy order (this is what actually left the purse).
    private static final Pattern BUY_CLAIM_PATTERN = Pattern.compile(
            "Claimed \\d+x .+ worth ([\\d,]+(?:\\.\\d+)?) coins bought for [\\d,]+(?:\\.\\d+)? each!"
    );

    // Group 1 = total coins actually received from the sell order (post-tax amount).
    private static final Pattern SELL_CLAIM_PATTERN = Pattern.compile(
            "Claimed ([\\d,]+(?:\\.\\d+)?) coins from selling \\d+x .+ at [\\d,]+(?:\\.\\d+)? each!"
    );

    private EconomyTracker() {
    }

    public static void register() {
        ChatHook.onMessage("Claimed", EconomyTracker::onChatMessage);
    }

    private static void onChatMessage(String message) {
        Matcher buyMatcher = BUY_CLAIM_PATTERN.matcher(message);
        if (buyMatcher.find()) {
            GoofyConfig.INSTANCE.totalSpend += parseAmount(buyMatcher.group(1));
            GoofyConfig.save();
            return;
        }

        Matcher sellMatcher = SELL_CLAIM_PATTERN.matcher(message);
        if (sellMatcher.find()) {
            GoofyConfig.INSTANCE.totalEarn += parseAmount(sellMatcher.group(1));
            GoofyConfig.save();
        }
    }

    private static double parseAmount(String raw) {
        return Double.parseDouble(raw.replace(",", ""));
    }

    public static double getTotalSpend() {
        return GoofyConfig.INSTANCE.totalSpend;
    }

    public static double getTotalEarn() {
        return GoofyConfig.INSTANCE.totalEarn;
    }

    public static double getCleanProfit() {
        return GoofyConfig.INSTANCE.totalEarn - GoofyConfig.INSTANCE.totalSpend;
    }

    public static void reset() {
        GoofyConfig.INSTANCE.totalSpend = 0;
        GoofyConfig.INSTANCE.totalEarn = 0;
        GoofyConfig.save();
    }
}

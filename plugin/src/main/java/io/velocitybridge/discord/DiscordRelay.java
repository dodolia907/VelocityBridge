package io.velocitybridge.discord;

import io.velocitybridge.config.VelocityBridgeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Discord Webhook連携の管理と通知を担当するクラス。
 */
public class DiscordRelay {
    private static final Logger logger = LoggerFactory.getLogger(DiscordRelay.class);

    private record DiscordHookEntry(DiscordHook hook, VelocityBridgeConfig.DiscordWebhookConfig config) {}

    private final List<DiscordHookEntry> discordHooks = new CopyOnWriteArrayList<>();
    private final Supplier<Boolean> isLeaderSupplier;

    public DiscordRelay(Supplier<Boolean> isLeaderSupplier) {
        this.isLeaderSupplier = isLeaderSupplier;
    }

    public void setup(VelocityBridgeConfig.DiscordConfig discord) {
        close();
        if (isLeaderSupplier.get() && discord != null && discord.enabled() && discord.webhooks() != null) {
            for (VelocityBridgeConfig.DiscordWebhookConfig w : discord.webhooks()) {
                if (w.enabled()) {
                    discordHooks.add(new DiscordHookEntry(
                            new DiscordHook(w.webhookUrl(), w.username(), w.avatarUrl()),
                            w));
                }
            }
        }
        if (!discordHooks.isEmpty()) {
            logger.info("Discord webhooks enabled (count={})", discordHooks.size());
        }
    }

    public void close() {
        for (DiscordHookEntry entry : discordHooks) {
            entry.hook().close();
        }
        discordHooks.clear();
    }

    /**
     * 設定された Discord WebHook へ通知を送信する。
     *
     * @param filter 投稿条件フィルター
     * @param action 投稿処理
     */
    public void post(Predicate<VelocityBridgeConfig.DiscordWebhookConfig> filter, Consumer<DiscordHook> action) {
        if (!isLeaderSupplier.get()) {
            return;
        }
        for (DiscordHookEntry entry : discordHooks) {
            if (entry.config().enabled() && filter.test(entry.config())) {
                action.accept(entry.hook());
            }
        }
    }

    public void post(boolean enabled, Consumer<DiscordHook> action) {
        if (enabled) {
            post(w -> true, action);
        }
    }
}

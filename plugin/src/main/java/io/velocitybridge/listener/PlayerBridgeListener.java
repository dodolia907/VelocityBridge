package io.velocitybridge.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import io.velocitybridge.BridgeCoordinator;
import io.velocitybridge.chat.ChatRelay;
import io.velocitybridge.chat.RomajiConverter;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プレイヤーの参加・退出・チャットを検知してネットワークへ配信するリスナー。
 */
public final class PlayerBridgeListener {

    private final BridgeCoordinator coordinator;
    private final ChatRelay chatRelay;
    private final com.velocitypowered.api.proxy.ProxyServer proxy;
    private final Set<UUID> romajiModeDisabled = ConcurrentHashMap.newKeySet();
    private final io.velocitybridge.tab.TabListManager tabListManager;

    public PlayerBridgeListener(BridgeCoordinator coordinator, ChatRelay chatRelay,
                                io.velocitybridge.tab.TabListManager tabListManager,
                                com.velocitypowered.api.proxy.ProxyServer proxy) {
        this.coordinator = coordinator;
        this.chatRelay = chatRelay;
        this.tabListManager = tabListManager;
        this.proxy = proxy;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        coordinator.onPlayerJoin(player.getUniqueId(), player.getUsername());
        if (tabListManager != null) {
            tabListManager.updateAll();
        }
    }

    @Subscribe
    public void onServerPostConnect(com.velocitypowered.api.event.player.ServerPostConnectEvent event) {
        if (tabListManager != null) {
            // バックエンドサーバーが PlayerInfo パケットを送信し TabList が確定するまで少し待つ
            Object plugin = proxy.getPluginManager().getPlugin("velocitybridge")
                    .flatMap(c -> c.getInstance()).orElse(null);
            if (plugin != null) {
                proxy.getScheduler()
                        .buildTask(plugin, () -> tabListManager.updateAll())
                        .delay(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .schedule();
            }
        }
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        coordinator.onPlayerChooseInitialServer(event);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        coordinator.onPlayerLeave(player.getUniqueId());
        romajiModeDisabled.remove(player.getUniqueId());
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        String kana = "";
        if (!romajiModeDisabled.contains(player.getUniqueId())) {
            String converted = RomajiConverter.convertToKanji(message);
            if (!converted.equals(message)) {
                kana = converted;
            }
        }

        // SignedVelocity-Proxy 導入済みのため denied() でバックエンド表示を抑止し、
        // 変換結果付きのチャットは ChatRelay が表示する。
        event.setResult(PlayerChatEvent.ChatResult.denied());
        coordinator.onChat(player.getUsername(), message, kana, player.getUniqueId());
    }

    /** ローマ字変換モードを切り替える。ON になった場合 true を返す。 */
    public boolean toggleRomajiMode(UUID uniqueId) {
        if (romajiModeDisabled.remove(uniqueId)) {
            return true;
        }
        romajiModeDisabled.add(uniqueId);
        return false;
    }

    /** ローマ字変換モードが有効か。 */
    public boolean isRomajiMode(UUID uniqueId) {
        return !romajiModeDisabled.contains(uniqueId);
    }
}

package io.velocitybridge.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
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
    private final Set<UUID> romajiModePlayers = ConcurrentHashMap.newKeySet();

    public PlayerBridgeListener(BridgeCoordinator coordinator, ChatRelay chatRelay) {
        this.coordinator = coordinator;
        this.chatRelay = chatRelay;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        coordinator.onPlayerJoin(player.getUniqueId(), player.getUsername());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        coordinator.onPlayerLeave(player.getUniqueId());
        romajiModePlayers.remove(player.getUniqueId());
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        if (romajiModePlayers.contains(player.getUniqueId())) {
            message = RomajiConverter.convert(message);
        }

        // 1.19.1+ の署名付きチャットは denied() を返すと protocol error で蹴られるため、
        // message() で書き換えて転送し、送信元バックエンドがローカル表示する。
        event.setResult(PlayerChatEvent.ChatResult.message(message));
        String senderServer = player.getCurrentServer()
                .map(s -> s.getServerInfo().getName()).orElse("");
        coordinator.onChat(player.getUsername(), message, senderServer);
    }

    /** ローマ字変換モードを切り替える。 */
    public boolean toggleRomajiMode(UUID uniqueId) {
        if (romajiModePlayers.contains(uniqueId)) {
            romajiModePlayers.remove(uniqueId);
            return false;
        }
        romajiModePlayers.add(uniqueId);
        return true;
    }

    /** ローマ字変換モードの有無を返す。 */
    public boolean isRomajiMode(UUID uniqueId) {
        return romajiModePlayers.contains(uniqueId);
    }
}

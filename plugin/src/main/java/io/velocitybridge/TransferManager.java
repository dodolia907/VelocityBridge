package io.velocitybridge;

import com.google.gson.JsonObject;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.velocitybridge.config.VelocityBridgeConfig;
import io.velocitybridge.discord.DiscordMessages;
import io.velocitybridge.hub.GlobalPlayerRegistry;
import io.velocitybridge.hub.Message;
import io.velocitybridge.hub.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * プレイヤーのサーバー間移動（Transfer）を管理するクラス。
 */
public class TransferManager {
    private static final Logger logger = LoggerFactory.getLogger(TransferManager.class);

    private final BridgeCoordinator coordinator;
    private final ProxyServer proxy;

    /** 転送中プレイヤーが移動先プロキシで接続すべきサーバーの保留リスト（UUID → サーバー名）。 */
    private final Map<UUID, PendingTransfer> pendingTransfers = new ConcurrentHashMap<>();

    /** 転送先サーバー情報の有効期限。これを過ぎると既定サーバーへ案内する。 */
    private static final long TRANSFER_PENDING_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private record PendingTransfer(String serverName, long expireNanos) {}

    public TransferManager(BridgeCoordinator coordinator, ProxyServer proxy) {
        this.coordinator = coordinator;
        this.proxy = proxy;
    }

    /** 指定プレイヤーが指定プロキシにオンラインか。 */
    private boolean isOnlineOn(String proxyId, UUID uniqueId) {
        for (GlobalPlayerRegistry.PlayerEntry entry : coordinator.getRegistry().snapshot()) {
            if (entry.uniqueId().equals(uniqueId) && entry.proxyId().equals(proxyId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * プレイヤーを別プロキシへ転送する（Transfer パケット利用）。
     */
    public void transferPlayer(UUID uniqueId, String targetProxyId, BiConsumer<Boolean, String> onResult) {
        VelocityBridgeConfig.ProxyInfo target = coordinator.getConfig().proxies().stream()
                .filter(p -> p.id().equals(targetProxyId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            onResult.accept(false, "Unknown proxy: " + targetProxyId);
            return;
        }
        if (coordinator.getNodeId().equals(targetProxyId)) {
            onResult.accept(false, "Player is already connected to proxy " + targetProxyId);
            return;
        }

        Player player = proxy.getPlayer(uniqueId).orElse(null);
        if (player == null) {
            GlobalPlayerRegistry.PlayerEntry entry = null;
            for (GlobalPlayerRegistry.PlayerEntry e : coordinator.getRegistry().snapshot()) {
                if (e.uniqueId().equals(uniqueId)) {
                    entry = e;
                    break;
                }
            }
            onResult.accept(false, entry == null
                    ? "Player is not online on this proxy."
                    : "Player is online on proxy " + entry.proxyId() + ". Run the transfer there.");
            return;
        }
        if (isOnlineOn(targetProxyId, uniqueId)) {
            onResult.accept(false, "Player is already online on proxy " + targetProxyId);
            return;
        }

        InetSocketAddress address = BridgeCoordinator.parseAddress(target.address());
        String currentServer = player.getCurrentServer()
                .map(ServerConnection::getServerInfo)
                .map(ServerInfo::getName)
                .orElse("");
        logger.info("Transferring {} ({}) from {} to {} ({})",
                player.getUsername(), uniqueId, coordinator.getNodeId(), targetProxyId, target.address());
        try {
            player.transferToHost(address);
        } catch (RuntimeException e) {
            logger.warn("Transfer failed for {}: {}", uniqueId, e.toString());
            onResult.accept(false, "Transfer failed: " + e.getMessage());
            return;
        }

        broadcastTransferResponse(uniqueId, player.getUsername(), targetProxyId, target.address(),
                currentServer, true, "ok");
        onResult.accept(true, "Transferring " + player.getUsername() + " to "
                + targetProxyId + " (" + address + ")");
    }

    /** 転送結果をネットワーク全体へ配信する。 */
    private void broadcastTransferResponse(UUID uniqueId, String username, String targetProxyId,
                                           String address, String server, boolean success, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uniqueId.toString());
        payload.addProperty("username", username);
        payload.addProperty("sourceProxyId", coordinator.getNodeId());
        payload.addProperty("targetProxyId", targetProxyId);
        payload.addProperty("address", address);
        payload.addProperty("server", server);
        payload.addProperty("success", success);
        payload.addProperty("message", message);

        Message msg = Message.of(MessageType.TRANSFER_RESPONSE, coordinator.getNodeId(), payload);
        if (coordinator.isLeader()) {
            if (coordinator.getHubServer() != null) {
                coordinator.getHubServer().broadcast(msg, null);
            }
            postTransferResult(payload, coordinator.getNodeId());
        } else {
            if (coordinator.getHubClient() != null) {
                coordinator.getHubClient().send(msg);
            }
        }
    }

    /** 転送結果を Discord へ投稿する（リーダーのみ）。 */
    public void postTransferResult(JsonObject payload, String sender) {
        String username = payload.has("username") ? payload.get("username").getAsString() : "?";
        String sourceProxyId = payload.has("sourceProxyId") ? payload.get("sourceProxyId").getAsString() : "?";
        String targetProxyId = payload.has("targetProxyId") ? payload.get("targetProxyId").getAsString() : "?";
        String reason = payload.has("message") ? payload.get("message").getAsString() : "";
        boolean success = payload.has("success") && payload.get("success").getAsBoolean();
        coordinator.getDiscordRelay().post(VelocityBridgeConfig.DiscordWebhookConfig::notifyTransfer, discord -> discord.send(success
                ? DiscordMessages.transferSuccess(username, sourceProxyId, targetProxyId)
                : DiscordMessages.transferFailure(username, targetProxyId, reason)));
    }

    /** Transfer の要求元が指定したサーバーに接続できるよう一時保存する。 */
    public void recordTransferTarget(JsonObject payload) {
        if (!payload.has("success") || !payload.get("success").getAsBoolean()) {
            return;
        }
        String targetProxyId = payload.get("targetProxyId").getAsString();
        if (coordinator.getNodeId().equals(targetProxyId)) {
            UUID uuid = UUID.fromString(payload.get("uuid").getAsString());
            String server = payload.get("server").getAsString();
            long expireNanos = System.nanoTime() + TRANSFER_PENDING_TTL_NANOS;
            pendingTransfers.put(uuid, new PendingTransfer(server, expireNanos));
            logger.info("Recorded pending transfer target for {}: {} (expires in 60s)", uuid, server);
        }
    }

    /** 転送で移動してきたプレイヤーの保留サーバーを取り出す（消費する/テスト用）。 */
    Optional<String> takePendingServer(UUID uniqueId) {
        PendingTransfer pending = pendingTransfers.remove(uniqueId);
        if (pending == null || pending.serverName().isEmpty()
                || System.nanoTime() - pending.expireNanos() > 0) {
            return Optional.empty();
        }
        return Optional.of(pending.serverName());
    }

    /** プロキシ参加時に、保留中の Transfer 宛先があればそのサーバーを初期サーバーに設定する。 */
    public void onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Optional<String> server = takePendingServer(uuid);
        if (server.isPresent()) {
            Optional<RegisteredServer> target = proxy.getServer(server.get());
            if (target.isPresent()) {
                logger.info("Routing transferred player {} to {}", event.getPlayer().getUsername(), server.get());
                event.setInitialServer(target.get());
            } else {
                logger.warn("Transfer target server {} not found for {}", server.get(), event.getPlayer().getUsername());
            }
        }
    }

    /** 転送関連メッセージのペイロードをログ用に要約する。 */
    public static String summarizeTransfer(JsonObject payload) {
        String user = payload.has("username") ? payload.get("username").getAsString() : "?";
        String source = payload.has("sourceProxyId") ? payload.get("sourceProxyId").getAsString() : "?";
        String target = payload.has("targetProxyId") ? payload.get("targetProxyId").getAsString() : "?";
        boolean success = payload.has("success") && payload.get("success").getAsBoolean();
        return (success ? "ok " : "fail ") + user + " " + source + " -> " + target;
    }
}

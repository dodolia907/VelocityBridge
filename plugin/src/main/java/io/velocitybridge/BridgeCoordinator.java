package io.velocitybridge;

import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.velocitybridge.chat.ChatRelay;
import io.velocitybridge.config.VelocityBridgeConfig;
import io.velocitybridge.hub.AuthHandler;
import io.velocitybridge.hub.GlobalPlayerRegistry;
import io.velocitybridge.hub.HubClient;
import io.velocitybridge.hub.HubServer;
import io.velocitybridge.hub.Message;
import io.velocitybridge.hub.MessageCodec;
import io.velocitybridge.hub.MessageType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * リーダー型メッセージハブの動作を統括するクラス。
 *
 * <p>{@code leader} モードでは {@link HubServer}、{@code follower} モードでは {@link HubClient} を
 * 起動し、プレイヤーの参加/退出・チャット・転送などのイベントをプロキシ間で配送する。</p>
 */
public final class BridgeCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(BridgeCoordinator.class);

    private final ProxyServer proxy;
    private final VelocityBridgeConfig config;
    private final GlobalPlayerRegistry registry;
    private final ChatRelay chatRelay;
    private final HubServer hubServer;
    private final HubClient hubClient;
    private final boolean leader;
    private final String nodeId;

    private BiConsumer<String, JsonObject> messageListener;

    /**
     * @param proxy    プロキシサーバ（プレイヤーの転送に使用）
     * @param config   設定
     * @param chatRelay チャット配信ハンドラ
     * @param serverNodeId リーダーのノードID（follower 用、接続後に設定される）
     */
    public BridgeCoordinator(ProxyServer proxy, VelocityBridgeConfig config, ChatRelay chatRelay,
                             AtomicReference<String> serverNodeId) {
        this.proxy = proxy;
        this.config = config;
        this.chatRelay = chatRelay;
        this.registry = new GlobalPlayerRegistry();
        this.leader = "leader".equalsIgnoreCase(config.mode());
        this.nodeId = config.nodeId();
        this.hubServer = leader ? new HubServer(new LeaderHandler(), new AuthHandler(config.secret()), nodeId) : null;
        this.hubClient = leader ? null : new HubClient(
                nodeId,
                parseAddress(config.leaderAddress()),
                config.secret(),
                new FollowerHandler(serverNodeId));
    }

    /** ノードIDを返す。 */
    public String getNodeId() {
        return nodeId;
    }

    /** このノードがリーダーか。 */
    public boolean isLeader() {
        return leader;
    }

    /** グローバルプレイヤー一覧を返す。 */
    public GlobalPlayerRegistry getRegistry() {
        return registry;
    }

    /** リーダーのハブサーバ（leader のみ）。 */
    public HubServer getHubServer() {
        return hubServer;
    }

    /** リーダーへの接続クライアント（follower のみ）。 */
    public HubClient getHubClient() {
        return hubClient;
    }

    /** プロキシ間メッセージ受信時のリスナーを設定する。 */
    public void setMessageListener(BiConsumer<String, JsonObject> listener) {
        this.messageListener = listener;
    }

    /** 起動する。 */
    public void start() {
        if (leader) {
            try {
                hubServer.start(config.hubPort());
                logger.info("Hub server listening on port {}", hubServer.getPort());
            } catch (Exception e) {
                throw new RuntimeException("Failed to start hub server", e);
            }
        } else {
            logger.info("Connecting to leader hub at {}", config.leaderAddress());
            hubClient.start();
        }
    }

    /** 停止する。 */
    public void stop() {
        if (hubServer != null) {
            hubServer.close();
        }
        if (hubClient != null) {
            hubClient.close();
        }
    }

    /**
     * プレイヤーの参加をネットワークへ通知する。
     *
     * @param uniqueId プレイヤーUUID
     * @param username プレイヤー名
     */
    public void onPlayerJoin(UUID uniqueId, String username) {
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uniqueId.toString());
        payload.addProperty("username", username);
        payload.addProperty("proxyId", nodeId);

        if (leader) {
            registry.register(new GlobalPlayerRegistry.PlayerEntry(uniqueId, username, nodeId));
            hubServer.broadcast(Message.of(MessageType.PLAYER_JOIN, nodeId, payload), null);
        } else {
            registry.register(new GlobalPlayerRegistry.PlayerEntry(uniqueId, username, nodeId));
            hubClient.send(Message.of(MessageType.PLAYER_JOIN, nodeId, payload));
        }
    }

    /**
     * プレイヤーの退出をネットワークへ通知する。
     *
     * @param uniqueId プレイヤーUUID
     */
    public void onPlayerLeave(UUID uniqueId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uniqueId.toString());
        payload.addProperty("proxyId", nodeId);

        if (leader) {
            registry.remove(uniqueId);
            hubServer.broadcast(Message.of(MessageType.PLAYER_LEAVE, nodeId, payload), null);
        } else {
            registry.remove(uniqueId);
            hubClient.send(Message.of(MessageType.PLAYER_LEAVE, nodeId, payload));
        }
    }

    /**
     * チャットメッセージをネットワーク全体へ配信する。
     *
     * <p>プラグインは {@code PlayerChatEvent} を deny しているため、発言元プロキシでも
     * ローカル表示が必要。ここで一度ローカル表示し、他プロキシへはハブ経由で配信する
     * （受信側は各ハンドラで表示する）。</p>
     *
     * @param username 発言者
     * @param message  メッセージ内容（変換済み）
     */
    public void onChat(String username, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", username);
        payload.addProperty("message", message);

        chatRelay.onRemoteChat(nodeId, username, message);

        if (leader) {
            hubServer.broadcast(Message.of(MessageType.CHAT_MESSAGE, nodeId, payload), null);
        } else {
            hubClient.send(Message.of(MessageType.CHAT_MESSAGE, nodeId, payload));
        }
    }

    /**
     * プレイヤーを別プロキシへ転送する（Transfer パケット利用）。
     *
     * <p>対象プレイヤーはこのプロキシに接続中である必要がある。宛先プロキシのアドレスを設定から解決し、
     * {@link Player#transferToHost(InetSocketAddress)} で転送を実行する。転送はクライアント側で
     * 切断→再接続として処理されるため、以降のグローバルプレイヤー一覧の整合は参加/退出イベントで
     * 維持される。</p>
     *
     * @param uniqueId 対象プレイヤーUUID
     * @param targetProxyId 転送先プロキシID
     * @param onResult 結果コールバック（成功フラグ, メッセージ）
     */
    public void transferPlayer(UUID uniqueId, String targetProxyId,
                               java.util.function.BiConsumer<Boolean, String> onResult) {
        VelocityBridgeConfig.ProxyInfo target = config.proxies().stream()
                .filter(p -> p.id().equals(targetProxyId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            onResult.accept(false, "Unknown proxy: " + targetProxyId);
            return;
        }
        if (nodeId.equals(targetProxyId)) {
            onResult.accept(false, "Player is already connected to proxy " + targetProxyId);
            return;
        }

        Player player = proxy.getPlayer(uniqueId).orElse(null);
        if (player == null) {
            GlobalPlayerRegistry.PlayerEntry entry = findEntry(uniqueId);
            onResult.accept(false, entry == null
                    ? "Player is not online on this proxy."
                    : "Player is online on proxy " + entry.proxyId() + ". Run the transfer there.");
            return;
        }
        if (isOnlineOn(targetProxyId, uniqueId)) {
            onResult.accept(false, "Player is already online on proxy " + targetProxyId);
            return;
        }

        InetSocketAddress address = parseAddress(target.address());
        logger.info("Transferring {} ({}) from {} to {} ({})",
                player.getUsername(), uniqueId, nodeId, targetProxyId, target.address());
        try {
            player.transferToHost(address);
        } catch (RuntimeException e) {
            logger.warn("Transfer failed for {}: {}", uniqueId, e.toString());
            onResult.accept(false, "Transfer failed: " + e.getMessage());
            return;
        }

        broadcastTransferResponse(uniqueId, player.getUsername(), targetProxyId, target.address(), true, "ok");
        onResult.accept(true, "Transferring " + player.getUsername() + " to "
                + targetProxyId + " (" + address + ")");
    }

    /** グローバル一覧から指定 UUID のプレイヤーを探す。 */
    private GlobalPlayerRegistry.PlayerEntry findEntry(UUID uniqueId) {
        for (GlobalPlayerRegistry.PlayerEntry entry : registry.snapshot()) {
            if (entry.uniqueId().equals(uniqueId)) {
                return entry;
            }
        }
        return null;
    }

    /** 指定プレイヤーが指定プロキシにオンラインか。 */
    private boolean isOnlineOn(String proxyId, UUID uniqueId) {
        for (GlobalPlayerRegistry.PlayerEntry entry : registry.snapshot()) {
            if (entry.uniqueId().equals(uniqueId) && entry.proxyId().equals(proxyId)) {
                return true;
            }
        }
        return false;
    }

    /** 転送結果をネットワーク全体へ配信する。 */
    private void broadcastTransferResponse(UUID uniqueId, String username, String targetProxyId,
                                           String address, boolean success, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uniqueId.toString());
        payload.addProperty("username", username);
        payload.addProperty("sourceProxyId", nodeId);
        payload.addProperty("targetProxyId", targetProxyId);
        payload.addProperty("address", address);
        payload.addProperty("success", success);
        payload.addProperty("message", message);

        Message msg = Message.of(MessageType.TRANSFER_RESPONSE, nodeId, payload);
        if (leader) {
            hubServer.broadcast(msg, null);
        } else {
            hubClient.send(msg);
        }
    }

    /** フォロワーがリーダーへ接続済みか。 */
    public boolean isHubConnected() {
        return leader || (hubClient != null && hubClient.isConnected());
    }

    private static InetSocketAddress parseAddress(String address) {
        String[] parts = address.split(":");
        return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
    }

    /** リーダー側のハブイベントハンドラ。 */
    private final class LeaderHandler implements HubServer.Handler {
        @Override
        public void onAuthenticated(String nodeId) {
            logger.info("Follower connected: {}", nodeId);
            // フォロワーが接続した時、既存グローバル一覧を送る
            JsonObject response = new JsonObject();
            response.addProperty("serverNodeId", BridgeCoordinator.this.nodeId);
            hubServer.sendTo(nodeId, Message.of(MessageType.GLOBAL_LIST_RESPONSE, BridgeCoordinator.this.nodeId,
                    encodeRegistry()));
        }

        @Override
        public void onMessage(String sender, Message message) {
            switch (message.type()) {
                case MessageType.PLAYER_JOIN -> {
                    UUID uuid = UUID.fromString(message.payload().get("uuid").getAsString());
                    String username = message.payload().get("username").getAsString();
                    registry.register(new GlobalPlayerRegistry.PlayerEntry(uuid, username, sender));
                    hubServer.broadcast(Message.of(MessageType.PLAYER_JOIN, sender, message.payload()), sender);
                    notifyListener(sender, message);
                }
                case MessageType.PLAYER_LEAVE -> {
                    UUID uuid = UUID.fromString(message.payload().get("uuid").getAsString());
                    registry.remove(uuid);
                    hubServer.broadcast(Message.of(MessageType.PLAYER_LEAVE, sender, message.payload()), sender);
                    notifyListener(sender, message);
                }
                case MessageType.PLAYER_LIST_FULL -> {
                    // 再接続時の再同期: 送信ノード配下のプレイヤーを登録し、他ノードへJOINを再配信する
                    if (message.payload().has("players") && message.payload().get("players").isJsonArray()) {
                        for (com.google.gson.JsonElement e : message.payload().getAsJsonArray("players")) {
                            JsonObject p = e.getAsJsonObject();
                            UUID uuid = UUID.fromString(p.get("uuid").getAsString());
                            String username = p.get("username").getAsString();
                            GlobalPlayerRegistry.PlayerEntry entry =
                                    new GlobalPlayerRegistry.PlayerEntry(uuid, username, sender);
                            GlobalPlayerRegistry.PlayerEntry previous = registry.register(entry);
                            if (previous == null) {
                                JsonObject joinPayload = new JsonObject();
                                joinPayload.addProperty("uuid", uuid.toString());
                                joinPayload.addProperty("username", username);
                                joinPayload.addProperty("proxyId", sender);
                                hubServer.broadcast(Message.of(MessageType.PLAYER_JOIN, sender, joinPayload), sender);
                            }
                        }
                    }
                    // 更新後のグローバル一覧を送信ノードへ返す
                    hubServer.sendTo(sender, Message.of(MessageType.GLOBAL_LIST_RESPONSE,
                            BridgeCoordinator.this.nodeId, encodeRegistry()));
                }
                case MessageType.CHAT_MESSAGE -> {
                    hubServer.broadcast(Message.of(MessageType.CHAT_MESSAGE, sender, message.payload()), sender);
                    // リーダー自身のプレイヤーにも表示する
                    chatRelay.onRemoteChat(sender,
                            message.payload().get("username").getAsString(),
                            message.payload().get("message").getAsString());
                    notifyListener(sender, message);
                }
                case MessageType.TRANSFER_REQUEST -> {
                    hubServer.broadcast(Message.of(MessageType.TRANSFER_REQUEST, sender, message.payload()), sender);
                    logger.info("Transfer request from {}: {}", sender, summarizeTransfer(message.payload()));
                    notifyListener(sender, message);
                }
                case MessageType.TRANSFER_RESPONSE -> {
                    hubServer.broadcast(Message.of(MessageType.TRANSFER_RESPONSE, sender, message.payload()), sender);
                    logger.info("Transfer result from {}: {}", sender, summarizeTransfer(message.payload()));
                    notifyListener(sender, message);
                }
                case MessageType.HEARTBEAT -> {
                    // ハートビートは HubServer が最終受信時刻を更新済み。追加処理なし。
                }
                default -> {
                }
            }
        }

        @Override
        public void onDisconnect(String nodeId) {
            logger.info("Follower disconnected: {}", nodeId);
            for (GlobalPlayerRegistry.PlayerEntry entry : registry.removeAllForProxy(nodeId)) {
                JsonObject payload = new JsonObject();
                payload.addProperty("uuid", entry.uniqueId().toString());
                payload.addProperty("proxyId", nodeId);
                hubServer.broadcast(Message.of(MessageType.PLAYER_LEAVE, nodeId, payload), nodeId);
            }
        }

        private void notifyListener(String sender, Message message) {
            if (messageListener != null) {
                messageListener.accept(sender, message.payload());
            }
        }
    }

    /** フォロワー側のハブイベントハンドラ。 */
    private final class FollowerHandler implements HubClient.Handler {
        private final AtomicReference<String> serverNodeId;

        private FollowerHandler(AtomicReference<String> serverNodeId) {
            this.serverNodeId = serverNodeId;
        }

        @Override
        public void onConnected(String leaderNodeId) {
            serverNodeId.set(leaderNodeId);
            logger.info("Connected to leader hub (node={})", leaderNodeId);
            // 再接続時: 自プロキシ配下のプレイヤーのみ全量送信して再同期する
            JsonObject payload = new JsonObject();
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            for (GlobalPlayerRegistry.PlayerEntry entry : registry.snapshot()) {
                if (!nodeId.equals(entry.proxyId())) {
                    continue;
                }
                JsonObject p = new JsonObject();
                p.addProperty("uuid", entry.uniqueId().toString());
                p.addProperty("username", entry.username());
                p.addProperty("proxyId", entry.proxyId());
                array.add(p);
            }
            payload.add("players", array);
            hubClient.send(Message.of(MessageType.PLAYER_LIST_FULL, nodeId, payload));
        }

        @Override
        public void onMessage(Message message) {
            switch (message.type()) {
                case MessageType.GLOBAL_LIST_RESPONSE -> applyGlobalList(message.payload());
                case MessageType.PLAYER_JOIN -> {
                    UUID uuid = UUID.fromString(message.payload().get("uuid").getAsString());
                    String username = message.payload().get("username").getAsString();
                    String proxyId = message.payload().get("proxyId").getAsString();
                    registry.register(new GlobalPlayerRegistry.PlayerEntry(uuid, username, proxyId));
                    notifyListener(message);
                }
                case MessageType.PLAYER_LEAVE -> {
                    UUID uuid = UUID.fromString(message.payload().get("uuid").getAsString());
                    registry.remove(uuid);
                    notifyListener(message);
                }
                case MessageType.CHAT_MESSAGE -> {
                    String username = message.payload().get("username").getAsString();
                    String text = message.payload().get("message").getAsString();
                    chatRelay.onRemoteChat(message.sender(), username, text);
                    notifyListener(message);
                }
                case MessageType.TRANSFER_REQUEST -> {
                    logger.info("Transfer request from {}: {}", nodeId, summarizeTransfer(message.payload()));
                    notifyListener(message);
                }
                case MessageType.TRANSFER_RESPONSE -> {
                    logger.info("Transfer result from {}: {}", nodeId, summarizeTransfer(message.payload()));
                    notifyListener(message);
                }
                default -> {
                }
            }
        }

        @Override
        public void onDisconnected() {
            logger.warn("Disconnected from leader hub; reconnecting...");
            // 再接続時に再同期されるため、ここでは何もしない
        }

        private void notifyListener(Message message) {
            if (messageListener != null) {
                messageListener.accept(nodeId, message.payload());
            }
        }

        private void applyGlobalList(JsonObject payload) {
            if (payload.has("players") && payload.get("players").isJsonArray()) {
                for (com.google.gson.JsonElement e : payload.getAsJsonArray("players")) {
                    JsonObject p = e.getAsJsonObject();
                    UUID uuid = UUID.fromString(p.get("uuid").getAsString());
                    String username = p.get("username").getAsString();
                    String proxyId = p.get("proxyId").getAsString();
                    registry.register(new GlobalPlayerRegistry.PlayerEntry(uuid, username, proxyId));
                }
            }
        }
    }

    private JsonObject encodeRegistry() {
        JsonObject response = new JsonObject();
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (GlobalPlayerRegistry.PlayerEntry entry : registry.snapshot()) {
            JsonObject p = new JsonObject();
            p.addProperty("uuid", entry.uniqueId().toString());
            p.addProperty("username", entry.username());
            p.addProperty("proxyId", entry.proxyId());
            array.add(p);
        }
        response.add("players", array);
        return response;
    }

    /** 転送関連メッセージのペイロードをログ用に要約する。 */
    private static String summarizeTransfer(JsonObject payload) {
        String user = payload.has("username") ? payload.get("username").getAsString() : "?";
        String source = payload.has("sourceProxyId") ? payload.get("sourceProxyId").getAsString() : "?";
        String target = payload.has("targetProxyId") ? payload.get("targetProxyId").getAsString() : "?";
        boolean success = payload.has("success") && payload.get("success").getAsBoolean();
        return (success ? "ok " : "fail ") + user + " " + source + " -> " + target;
    }
}

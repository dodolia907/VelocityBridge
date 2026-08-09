package io.velocitybridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.velocitybridge.chat.ChatRelay;
import io.velocitybridge.config.VelocityBridgeConfig;
import io.velocitybridge.discord.DiscordHook;
import io.velocitybridge.discord.DiscordMessages;
import io.velocitybridge.hub.AuthHandler;
import io.velocitybridge.hub.GlobalPlayerRegistry;
import io.velocitybridge.hub.HubClient;
import io.velocitybridge.hub.HubServer;
import io.velocitybridge.hub.Message;
import io.velocitybridge.hub.MessageCipher;
import io.velocitybridge.hub.MessageCodec;
import io.velocitybridge.hub.MessageType;
import io.velocitybridge.hub.payload.Payloads;
import io.velocitybridge.permission.PermissionBackend;
import io.velocitybridge.permission.PermissionSync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
    private volatile VelocityBridgeConfig config;
    private final GlobalPlayerRegistry registry;
    private final ChatRelay chatRelay;
    private final HubServer hubServer;
    private final HubClient hubClient;
    private final List<DiscordHookEntry> discordHooks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile PermissionSync permissionSync;
    private volatile boolean leader;
    private final String nodeId;
    private final io.velocitybridge.ha.RaftNode raftNode;

    /** リーダーが発行する権限バージョン。 */
    private final AtomicLong permissionVersion = new AtomicLong();

    /** フォロワーが適用済みの権限バージョン。 */
    private final AtomicLong appliedPermissionVersion = new AtomicLong();

    /** 転送中プレイヤーが移動先プロキシで接続すべきサーバーの保留リスト（UUID → サーバー名）。 */
    private final Map<UUID, PendingTransfer> pendingTransfers = new ConcurrentHashMap<>();

    private BiConsumer<String, JsonObject> messageListener;

    /** 転送先サーバー情報の有効期限。これを過ぎると既定サーバーへ案内する。 */
    private static final long TRANSFER_PENDING_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);

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
                parseAddress(config.leaderAddress(), config.hubPort()),
                config.secret(),
                new FollowerHandler(serverNodeId));
        VelocityBridgeConfig.DiscordConfig discord = config.discord();
        setupDiscordHooks(discord);

        List<String> allProxyIds = config.proxies().stream().map(VelocityBridgeConfig.ProxyInfo::id).toList();
        if (config.autoFailover() != null && config.autoFailover().enabled() && allProxyIds.size() >= 3) {
            this.raftNode = new io.velocitybridge.ha.RaftNode(nodeId, allProxyIds, leader, config.autoFailover().electionTimeoutMs(),
                    new io.velocitybridge.ha.RaftNode.RaftListener() {
                        @Override
                        public void onPromotedToLeader(long term) {
                            promoteToLeader();
                        }

                        @Override
                        public void onDemotedToFollower(long term, String leaderId) {
                            demoteToFollower(leaderId);
                        }

                        @Override
                        public void sendRequestVote(String targetNodeId, io.velocitybridge.hub.payload.Payloads.RequestVote vote) {
                            sendDirectVoteRequest(targetNodeId, vote);
                        }
                    });
        } else {
            this.raftNode = null;
        }
    }

    private record DiscordHookEntry(DiscordHook hook, VelocityBridgeConfig.DiscordWebhookConfig config) {
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

    /** テスト等で参照するための権限バージョン（リーダー）。 */
    long getPermissionVersion() {
        return permissionVersion.get();
    }

    /** テスト等で参照するための適用済み権限バージョン（フォロワー）。 */
    long getAppliedPermissionVersion() {
        return appliedPermissionVersion.get();
    }

    /** リーダーへの接続クライアント（follower のみ）。 */
    public HubClient getHubClient() {
        return hubClient;
    }

    /** プロキシ間メッセージ受信時のリスナーを設定する。 */
    public void setMessageListener(BiConsumer<String, JsonObject> listener) {
        this.messageListener = listener;
    }

    /** 権限同期ハンドラを設定する。 */
    public void setPermissionSync(PermissionSync permissionSync) {
        this.permissionSync = permissionSync;
    }

    /**
     * ローカルの権限変更の差分をネットワーク全体へ配信する。
     *
     * <p>発端のプロキシでは既に権限が適用済み（イベント起因）のため、他プロキシへ
     * 差分を伝えるだけでよい。リーダーは全フォロワーへブロードキャストし、フォロワーは
     * リーダーへ送信する（リーダーが中継する）。</p>
     *
     * @param change 権限変更の差分
     */
    public void onPermissionChange(PermissionBackend.NodeChange change) {
        JsonObject payload = new JsonObject();
        payload.addProperty("holderType", change.holderType());
        payload.addProperty("holderKey", change.holderKey());
        payload.addProperty("node", change.node());
        payload.addProperty("add", change.add());
        payload.addProperty("value", change.value());

        if (leader) {
            // リーダーが発端の変更は、ここでバージョンを発行して全フォロワーへ配信する
            payload.addProperty("version", permissionVersion.incrementAndGet());
            hubServer.broadcast(Message.of(MessageType.PERMISSION_UPDATE, nodeId, payload), null);
        } else {
            hubClient.send(Message.of(MessageType.PERMISSION_UPDATE, nodeId, payload));
        }
    }

    /** 起動する。 */
    public void start() {
        if (raftNode != null) {
            raftNode.start();
        }
        if (leader) {
            try {
                if (hubServer != null) {
                    hubServer.start(config.hubPort());
                    logger.info("Hub server listening on port {}", hubServer.getPort());
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to start hub server", e);
            }
        } else {
            if (hubClient != null) {
                logger.info("Connecting to leader hub at {}", config.leaderAddress());
                hubClient.start();
            }
        }
    }

    /** 停止する。 */
    public void stop() {
        if (raftNode != null) {
            raftNode.stop();
        }
        if (hubServer != null) {
            hubServer.close();
        }
        if (hubClient != null) {
            hubClient.close();
        }
        for (DiscordHookEntry entry : discordHooks) {
            entry.hook().close();
        }
        discordHooks.clear();
    }

    /**
     * 設定を再読み込みして反映できる項目を更新する。
     *
     * <p>反映される: Discord 連携・チャット配信（include-sender）・プロキシ定義。
     * 反映されない（再起動が必要）: node-id / mode / hub-port / leader-address / secret。</p>
     *
     * @param newConfig 新しい設定
     */
    public synchronized void reload(VelocityBridgeConfig newConfig) {
        if (!nodeId.equals(newConfig.nodeId())) {
            logger.warn("node-id change requires a proxy restart; ignoring (was {}, requested {})",
                    nodeId, newConfig.nodeId());
        }
        if (leader != "leader".equalsIgnoreCase(newConfig.mode())) {
            logger.warn("mode change requires a proxy restart; ignoring (was {}, requested {})",
                    leader ? "leader" : "follower", newConfig.mode());
        }
        if (hubServer != null && hubServer.getPort() != newConfig.hubPort()) {
            logger.warn("hub-port change requires a proxy restart; ignoring (was {}, requested {})",
                    hubServer.getPort(), newConfig.hubPort());
        }
        if (leader && !config.secret().equals(newConfig.secret())) {
            logger.warn("secret change requires a proxy restart; ignoring");
        }
        if (!leader && !config.leaderAddress().equals(newConfig.leaderAddress())) {
            logger.warn("leader-address change requires a proxy restart; ignoring");
        }

        VelocityBridgeConfig.DiscordConfig oldDiscord = config.discord();
        VelocityBridgeConfig.DiscordConfig newDiscord = newConfig.discord();
        if (leader && !oldDiscord.equals(newDiscord)) {
            setupDiscordHooks(newDiscord);
        }

        this.config = newConfig;
        logger.info("Config reloaded (node={})", nodeId);
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
            postDiscord(VelocityBridgeConfig.DiscordWebhookConfig::notifyJoinLeave,
                    discord -> discord.send(DiscordMessages.playerJoin(username, nodeId)));
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

        GlobalPlayerRegistry.PlayerEntry entry = findEntry(uniqueId);
        String username = entry == null ? "?" : entry.username();
        payload.addProperty("username", username);

        if (leader) {
            registry.remove(uniqueId);
            hubServer.broadcast(Message.of(MessageType.PLAYER_LEAVE, nodeId, payload), null);
            postDiscord(VelocityBridgeConfig.DiscordWebhookConfig::notifyJoinLeave,
                    discord -> discord.send(DiscordMessages.playerLeave(username, nodeId)));
        } else {
            registry.remove(uniqueId);
            hubClient.send(Message.of(MessageType.PLAYER_LEAVE, nodeId, payload));
        }
    }

    /**
     * チャットメッセージをネットワーク全体へ配信する（テスト等の便宜用）。
     *
     * @param username 発言者
     * @param message  メッセージ内容（原文）
     */
    public void onChat(String username, String message) {
        onChat(username, message, "");
    }

    /**
     * チャットメッセージをネットワーク全体へ配信する（テスト等の便宜用）。
     *
     * @param username 発言者
     * @param message  メッセージ内容（原文）
     * @param kana     ローマ字変換のカナ（非空なら括弧付き表示）
     */
    public void onChat(String username, String message, String kana) {
        onChat(username, message, kana, null);
    }

    /**
     * チャットメッセージをネットワーク全体へ配信する。
     *
     * <p>{@code PlayerChatEvent} は {@code denied()} でバックエンド表示を止めているため、
     * ここで全プレイヤーへの表示と他プロキシへの配信を行う。設定 {@code chat.include-sender} が
     * 変換が適用されたメッセージは送信者へリレーで返すと、1.19.3+ のクライアントが自分の
     * メッセージを最適化表示するため二重表示になる。そのため変換時は送信者をリレーから除外し、
     * 変換結果のかなのみを単独で通知する。変換されなかったメッセージは include-sender 設定に従い、
     * {@code true}（既定）なら送信者にも配信し、{@code false} ならリレーから除外する。</p>
     *
     * @param username   発言者
     * @param message    メッセージ内容（原文）
     * @param kana       ローマ字変換のカナ（非空なら変換済み）
     * @param senderUuid 送信者UUID
     */
    public void onChat(String username, String message, String kana, UUID senderUuid) {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", username);
        payload.addProperty("message", message);
        payload.addProperty("kana", kana);

        boolean includeSender = config.chat().includeSender();
        boolean converted = kana != null && !kana.isEmpty();
        if (converted && includeSender && senderUuid != null) {
            chatRelay.onRemoteChat(nodeId, username, message, kana, senderUuid);
            chatRelay.sendConversionNotice(senderUuid, kana);
        } else {
            UUID relaySenderUuid = includeSender ? null : senderUuid;
            chatRelay.onRemoteChat(nodeId, username, message, kana, relaySenderUuid);
        }

        if (leader) {
            hubServer.broadcast(Message.of(MessageType.CHAT_MESSAGE, nodeId, payload), null);
            postDiscord(VelocityBridgeConfig.DiscordWebhookConfig::notifyChat,
                    discord -> discord.send(DiscordMessages.chat(username, message, kana)));
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
        String currentServer = player.getCurrentServer()
                .map(ServerConnection::getServerInfo)
                .map(ServerInfo::getName)
                .orElse("");
        logger.info("Transferring {} ({}) from {} to {} ({})",
                player.getUsername(), uniqueId, nodeId, targetProxyId, target.address());
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

    /** グローバル一覧から指定 UUID のプレイヤーを探す。 */
    private GlobalPlayerRegistry.PlayerEntry findEntry(UUID uniqueId) {
        for (GlobalPlayerRegistry.PlayerEntry entry : registry.snapshot()) {
            if (entry.uniqueId().equals(uniqueId)) {
                return entry;
            }
        }
        return null;
    }

    private void setupDiscordHooks(VelocityBridgeConfig.DiscordConfig discord) {
        for (DiscordHookEntry entry : discordHooks) {
            entry.hook().close();
        }
        discordHooks.clear();
        if (leader && discord != null && discord.enabled() && discord.webhooks() != null) {
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

    /**
     * Leader のみ、設定された Discord WebHook へ通知を送信する。
     *
     * @param filter 投稿条件フィルター
     * @param action 投稿処理
     */
    private void postDiscord(java.util.function.Predicate<VelocityBridgeConfig.DiscordWebhookConfig> filter,
                             java.util.function.Consumer<DiscordHook> action) {
        if (!leader) {
            return;
        }
        for (DiscordHookEntry entry : discordHooks) {
            if (entry.config().enabled() && filter.test(entry.config())) {
                action.accept(entry.hook());
            }
        }
    }

    private void postDiscord(boolean enabled, java.util.function.Consumer<DiscordHook> action) {
        if (enabled) {
            postDiscord(w -> true, action);
        }
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
                                           String address, String server, boolean success, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uniqueId.toString());
        payload.addProperty("username", username);
        payload.addProperty("sourceProxyId", nodeId);
        payload.addProperty("targetProxyId", targetProxyId);
        payload.addProperty("address", address);
        payload.addProperty("server", server);
        payload.addProperty("success", success);
        payload.addProperty("message", message);

        Message msg = Message.of(MessageType.TRANSFER_RESPONSE, nodeId, payload);
        if (leader) {
            hubServer.broadcast(msg, null);
            postTransferResult(payload, nodeId);
        } else {
            hubClient.send(msg);
        }
    }

    /** 転送結果を Discord へ投稿する（リーダーのみ）。 */
    private void postTransferResult(JsonObject payload, String sender) {
        String username = payload.has("username") ? payload.get("username").getAsString() : "?";
        String sourceProxyId = payload.has("sourceProxyId") ? payload.get("sourceProxyId").getAsString() : "?";
        String targetProxyId = payload.has("targetProxyId") ? payload.get("targetProxyId").getAsString() : "?";
        String reason = payload.has("message") ? payload.get("message").getAsString() : "";
        boolean success = payload.has("success") && payload.get("success").getAsBoolean();
        postDiscord(VelocityBridgeConfig.DiscordWebhookConfig::notifyTransfer, discord -> discord.send(success
                ? DiscordMessages.transferSuccess(username, sourceProxyId, targetProxyId)
                : DiscordMessages.transferFailure(username, targetProxyId, reason)));
    }

    public boolean isHubConnected() {
        return leader || (hubClient != null && hubClient.isConnected());
    }

    public io.velocitybridge.ha.RaftNode getRaftNode() {
        return raftNode;
    }

    private void promoteToLeader() {
        this.leader = true;
        logger.info("Promoted to LEADER node: {}", nodeId);
    }

    private void demoteToFollower(String leaderId) {
        this.leader = false;
        logger.info("Demoted to FOLLOWER node: {} (leader={})", nodeId, leaderId);
    }

    private void sendDirectVoteRequest(String targetProxyId, io.velocitybridge.hub.payload.Payloads.RequestVote voteReq) {
        VelocityBridgeConfig.ProxyInfo targetInfo = config.proxies().stream()
                .filter(p -> p.id().equals(targetProxyId))
                .findFirst().orElse(null);
        if (targetInfo == null) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try (Socket s = new Socket()) {
                InetSocketAddress addr = parseAddress(targetInfo.address(), config.hubPort());
                s.connect(addr, 2000);
                s.setTcpNoDelay(true);
                MessageCipher cipher = new MessageCipher(config.secret());

                JsonObject payload = MessageCodec.encodePayload(voteReq);
                Message msg = Message.of(MessageType.REQUEST_VOTE, nodeId, payload);
                MessageCodec.write(s.getOutputStream(), msg, cipher);

                Message respMsg = MessageCodec.read(s.getInputStream(), cipher);
                if (respMsg != null && MessageType.REQUEST_VOTE_RESPONSE.equals(respMsg.type())) {
                    io.velocitybridge.hub.payload.Payloads.RequestVoteResponse resp =
                            MessageCodec.decodePayload(respMsg.payload(), io.velocitybridge.hub.payload.Payloads.RequestVoteResponse.class);
                    if (raftNode != null) {
                        raftNode.handleVoteResponse(resp);
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** 他プロキシからの権限変更の差分をローカルに適用する。 */
    private void handlePermissionUpdate(JsonObject payload) {
        PermissionSync sync = permissionSync;
        if (sync == null) {
            return;
        }
        sync.onRemoteChange(new PermissionBackend.NodeChange(
                payload.get("holderType").getAsString(),
                payload.get("holderKey").getAsString(),
                payload.get("node").getAsString(),
                payload.get("add").getAsBoolean(),
                payload.get("value").getAsBoolean()));
    }

    /** 権限のフル状態をスナップショットとして要求ノードへ送信する（リーダーのみ）。 */
    private void sendPermissionSnapshot(String target) {
        PermissionSync sync = permissionSync;
        if (sync == null) {
            return;
        }
        sync.snapshot().whenComplete((holders, error) -> {
            if (error != null) {
                logger.warn("Failed to build permission snapshot for {}: {}", target, error.toString());
                return;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("version", permissionVersion.get());
            JsonArray holdersArray = new JsonArray();
            for (PermissionBackend.HolderSnapshot holder : holders) {
                JsonObject holderObject = new JsonObject();
                holderObject.addProperty("holderType", holder.holderType());
                holderObject.addProperty("holderKey", holder.holderKey());
                JsonArray nodesArray = new JsonArray();
                for (PermissionBackend.NodeValue node : holder.nodes()) {
                    JsonObject nodeObject = new JsonObject();
                    nodeObject.addProperty("node", node.node());
                    nodeObject.addProperty("value", node.value());
                    nodesArray.add(nodeObject);
                }
                holderObject.add("nodes", nodesArray);
                holdersArray.add(holderObject);
            }
            payload.add("holders", holdersArray);
            hubServer.sendTo(target, Message.of(MessageType.PERMISSION_SNAPSHOT, nodeId, payload));
        });
    }

    /** 権限スナップショットをローカルに適用し、適用済みバージョンを記録する（フォロワーのみ）。 */
    private void handlePermissionSnapshot(JsonObject payload) {
        PermissionSync sync = permissionSync;
        if (sync == null) {
            return;
        }
        List<PermissionBackend.HolderSnapshot> holders = new ArrayList<>();
        if (payload.has("holders") && payload.get("holders").isJsonArray()) {
            for (JsonElement element : payload.getAsJsonArray("holders")) {
                JsonObject holderObject = element.getAsJsonObject();
                List<PermissionBackend.NodeValue> nodes = new ArrayList<>();
                if (holderObject.has("nodes") && holderObject.get("nodes").isJsonArray()) {
                    for (JsonElement nodeElement : holderObject.getAsJsonArray("nodes")) {
                        JsonObject nodeObject = nodeElement.getAsJsonObject();
                        nodes.add(new PermissionBackend.NodeValue(
                                nodeObject.get("node").getAsString(),
                                nodeObject.get("value").getAsBoolean()));
                    }
                }
                holders.add(new PermissionBackend.HolderSnapshot(
                        holderObject.get("holderType").getAsString(),
                        holderObject.get("holderKey").getAsString(),
                        nodes));
            }
        }
        sync.applySnapshot(holders);
        if (payload.has("version")) {
            appliedPermissionVersion.set(payload.get("version").getAsLong());
        }
    }

    /** Minecraft の既定ポート。 */
    private static final int DEFAULT_PORT = 25565;

    static InetSocketAddress parseAddress(String address) {
        return parseAddress(address, DEFAULT_PORT);
    }

    private static InetSocketAddress parseAddress(String address, int defaultPort) {
        int colon = address.lastIndexOf(':');
        if (colon == -1) {
            return new InetSocketAddress(address, defaultPort);
        }
        try {
            return new InetSocketAddress(address.substring(0, colon),
                    Integer.parseInt(address.substring(colon + 1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port in address: " + address);
        }
    }

    private interface MessageHandler {
        void handle(String sender, Message message);
    }

    /** リーダー側のハブイベントハンドラ。 */
    private final class LeaderHandler implements HubServer.Handler {
        private final Map<String, MessageHandler> handlers = new ConcurrentHashMap<>();

        LeaderHandler() {
            handlers.put(MessageType.PLAYER_JOIN, this::handlePlayerJoin);
            handlers.put(MessageType.PLAYER_LEAVE, this::handlePlayerLeave);
            handlers.put(MessageType.PLAYER_LIST_FULL, this::handlePlayerListFull);
            handlers.put(MessageType.CHAT_MESSAGE, this::handleChatMessage);
            handlers.put(MessageType.TRANSFER_REQUEST, this::handleTransferRequest);
            handlers.put(MessageType.TRANSFER_RESPONSE, this::handleTransferResponse);
            handlers.put(MessageType.HEARTBEAT, (s, m) -> {});
            handlers.put(MessageType.PERMISSION_UPDATE, this::handlePermissionUpdateMsg);
            handlers.put(MessageType.PERMISSION_VERSION_REQUEST, this::handlePermissionVersionRequest);
            handlers.put(MessageType.PERMISSION_SNAPSHOT_REQUEST, (s, m) -> sendPermissionSnapshot(s));
        }

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
            MessageHandler handler = handlers.get(message.type());
            if (handler != null) {
                handler.handle(sender, message);
            }
        }

        private void handlePlayerJoin(String sender, Message message) {
            Payloads.PlayerJoin join = message.payloadAs(Payloads.PlayerJoin.class);
            registry.register(new GlobalPlayerRegistry.PlayerEntry(join.uuid(), join.username(), sender));
            hubServer.broadcast(Message.of(MessageType.PLAYER_JOIN, sender, message.payload()), sender);
            notifyListener(sender, message);
            postDiscord(VelocityBridgeConfig.DiscordWebhookConfig::notifyJoinLeave,
                    discord -> discord.send(DiscordMessages.playerJoin(join.username(), sender)));
        }

        private void handlePlayerLeave(String sender, Message message) {
            Payloads.PlayerLeave leave = message.payloadAs(Payloads.PlayerLeave.class);
            registry.remove(leave.uuid());
            hubServer.broadcast(Message.of(MessageType.PLAYER_LEAVE, sender, message.payload()), sender);
            notifyListener(sender, message);
            String username = leave.username() != null ? leave.username() : "?";
            postDiscord(VelocityBridgeConfig.DiscordWebhookConfig::notifyJoinLeave,
                    discord -> discord.send(DiscordMessages.playerLeave(username, sender)));
        }

        private void handlePlayerListFull(String sender, Message message) {
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
            hubServer.sendTo(sender, Message.of(MessageType.GLOBAL_LIST_RESPONSE,
                    BridgeCoordinator.this.nodeId, encodeRegistry()));
        }

        private void handleChatMessage(String sender, Message message) {
            Payloads.ChatMessage chat = message.payloadAs(Payloads.ChatMessage.class);
            hubServer.broadcast(Message.of(MessageType.CHAT_MESSAGE, sender, message.payload()), sender);
            chatRelay.onRemoteChat(sender, chat.username(), chat.message(), kanaOf(message.payload()));
            notifyListener(sender, message);
            postDiscord(VelocityBridgeConfig.DiscordWebhookConfig::notifyChat,
                    discord -> discord.send(DiscordMessages.chat(chat.username(), chat.message(), kanaOf(message.payload()))));
        }

        private void handleTransferRequest(String sender, Message message) {
            hubServer.broadcast(Message.of(MessageType.TRANSFER_REQUEST, sender, message.payload()), sender);
            logger.info("Transfer request from {}: {}", sender, summarizeTransfer(message.payload()));
            notifyListener(sender, message);
        }

        private void handleTransferResponse(String sender, Message message) {
            hubServer.broadcast(Message.of(MessageType.TRANSFER_RESPONSE, sender, message.payload()), sender);
            logger.info("Transfer result from {}: {}", sender, summarizeTransfer(message.payload()));
            recordTransferTarget(message.payload());
            notifyListener(sender, message);
            postTransferResult(message.payload(), sender);
        }

        private void handlePermissionUpdateMsg(String sender, Message message) {
            message.payload().addProperty("version", permissionVersion.incrementAndGet());
            hubServer.broadcast(Message.of(MessageType.PERMISSION_UPDATE, sender, message.payload()), sender);
            handlePermissionUpdate(message.payload());
            notifyListener(sender, message);
        }

        private void handlePermissionVersionRequest(String sender, Message message) {
            JsonObject response = new JsonObject();
            response.addProperty("version", permissionVersion.get());
            hubServer.sendTo(sender, Message.of(MessageType.PERMISSION_VERSION_RESPONSE,
                    BridgeCoordinator.this.nodeId, response));
        }

        @Override
        public void onDisconnect(String nodeId) {
            logger.info("Follower disconnected: {}", nodeId);
            for (GlobalPlayerRegistry.PlayerEntry entry : registry.removeAllForProxy(nodeId)) {
                JsonObject payload = new JsonObject();
                payload.addProperty("uuid", entry.uniqueId().toString());
                payload.addProperty("proxyId", nodeId);
                payload.addProperty("username", entry.username());
                hubServer.broadcast(Message.of(MessageType.PLAYER_LEAVE, nodeId, payload), nodeId);
                postDiscord(VelocityBridgeConfig.DiscordWebhookConfig::notifyJoinLeave,
                        discord -> discord.send(DiscordMessages.playerLeave(entry.username(), nodeId)));
            }
        }

        private void notifyListener(String sender, Message message) {
            if (messageListener != null) {
                messageListener.accept(sender, message.payload());
            }
        }
    }

    private interface FollowerMessageHandler {
        void handle(Message message);
    }

    /** フォロワー側のハブイベントハンドラ。 */
    private final class FollowerHandler implements HubClient.Handler {
        private final AtomicReference<String> serverNodeId;
        private final Map<String, FollowerMessageHandler> handlers = new ConcurrentHashMap<>();

        private FollowerHandler(AtomicReference<String> serverNodeId) {
            this.serverNodeId = serverNodeId;
            handlers.put(MessageType.GLOBAL_LIST_RESPONSE, m -> applyGlobalList(m.payload()));
            handlers.put(MessageType.PLAYER_JOIN, this::handlePlayerJoin);
            handlers.put(MessageType.PLAYER_LEAVE, this::handlePlayerLeave);
            handlers.put(MessageType.CHAT_MESSAGE, this::handleChatMessage);
            handlers.put(MessageType.TRANSFER_REQUEST, this::handleTransferRequest);
            handlers.put(MessageType.TRANSFER_RESPONSE, this::handleTransferResponse);
            handlers.put(MessageType.PERMISSION_UPDATE, this::handlePermissionUpdateMsg);
            handlers.put(MessageType.PERMISSION_VERSION_RESPONSE, this::handlePermissionVersionResponse);
            handlers.put(MessageType.PERMISSION_SNAPSHOT, m -> handlePermissionSnapshot(m.payload()));
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

            // 権限の適用バージョンを問い合わせ、不足していればスナップショットで再同期する
            JsonObject versionRequest = new JsonObject();
            versionRequest.addProperty("appliedVersion", appliedPermissionVersion.get());
            hubClient.send(Message.of(MessageType.PERMISSION_VERSION_REQUEST, nodeId, versionRequest));
        }

        @Override
        public void onMessage(Message message) {
            FollowerMessageHandler handler = handlers.get(message.type());
            if (handler != null) {
                handler.handle(message);
            }
        }

        private void handlePlayerJoin(Message message) {
            Payloads.PlayerJoin join = message.payloadAs(Payloads.PlayerJoin.class);
            registry.register(new GlobalPlayerRegistry.PlayerEntry(join.uuid(), join.username(), join.proxyId()));
            notifyListener(message);
        }

        private void handlePlayerLeave(Message message) {
            Payloads.PlayerLeave leave = message.payloadAs(Payloads.PlayerLeave.class);
            registry.remove(leave.uuid());
            notifyListener(message);
        }

        private void handleChatMessage(Message message) {
            Payloads.ChatMessage chat = message.payloadAs(Payloads.ChatMessage.class);
            chatRelay.onRemoteChat(message.sender(), chat.username(), chat.message(), kanaOf(message.payload()));
            notifyListener(message);
        }

        private void handleTransferRequest(Message message) {
            logger.info("Transfer request from {}: {}", nodeId, summarizeTransfer(message.payload()));
            notifyListener(message);
        }

        private void handleTransferResponse(Message message) {
            logger.info("Transfer result from {}: {}", nodeId, summarizeTransfer(message.payload()));
            recordTransferTarget(message.payload());
            notifyListener(message);
        }

        private void handlePermissionUpdateMsg(Message message) {
            handlePermissionUpdate(message.payload());
            if (message.payload().has("version")) {
                long version = message.payload().get("version").getAsLong();
                appliedPermissionVersion.accumulateAndGet(version, Math::max);
            }
        }

        private void handlePermissionVersionResponse(Message message) {
            long serverVersion = message.payload().get("version").getAsLong();
            if (serverVersion > appliedPermissionVersion.get()) {
                // 適用済みより進んでいるため、フル状態を要求して再同期する
                hubClient.send(Message.of(MessageType.PERMISSION_SNAPSHOT_REQUEST,
                        nodeId, MessageCodec.emptyPayload()));
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

    /** チャットペイロードからカナを取り出す（無ければ空文字）。 */
    private static String kanaOf(JsonObject payload) {
        return payload.has("kana") ? payload.get("kana").getAsString() : "";
    }

    /**
     * 転送先プロキシが、移動してくるプレイヤーの接続先サーバーを引き継ぐための保留を登録する。
     *
     * <p>転送元が送った {@code TRANSFER_RESPONSE} から、自ノード宛かつ成功したものだけを対象に、
     * UUID → サーバー名を記録する。プレイヤーがこのプロキシへログインしたとき
     * {@link #onPlayerChooseInitialServer(PlayerChooseInitialServerEvent)} がこれを消費する。</p>
     *
     * @param payload TRANSFER_RESPONSE のペイロード
     */
    private void recordTransferTarget(JsonObject payload) {
        if (!payload.has("success") || !payload.get("success").getAsBoolean()) {
            return;
        }
        String target = payload.has("targetProxyId") ? payload.get("targetProxyId").getAsString() : "";
        if (!nodeId.equals(target)) {
            return;
        }
        String server = payload.has("server") ? payload.get("server").getAsString() : "";
        if (server.isEmpty()) {
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(payload.get("uuid").getAsString());
        } catch (RuntimeException e) {
            logger.warn("Malformed transfer response uuid: {}", payload.get("uuid"));
            return;
        }
        pendingTransfers.put(uuid, new PendingTransfer(server, System.nanoTime()));
        logger.info("Preserving server {} for transferred player {}", server, uuid);
    }

    /**
     * 転送で移動してきたプレイヤーの初期接続先を、転送元と同じバックエンドサーバーにする。
     *
     * <p>自ノード宛の転送が保留されていれば {@link PlayerChooseInitialServerEvent} で
     * サーバーを差し替える。保留が無い・期限切れ・対象サーバーが存在しない場合は既定の
     * 接続先（ロビー等）へフォールバックする。</p>
     *
     * @param event 初期接続先選択イベント
     */
    public void onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        Optional<String> server = takePendingServer(player.getUniqueId());
        if (server.isEmpty() || proxy == null) {
            return;
        }
        Optional<RegisteredServer> target = proxy.getServer(server.get());
        if (target.isEmpty()) {
            logger.warn("Preserved server {} for transferred player {} not found on this proxy; "
                    + "falling back to default", server.get(), player.getUsername());
            return;
        }
        logger.info("Connecting transferred player {} to preserved server {}",
                player.getUsername(), server.get());
        event.setInitialServer(target.get());
    }

    /**
     * 転送で移動してきたプレイヤーの保留サーバーを取り出す（消費する）。
     *
     * <p>保留が無い・サーバー名が空・期限切れの場合は {@link Optional#empty()} を返す。</p>
     *
     * @param uniqueId プレイヤーUUID
     * @return 保留されたサーバー名
     */
    Optional<String> takePendingServer(UUID uniqueId) {
        PendingTransfer pending = pendingTransfers.remove(uniqueId);
        if (pending == null || pending.server.isEmpty()
                || System.nanoTime() - pending.createdAt > TRANSFER_PENDING_TTL_NANOS) {
            return Optional.empty();
        }
        return Optional.of(pending.server);
    }

    /** 転送先サーバーの保留エントリ。 */
    private static final class PendingTransfer {
        final String server;
        final long createdAt;

        PendingTransfer(String server, long createdAt) {
            this.server = server;
            this.createdAt = createdAt;
        }
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

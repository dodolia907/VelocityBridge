package io.velocitybridge.permission;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.velocitybridge.BridgeCoordinator;
import io.velocitybridge.hub.Message;
import io.velocitybridge.hub.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 権限同期関連のメッセージ処理と配信を担当するクラス。
 */
public class PermissionCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(PermissionCoordinator.class);

    private final BridgeCoordinator coordinator;
    private volatile PermissionSync permissionSync;

    /** リーダーが発行する権限バージョン。 */
    private final AtomicLong permissionVersion = new AtomicLong();

    /** フォロワーが適用済みの権限バージョン。 */
    private final AtomicLong appliedPermissionVersion = new AtomicLong();

    public PermissionCoordinator(BridgeCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public void setPermissionSync(PermissionSync permissionSync) {
        this.permissionSync = permissionSync;
    }

    public long getPermissionVersion() {
        return permissionVersion.get();
    }

    public long incrementAndGetPermissionVersion() {
        return permissionVersion.incrementAndGet();
    }

    public long getAppliedPermissionVersion() {
        return appliedPermissionVersion.get();
    }

    /**
     * ローカルの権限変更の差分をネットワーク全体へ配信する。
     */
    public void onPermissionChange(PermissionBackend.NodeChange change) {
        JsonObject payload = new JsonObject();
        payload.addProperty("holderType", change.holderType());
        payload.addProperty("holderKey", change.holderKey());
        payload.addProperty("node", change.node());
        payload.addProperty("add", change.add());
        payload.addProperty("value", change.value());

        if (coordinator.isLeader()) {
            payload.addProperty("version", permissionVersion.incrementAndGet());
            if (coordinator.getHubServer() != null) {
                coordinator.getHubServer().broadcast(Message.of(MessageType.PERMISSION_UPDATE, coordinator.getNodeId(), payload), null);
            }
        } else {
            if (coordinator.getHubClient() != null) {
                coordinator.getHubClient().send(Message.of(MessageType.PERMISSION_UPDATE, coordinator.getNodeId(), payload));
            }
        }
    }

    /** 他プロキシからの権限変更の差分をローカルに適用する。 */
    public void handlePermissionUpdate(JsonObject payload) {
        PermissionSync sync = permissionSync;
        if (sync == null) return;

        sync.onRemoteChange(new PermissionBackend.NodeChange(
                payload.get("holderType").getAsString(),
                payload.get("holderKey").getAsString(),
                payload.get("node").getAsString(),
                payload.get("add").getAsBoolean(),
                payload.get("value").getAsBoolean()));
    }

    /** 権限のフル状態をスナップショットとして要求ノードへ送信する（リーダーのみ）。 */
    public void sendPermissionSnapshot(String target) {
        PermissionSync sync = permissionSync;
        if (sync == null) return;

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
            if (coordinator.getHubServer() != null) {
                coordinator.getHubServer().sendTo(target, Message.of(MessageType.PERMISSION_SNAPSHOT, coordinator.getNodeId(), payload));
            }
        });
    }

    /** 権限スナップショットをローカルに適用し、適用済みバージョンを記録する（フォロワーのみ）。 */
    public void handlePermissionSnapshot(JsonObject payload) {
        PermissionSync sync = permissionSync;
        if (sync == null) return;

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
            long newVersion = payload.get("version").getAsLong();
            appliedPermissionVersion.set(newVersion);
            logger.info("Applied permission snapshot (version={})", newVersion);
        }
    }
}

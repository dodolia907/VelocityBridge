package io.velocitybridge.permission;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.node.NodeMutateEvent;
import net.luckperms.api.event.node.NodeRemoveEvent;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * LuckPerms API を利用した権限バックエンド。
 *
 * <p>すべての LuckPerms 型への参照をこのクラスに集約する。LuckPerms 未導入の環境では
 * クラス解決が行われないよう {@link #tryCreate} の try/catch で検出し、{@code null} を返す。</p>
 */
public final class LuckPermsBackend implements PermissionBackend {

    private final LuckPerms luckPerms;
    private final List<EventSubscription<?>> subscriptions = new ArrayList<>();
    private volatile Consumer<NodeChange> listener;

    private LuckPermsBackend(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    /**
     * LuckPerms が利用可能ならバックエンドを作成し、無ければ {@code null} を返す。
     *
     * @param logger ロガー
     * @return バックエンド（未導入時は {@code null}）
     */
    public static LuckPermsBackend tryCreate(org.slf4j.Logger logger) {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            return new LuckPermsBackend(luckPerms);
        } catch (Throwable t) {
            logger.warn("LuckPerms not available; cross-proxy permission sync disabled");
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void subscribe(Consumer<NodeChange> listener) {
        this.listener = listener;
        subscriptions.add(luckPerms.getEventBus().subscribe(NodeAddEvent.class, this::onNodeMutate));
        subscriptions.add(luckPerms.getEventBus().subscribe(NodeRemoveEvent.class, this::onNodeMutate));
    }

    private void onNodeMutate(NodeMutateEvent event) {
        if (event.getDataType() != DataType.NORMAL) {
            return; // 一時データ（transient）はセッション限定のため同期しない
        }
        Consumer<NodeChange> l = listener;
        if (l == null) {
            return;
        }
        Node node = event instanceof NodeAddEvent
                ? ((NodeAddEvent) event).getNode()
                : ((NodeRemoveEvent) event).getNode();
        NodeChange change = new NodeChange(
                holderType(event),
                holderKey(event),
                node.getKey(),
                event instanceof NodeAddEvent,
                node.getValue());
        l.accept(change);
    }

    private static String holderType(NodeMutateEvent event) {
        return event.isUser() ? "user" : "group";
    }

    private static String holderKey(NodeMutateEvent event) {
        if (event.isUser()) {
            return ((User) event.getTarget()).getUniqueId().toString();
        }
        if (event.isGroup()) {
            return ((Group) event.getTarget()).getName();
        }
        return "?";
    }

    @Override
    public void apply(NodeChange change) {
        Node node = Node.builder(change.node()).value(change.value()).build();
        if ("user".equals(change.holderType())) {
            UUID uuid = UUID.fromString(change.holderKey());
            luckPerms.getUserManager().modifyUser(uuid, user -> mutate(user, node, change.add()));
        } else if ("group".equals(change.holderType())) {
            luckPerms.getGroupManager().modifyGroup(change.holderKey(), group -> mutate(group, node, change.add()));
        }
    }

    private static void mutate(PermissionHolder holder, Node node, boolean add) {
        if (add) {
            holder.data().add(node);
        } else {
            holder.data().remove(node);
        }
    }

    @Override
    public CompletableFuture<List<HolderSnapshot>> snapshot() {
        UserManager userManager = luckPerms.getUserManager();
        GroupManager groupManager = luckPerms.getGroupManager();
        List<HolderSnapshot> result = new ArrayList<>();
        return userManager.getUniqueUsers()
                .thenCompose(uuids -> {
                    List<CompletableFuture<User>> futures = uuids.stream()
                            .map(userManager::loadUser)
                            .collect(Collectors.toList());
                    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
                })
                .thenCompose(users -> {
                    for (User user : users) {
                        result.add(snapshotOf("user", user.getUniqueId().toString(), user));
                    }
                    return groupManager.loadAllGroups();
                })
                .thenApply(groups -> {
                    for (Group group : groupManager.getLoadedGroups()) {
                        result.add(snapshotOf("group", group.getName(), group));
                    }
                    return result;
                });
    }

    private static HolderSnapshot snapshotOf(String holderType, String holderKey, PermissionHolder holder) {
        List<NodeValue> nodes = new ArrayList<>();
        for (Node node : holder.getNodes()) {
            nodes.add(new NodeValue(node.getKey(), node.getValue()));
        }
        return new HolderSnapshot(holderType, holderKey, nodes);
    }

    @Override
    public void applySnapshot(List<HolderSnapshot> holders) {
        for (HolderSnapshot holder : holders) {
            if ("user".equals(holder.holderType())) {
                UUID uuid = UUID.fromString(holder.holderKey());
                luckPerms.getUserManager().modifyUser(uuid, user -> replaceNodes(user, holder));
            } else if ("group".equals(holder.holderType())) {
                luckPerms.getGroupManager().modifyGroup(holder.holderKey(), group -> replaceNodes(group, holder));
            }
        }
    }

    private static void replaceNodes(PermissionHolder holder, HolderSnapshot snapshot) {
        holder.data().clear();
        for (NodeValue nodeValue : snapshot.nodes()) {
            holder.data().add(Node.builder(nodeValue.node()).value(nodeValue.value()).build());
        }
    }

    @Override
    public void close() {
        for (EventSubscription<?> subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception ignored) {
            }
        }
        subscriptions.clear();
    }
}

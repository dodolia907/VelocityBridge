package io.velocitybridge.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * テスト用の権限バックエンド実装。
 *
 * <p>ノード変更の適用・スナップショットの保持、およびローカルイベントの発火を記録する。</p>
 */
public final class FakePermissionBackend implements PermissionBackend {

    public final Map<String, Map<String, Boolean>> holders = new ConcurrentHashMap<>();
    public final List<NodeChange> appliedChanges = new CopyOnWriteArrayList<>();
    public final List<List<HolderSnapshot>> appliedSnapshots = new CopyOnWriteArrayList<>();
    volatile Consumer<NodeChange> listener;

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void subscribe(Consumer<NodeChange> listener) {
        this.listener = listener;
    }

    @Override
    public void apply(NodeChange change) {
        appliedChanges.add(change);
        Map<String, Boolean> nodes = holders.computeIfAbsent(
                change.holderType() + "|" + change.holderKey(), k -> new ConcurrentHashMap<>());
        if (change.add()) {
            nodes.put(change.node(), change.value());
        } else {
            nodes.remove(change.node());
        }
    }

    @Override
    public CompletableFuture<List<HolderSnapshot>> snapshot() {
        List<HolderSnapshot> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Boolean>> e : holders.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            List<NodeValue> nodes = new ArrayList<>();
            for (Map.Entry<String, Boolean> node : e.getValue().entrySet()) {
                nodes.add(new NodeValue(node.getKey(), node.getValue()));
            }
            result.add(new HolderSnapshot(parts[0], parts[1], nodes));
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void applySnapshot(List<HolderSnapshot> holdersSnapshot) {
        appliedSnapshots.add(List.copyOf(holdersSnapshot));
        holders.clear();
        for (HolderSnapshot holder : holdersSnapshot) {
            Map<String, Boolean> nodes = holders.computeIfAbsent(
                    holder.holderType() + "|" + holder.holderKey(), k -> new ConcurrentHashMap<>());
            for (NodeValue node : holder.nodes()) {
                nodes.put(node.node(), node.value());
            }
        }
    }

    /** ローカルで権限が変更されたイベントを発火する（LuckPerms イベントの代替）。 */
    public void fireLocal(NodeChange change) {
        Consumer<NodeChange> l = listener;
        if (l != null) {
            l.accept(change);
        }
    }

    @Override
    public void close() {
    }
}

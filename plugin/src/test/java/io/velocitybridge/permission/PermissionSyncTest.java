package io.velocitybridge.permission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionSync} の単体テスト。
 *
 * <p>差分配信・配信ループ防止・スナップショット適用時の再配信抑制を検証する。</p>
 */
class PermissionSyncTest {

    private FakePermissionBackend backend;
    private PermissionSync sync;
    private final List<PermissionBackend.NodeChange> broadcast = new ArrayList<>();

    @BeforeEach
    void setUp() {
        backend = new FakePermissionBackend();
        sync = new PermissionSync(backend, broadcast::add);
        sync.start();
    }

    @Test
    void localChangeIsBroadcast() {
        backend.fireLocal(new PermissionBackend.NodeChange("user", "u1", "test.node", true, true));
        assertEquals(1, broadcast.size());
    }

    @Test
    void remoteChangeDoesNotEchoBack() {
        PermissionBackend.NodeChange change =
                new PermissionBackend.NodeChange("user", "u1", "test.node", true, true);
        // 他プロキシからの適用
        sync.onRemoteChange(change);
        // 適用に起因して LuckPerms イベントが発火し、同じ変更がリスナーへ届く想定
        backend.fireLocal(change);
        assertEquals(0, broadcast.size(), "applied change must not be re-broadcast");
        assertEquals(1, backend.appliedChanges.size());
    }

    @Test
    void snapshotIsTakenFromBackend() throws ExecutionException, InterruptedException {
        backend.apply(new PermissionBackend.NodeChange("group", "default", "group.default", true, true));
        backend.apply(new PermissionBackend.NodeChange("group", "default", "velocitybridge.transfer", true, false));
        backend.apply(new PermissionBackend.NodeChange("user", "u1", "minecraft.command.vb", true, true));

        List<PermissionBackend.HolderSnapshot> snapshots = sync.snapshot().get();
        assertEquals(2, snapshots.size());
        PermissionBackend.HolderSnapshot group = snapshots.stream()
                .filter(h -> "group".equals(h.holderType()) && "default".equals(h.holderKey()))
                .findFirst().orElseThrow();
        assertEquals(2, group.nodes().size());
    }

    @Test
    void applySnapshotSuppressesLocalEvents() {
        List<PermissionBackend.NodeValue> nodes = List.of(new PermissionBackend.NodeValue("test.node", true));
        PermissionBackend.HolderSnapshot holder =
                new PermissionBackend.HolderSnapshot("user", "u1", nodes);
        sync.applySnapshot(List.of(holder));

        assertEquals(1, backend.appliedSnapshots.size());
        // 適用中に発火したローカルイベントは再配信されない
        backend.fireLocal(new PermissionBackend.NodeChange("user", "u1", "test.node", true, true));
        assertEquals(0, broadcast.size(), "snapshot-applied events must not be re-broadcast");
        // スナップショットが丸ごと適用されている
        assertTrue(backend.holders.containsKey("user|u1"));
        assertEquals(1, backend.holders.get("user|u1").size());
    }
}

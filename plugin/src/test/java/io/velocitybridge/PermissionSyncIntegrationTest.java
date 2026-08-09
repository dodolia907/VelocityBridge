package io.velocitybridge;

import com.velocitypowered.api.proxy.ProxyServer;
import io.velocitybridge.chat.ChatRelay;
import io.velocitybridge.config.VelocityBridgeConfig;
import io.velocitybridge.permission.FakePermissionBackend;
import io.velocitybridge.permission.PermissionBackend;
import io.velocitybridge.permission.PermissionSync;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 権限同期（バージョン問い合わせ + スナップショット再同期 + 差分配信）の統合テスト。
 *
 * <p>リーダーとフォロワーの実際のハブ通信を通して、起動時のバージョン不一致検出から
 * フル状態の適用、通常時の差分配信までを検証する。</p>
 */
class PermissionSyncIntegrationTest {

    private BridgeCoordinator leader;
    private BridgeCoordinator follower;
    private FakePermissionBackend leaderBackend;
    private FakePermissionBackend followerBackend;

    @AfterEach
    void tearDown() {
        if (follower != null) {
            follower.stop();
        }
        if (leader != null) {
            leader.stop();
        }
    }

    @Test
    void followerCatchesUpWithSnapshotWhenVersionDiffers() throws Exception {
        // リーダーが先に権限変更を処理済み（フォロワーは古いバージョンのまま接続）
        startLeader();
        leaderBackend.apply(new PermissionBackend.NodeChange("user", "u1", "test.node", true, true));
        leader.getPermissionCoordinator().onPermissionChange(new PermissionBackend.NodeChange("user", "u1", "test.node", true, true));
        assertEquals(1, leader.getPermissionCoordinator().getPermissionVersion());

        startFollower();

        assertTrue(awaitUntil(() -> follower.getPermissionCoordinator().getAppliedPermissionVersion() == 1),
                "follower should detect version mismatch and apply snapshot");
        assertTrue(followerBackend.holders.containsKey("user|u1"),
                "follower should have the node from the leader's snapshot");
        assertEquals(true, followerBackend.holders.get("user|u1").get("test.node"));
    }

    @Test
    void followerLocalChangePropagatesToLeader() throws Exception {
        startLeader();
        startFollower();

        followerBackend.fireLocal(new PermissionBackend.NodeChange("user", "u1", "local.node", true, true));

        assertTrue(awaitUntil(() -> leaderBackend.holders.containsKey("user|u1")
                        && leader.getPermissionCoordinator().getPermissionVersion() == 1),
                "leader should receive and apply the follower's change");
        assertEquals(true, leaderBackend.holders.get("user|u1").get("local.node"));
    }

    @Test
    void upToDateFollowerSkipsSnapshot() throws Exception {
        startLeader();
        startFollower();

        // バージョン差が無ければスナップショット要求が起きない
        assertTrue(awaitUntil(() -> follower.getPermissionCoordinator().getAppliedPermissionVersion() == 0),
                "follower should stay connected");
        Thread.sleep(500);
        assertEquals(0, followerBackend.appliedSnapshots.size(),
                "no snapshot should be transferred when versions match");
    }

    private void startLeader() throws Exception {
        leaderBackend = new FakePermissionBackend();
        leader = new BridgeCoordinator(null,
                config("proxy-1", "leader", "", 0), new ChatRelay(null), new AtomicReference<>());
        PermissionSync sync = new PermissionSync(leaderBackend, leader.getPermissionCoordinator()::onPermissionChange);
        leader.getPermissionCoordinator().setPermissionSync(sync);
        sync.start();
        leader.start();
    }

    private void startFollower() throws Exception {
        int hubPort = leader.getHubServer().getPort();
        assertTrue(hubPort > 0);

        followerBackend = new FakePermissionBackend();
        follower = new BridgeCoordinator(null,
                config("proxy-2", "follower", "127.0.0.1:" + hubPort, 51850),
                new ChatRelay(null), new AtomicReference<>());
        PermissionSync sync = new PermissionSync(followerBackend, follower.getPermissionCoordinator()::onPermissionChange);
        follower.getPermissionCoordinator().setPermissionSync(sync);
        sync.start();
        follower.start();

        assertTrue(awaitUntil(() -> follower.getHubClient().isConnected()),
                "follower should connect to the leader hub");
    }

    private static VelocityBridgeConfig config(String nodeId, String mode, String leaderAddress, int hubPort) {
        return new VelocityBridgeConfig(nodeId, mode, leaderAddress, hubPort, "test-secret", List.of(
                new VelocityBridgeConfig.ProxyInfo("proxy-1", "127.0.0.1:25565", "Local"),
                new VelocityBridgeConfig.ProxyInfo("proxy-2", "127.0.0.1:25566", "Local")));
    }

    private static boolean awaitUntil(Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.call()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.call();
    }
}

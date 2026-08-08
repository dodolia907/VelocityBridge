package io.velocitybridge;

import io.velocitybridge.chat.ChatRelay;
import io.velocitybridge.config.VelocityBridgeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2ノード（リーダー + フォロワー1）構成でのチャット配信の統合テスト。
 *
 * <p>発言元プロキシのローカル表示、相手プロキシでの表示、および二重表示が起きないことを
 * 検証する（{@code PlayerChatEvent} は deny されるため、表示はプラグインが担う）。</p>
 */
class CoordinatorChatTest {

    private BridgeCoordinator leader;
    private BridgeCoordinator follower;
    private final RecordingChatRelay leaderChat = new RecordingChatRelay();
    private final RecordingChatRelay followerChat = new RecordingChatRelay();

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
    void followerChatIsShownLocallyAndOnLeader() throws Exception {
        startTwoNodes();

        follower.onChat("Alice", "hello");

        assertTrue(awaitUntil(() -> leaderChat.contains("proxy-2|Alice|hello")),
                "leader should display the follower's chat");
        assertEquals(1, leaderChat.count("proxy-2|Alice|hello"));
        assertEquals(1, followerChat.count("proxy-2|Alice|hello"),
                "sender proxy should display the chat exactly once");
    }

    @Test
    void leaderChatIsShownLocallyAndOnFollower() throws Exception {
        startTwoNodes();

        leader.onChat("Bob", "hi");

        assertTrue(awaitUntil(() -> followerChat.contains("proxy-1|Bob|hi")),
                "follower should display the leader's chat");
        assertEquals(1, leaderChat.count("proxy-1|Bob|hi"),
                "leader should display its own chat exactly once");
        assertEquals(1, followerChat.count("proxy-1|Bob|hi"));
    }

    private void startTwoNodes() throws Exception {
        leader = new BridgeCoordinator(null,
                config("proxy-1", "leader", "", 0),
                leaderChat, new AtomicReference<>());
        leader.start();

        int hubPort = leader.getHubServer().getPort();
        assertTrue(hubPort > 0);

        follower = new BridgeCoordinator(null,
                config("proxy-2", "follower", "127.0.0.1:" + hubPort, 51850),
                followerChat, new AtomicReference<>());
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

    /** {@link ChatRelay} を差し替え、表示呼び出しを記録するフェイク。 */
    private static final class RecordingChatRelay extends ChatRelay {

        private final List<String> calls = new CopyOnWriteArrayList<>();

        private RecordingChatRelay() {
            super(null);
        }

        @Override
        public void onRemoteChat(String senderProxyId, String username, String message) {
            calls.add(senderProxyId + "|" + username + "|" + message);
        }

        private boolean contains(String expected) {
            return calls.contains(expected);
        }

        private int count(String expected) {
            int n = 0;
            for (String call : calls) {
                if (call.equals(expected)) {
                    n++;
                }
            }
            return n;
        }
    }
}

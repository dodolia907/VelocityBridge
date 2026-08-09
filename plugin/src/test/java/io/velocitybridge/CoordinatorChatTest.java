package io.velocitybridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import io.velocitybridge.chat.ChatRelay;
import io.velocitybridge.config.VelocityBridgeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

        assertTrue(awaitUntil(() -> leaderChat.contains("proxy-2|Alice|hello|")),
                "leader should display the follower's chat");
        assertEquals(1, leaderChat.count("proxy-2|Alice|hello|"));
        assertEquals(1, followerChat.count("proxy-2|Alice|hello|"),
                "sender proxy should display the chat exactly once");
    }

    @Test
    void leaderChatIsShownLocallyAndOnFollower() throws Exception {
        startTwoNodes();

        leader.onChat("Bob", "hi");

        assertTrue(awaitUntil(() -> followerChat.contains("proxy-1|Bob|hi|")),
                "follower should display the leader's chat");
        assertEquals(1, leaderChat.count("proxy-1|Bob|hi|"),
                "leader should display its own chat exactly once");
        assertEquals(1, followerChat.count("proxy-1|Bob|hi|"));
    }

    @Test
    void chatKanaPropagatesAcrossNodes() throws Exception {
        startTwoNodes();

        leader.onChat("Bob", "konnitiha", "こんにちは");

        assertTrue(awaitUntil(() -> followerChat.contains("proxy-1|Bob|konnitiha|こんにちは")),
                "kana should propagate to the follower");
        assertEquals(1, leaderChat.count("proxy-1|Bob|konnitiha|こんにちは"),
                "leader should display its own kana chat exactly once");
    }

    @Test
    void convertedChatExcludesSenderFromRelayAndSendsConversionNotice() throws Exception {
        startTwoNodes();

        UUID sender = UUID.randomUUID();
        leader.onChat("Bob", "konnitiha", "こんにちは", sender);

        assertTrue(awaitUntil(() -> followerChat.contains("proxy-1|Bob|konnitiha|こんにちは")),
                "converted chat should still reach other players on the follower");
        assertEquals(1, leaderChat.count("proxy-1|Bob|konnitiha|こんにちは", sender),
                "the sender must not receive the relayed copy (client renders it locally)");
        assertEquals(1, leaderChat.notices(sender, "こんにちは"),
                "the sender should receive a single conversion notice instead");
    }

    @Test
    void chatWithoutKanaDisplaysPlain() throws Exception {
        startTwoNodes();

        leader.onChat("Bob", "hello");

        assertTrue(awaitUntil(() -> followerChat.contains("proxy-1|Bob|hello|")),
                "chat without kana should propagate");
        assertEquals(0, followerChat.count("proxy-1|Bob|hello|こんにちは"));
    }

    @Test
    void discordChatIsPostedByLeaderExactlyOnce() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> posts = new CopyOnWriteArrayList<>();
        HttpServer discord = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            discord.createContext("/webhook", exchange -> {
                posts.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, ok.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(ok);
                }
                latch.countDown();
            });
            discord.start();
            String webhookUrl = "http://127.0.0.1:" + discord.getAddress().getPort() + "/webhook";
            startTwoNodes(webhookUrl);

            follower.onChat("Alice", "hello from follower");
            leader.onChat("Bob", "hi from leader");

            assertTrue(awaitUntil(() -> posts.stream().anyMatch(p -> p.contains("hello from follower"))),
                    "leader should post follower chat to Discord");
            assertTrue(awaitUntil(() -> posts.stream().anyMatch(p -> p.contains("hi from leader"))),
                    "leader should post its own chat to Discord");
            // 各発言が 1 回だけ投稿されること（二重投稿なし）
            long followerPosts = posts.stream().filter(p -> p.contains("hello from follower")).count();
            long leaderPosts = posts.stream().filter(p -> p.contains("hi from leader")).count();
            assertEquals(1, followerPosts, "follower chat should be posted exactly once");
            assertEquals(1, leaderPosts, "leader chat should be posted exactly once");
        } finally {
            discord.stop(0);
        }
    }

    @Test
    void parseAddressDefaultsToPort25565() {
        assertEquals(25565, BridgeCoordinator.parseAddress("mc.example.com").getPort());
        assertEquals("mc.example.com", BridgeCoordinator.parseAddress("mc.example.com").getHostString());
        assertEquals(25566, BridgeCoordinator.parseAddress("mc.example.com:25566").getPort());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> BridgeCoordinator.parseAddress("mc.example.com:not-a-number"));
    }

    @Test
    void transferTargetPreservesBackendServerOnTargetProxy() throws Exception {
        startTwoNodes();

        UUID uuid = UUID.randomUUID();
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uuid.toString());
        payload.addProperty("username", "Alice");
        payload.addProperty("sourceProxyId", "proxy-2");
        payload.addProperty("targetProxyId", "proxy-1");
        payload.addProperty("address", "127.0.0.1:25566");
        payload.addProperty("server", "main");
        payload.addProperty("success", true);
        payload.addProperty("message", "ok");

        follower.getHubClient().send(
                io.velocitybridge.hub.Message.of(io.velocitybridge.hub.MessageType.TRANSFER_RESPONSE, "proxy-2", payload));

        assertTrue(awaitUntil(() -> leader.takePendingServer(uuid).equals(Optional.of("main"))),
                "target proxy should record the preserved server for the transferred player");
        assertEquals(Optional.empty(), leader.takePendingServer(uuid),
                "pending server should be consumed once");
    }

    @Test
    void transferTargetIgnoresResponsesForOtherProxies() throws Exception {
        startTwoNodes();

        UUID uuid = UUID.randomUUID();
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uuid.toString());
        payload.addProperty("username", "Alice");
        payload.addProperty("sourceProxyId", "proxy-2");
        payload.addProperty("targetProxyId", "proxy-9");
        payload.addProperty("address", "127.0.0.1:25569");
        payload.addProperty("server", "main");
        payload.addProperty("success", true);
        payload.addProperty("message", "ok");

        follower.getHubClient().send(
                io.velocitybridge.hub.Message.of(io.velocitybridge.hub.MessageType.TRANSFER_RESPONSE, "proxy-2", payload));

        Thread.sleep(300);
        assertEquals(Optional.empty(), leader.takePendingServer(uuid),
                "a response addressed to another proxy must not be recorded");
    }

    private void startTwoNodes() throws Exception {
        startTwoNodes(null);
    }

    private void startTwoNodes(String webhookUrl) throws Exception {
        VelocityBridgeConfig leaderConfig = webhookUrl == null
                ? config("proxy-1", "leader", "", 0)
                : new VelocityBridgeConfig("proxy-1", "leader", "", 0, "test-secret", List.of(
                        new VelocityBridgeConfig.ProxyInfo("proxy-1", "127.0.0.1:25565", "Local"),
                        new VelocityBridgeConfig.ProxyInfo("proxy-2", "127.0.0.1:25566", "Local")),
                        new VelocityBridgeConfig.DiscordConfig(webhookUrl, "VelocityBridge", "", true, true, true),
                        new VelocityBridgeConfig.ChatConfig(true));
        leader = new BridgeCoordinator(null, leaderConfig, leaderChat, new AtomicReference<>());
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
        private final List<String> notices = new CopyOnWriteArrayList<>();

        private RecordingChatRelay() {
            super(null);
        }

        @Override
        public void onRemoteChat(String senderProxyId, String username, String message, String kana,
                                 java.util.UUID senderUuid) {
            calls.add(senderProxyId + "|" + username + "|" + message + "|" + kana + "|" + senderUuid);
        }

        @Override
        public void sendConversionNotice(java.util.UUID senderUuid, String kana) {
            notices.add(senderUuid + "|" + kana);
        }

        private boolean contains(String expected) {
            return calls.stream().anyMatch(c -> c.startsWith(expected + "|"));
        }

        private int count(String expected) {
            return count(expected, null);
        }

        private int count(String expected, java.util.UUID senderUuid) {
            String suffix = senderUuid == null ? "" : "|" + senderUuid;
            int n = 0;
            for (String call : calls) {
                if (call.startsWith(expected + "|") && call.endsWith(suffix)) {
                    n++;
                }
            }
            return n;
        }

        private int notices(java.util.UUID senderUuid, String kana) {
            int n = 0;
            for (String notice : notices) {
                if (notice.equals(senderUuid + "|" + kana)) {
                    n++;
                }
            }
            return n;
        }
    }
}

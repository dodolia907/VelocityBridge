package io.velocitybridge.hub;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * リーダーとフォロワー間のハブ通信の統合テスト。
 */
class HubIntegrationTest {

    private HubServer server;
    private HubClient client;
    private final List<Message> clientReceived = new CopyOnWriteArrayList<>();
    private final List<Message> serverReceived = new CopyOnWriteArrayList<>();
    private final CountDownLatch serverLatch = new CountDownLatch(1);
    private final CountDownLatch clientLatch = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        server = new HubServer(new HubServer.Handler() {
            @Override
            public void onAuthenticated(String nodeId) {
            }

            @Override
            public void onMessage(String nodeId, Message message) {
                serverReceived.add(message);
                serverLatch.countDown();
            }

            @Override
            public void onDisconnect(String nodeId) {
            }
        }, new AuthHandler("test-secret"), "leader-1");
        server.start(0);
        int port = server.getPort();
        assertTrue(port > 0);

        client = new HubClient("follower-1", new InetSocketAddress("127.0.0.1", port), "test-secret",
                new HubClient.Handler() {
                    @Override
                    public void onConnected(String serverNodeId) {
                    }

                    @Override
                    public void onMessage(Message message) {
                        clientReceived.add(message);
                        clientLatch.countDown();
                    }

                    @Override
                    public void onDisconnected() {
                    }
                });
        client.start();

        // 接続が確立するのを待つ
        long deadline = System.currentTimeMillis() + 5_000;
        while (!client.isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(client.isConnected(), "client should connect to server");
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void relaysChatMessageToLeader() throws InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", "Alice");
        payload.addProperty("message", "hello from follower");
        boolean sent = client.send(Message.of(MessageType.CHAT_MESSAGE, "follower-1", payload));

        assertTrue(sent, "client should send message");
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS), "server should receive message");
        assertEquals(MessageType.CHAT_MESSAGE, serverReceived.get(0).type());
        assertEquals("follower-1", serverReceived.get(0).sender());
        assertEquals("hello from follower", serverReceived.get(0).payload().get("message").getAsString());
    }

    @Test
    void leaderBroadcastsToFollower() throws InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", UUID.randomUUID().toString());
        payload.addProperty("username", "Bob");
        payload.addProperty("proxyId", "follower-2");
        server.broadcast(Message.of(MessageType.PLAYER_JOIN, "follower-2", payload), "leader-1");

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS), "client should receive broadcast");
        assertEquals(MessageType.PLAYER_JOIN, clientReceived.get(0).type());
        assertEquals("Bob", clientReceived.get(0).payload().get("username").getAsString());
    }

    @Test
    void authRejectsWrongSecret() throws Exception {
        HubClient badClient = new HubClient("bad", new InetSocketAddress("127.0.0.1", server.getPort()), "wrong",
                new HubClient.Handler() {
                    @Override
                    public void onConnected(String serverNodeId) {
                    }

                    @Override
                    public void onMessage(Message message) {
                    }

                    @Override
                    public void onDisconnected() {
                    }
                });
        badClient.start();
        Thread.sleep(1_000);
        // シークレット不一致のため接続されないはず
        assertEquals(1, server.connectedNodes().size(), "only valid follower should be connected");
        badClient.close();
    }

    @Test
    void heartbeatKeepsConnectionAlive() throws Exception {
        Thread.sleep(3_500);
        assertTrue(client.isConnected(), "client should stay connected via heartbeats");
        assertEquals(1, server.connectedNodes().size());
    }

    @Test
    void reconnectAfterServerRestart() throws Exception {
        server.close();
        Thread.sleep(1_500);

        HubServer newServer = new HubServer(new HubServer.Handler() {
            @Override
            public void onAuthenticated(String nodeId) {
            }

            @Override
            public void onMessage(String nodeId, Message message) {
            }

            @Override
            public void onDisconnect(String nodeId) {
            }
        }, new AuthHandler("test-secret"), "leader-1");
        newServer.start(server.getPort());

        long deadline = System.currentTimeMillis() + 8_000;
        while (!client.isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(client.isConnected(), "client should reconnect to restarted server");
        assertEquals(1, newServer.connectedNodes().size());
        newServer.close();
    }

    @Test
    void broadcastExcludesSenderNode() throws Exception {
        CountDownLatch otherLatch = new CountDownLatch(1);
        HubClient other = new HubClient("follower-2", new InetSocketAddress("127.0.0.1", server.getPort()),
                "test-secret", new HubClient.Handler() {
                    @Override
                    public void onConnected(String serverNodeId) {
                    }

                    @Override
                    public void onMessage(Message message) {
                        otherLatch.countDown();
                    }

                    @Override
                    public void onDisconnected() {
                    }
                });
        other.start();
        long deadline = System.currentTimeMillis() + 5_000;
        while (!other.isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        // 送信元ノード（follower-2）を除外してブロードキャストする
        JsonObject payload = new JsonObject();
        payload.addProperty("username", "Carol");
        payload.addProperty("message", "hi");
        server.broadcast(Message.of(MessageType.CHAT_MESSAGE, "follower-2", payload), "follower-2");

        // 除外された follower-2 には届かないこと、follower-1 には届くことを確認
        assertTrue(!otherLatch.await(1, TimeUnit.SECONDS), "excluded node should NOT receive the broadcast");
        assertEquals(1, clientReceived.size(), "follower-1 should receive the broadcast");
        other.close();
    }

    @Test
    void heartbeatIsActuallyDeliveredToLeader() throws Exception {
        server.close();
        Thread.sleep(500);

        // 短いタイムアウト（600ms）と短いハートビート間隔（200ms）で、ハートビートが
        // 実際にリーダーへ届いているかを検証する。
        // ハートビート未達ならリーダー側タイムアウトで切断され、このテストは失敗する。
        HubServer fastServer = new HubServer(new HubServer.Handler() {
            @Override
            public void onAuthenticated(String nodeId) {
            }

            @Override
            public void onMessage(String nodeId, Message message) {
            }

            @Override
            public void onDisconnect(String nodeId) {
            }
        }, new AuthHandler("test-secret"), "leader-1", 600L);
        fastServer.start(0);

        HubClient fastClient = new HubClient("follower-1",
                new InetSocketAddress("127.0.0.1", fastServer.getPort()), "test-secret",
                new HubClient.Handler() {
                    @Override
                    public void onConnected(String serverNodeId) {
                    }

                    @Override
                    public void onMessage(Message message) {
                    }

                    @Override
                    public void onDisconnected() {
                    }
                }, 200L);
        fastClient.start();

        long deadline = System.currentTimeMillis() + 5_000;
        while (!fastClient.isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(fastClient.isConnected(), "client should connect to fast server");

        // タイムアウト（600ms）の 3 倍以上待っても切断されないことを確認
        Thread.sleep(2_000);
        assertTrue(fastClient.isConnected(), "heartbeats should keep the connection alive");
        assertEquals(1, fastServer.connectedNodes().size());

        fastClient.close();
        fastServer.close();
    }
}

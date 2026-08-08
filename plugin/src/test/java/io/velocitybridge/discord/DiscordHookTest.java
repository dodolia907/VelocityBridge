package io.velocitybridge.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discord WebHook 送信の統合テスト（JDK 組み込み HTTP サーバで受信確認）。
 */
class DiscordHookTest {

    private HttpServer server;
    private DiscordHook hook;
    private final CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
    private final CountDownLatch latch = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(body);
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType != null) {
                received.add("Content-Type=" + contentType);
            }
            received.add("Method=" + exchange.getRequestMethod());
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
            latch.countDown();
        });
        server.start();
        hook = new DiscordHook("http://127.0.0.1:" + server.getAddress().getPort() + "/webhook",
                "VelocityBridge", "");
    }

    @AfterEach
    void tearDown() {
        if (hook != null) {
            hook.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsJsonToWebhook() throws Exception {
        hook.send("**Alice**: hello from MC");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "webhook should receive a POST");
        JsonObject body = JsonParser.parseString(received.get(0)).getAsJsonObject();
        assertEquals("**Alice**: hello from MC", body.get("content").getAsString());
        assertEquals("VelocityBridge", body.get("username").getAsString());
        assertFalse(body.has("avatar_url"), "avatar_url omitted when empty");
        assertTrue(received.contains("Content-Type=application/json"));
        assertTrue(received.contains("Method=POST"));
    }

    @Test
    void sendsMultipleMessagesInOrder() throws Exception {
        hook.send("first");
        hook.send("second");

        long deadline = System.currentTimeMillis() + 5_000;
        while (received.stream().noneMatch(s -> s.contains("second")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(received.stream().anyMatch(s -> s.contains("second")), "second message should arrive");
    }
}

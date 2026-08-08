package io.velocitybridge.discord;

import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discord WebHook へのメッセージ送信クライアント。
 *
 * <p>WebHook URL は送信専用であるため、Discord → ゲーム方向の同期には対応しない。
 * 送信は単一スレッドの非同期キューで行い、ゲームスレッドをブロックしない。</p>
 */
public final class DiscordHook implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(DiscordHook.class);

    private final String webhookUrl;
    private final String username;
    private final String avatarUrl;
    private final HttpClient client;
    private final ExecutorService executor;

    public DiscordHook(String webhookUrl, String username, String avatarUrl) {
        this.webhookUrl = webhookUrl;
        this.username = username;
        this.avatarUrl = avatarUrl;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "velocitybridge-discord");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * メッセージを非同期で送信する。
     *
     * @param content 投稿する本文
     */
    public void send(String content) {
        executor.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("content", content);
                if (username != null && !username.isEmpty()) {
                    body.addProperty("username", username);
                }
                if (avatarUrl != null && !avatarUrl.isEmpty()) {
                    body.addProperty("avatar_url", avatarUrl);
                }
                HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    logger.warn("Discord webhook failed: HTTP {}", response.statusCode());
                }
            } catch (IOException | InterruptedException e) {
                logger.warn("Discord webhook error: {}", e.toString());
            }
        });
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}

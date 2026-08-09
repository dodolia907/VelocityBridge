package io.velocitybridge.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityBridgeConfigTest {

    @Test
    void loadsMultipleDiscordWebhooks(@TempDir Path tempDir) throws IOException {
        String yaml = """
                node-id: "proxy-1"
                mode: "leader"
                hub-port: 51850
                discord:
                  webhooks:
                    - webhook-url: "https://discord.com/api/webhooks/111"
                      username: "ChatHook"
                      notify-chat: true
                      notify-join-leave: false
                      notify-transfer: false
                    - webhook-url: "https://discord.com/api/webhooks/222"
                      username: "AdminHook"
                      notify-chat: false
                      notify-join-leave: true
                      notify-transfer: true
                """;
        Files.writeString(tempDir.resolve(VelocityBridgeConfig.CONFIG_FILE), yaml);

        VelocityBridgeConfig config = VelocityBridgeConfig.load(tempDir, LoggerFactory.getLogger("test"));

        assertTrue(config.discord().enabled());
        assertEquals(2, config.discord().webhooks().size());

        VelocityBridgeConfig.DiscordWebhookConfig hook1 = config.discord().webhooks().get(0);
        assertEquals("https://discord.com/api/webhooks/111", hook1.webhookUrl());
        assertEquals("ChatHook", hook1.username());
        assertTrue(hook1.notifyChat());
        assertFalse(hook1.notifyJoinLeave());

        VelocityBridgeConfig.DiscordWebhookConfig hook2 = config.discord().webhooks().get(1);
        assertEquals("https://discord.com/api/webhooks/222", hook2.webhookUrl());
        assertEquals("AdminHook", hook2.username());
        assertFalse(hook2.notifyChat());
        assertTrue(hook2.notifyJoinLeave());
        assertTrue(hook2.notifyTransfer());
    }

    @Test
    void loadsLegacySingleDiscordWebhook(@TempDir Path tempDir) throws IOException {
        String yaml = """
                node-id: "proxy-1"
                mode: "leader"
                hub-port: 51850
                discord:
                  webhook-url: "https://discord.com/api/webhooks/legacy"
                  username: "LegacyHook"
                  notify-chat: true
                  notify-join-leave: true
                  notify-transfer: false
                """;
        Files.writeString(tempDir.resolve(VelocityBridgeConfig.CONFIG_FILE), yaml);

        VelocityBridgeConfig config = VelocityBridgeConfig.load(tempDir, LoggerFactory.getLogger("test"));

        assertTrue(config.discord().enabled());
        assertEquals(1, config.discord().webhooks().size());
        assertEquals("https://discord.com/api/webhooks/legacy", config.discord().webhookUrl());
        assertTrue(config.discord().notifyChat());
        assertTrue(config.discord().notifyJoinLeave());
        assertFalse(config.discord().notifyTransfer());
    }
}

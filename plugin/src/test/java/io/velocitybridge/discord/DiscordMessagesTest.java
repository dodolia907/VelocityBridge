package io.velocitybridge.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Discord 投稿本文のフォーマット検証。
 */
class DiscordMessagesTest {

    @Test
    void formatsChat() {
        assertEquals("Alice: hello", DiscordMessages.chat("Alice", "hello", ""));
    }

    @Test
    void formatsChatWithKana() {
        assertEquals("Alice: konnitiha (こんにちは)",
                DiscordMessages.chat("Alice", "konnitiha", "こんにちは"));
    }

    @Test
    void formatsJoin() {
        assertEquals("**Alice** joined (proxy-1)",
                DiscordMessages.playerJoin("Alice", "proxy-1"));
    }

    @Test
    void formatsLeave() {
        assertEquals("**Alice** left (proxy-1)",
                DiscordMessages.playerLeave("Alice", "proxy-1"));
    }

    @Test
    void formatsTransferSuccess() {
        assertEquals(":arrows_counterclockwise: **Bob** transferred proxy-1 -> proxy-2",
                DiscordMessages.transferSuccess("Bob", "proxy-1", "proxy-2"));
    }

    @Test
    void formatsTransferFailure() {
        assertEquals(":warning: **Bob** transfer to proxy-2 failed: no route",
                DiscordMessages.transferFailure("Bob", "proxy-2", "no route"));
    }
}

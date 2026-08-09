package io.velocitybridge.listener;

import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * サーバーリスト ping の人数をグローバル集計値で上書きする処理のテスト。
 */
class ServerPingListenerTest {

    private static final ServerPing.Version VERSION = new ServerPing.Version(-1, "1.20.5");

    @Test
    void overridesOnlineCountWithGlobalCount() {
        ServerPing ping = ServerPing.builder()
                .version(VERSION)
                .description(Component.text("test"))
                .onlinePlayers(3)
                .maximumPlayers(100)
                .samplePlayers(new ServerPing.SamplePlayer("alice", java.util.UUID.randomUUID()))
                .build();

        ServerPing updated = ServerPingListener.apply(ping, 42);

        assertTrue(updated.getPlayers().isPresent());
        assertEquals(42, updated.getPlayers().get().getOnline());
        assertEquals(100, updated.getPlayers().get().getMax(), "max players should be preserved");
        assertEquals(1, updated.getPlayers().get().getSample().size(), "sample should be preserved");
    }

    @Test
    void keepsPingWhenCountAlreadyMatches() {
        ServerPing ping = ServerPing.builder()
                .version(VERSION)
                .description(Component.text("test"))
                .onlinePlayers(7)
                .maximumPlayers(100)
                .build();

        assertSame(ping, ServerPingListener.apply(ping, 7));
    }

    @Test
    void leavesPingWithoutPlayersUntouched() {
        ServerPing ping = ServerPing.builder()
                .version(VERSION)
                .description(Component.text("test"))
                .nullPlayers()
                .build();

        assertSame(ping, ServerPingListener.apply(ping, 10));
        assertTrue(ping.getPlayers().isEmpty());
    }

    @Test
    void returnsPingUnchangedWhenCountIsZeroOnEmptyRegistry() {
        ServerPing ping = ServerPing.builder()
                .version(VERSION)
                .description(Component.text("test"))
                .onlinePlayers(0)
                .maximumPlayers(100)
                .build();

        assertSame(ping, ServerPingListener.apply(ping, 0));
    }

    @Test
    void sampleIsPreservedWhenOverridingCount() {
        ServerPing.SamplePlayer alice = new ServerPing.SamplePlayer("alice", java.util.UUID.randomUUID());
        ServerPing.SamplePlayer bob = new ServerPing.SamplePlayer("bob", java.util.UUID.randomUUID());
        ServerPing ping = ServerPing.builder()
                .version(VERSION)
                .description(Component.text("test"))
                .onlinePlayers(2)
                .maximumPlayers(100)
                .samplePlayers(List.of(alice, bob))
                .build();

        ServerPing updated = ServerPingListener.apply(ping, 5);

        assertEquals(List.of(alice, bob), updated.getPlayers().get().getSample());
    }
}

package io.velocitybridge.probe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MinecraftStatusPing} の統合テスト。
 */
class MinecraftStatusPingTest {

    @Test
    void measuresRttAgainstStatusServer() throws Exception {
        try (StatusServer server = new StatusServer()) {
            server.start();
            long rtt = MinecraftStatusPing.measureRtt(
                    new InetSocketAddress("127.0.0.1", server.port()), 3_000);
            assertTrue(rtt >= 0, "RTT should be non-negative, got " + rtt);
        }
    }

    @Test
    void throwsOnConnectionRefused() throws Exception {
        try (StatusServer server = new StatusServer()) {
            int port = server.port();
            server.close();
            assertThrows(IOException.class,
                    () -> MinecraftStatusPing.measureRtt(new InetSocketAddress("127.0.0.1", port), 1_000));
        }
    }

    @Test
    void throwsOnTimeout() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket ignored = silent.accept()) {
                    Thread.sleep(10_000);
                } catch (IOException | InterruptedException ignored2) {
                }
            });
            t.setDaemon(true);
            t.start();
            assertThrows(IOException.class, () -> MinecraftStatusPing.measureRtt(
                    new InetSocketAddress("127.0.0.1", silent.getLocalPort()), 500));
        }
    }
}

package io.velocitybridge.probe;

import io.velocitybridge.config.VelocityBridgeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LatencyProbe} のテスト。
 */
class LatencyProbeTest {

    @Test
    void measuresAllProxiesIncludingUnreachable() throws Exception {
        try (StatusServer server = new StatusServer()) {
            server.start();
            VelocityBridgeConfig.ProxyInfo live =
                    new VelocityBridgeConfig.ProxyInfo("live", "127.0.0.1:" + server.port(), "Local");
            VelocityBridgeConfig.ProxyInfo dead =
                    new VelocityBridgeConfig.ProxyInfo("dead", "127.0.0.1:1", "");

            try (LatencyProbe probe = new LatencyProbe(List.of(live, dead), 1_000, 1_000)) {
                probe.probeAll();

                long deadline = System.currentTimeMillis() + 5_000;
                while (probe.rtt("live") == LatencyProbe.UNREACHABLE && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }

                assertTrue(probe.rtt("live") >= 0, "live proxy should have a measured RTT");
                assertEquals(LatencyProbe.UNREACHABLE, probe.rtt("dead"));
            }
        }
    }

    @Test
    void returnsUnreachableBeforeAnyMeasurement() {
        try (LatencyProbe probe = new LatencyProbe(List.of(
                new VelocityBridgeConfig.ProxyInfo("x", "127.0.0.1:25565", "")))) {
            assertEquals(LatencyProbe.UNREACHABLE, probe.rtt("x"));
        }
    }

    @Test
    void skipsProxiesWithMalformedAddress() throws Exception {
        try (LatencyProbe probe = new LatencyProbe(List.of(
                new VelocityBridgeConfig.ProxyInfo("no-port", "127.0.0.1", ""),
                new VelocityBridgeConfig.ProxyInfo("bad-port", "127.0.0.1:not-a-number", "")))) {
            probe.probeAll();

            Thread.sleep(300);
            // 不正アドレスのプロキシは例外で止めず UNREACHABLE として保持される
            assertEquals(LatencyProbe.UNREACHABLE, probe.rtt("no-port"));
            assertEquals(LatencyProbe.UNREACHABLE, probe.rtt("bad-port"));
        }
    }
}

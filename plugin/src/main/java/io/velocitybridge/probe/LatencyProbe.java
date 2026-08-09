package io.velocitybridge.probe;

import io.velocitybridge.config.VelocityBridgeConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 設定された全プロキシへのレイテンシを定期的に計測するプローブ。
 *
 * <p>{@link MinecraftStatusPing} で各プロキシの RTT を並列に計測し、スレッドセーフな
 * キャッシュに保持する。計測失敗（到達不能）は {@link #UNREACHABLE}（-1）として保持する。</p>
 */
public final class LatencyProbe implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(LatencyProbe.class);

    /** 未計測・到達不能を表す値。 */
    public static final long UNREACHABLE = -1L;

    private volatile List<VelocityBridgeConfig.ProxyInfo> proxies;
    private final int timeoutMs;
    private final long intervalMs;
    private final Map<String, Long> rtts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService probers;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LatencyProbe(List<VelocityBridgeConfig.ProxyInfo> proxies) {
        this(proxies, 3_000L, 15_000L);
    }

    public LatencyProbe(List<VelocityBridgeConfig.ProxyInfo> proxies, long timeoutMs, long intervalMs) {
        this.proxies = List.copyOf(proxies);
        this.timeoutMs = Math.toIntExact(timeoutMs);
        this.intervalMs = intervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "velocitybridge-probe-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.probers = Executors.newFixedThreadPool(Math.max(1, proxies.size()), r -> {
            Thread t = new Thread(r, "velocitybridge-probe");
            t.setDaemon(true);
            return t;
        });
    }

    /** 定期計測を開始する。 */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::probeAll, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 計測対象のプロキシ一覧を更新する（設定リロード用）。
     *
     * @param proxies 新しいプロキシ定義
     */
    public void updateProxies(List<VelocityBridgeConfig.ProxyInfo> proxies) {
        List<VelocityBridgeConfig.ProxyInfo> updated = List.copyOf(proxies);
        this.proxies = updated;
        rtts.keySet().removeIf(id -> updated.stream().noneMatch(p -> p.id().equals(id)));
    }

    /** 計測サイクルを1回実行する（テスト用に公開）。 */
    public void probeAll() {
        for (VelocityBridgeConfig.ProxyInfo proxy : proxies) {
            probers.execute(() -> {
                long rtt = UNREACHABLE;
                try {
                    rtt = MinecraftStatusPing.measureRtt(parseAddress(proxy.address()), timeoutMs);
                } catch (IllegalArgumentException e) {
                    logger.warn("Skipping proxy {}: {}", proxy.id(), e.getMessage());
                } catch (IOException ignored) {
                    // 到達不能として扱う
                }
                rtts.put(proxy.id(), rtt);
            });
        }
    }

    /** 指定プロキシの最新 RTT（未計測・到達不能なら {@link #UNREACHABLE}）。 */
    public long rtt(String proxyId) {
        return rtts.getOrDefault(proxyId, UNREACHABLE);
    }

    /** proxyId → RTT のスナップショットを返す。 */
    public Map<String, Long> snapshot() {
        return new ConcurrentHashMap<>(rtts);
    }

    private static InetSocketAddress parseAddress(String address) {
        String[] parts = address.split(":");
        if (parts.length != 2 || parts[1].isEmpty()) {
            throw new IllegalArgumentException("Invalid proxy address (expected host:port): " + address);
        }
        try {
            return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid proxy port in address: " + address);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        probers.shutdownNow();
    }
}

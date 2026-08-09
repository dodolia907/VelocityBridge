package io.velocitybridge.command;

import com.velocitypowered.api.command.CommandSource;
import io.velocitybridge.config.VelocityBridgeConfig;
import io.velocitybridge.hub.GlobalPlayerRegistry;
import io.velocitybridge.probe.LatencyProbe;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * プロキシ一覧とレイテンシを表示する。{@code /vb proxies} サブコマンドとして使用する。
 *
 * <p>表示内容: プロキシID / 公開ホスト名 / region / 在籍プレイヤー数 / このノードからのレイテンシ(ms)。
 * 到達不能なプロキシは遅延順の末尾に表示する。</p>
 */
public final class VbProxiesCommand {

    public static final String PERMISSION = "velocitybridge.proxies";

    private volatile VelocityBridgeConfig config;
    private final LatencyProbe probe;
    private final GlobalPlayerRegistry registry;

    public VbProxiesCommand(VelocityBridgeConfig config, LatencyProbe probe, GlobalPlayerRegistry registry) {
        this.config = config;
        this.probe = probe;
        this.registry = registry;
    }

    /** 設定を更新する（設定リロード用）。 */
    public void reloadConfig(VelocityBridgeConfig config) {
        this.config = config;
    }

    /** 実行可能か（権限チェック）。 */
    public boolean canUse(CommandSource source) {
        return source.hasPermission(PERMISSION);
    }

    /** 一覧を表示する。 */
    public void execute(CommandSource source) {
        Map<String, Integer> counts = registry.proxyCountsSnapshot();
        List<VelocityBridgeConfig.ProxyInfo> sorted = config.proxies().stream()
                .sorted(Comparator.comparingLong(p -> sortKey(probe.rtt(p.id()))))
                .toList();

        source.sendPlainMessage("Proxy list (latency measured from this node):");
        for (VelocityBridgeConfig.ProxyInfo proxy : sorted) {
            long rtt = probe.rtt(proxy.id());
            String latency = rtt == LatencyProbe.UNREACHABLE ? "unreachable" : rtt + " ms";
            String region = proxy.region() == null || proxy.region().isEmpty()
                    ? "" : " [" + proxy.region() + "]";
            int players = counts.getOrDefault(proxy.id(), 0);
            source.sendPlainMessage("  " + proxy.id()
                    + "  " + proxy.address() + region
                    + "  players=" + players
                    + "  latency=" + latency);
        }
    }

    private static long sortKey(long rtt) {
        return rtt == LatencyProbe.UNREACHABLE ? Long.MAX_VALUE : rtt;
    }
}

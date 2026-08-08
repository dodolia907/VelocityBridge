package io.velocitybridge.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.velocitybridge.BridgeCoordinator;
import io.velocitybridge.config.VelocityBridgeConfig;
import io.velocitybridge.hub.GlobalPlayerRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code /vb} ルートコマンド。
 *
 * <ul>
 *   <li>{@code /vb list} — ネットワーク全体のオンラインプレイヤー一覧</li>
 *   <li>{@code /vb status} — ハブ接続状態・ノード情報・人数</li>
 *   <li>{@code /vb transfer <プロキシ名> [プレイヤー]} — プレイヤーを別プロキシへ転送</li>
 *   <li>{@code /vb proxies} — プロキシ一覧とレイテンシを表示</li>
 * </ul>
 */
public final class VbCommand implements SimpleCommand {

    private static final String PERMISSION_LIST = "velocitybridge.list";
    private static final String PERMISSION_STATUS = "velocitybridge.status";
    private static final String PERMISSION_TRANSFER = "velocitybridge.transfer";
    private static final String PERMISSION_TRANSFER_OTHERS = "velocitybridge.transfer.others";

    private final ProxyServer proxy;
    private final BridgeCoordinator coordinator;
    private final VelocityBridgeConfig config;
    private final VbProxiesCommand proxiesCommand;

    public VbCommand(ProxyServer proxy, BridgeCoordinator coordinator, VelocityBridgeConfig config,
                     VbProxiesCommand proxiesCommand) {
        this.proxy = proxy;
        this.coordinator = coordinator;
        this.config = config;
        this.proxiesCommand = proxiesCommand;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendUsage(source);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> handleList(source);
            case "status" -> handleStatus(source);
            case "transfer" -> handleTransfer(source, args);
            case "proxies" -> handleProxies(source);
            case "reload" -> handleReload(source);
            default -> sendUsage(source);
        }
    }

    private void handleList(CommandSource source) {
        if (!hasPermission(source, PERMISSION_LIST)) {
            source.sendPlainMessage("You do not have permission to use this command.");
            return;
        }
        List<GlobalPlayerRegistry.PlayerEntry> players = coordinator.getRegistry().snapshot();
        if (players.isEmpty()) {
            source.sendPlainMessage("No players are online across the network.");
            return;
        }
        source.sendPlainMessage("Online players (" + players.size() + "):");
        Map<String, List<GlobalPlayerRegistry.PlayerEntry>> byProxy = players.stream()
                .collect(Collectors.groupingBy(GlobalPlayerRegistry.PlayerEntry::proxyId));
        for (Map.Entry<String, List<GlobalPlayerRegistry.PlayerEntry>> e : byProxy.entrySet()) {
            String names = e.getValue().stream()
                    .map(GlobalPlayerRegistry.PlayerEntry::username)
                    .collect(Collectors.joining(", "));
            source.sendPlainMessage("  [" + e.getKey() + "] " + names + " (" + e.getValue().size() + ")");
        }
    }

    private void handleStatus(CommandSource source) {
        if (!hasPermission(source, PERMISSION_STATUS)) {
            source.sendPlainMessage("You do not have permission to use this command.");
            return;
        }
        String role = coordinator.isLeader() ? "leader" : "follower";
        source.sendPlainMessage("VelocityBridge status");
        source.sendPlainMessage("  Node ID: " + coordinator.getNodeId());
        source.sendPlainMessage("  Role: " + role);
        source.sendPlainMessage("  Hub connected: " + coordinator.isHubConnected());
        if (coordinator.getHubServer() != null) {
            source.sendPlainMessage("  Leader hub port: " + coordinator.getHubServer().getPort());
            source.sendPlainMessage("  Connected nodes: " + String.join(", ", coordinator.getHubServer().connectedNodes()));
        }
        GlobalPlayerRegistry registry = coordinator.getRegistry();
        source.sendPlainMessage("  Global players: " + registry.size());
        for (Map.Entry<String, Integer> e : registry.proxyCountsSnapshot().entrySet()) {
            source.sendPlainMessage("    " + e.getKey() + ": " + e.getValue());
        }
    }

    private void handleTransfer(CommandSource source, String[] args) {
        if (!hasPermission(source, PERMISSION_TRANSFER)) {
            source.sendPlainMessage("You do not have permission to use this command.");
            return;
        }
        if (args.length < 2) {
            source.sendPlainMessage("Usage: /vb transfer <proxyId> [player]");
            return;
        }
        String targetProxy = args[1];

        UUID targetUuid;
        if (args.length >= 3) {
            if (!hasPermission(source, PERMISSION_TRANSFER_OTHERS)) {
                source.sendPlainMessage("You do not have permission to transfer other players.");
                return;
            }
            Player target = proxy.getPlayer(args[2]).orElse(null);
            if (target == null) {
                source.sendPlainMessage("Player not found: " + args[2]);
                return;
            }
            targetUuid = target.getUniqueId();
        } else {
            if (!(source instanceof Player sender)) {
                source.sendPlainMessage("You must specify a player when running from console.");
                return;
            }
            targetUuid = sender.getUniqueId();
        }

        coordinator.transferPlayer(targetUuid, targetProxy, (success, message) ->
                source.sendPlainMessage((success ? "[VelocityBridge] " : "[VelocityBridge] Error: ") + message));
    }

    private void handleReload(CommandSource source) {
        source.sendPlainMessage("[VelocityBridge] Config reload is not supported yet. Restart the proxy to apply changes.");
    }

    private void handleProxies(CommandSource source) {
        if (!proxiesCommand.canUse(source)) {
            source.sendPlainMessage("You do not have permission to use this command.");
            return;
        }
        proxiesCommand.execute(source);
    }

    private void sendUsage(CommandSource source) {
        source.sendPlainMessage("Usage:");
        source.sendPlainMessage("  /vb list");
        source.sendPlainMessage("  /vb status");
        source.sendPlainMessage("  /vb transfer <proxyId> [player]");
        source.sendPlainMessage("  /vb proxies");
    }

    private boolean hasPermission(CommandSource source, String permission) {
        return source.hasPermission(permission);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true; // 個別サブコマンドでチェックする
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            return List.of("list", "status", "transfer", "proxies");
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return List.of("list", "status", "transfer", "proxies").stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args[0].equalsIgnoreCase("transfer") && args.length == 2) {
            String prefix = args[1].toLowerCase();
            return config.proxies().stream()
                    .map(VelocityBridgeConfig.ProxyInfo::id)
                    .filter(id -> id.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}

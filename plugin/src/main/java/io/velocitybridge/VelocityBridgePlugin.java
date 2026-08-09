package io.velocitybridge;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.velocitybridge.chat.ChatRelay;
import io.velocitybridge.command.VbCommand;
import io.velocitybridge.command.VbModeCommand;
import io.velocitybridge.command.VbProxiesCommand;
import io.velocitybridge.config.VelocityBridgeConfig;
import io.velocitybridge.listener.PlayerBridgeListener;
import io.velocitybridge.listener.ServerPingListener;
import io.velocitybridge.permission.LuckPermsBackend;
import io.velocitybridge.permission.PermissionBackend;
import io.velocitybridge.permission.PermissionSync;
import io.velocitybridge.probe.LatencyProbe;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * VelocityBridge プラグインのエントリポイント。
 */
@Plugin(
        id = "velocitybridge",
        name = "VelocityBridge",
        version = "1.0.0",
        description = "Multi-proxy coordination plugin for Velocity",
        url = "https://github.com/dodolia907/VelocityBridge",
        authors = {"dodolia907"}
)
public final class VelocityBridgePlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private ChatRelay chatRelay;
    private BridgeCoordinator coordinator;
    private LatencyProbe latencyProbe;
    private VbProxiesCommand proxiesCommand;
    private VbCommand vbCommand;
    private PermissionBackend permissionBackend;
    private PermissionSync permissionSync;

    @Inject
    public VelocityBridgePlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            VelocityBridgeConfig config = VelocityBridgeConfig.load(dataDirectory, logger);
            logger.info("VelocityBridge starting (node={}, mode={})", config.nodeId(), config.mode());

            ChatRelay chatRelay = new ChatRelay(proxy);
            this.chatRelay = chatRelay;
            AtomicReference<String> serverNodeId = new AtomicReference<>(null);
            coordinator = new BridgeCoordinator(proxy, config, chatRelay, serverNodeId);

            io.velocitybridge.tab.TabListManager tabListManager = new io.velocitybridge.tab.TabListManager(proxy, coordinator);
            PlayerBridgeListener listener = new PlayerBridgeListener(coordinator, chatRelay, tabListManager, proxy);
            coordinator.setMessageListener((sender, payload) -> {
                tabListManager.updateAll();
            });

            coordinator.start();
            proxy.getEventManager().register(this, listener);
            proxy.getEventManager().register(this, new ServerPingListener(coordinator.getRegistry()));

            permissionBackend = createPermissionBackend(logger);
            if (permissionBackend != null) {
                permissionSync = new PermissionSync(permissionBackend, coordinator.getPermissionCoordinator()::onPermissionChange);
                coordinator.getPermissionCoordinator().setPermissionSync(permissionSync);
                permissionSync.start();
                logger.info("Cross-proxy permission sync enabled (LuckPerms, diff-only)");
            }

            latencyProbe = new LatencyProbe(config.proxies());
            latencyProbe.start();

            registerCommands(config, listener, latencyProbe);
            logger.info("VelocityBridge enabled (leader={})", coordinator.isLeader());
        } catch (Exception e) {
            logger.error("Failed to initialize VelocityBridge", e);
        }
    }

    private void registerCommands(VelocityBridgeConfig config, PlayerBridgeListener listener,
                                  LatencyProbe latencyProbe) {
        CommandManager commandManager = proxy.getCommandManager();

        proxiesCommand = new VbProxiesCommand(config, latencyProbe, coordinator.getRegistry());
        vbCommand = new VbCommand(proxy, coordinator, config, proxiesCommand, this::reloadConfig);
        CommandMeta vbMeta = commandManager.metaBuilder("vb").plugin(this).build();
        commandManager.register(vbMeta, vbCommand);

        VbModeCommand vbModeCommand = new VbModeCommand(listener);
        CommandMeta modeMeta = commandManager.metaBuilder("vbmode").plugin(this).build();
        commandManager.register(modeMeta, vbModeCommand);
    }

    /**
     * 設定を再読み込みして反映する（{@code /vb reload}）。
     *
     * <p>各コンポーネントへ新しい設定を配布する。再起動が必要な項目（node-id / mode /
     * hub-port / leader-address / secret）は {@link BridgeCoordinator#reload} が警告を出す。</p>
     *
     * @param source コマンド実行元
     */
    private void reloadConfig(com.velocitypowered.api.command.CommandSource source) {
        try {
            VelocityBridgeConfig newConfig = VelocityBridgeConfig.load(dataDirectory, logger);
            coordinator.reload(newConfig);
            if (latencyProbe != null) {
                latencyProbe.updateProxies(newConfig.proxies());
            }
            if (proxiesCommand != null) {
                proxiesCommand.reloadConfig(newConfig);
            }
            if (vbCommand != null) {
                vbCommand.reloadConfig(newConfig);
            }
            logger.info("Config reloaded via command");
            source.sendPlainMessage("[VelocityBridge] Config reloaded. Note: node-id / mode / hub-port / "
                    + "leader-address / secret changes require a proxy restart.");
        } catch (Exception e) {
            logger.error("Failed to reload config", e);
            source.sendPlainMessage("[VelocityBridge] Reload failed: " + e.getMessage());
        }
    }

    /**
     * 利用可能な権限バックエンドを作成する。
     *
     * <p>LuckPerms は optional 依存のため、API クラスが存在しない環境ではクラスのロード自体が
     * {@link NoClassDefFoundError} を起こす（{@link LuckPermsBackend} 内の try/catch では
     * クラスロード前のため捕捉できない）。ここで先に存在確認してから参照することで、
     * 未導入環境でも初期化を中断せずに無効化できる。</p>
     *
     * @param logger ロガー
     * @return バックエンド（LuckPerms 未導入時は {@code null}）
     */
    private static PermissionBackend createPermissionBackend(Logger logger) {
        if (!isClassPresent("net.luckperms.api.LuckPerms")) {
            logger.info("LuckPerms not detected; cross-proxy permission sync disabled");
            return null;
        }
        return LuckPermsBackend.tryCreate(logger);
    }

    private static boolean isClassPresent(String name) {
        try {
            Class.forName(name, false, VelocityBridgePlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (coordinator != null) {
            coordinator.stop();
        }
        if (latencyProbe != null) {
            latencyProbe.close();
        }
        if (chatRelay != null) {
            chatRelay.close();
        }
        if (permissionBackend != null) {
            permissionBackend.close();
        }
        logger.info("VelocityBridge disabled");
    }

    /** テスト等で coordinator を外部参照するためのアクセサ。 */
    BridgeCoordinator getCoordinator() {
        return coordinator;
    }
}

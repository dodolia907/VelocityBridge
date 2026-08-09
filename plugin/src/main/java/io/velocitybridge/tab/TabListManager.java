package io.velocitybridge.tab;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabListEntry;
import io.velocitybridge.BridgeCoordinator;
import io.velocitybridge.hub.GlobalPlayerRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * プロキシ間横断 TabList 同期・表示マネージャー。
 */
public class TabListManager {

    private final ProxyServer proxy;
    private final BridgeCoordinator coordinator;

    public TabListManager(ProxyServer proxy, BridgeCoordinator coordinator) {
        this.proxy = proxy;
        this.coordinator = coordinator;
    }

    /**
     * 全オンラインプレイヤーの TabList 表示を更新する。
     */
    public void updateAll() {
        for (Player player : proxy.getAllPlayers()) {
            updateForPlayer(player);
        }
    }

    /**
     * 特定のプレイヤーの TabList 表示を更新する。
     */
    public void updateForPlayer(Player viewer) {
        // 自プロキシ上の自エントリまたはローカルプレイヤーの表示名を更新
        viewer.getTabList().getEntries().forEach(entry -> {
            UUID uuid = entry.getProfile().getId();
            // グローバルレジストリから検索
            GlobalPlayerRegistry.PlayerEntry matched = null;
            for (GlobalPlayerRegistry.PlayerEntry p : coordinator.getRegistry().snapshot()) {
                if (p.uniqueId().equals(uuid)) {
                    matched = p;
                    break;
                }
            }

            String proxyId = matched != null ? matched.proxyId() : coordinator.getNodeId();
            boolean isLocalNode = coordinator.getNodeId().equals(proxyId);
            NamedTextColor color = isLocalNode ? NamedTextColor.GREEN : NamedTextColor.GRAY;

            Component displayName = Component.text("[" + proxyId + "] ", color)
                    .append(Component.text(entry.getProfile().getName(), NamedTextColor.WHITE));

            entry.setDisplayName(displayName);
        });

        // 他プロキシにしか存在しないリモートプレイヤーを TabList に追加
        Collection<GlobalPlayerRegistry.PlayerEntry> entries = coordinator.getRegistry().snapshot();
        for (GlobalPlayerRegistry.PlayerEntry entry : entries) {
            if (coordinator.getNodeId().equals(entry.proxyId())) {
                continue; // ローカルプレイヤーは標準で TabList に存在するためスキップ
            }

            NamedTextColor color = NamedTextColor.GRAY;
            Component displayName = Component.text("[" + entry.proxyId() + "] ", color)
                    .append(Component.text(entry.username(), NamedTextColor.WHITE));

            if (!viewer.getTabList().containsEntry(entry.uniqueId())) {
                TabListEntry newEntry = TabListEntry.builder()
                        .tabList(viewer.getTabList())
                        .profile(new com.velocitypowered.api.util.GameProfile(entry.uniqueId(), entry.username(), java.util.List.of()))
                        .displayName(displayName)
                        .build();
                viewer.getTabList().addEntry(newEntry);
            } else {
                viewer.getTabList().getEntry(entry.uniqueId()).ifPresent(tabEntry -> {
                    tabEntry.setDisplayName(displayName);
                });
            }
        }
    }
}

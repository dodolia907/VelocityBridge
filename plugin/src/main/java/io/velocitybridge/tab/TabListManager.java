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
        Collection<GlobalPlayerRegistry.PlayerEntry> entries = coordinator.getRegistry().snapshot();
        Set<UUID> globalPlayerUuids = new HashSet<>();

        for (GlobalPlayerRegistry.PlayerEntry entry : entries) {
            globalPlayerUuids.add(entry.uniqueId());

            boolean isLocalNode = coordinator.getNodeId().equals(entry.proxyId());
            NamedTextColor color = isLocalNode ? NamedTextColor.GREEN : NamedTextColor.GRAY;

            Component displayName = Component.text("[" + entry.proxyId() + "] ", color)
                    .append(Component.text(entry.username(), NamedTextColor.WHITE));

            if (viewer.getUniqueId().equals(entry.uniqueId()) || viewer.getTabList().containsEntry(entry.uniqueId())) {
                viewer.getTabList().getEntry(entry.uniqueId()).ifPresent(tabEntry -> {
                    tabEntry.setDisplayName(displayName);
                });
            } else {
                TabListEntry newEntry = TabListEntry.builder()
                        .tabList(viewer.getTabList())
                        .profile(new com.velocitypowered.api.util.GameProfile(entry.uniqueId(), entry.username(), java.util.List.of()))
                        .displayName(displayName)
                        .build();
                viewer.getTabList().addEntry(newEntry);
            }
        }

        // ネットワーク上に存在しなくなったエントリを削除
        for (TabListEntry existing : viewer.getTabList().getEntries()) {
            if (!globalPlayerUuids.contains(existing.getProfile().getId())) {
                viewer.getTabList().removeEntry(existing.getProfile().getId());
            }
        }
    }
}

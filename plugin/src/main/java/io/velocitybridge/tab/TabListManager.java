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
    public void updateForPlayer(Player player) {
        Collection<GlobalPlayerRegistry.PlayerEntry> entries = coordinator.getRegistry().snapshot();
        Set<UUID> globalPlayerUuids = new HashSet<>();

        for (GlobalPlayerRegistry.PlayerEntry entry : entries) {
            globalPlayerUuids.add(entry.uniqueId());

            boolean isLocal = coordinator.getNodeId().equals(entry.proxyId());
            NamedTextColor color = isLocal ? NamedTextColor.GREEN : NamedTextColor.GRAY;

            Component displayName = Component.text("[" + entry.proxyId() + "] ", color)
                    .append(Component.text(entry.username(), NamedTextColor.WHITE));

            // ローカルプレイヤーの場合
            if (isLocal) {
                proxy.getPlayer(entry.uniqueId()).ifPresent(targetPlayer -> {
                    targetPlayer.getTabList().getEntry(entry.uniqueId()).ifPresent(tabEntry -> {
                        tabEntry.setDisplayName(displayName);
                    });
                });
            } else {
                // リモートプレイヤーの場合、TabList にエントリーを追加・更新する
                if (!player.getTabList().containsEntry(entry.uniqueId())) {
                    TabListEntry newEntry = TabListEntry.builder()
                            .tabList(player.getTabList())
                            .profile(new com.velocitypowered.api.util.GameProfile(entry.uniqueId(), entry.username(), java.util.List.of()))
                            .displayName(displayName)
                            .build();
                    player.getTabList().addEntry(newEntry);
                } else {
                    player.getTabList().getEntry(entry.uniqueId()).ifPresent(tabEntry -> {
                        tabEntry.setDisplayName(displayName);
                    });
                }
            }
        }

        // 切断したリモートプレイヤーの TabList エントリーを削除する
        for (TabListEntry existing : player.getTabList().getEntries()) {
            if (!globalPlayerUuids.contains(existing.getProfile().getId())) {
                player.getTabList().removeEntry(existing.getProfile().getId());
            }
        }
    }
}

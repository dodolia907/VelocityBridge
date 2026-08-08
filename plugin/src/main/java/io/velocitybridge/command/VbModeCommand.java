package io.velocitybridge.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import io.velocitybridge.listener.PlayerBridgeListener;

import java.util.List;

/**
 * {@code /vbmode} コマンド。
 *
 * <p>プレイヤーのローマ字→日本語変換の ON/OFF を切り替える。</p>
 */
public final class VbModeCommand implements SimpleCommand {

    private static final String PERMISSION = "velocitybridge.vbmode";

    private final PlayerBridgeListener listener;

    public VbModeCommand(PlayerBridgeListener listener) {
        this.listener = listener;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendPlainMessage("This command can only be used by players.");
            return;
        }
        if (!source.hasPermission(PERMISSION)) {
            source.sendPlainMessage("You do not have permission to use this command.");
            return;
        }

        boolean enabled = listener.toggleRomajiMode(player.getUniqueId());
        if (enabled) {
            player.sendPlainMessage("[VelocityBridge] Romaji-to-Japanese conversion is now ON.");
        } else {
            player.sendPlainMessage("[VelocityBridge] Romaji-to-Japanese conversion is now OFF.");
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of("on", "off");
    }
}

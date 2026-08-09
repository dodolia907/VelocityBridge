package io.velocitybridge.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

/**
 * コマンド処理の共通ユーティリティ。
 */
public final class CommandUtils {

    private CommandUtils() {
    }

    /**
     * 権限チェックを行い、権限がない場合はエラーメッセージを送信する。
     *
     * @param source 実行者
     * @param permission 確認する権限
     * @return 権限があれば {@code true}
     */
    public static boolean checkPermission(CommandSource source, String permission) {
        if (!source.hasPermission(permission)) {
            source.sendPlainMessage("You do not have permission to use this command.");
            return false;
        }
        return true;
    }

    /**
     * 実行者がプレイヤーであるか確認し、そうでない場合はエラーメッセージを送信する。
     *
     * @param source 実行者
     * @return プレイヤーであれば {@link Player} インスタンス、それ以外は {@code null}
     */
    public static Player checkPlayer(CommandSource source) {
        if (!(source instanceof Player player)) {
            source.sendPlainMessage("This command can only be used by players.");
            return null;
        }
        return player;
    }
}

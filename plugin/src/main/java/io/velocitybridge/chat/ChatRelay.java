package io.velocitybridge.chat;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

/**
 * チャットメッセージの配信を担う。
 *
 * <p>プロキシ配下の全プレイヤーへチャットを表示する。プラグインは {@code PlayerChatEvent} を
 * deny してバックエンドでの表示を行わないため、発言元プロキシのローカルチャットと他プロキシから
 * 届いたリモートチャットの両方をここで表示する。二重表示を避ける責務は呼び出し側が負う。</p>
 */
public class ChatRelay {

    private final ProxyServer proxy;

    public ChatRelay(ProxyServer proxy) {
        this.proxy = proxy;
    }

    /**
     * チャットをこのプロキシ配下の全プレイヤーへ表示する。
     *
     * <p>他プロキシから届いたリモートチャットに使用する（表示対象のバックエンドは存在しない）。</p>
     *
     * @param senderProxyId 送信元プロキシID
     * @param username      発言者名
     * @param message       メッセージ内容
     */
    public void onRemoteChat(String senderProxyId, String username, String message) {
        onRemoteChat(senderProxyId, username, message, "");
    }

    /**
     * チャットをこのプロキシ配下のプレイヤーへ表示する。
     *
     * <p>ローカル発言は送信元バックエンドが署名なしメッセージとしてローカル表示するため、
     * そのバックエンドのプレイヤーへは配信しない（二重表示防止）。それ以外のプレイヤーへ配信する。</p>
     *
     * @param senderProxyId 送信元プロキシID
     * @param username      発言者名
     * @param message       メッセージ内容
     * @param excludeServer 除外するバックエンドサーバ名（送信元が接続中のサーバ）
     */
    public void onRemoteChat(String senderProxyId, String username, String message, String excludeServer) {
        Component component = format(username, message);
        for (Player player : proxy.getAllPlayers()) {
            String server = player.getCurrentServer()
                    .map(s -> s.getServerInfo().getName()).orElse("");
            if (!server.equals(excludeServer)) {
                player.sendMessage(component);
            }
        }
    }

    /**
     * ローカルプレイヤーのチャットをフォーマットする（表示用）。
     *
     * @param username 発言者名
     * @param message  メッセージ内容
     * @return 表示コンポーネント
     */
    public static Component format(String username, String message) {
        return Component.text("<" + username + "> " + message);
    }
}

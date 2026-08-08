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
     * <p>ローカル発言（発言元プロキシ）とリモート発言（他プロキシ）の両方に使用する。
     * 発言元プロキシでは {@code onChat} から呼び出され、それ以外のプロキシでは
     * {@code CHAT_MESSAGE} 受信ハンドラから呼び出される。</p>
     *
     * @param senderProxyId 送信元プロキシID
     * @param username      発言者名
     * @param message       メッセージ内容
     */
    public void onRemoteChat(String senderProxyId, String username, String message) {
        Component component = format(username, message);
        for (Player player : proxy.getAllPlayers()) {
            player.sendMessage(component);
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

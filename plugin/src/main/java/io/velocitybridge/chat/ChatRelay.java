package io.velocitybridge.chat;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.UUID;

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
     * チャットをこのプロキシ配下の全プレイヤーへ表示する（除外対象なし）。
     *
     * <p>他プロキシから届いたリモートチャットに使用する。送信者はこのプロキシにいないため除外は不要。</p>
     *
     * @param senderProxyId 送信元プロキシID
     * @param username      発言者名
     * @param message       メッセージ内容（原文）
     * @param kana          ローマ字変換のカナ（非空なら括弧付きで表示、空なら省略）
     */
    public void onRemoteChat(String senderProxyId, String username, String message, String kana) {
        onRemoteChat(senderProxyId, username, message, kana, null);
    }

    /**
     * チャットをこのプロキシ配下のプレイヤーへ表示する。
     *
     * <p>ローカル発言はこのプロキシ配下の全プレイヤーへ配信する。{@code senderUuid} を指定すると
     * そのプレイヤー（送信者）を除外できる（設定 {@code chat.include-sender=false} で使用）。</p>
     *
     * @param senderProxyId 送信元プロキシID
     * @param username      発言者名
     * @param message       メッセージ内容（原文）
     * @param kana          ローマ字変換のカナ（非空なら括弧付き表示、空なら省略）
     * @param senderUuid    除外する送信者UUID（null なら除外しない）
     */
    public void onRemoteChat(String senderProxyId, String username, String message, String kana, UUID senderUuid) {
        Component component = format(username, message, kana);
        for (Player player : proxy.getAllPlayers()) {
            if (senderUuid != null && senderUuid.equals(player.getUniqueId())) {
                continue;
            }
            player.sendMessage(component);
        }
    }

    /**
     * チャット表示コンポーネントをフォーマットする。
     *
     * @param username 発言者名
     * @param message  メッセージ内容（原文）
     * @param kana     ローマ字変換のカナ（非空なら括弧付きオレンジで表示）
     * @return 表示コンポーネント
     */
    public static Component format(String username, String message, String kana) {
        Component base = Component.text("<" + username + "> " + message);
        if (kana != null && !kana.isEmpty()) {
            base = base.append(Component.text(" (" + kana + ")", NamedTextColor.GOLD));
        }
        return base;
    }
}

package io.velocitybridge.chat;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * チャットメッセージの配信を担う。
 *
 * <p>プロキシ配下の全プレイヤーへチャットを表示する。プラグインは {@code PlayerChatEvent} を
 * deny してバックエンドでの表示を行わないため、発言元プロキシのローカルチャットと他プロキシから
 * 届いたリモートチャットの両方をここで表示する。二重表示を避ける責務は呼び出し側が負う。</p>
 */
public class ChatRelay {

    /**
     * 送信者へのかな通知を送るまでの遅延（ミリ秒）。
     *
     * <p>1.19.3+ のクライアントはサーバー送信行を自分のメッセージより先に描画するため、
     * 通知を即時送信するとオリジナルメッセージの上に表示されてしまう。わずかに遅延させることで
     * オリジナルメッセージの下の行に表示する。クライアントの描画タイミングにより調整が必要な場合は
     * この値を変更する。</p>
     */
    private static final long NOTICE_DELAY_MS = 200;

    private final ProxyServer proxy;
    private final ScheduledExecutorService scheduler;

    public ChatRelay(ProxyServer proxy) {
        this.proxy = proxy;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "velocitybridge-chat-notice");
            thread.setDaemon(true);
            return thread;
        });
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

    /**
     * 送信者へ変換結果のかなのみを単独で通知する。
     *
     * <p>変換されたメッセージを送信者へリレーで返すと、1.19.3+ のクライアントが自分の
     * メッセージを最適化表示するため二重表示になる。そのため送信者はリレーから除外し、
     * 変換結果のかなだけを別途表示して確認できるようにする。</p>
     *
     * @param senderUuid 送信者UUID
     * @param kana       変換結果のかな
     */
    public void sendConversionNotice(UUID senderUuid, String kana) {
        if (proxy == null || senderUuid == null) {
            return;
        }
        scheduler.schedule(() -> proxy.getPlayer(senderUuid)
                .ifPresent(p -> p.sendMessage(formatConversionNotice(kana))), NOTICE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 変換結果のかなの単独通知コンポーネントをフォーマットする。
     *
     * @param kana 変換結果のかな
     * @return 表示コンポーネント
     */
    public static Component formatConversionNotice(String kana) {
        return Component.text("(" + kana + ")", NamedTextColor.GOLD);
    }

    /**
     * 通知用スケジューラを停止する。
     *
     * <p>未送信の通知は破棄される。プラグインのシャットダウン時に呼び出す。</p>
     */
    public void close() {
        scheduler.shutdownNow();
    }
}

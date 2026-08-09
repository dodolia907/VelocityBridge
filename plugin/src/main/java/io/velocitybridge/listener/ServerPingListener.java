package io.velocitybridge.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.server.ServerPing;
import io.velocitybridge.hub.GlobalPlayerRegistry;

/**
 * サーバーリスト ping のオンライン人数をネットワーク全体の集計値で上書きするリスナー。
 *
 * <p>Velocity はデフォルトで自プロキシ配下のプレイヤー数しか返さない。複数プロキシを
 * 共有する VelocityBridge では、プレイヤーから見た人数を全プロキシ合計（グローバル一覧）に
 * 揃えることで、どのプロキシに接続しても同じ人数が表示されるようにする。</p>
 */
public final class ServerPingListener {

    private final GlobalPlayerRegistry registry;

    public ServerPingListener(GlobalPlayerRegistry registry) {
        this.registry = registry;
    }

    @Subscribe
    public void onPing(ProxyPingEvent event) {
        event.setPing(apply(event.getPing(), registry.size()));
    }

    /**
     * ping のオンライン人数をグローバル集計値で上書きする。
     *
     * <p>players 情報が無い ping（{@code nullPlayers()}）や、既にグローバル人数と一致する場合は
     * 元の ping をそのまま返す。最大人数とサンプル一覧は変更しない。</p>
     *
     * @param ping         元の ping
     * @param globalOnline ネットワーク全体のオンライン人数
     * @return 人数を反映した ping
     */
    static ServerPing apply(ServerPing ping, int globalOnline) {
        if (ping.getPlayers().isEmpty()) {
            return ping;
        }
        if (ping.getPlayers().get().getOnline() == globalOnline) {
            return ping;
        }
        return ping.asBuilder().onlinePlayers(globalOnline).build();
    }
}

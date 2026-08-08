package io.velocitybridge.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * グローバルプレイヤー一覧。
 *
 * <p>リーダーでは全プロキシのプレイヤーを管理する権威データソース、フォロワーではリーダーから
 * 配信された一覧を保持するキャッシュとして機能する。スレッドセーフ。</p>
 */
public class GlobalPlayerRegistry {

    /** プレイヤー1人分の情報。 */
    public record PlayerEntry(UUID uniqueId, String username, String proxyId) {
    }

    private final Map<UUID, PlayerEntry> players = new ConcurrentHashMap<>();

    /** プレイヤーが在籍するプロキシIDの集合（プレイヤー数ではなく、プロキシ単位の人数把握用）。 */
    private final Map<String, Integer> proxyPlayerCounts = new ConcurrentHashMap<>();

    /**
     * プレイヤーを追加・更新する。
     *
     * @param entry プレイヤー情報
     * @return 以前の登録情報（新規追加なら {@code null}）
     */
    public PlayerEntry register(PlayerEntry entry) {
        PlayerEntry previous = players.put(entry.uniqueId(), entry);
        if (previous == null || !previous.proxyId().equals(entry.proxyId())) {
            proxyPlayerCounts.merge(entry.proxyId(), 1, Integer::sum);
        }
        if (previous != null && !previous.proxyId().equals(entry.proxyId())) {
            decrementProxyCount(previous.proxyId());
        }
        return previous;
    }

    /**
     * プレイヤーを一覧から除去する。
     *
     * @param uniqueId プレイヤーUUID
     * @return 除去できた場合、除去された情報
     */
    public PlayerEntry remove(UUID uniqueId) {
        PlayerEntry removed = players.remove(uniqueId);
        if (removed != null) {
            decrementProxyCount(removed.proxyId());
        }
        return removed;
    }

    /**
     * 指定プロキシ配下の全プレイヤーを除去する。
     *
     * @param proxyId プロキシID
     * @return 除去されたプレイヤー一覧
     */
    public List<PlayerEntry> removeAllForProxy(String proxyId) {
        List<PlayerEntry> removed = new ArrayList<>();
        for (Map.Entry<UUID, PlayerEntry> e : players.entrySet()) {
            if (e.getValue().proxyId().equals(proxyId)) {
                removed.add(e.getValue());
            }
        }
        for (PlayerEntry entry : removed) {
            players.remove(entry.uniqueId());
        }
        proxyPlayerCounts.remove(proxyId);
        return removed;
    }

    /** 全プレイヤーのスナップショットを返す。 */
    public List<PlayerEntry> snapshot() {
        return new ArrayList<>(players.values());
    }

    /** 登録されているプレイヤー数を返す。 */
    public int size() {
        return players.size();
    }

    /** 指定プレイヤーが一覧に存在するか。 */
    public boolean contains(UUID uniqueId) {
        return players.containsKey(uniqueId);
    }

    /** プロキシごとのオンライン人数のスナップショットを返す。 */
    public Map<String, Integer> proxyCountsSnapshot() {
        return new ConcurrentHashMap<>(proxyPlayerCounts);
    }

    private void decrementProxyCount(String proxyId) {
        proxyPlayerCounts.computeIfPresent(proxyId, (k, v) -> v <= 1 ? null : v - 1);
    }
}

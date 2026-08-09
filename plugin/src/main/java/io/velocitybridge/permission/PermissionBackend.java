package io.velocitybridge.permission;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 権限ストレージ（現在は LuckPerms）へのアクセスを抽象化するインターフェース。
 *
 * <p>本番では {@link LuckPermsBackend}、テストではフェイク実装を利用する。</p>
 */
public interface PermissionBackend {

    /**
     * 権限ノード変更の差分1件分。
     *
     * @param holderType 対象種別（"user" / "group"）
     * @param holderKey  対象ID（ユーザーは UUID 文字列、グループはグループ名）
     * @param node       ノードキー（例: velocitybridge.transfer）
     * @param add        {@code true} なら追加、{@code false} なら削除
     * @param value      ノードの値（否定権限なら {@code false}）
     */
    record NodeChange(String holderType, String holderKey, String node, boolean add, boolean value) {

        /** 同一変更を識別するシグネチャ（配信ループ防止用）。 */
        public String signature() {
            return holderType + "|" + holderKey + "|" + node + "|" + add + "|" + value;
        }
    }

    /**
     * 権限ノード1件の値。
     *
     * @param node  ノードキー
     * @param value ノードの値（否定権限なら {@code false}）
     */
    record NodeValue(String node, boolean value) {
    }

    /**
     * 権限保持者1名（ユーザー/グループ）のノード集合。
     *
     * @param holderType 対象種別（"user" / "group"）
     * @param holderKey  対象ID（ユーザーは UUID 文字列、グループはグループ名）
     * @param nodes      ノード一覧
     */
    record HolderSnapshot(String holderType, String holderKey, List<NodeValue> nodes) {
    }

    /** このバックエンドが利用可能か。 */
    boolean isAvailable();

    /**
     * ローカルのノード変更イベントを購読する。
     *
     * @param listener 変更発生時に呼ばれるリスナー
     */
    void subscribe(Consumer<NodeChange> listener);

    /** ノード変更の差分をローカルに適用する。 */
    void apply(NodeChange change);

    /**
     * 全ユーザー・全グループの権限ノードを取得する（スナップショット構築用）。
     *
     * @return 全権限保持者のスナップショット
     */
    CompletableFuture<List<HolderSnapshot>> snapshot();

    /**
     * スナップショットを適用し、ローカルの権限状態を丸ごと置き換える。
     *
     * @param holders 適用するスナップショット
     */
    void applySnapshot(List<HolderSnapshot> holders);

    /** リソースを解放する。 */
    void close();
}

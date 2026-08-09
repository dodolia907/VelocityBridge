package io.velocitybridge.permission;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 権限変更のネットワーク配信を統括する。
 *
 * <p>権限は頻繁に変更されないため、フル状態は送らず「変更が起きた時点の差分」だけを
 * プロキシ間で配信する。ローカルの権限変更イベントをそのままハブへ送り、他プロキシから
 * 届いた差分をローカルに適用する。</p>
 *
 * <p>適用に起因して発火するローカルイベントが再配信される（配信ループ）のを防ぐため、
 * 直近に適用した変更のシグネチャを一定時間保持する。スナップショット適用時は全イベントを
 * 一時的に抑制する。</p>
 */
public final class PermissionSync {

    /** 適用直後の変更を「再配信しない」と判定する保持時間。 */
    private static final long RECENT_TTL_MS = 10_000L;

    private final PermissionBackend backend;
    private final Consumer<PermissionBackend.NodeChange> broadcaster;
    private final Map<String, Long> recentlyApplied = new ConcurrentHashMap<>();
    private final AtomicBoolean applyingSnapshot = new AtomicBoolean();
    private volatile long suppressUntil;
    private volatile boolean started;

    /**
     * @param backend     権限バックエンド（LuckPerms）
     * @param broadcaster 変更の差分を他プロキシへ配信する処理（例: コーディネータの送信メソッド）
     */
    public PermissionSync(PermissionBackend backend, Consumer<PermissionBackend.NodeChange> broadcaster) {
        this.backend = backend;
        this.broadcaster = broadcaster;
    }

    /** ローカルの権限変更イベントの購読を開始する。 */
    public void start() {
        if (started || !backend.isAvailable()) {
            return;
        }
        started = true;
        backend.subscribe(this::onLocalChange);
    }

    /** 他プロキシから権限変更の差分を受信した。ローカルに適用する。 */
    public void onRemoteChange(PermissionBackend.NodeChange change) {
        if (!backend.isAvailable()) {
            return;
        }
        markRecentlyApplied(change);
        backend.apply(change);
    }

    /**
     * フル状態のスナップショットを構築する（リーダーのみ）。
     *
     * @return 全権限保持者のスナップショット
     */
    public CompletableFuture<List<PermissionBackend.HolderSnapshot>> snapshot() {
        return backend.snapshot();
    }

    /**
     * 他プロキシから受信したフル状態をローカルに適用する（フォロワーのみ）。
     *
     * <p>適用に起因して発火するローカルイベントは、時間窓の間再配信を抑制する。</p>
     *
     * @param holders 適用するスナップショット
     */
    public void applySnapshot(List<PermissionBackend.HolderSnapshot> holders) {
        if (!backend.isAvailable()) {
            return;
        }
        long until = System.currentTimeMillis() + RECENT_TTL_MS;
        suppressUntil = until;
        applyingSnapshot.set(true);
        try {
            backend.applySnapshot(holders);
        } finally {
            applyingSnapshot.set(false);
        }
    }

    private void onLocalChange(PermissionBackend.NodeChange change) {
        if (applyingSnapshot.get() || System.currentTimeMillis() < suppressUntil) {
            return; // スナップショット適用由来のイベントは再配信しない
        }
        if (wasRecentlyApplied(change)) {
            return; // 他プロキシからの適用由来なので再配信しない
        }
        broadcaster.accept(change);
    }

    private boolean wasRecentlyApplied(PermissionBackend.NodeChange change) {
        prune();
        return recentlyApplied.containsKey(change.signature());
    }

    private void markRecentlyApplied(PermissionBackend.NodeChange change) {
        prune();
        recentlyApplied.put(change.signature(), System.currentTimeMillis());
    }

    private void prune() {
        long now = System.currentTimeMillis();
        recentlyApplied.entrySet().removeIf(e -> now - e.getValue() > RECENT_TTL_MS);
    }
}

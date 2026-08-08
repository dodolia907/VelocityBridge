package io.velocitybridge.hub;

/**
 * プロキシ間ハブ通信でやり取りされるメッセージ種別の定数。
 */
public final class MessageType {

    private MessageType() {
    }

    /** ハンドシェイク認証（接続確立時に交換）。 */
    public static final String AUTH = "AUTH";

    /** 認証成功の応答（リーダー→フォロワー）。 */
    public static final String AUTH_OK = "AUTH_OK";

    /** 生存確認。 */
    public static final String HEARTBEAT = "HEARTBEAT";

    /** プレイヤー参加イベント。 */
    public static final String PLAYER_JOIN = "PLAYER_JOIN";

    /** プレイヤー退出イベント。 */
    public static final String PLAYER_LEAVE = "PLAYER_LEAVE";

    /** 接続プロキシの全プレイヤー一覧（再同期時にフォロワー→リーダーへ送信）。 */
    public static final String PLAYER_LIST_FULL = "PLAYER_LIST_FULL";

    /** グローバルプレイヤー一覧の応答（リーダー→フォロワー）。 */
    public static final String GLOBAL_LIST_RESPONSE = "GLOBAL_LIST_RESPONSE";

    /** チャット配信（ネットワーク全体）。 */
    public static final String CHAT_MESSAGE = "CHAT_MESSAGE";

    /** プレイヤー転送の状態管理。 */
    public static final String TRANSFER_REQUEST = "TRANSFER_REQUEST";

    /** プレイヤー転送の応答。 */
    public static final String TRANSFER_RESPONSE = "TRANSFER_RESPONSE";

    /** プロキシ切断を通知し、その配下のプレイヤーをクリーンアップする。 */
    public static final String DISCONNECT_PROXY = "DISCONNECT_PROXY";
}

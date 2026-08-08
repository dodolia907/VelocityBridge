package io.velocitybridge.hub;

import com.google.gson.JsonObject;

/**
 * プロキシ間ハブ通信でやり取りされるメッセージ。
 *
 * <p>{@code type} は {@link MessageType} の定数文字列、{@code sender} は送信元ノードID、
 * {@code payload} は種別ごとの付加情報（JSON）を保持する。</p>
 *
 * @param type    メッセージ種別
 * @param sender  送信元ノードID
 * @param payload 付加情報
 */
public record Message(String type, String sender, JsonObject payload) {

    public static Message of(String type, String sender, JsonObject payload) {
        return new Message(type, sender, payload);
    }
}

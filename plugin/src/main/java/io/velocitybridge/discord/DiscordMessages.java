package io.velocitybridge.discord;

/**
 * Discord WebHook へ投稿するメッセージの整形を担う。
 */
public final class DiscordMessages {

    private DiscordMessages() {
    }

    /** チャットメッセージの投稿本文。 */
    public static String chat(String username, String message) {
        return "**" + username + "**: " + message;
    }

    /** 参加通知の投稿本文。 */
    public static String playerJoin(String username, String proxyId) {
        return ":green_circle: **" + username + "** joined (" + proxyId + ")";
    }

    /** 退出通知の投稿本文。 */
    public static String playerLeave(String username, String proxyId) {
        return ":red_circle: **" + username + "** left (" + proxyId + ")";
    }

    /** 転送成功の投稿本文。 */
    public static String transferSuccess(String username, String sourceProxyId, String targetProxyId) {
        return ":arrows_counterclockwise: **" + username + "** transferred "
                + sourceProxyId + " -> " + targetProxyId;
    }

    /** 転送失敗の投稿本文。 */
    public static String transferFailure(String username, String targetProxyId, String reason) {
        return ":warning: **" + username + "** transfer to " + targetProxyId + " failed: " + reason;
    }
}

package io.velocitybridge.hub;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * ハンドシェイク認証処理。
 *
 * <p>フォロワーは非公開シークレット（{@code forwarding.secret} を再利用）を共有している。
 * 認証時、フォロワーはランダムな nonce を生成し、{@code HMAC-SHA256(secret, nodeId + nonce)}
 * の MAC を添えて AUTH メッセージを送信する。リーダーは同一計算で検証し、一致すれば認証成功とする。</p>
 */
public final class AuthHandler {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final MessageCipher cipher;

    public AuthHandler(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.cipher = new MessageCipher(secret);
    }

    public MessageCipher getCipher() {
        return cipher;
    }

    /** ランダムな nonce（16バイト hex）を生成する。 */
    public static String generateNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * ノードIDとnonceに対するMACを計算する。
     *
     * @param nodeId ノードID
     * @param nonce  認証nonce
     * @return HMAC-SHA256 hex 文字列
     */
    public String computeMac(String nodeId, String nonce) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] data = (nodeId + nonce).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC", e);
        }
    }

    /**
     * 送信された MAC が期待値と一致するかを定数時間比較で検証する。
     *
     * @param nodeId ノードID
     * @param nonce  認証nonce
     * @param mac    受信したMAC
     * @return 一致すれば {@code true}
     */
    public boolean verify(String nodeId, String nonce, String mac) {
        String expected = computeMac(nodeId, nonce);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                mac.getBytes(StandardCharsets.UTF_8));
    }
}

package io.velocitybridge.hub;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * AES-256-GCM によるハブ通信フレームの暗号化 / 復号を行う。
 *
 * <p>共有シークレット（{@code forwarding.secret}）の SHA-256 ハッシュを 256bit 鍵とし、
 * 暗号化ごとに 12 バイトのランダム IV（nonce）を生成して {@code AES/GCM/NoPadding} で暗号化する。</p>
 * <p>暗号化フォーマット: [12バイト IV] + [暗号文 + 16バイト GCM タグ]</p>
 */
public final class MessageCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec keySpec;

    public MessageCipher(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Secret cannot be empty");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            this.keySpec = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize MessageCipher key", e);
        }
    }

    /**
     * 平文バイト列を AES-256-GCM で暗号化する。
     *
     * @param plaintext 平文データ
     * @return [12バイト IV] + [暗号文 + 16バイト GCM タグ]
     */
    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec);

            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] out = new byte[IV_LENGTH_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH_BYTES);
            System.arraycopy(ciphertext, 0, out, IV_LENGTH_BYTES, ciphertext.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * 暗号文バイト列を AES-256-GCM で復号・改ざん検証する。
     *
     * @param encryptedData [12バイト IV] + [暗号文 + 16バイト GCM タグ]
     * @return 復号された平文データ
     */
    public byte[] decrypt(byte[] encryptedData) {
        if (encryptedData.length < IV_LENGTH_BYTES + 16) {
            throw new IllegalArgumentException("Invalid encrypted payload: too short");
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(encryptedData, 0, iv, 0, IV_LENGTH_BYTES);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec);

            return cipher.doFinal(encryptedData, IV_LENGTH_BYTES, encryptedData.length - IV_LENGTH_BYTES);
        } catch (Exception e) {
            throw new IllegalArgumentException("Decryption or authentication failed", e);
        }
    }
}

package io.velocitybridge.hub;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link Message} のエンコード / デコード。
 *
 * <p>ワイヤ形式は「長さ(4バイト ビッグエンディアン) + JSON(UTF-8)」のフレーム。</p>
 */
public final class MessageCodec {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private MessageCodec() {
    }

    /**
     * DTO レコードを JsonObject に変換する。
     */
    public static JsonObject encodePayload(Object payloadDto) {
        return GSON.toJsonTree(payloadDto).getAsJsonObject();
    }

    /**
     * JsonObject を DTO レコードに変換する。
     */
    public static <T> T decodePayload(JsonObject payload, Class<T> clazz) {
        return GSON.fromJson(payload, clazz);
    }

    /**
     * メッセージをエンコードする。
     *
     * @param message 対象メッセージ
     * @return ワイヤ形式のバイト列
     */
    public static byte[] encode(Message message) {
        String json = GSON.toJson(message);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[4 + jsonBytes.length];
        out[0] = (byte) (jsonBytes.length >>> 24);
        out[1] = (byte) (jsonBytes.length >>> 16);
        out[2] = (byte) (jsonBytes.length >>> 8);
        out[3] = (byte) jsonBytes.length;
        System.arraycopy(jsonBytes, 0, out, 4, jsonBytes.length);
        return out;
    }

    /**
     * バイト列をデコードする。
     *
     * @param data ワイヤ形式のバイト列（1フレーム分）
     * @return デコードされたメッセージ
     */
    public static Message decode(byte[] data) {
        if (data.length < 4) {
            throw new IllegalArgumentException("Invalid frame: too short");
        }
        int length = ((data[0] & 0xFF) << 24)
                | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8)
                | (data[3] & 0xFF);
        if (data.length != 4 + length) {
            throw new IllegalArgumentException("Invalid frame: length mismatch");
        }
        String json = new String(data, 4, length, StandardCharsets.UTF_8);
        return GSON.fromJson(json, Message.class);
    }

    /**
     * メッセージを暗号化してエンコードする。
     *
     * @param message 対象メッセージ
     * @param cipher  暗号化器
     * @return ワイヤ形式のバイト列「長さ(4B) + [IV(12B) + 暗号文 + GCM Tag]」
     */
    public static byte[] encode(Message message, MessageCipher cipher) {
        if (cipher == null) {
            return encode(message);
        }
        String json = GSON.toJson(message);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBytes = cipher.encrypt(jsonBytes);
        byte[] out = new byte[4 + encryptedBytes.length];
        out[0] = (byte) (encryptedBytes.length >>> 24);
        out[1] = (byte) (encryptedBytes.length >>> 16);
        out[2] = (byte) (encryptedBytes.length >>> 8);
        out[3] = (byte) encryptedBytes.length;
        System.arraycopy(encryptedBytes, 0, out, 4, encryptedBytes.length);
        return out;
    }

    /**
     * 暗号化されたバイト列をデコードする。
     *
     * @param data   ワイヤ形式のバイト列
     * @param cipher 暗号化器
     * @return デコードされたメッセージ
     */
    public static Message decode(byte[] data, MessageCipher cipher) {
        if (cipher == null) {
            return decode(data);
        }
        if (data.length < 4) {
            throw new IllegalArgumentException("Invalid frame: too short");
        }
        int length = ((data[0] & 0xFF) << 24)
                | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8)
                | (data[3] & 0xFF);
        if (data.length != 4 + length) {
            throw new IllegalArgumentException("Invalid frame: length mismatch");
        }
        byte[] encryptedBytes = new byte[length];
        System.arraycopy(data, 4, encryptedBytes, 0, length);
        byte[] jsonBytes = cipher.decrypt(encryptedBytes);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        return GSON.fromJson(json, Message.class);
    }

    /**
     * ストリームへ1フレーム書き込む。
     *
     * @param out     出力ストリーム
     * @param message 対象メッセージ
     * @throws IOException 書き込み失敗時
     */
    public static void write(OutputStream out, Message message) throws IOException {
        write(out, message, null);
    }

    /**
     * ストリームへ暗号化された1フレームを書き込む。
     *
     * @param out     出力ストリーム
     * @param message 対象メッセージ
     * @param cipher  暗号化器（null の場合は平文）
     * @throws IOException 書き込み失敗時
     */
    public static void write(OutputStream out, Message message, MessageCipher cipher) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        byte[] data = cipher != null ? encode(message, cipher) : encode(message);
        dos.write(data);
        dos.flush();
    }

    /**
     * ストリームから1フレーム読み込む。
     *
     * @param in 入力ストリーム
     * @return 読み込んだメッセージ（ストリーム終端の場合は {@code null}）
     * @throws IOException 読み込み失敗時
     */
    public static Message read(InputStream in) throws IOException {
        return read(in, null);
    }

    /**
     * ストリームから暗号化された1フレームを読み込む。
     *
     * @param in     入力ストリーム
     * @param cipher 暗号化器（null の場合は平文）
     * @return 読み込んだメッセージ（ストリーム終端の場合は {@code null}）
     * @throws IOException 読み込み失敗時
     */
    public static Message read(InputStream in, MessageCipher cipher) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int length;
        try {
            length = dis.readInt();
        } catch (IOException e) {
            return null;
        }
        if (length < 0 || length > 64 * 1024 * 1024) {
            throw new IOException("Invalid frame length: " + length);
        }
        byte[] payload = new byte[length];
        dis.readFully(payload);
        if (cipher != null) {
            try {
                byte[] jsonBytes = cipher.decrypt(payload);
                String json = new String(jsonBytes, StandardCharsets.UTF_8);
                return GSON.fromJson(json, Message.class);
            } catch (Exception e) {
                throw new IOException("Failed to decrypt or authenticate frame", e);
            }
        } else {
            String json = new String(payload, StandardCharsets.UTF_8);
            return GSON.fromJson(json, Message.class);
        }
    }

    /** 空のペイロードを生成する。 */
    public static JsonObject emptyPayload() {
        return new JsonObject();
    }
}

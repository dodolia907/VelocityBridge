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
     * ストリームへ1フレーム書き込む。
     *
     * @param out     出力ストリーム
     * @param message 対象メッセージ
     * @throws IOException 書き込み失敗時
     */
    public static void write(OutputStream out, Message message) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        byte[] data = encode(message);
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
        DataInputStream dis = new DataInputStream(in);
        int length = dis.readInt();
        if (length < 0 || length > 64 * 1024 * 1024) {
            throw new IOException("Invalid frame length: " + length);
        }
        byte[] jsonBytes = new byte[length];
        dis.readFully(jsonBytes);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        return GSON.fromJson(json, Message.class);
    }

    /** 空のペイロードを生成する。 */
    public static JsonObject emptyPayload() {
        return new JsonObject();
    }
}

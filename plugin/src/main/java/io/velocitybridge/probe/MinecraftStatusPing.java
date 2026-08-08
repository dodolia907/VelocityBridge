package io.velocitybridge.probe;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minecraft サーバーリスト ping プロトコルによるラウンドトリップ時間 (RTT) の計測。
 *
 * <p>Velocity はサーバーリスト ping（status request）に応答するため、ハンドシェイク →
 * status request → ping/pong のやり取りを行い、ping 送信から pong 受信までの時間を
 * ミリ秒で返す。接続失敗・タイムアウトは {@link IOException} として通知される。</p>
 */
public final class MinecraftStatusPing {

    /** サーバーリスト ping 用のプロトコルバージョン（判定には使われない）。 */
    private static final int PROTOCOL_VERSION = -1;
    private static final int MAX_FRAME_SIZE = 1_048_576;

    private MinecraftStatusPing() {
    }

    /**
     * 指定アドレスへ status ping を送り、RTT をミリ秒で返す。
     *
     * @param address   計測対象（host:port）
     * @param timeoutMs 接続・応答タイムアウト（ミリ秒）
     * @return RTT（ミリ秒）
     * @throws IOException 接続失敗・タイムアウト・プロトコルエラー時
     */
    public static long measureRtt(InetSocketAddress address, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(address, timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            writePacket(out, handshake(address));
            writePacket(out, statusRequest());
            out.flush();
            readPacket(in);

            long start = System.nanoTime();
            writePacket(out, pingPacket());
            out.flush();
            readPacket(in);

            return (System.nanoTime() - start) / 1_000_000L;
        }
    }

    private static byte[] handshake(InetSocketAddress address) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        try {
            writeVarInt(out, 0x00);
            writeVarInt(out, PROTOCOL_VERSION);
            writeString(out, address.getHostString());
            out.writeShort(address.getPort());
            writeVarInt(out, 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buf.toByteArray();
    }

    private static byte[] statusRequest() {
        return new byte[]{0x00};
    }

    private static byte[] pingPacket() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        try {
            writeVarInt(out, 0x01);
            out.writeLong(0L);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buf.toByteArray();
    }

    private static void writePacket(DataOutputStream out, byte[] payload) throws IOException {
        writeVarInt(out, payload.length);
        out.write(payload);
    }

    private static void readPacket(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > MAX_FRAME_SIZE) {
            throw new IOException("Invalid packet length: " + length);
        }
        byte[] data = new byte[length];
        in.readFully(data);
        if (data.length == 0) {
            throw new IOException("Empty packet");
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int bits = 0;
        while (true) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << bits;
            if ((b & 0x80) == 0) {
                return value;
            }
            bits += 7;
            if (bits >= 35) {
                throw new IOException("VarInt is too big");
            }
        }
    }
}

package io.velocitybridge.probe;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * テスト用の疑似 Minecraft ステータス ping サーバ。
 *
 * <p>ハンドシェイク → status request に応答し、ping/pong を返す。TCP で受け付けるので
 * {@link MinecraftStatusPing} の計測対象として利用できる。</p>
 */
final class StatusServer implements AutoCloseable {

    private static final byte[] STATUS_JSON = (
            "{\"version\":{\"name\":\"test\",\"protocol\":-1},"
                    + "\"players\":{\"max\":10,\"online\":0},\"description\":\"test\"}")
            .getBytes(StandardCharsets.UTF_8);

    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean stop = new AtomicBoolean();

    StatusServer() throws IOException {
        serverSocket = new ServerSocket(0);
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    void start() {
        executor.execute(() -> {
            while (!stop.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    executor.execute(() -> handle(socket));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    private void handle(Socket socket) {
        try (socket;
             DataInputStream in = new DataInputStream(socket.getInputStream());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
            readPacket(in);
            readPacket(in);

            ByteArrayOutputStream status = new ByteArrayOutputStream();
            DataOutputStream tmp = new DataOutputStream(status);
            MinecraftStatusPing.writeVarInt(tmp, 0x00);
            MinecraftStatusPing.writeVarInt(tmp, STATUS_JSON.length);
            tmp.write(STATUS_JSON);
            writePacket(out, status.toByteArray());
            out.flush();

            readPacket(in);

            ByteArrayOutputStream pong = new ByteArrayOutputStream();
            DataOutputStream ptmp = new DataOutputStream(pong);
            MinecraftStatusPing.writeVarInt(ptmp, 0x01);
            ptmp.writeLong(0L);
            writePacket(out, pong.toByteArray());
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private static byte[] readPacket(DataInputStream in) throws IOException {
        int length = MinecraftStatusPing.readVarInt(in);
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    private static void writePacket(DataOutputStream out, byte[] payload) throws IOException {
        MinecraftStatusPing.writeVarInt(out, payload.length);
        out.write(payload);
    }

    @Override
    public void close() throws IOException {
        stop.set(true);
        executor.shutdownNow();
        serverSocket.close();
    }
}

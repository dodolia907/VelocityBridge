package io.velocitybridge.hub;

import com.google.gson.JsonObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * フォロワー側のリーダーへの接続クライアント。
 *
 * <p>リーダーへ常時接続を張り、ハンドシェイク認証後にメッセージを送受信する。
 * 切断時は指数バックオフで自動再接続する。</p>
 */
public final class HubClient implements Closeable {

    /** 接続状態の変化やメッセージ受信を通知するハンドラ。 */
    public interface Handler {
        /**
         * 認証成功・接続確立時に呼ばれる。
         *
         * @param serverNodeId リーダーのノードID
         */
        void onConnected(String serverNodeId);

        /**
         * メッセージ受信時に呼ばれる。
         *
         * @param message 受信メッセージ
         */
        void onMessage(Message message);

        /**
         * 接続が切断されたときに呼ばれる。
         */
        void onDisconnected();
    }

    private final String nodeId;
    private final InetSocketAddress leaderAddress;
    private final AuthHandler auth;
    private final Handler handler;
    private final long heartbeatIntervalMs;
    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService heartbeatScheduler;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Socket socket;
    private volatile OutputStream output;
    private volatile String serverNodeId;
    private volatile boolean connected;

    public HubClient(String nodeId, InetSocketAddress leaderAddress, String secret, Handler handler) {
        this(nodeId, leaderAddress, secret, handler, 10_000L);
    }

    public HubClient(String nodeId, InetSocketAddress leaderAddress, String secret, Handler handler,
                     long heartbeatIntervalMs) {
        this.nodeId = nodeId;
        this.leaderAddress = leaderAddress;
        this.auth = new AuthHandler(secret);
        this.handler = handler;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "velocitybridge-hub-client");
            t.setDaemon(true);
            return t;
        });
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "velocitybridge-hub-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    /** 接続試行を開始する（非同期）。 */
    public void start() {
        heartbeatScheduler.scheduleWithFixedDelay(this::sendHeartbeat,
                heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        scheduler.execute(this::connectLoop);
    }

    private void sendHeartbeat() {
        if (connected) {
            send(Message.of(MessageType.HEARTBEAT, nodeId, MessageCodec.emptyPayload()));
        }
    }

    /**
     * メッセージをリーダーへ送信する。
     *
     * <p>ハートビートスレッドやプラグインのイベントスレッドから並行して呼ばれるため、
     * ソケットへの書き込みは同期化する。</p>
     *
     * @param message 送信メッセージ
     * @return 送信に成功すれば {@code true}
     */
    public synchronized boolean send(Message message) {
        OutputStream out = this.output;
        if (out == null || !connected) {
            return false;
        }
        try {
            MessageCodec.write(out, message);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** リーダーへ接続中か。 */
    public boolean isConnected() {
        return connected;
    }

    /** リーダーのノードID（未接続なら {@code null}）。 */
    public String getServerNodeId() {
        return serverNodeId;
    }

    private void connectLoop() {
        long backoff = 1_000L;
        while (!closed.get()) {
            boolean wasConnected = connectOnce();
            // 切断直後は即再接続を試み、失敗時はバックオフを適用する
            if (wasConnected) {
                backoff = 1_000L;
            } else {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, 30_000L);
            }
        }
    }

    private boolean connectOnce() {
        Socket newSocket = null;
        try {
            newSocket = new Socket();
            newSocket.setTcpNoDelay(true);
            newSocket.connect(leaderAddress, 5_000);
            InputStream input = newSocket.getInputStream();
            OutputStream out = newSocket.getOutputStream();
            this.socket = newSocket;
            this.output = out;

            // ハンドシェイク認証
            String nonce = AuthHandler.generateNonce();
            JsonObject authPayload = new JsonObject();
            authPayload.addProperty("nodeId", nodeId);
            authPayload.addProperty("nonce", nonce);
            authPayload.addProperty("mac", auth.computeMac(nodeId, nonce));
            MessageCodec.write(out, Message.of(MessageType.AUTH, nodeId, authPayload));

            Message authResponse = MessageCodec.read(input);
            if (authResponse == null || !MessageType.AUTH_OK.equals(authResponse.type())) {
                newSocket.close();
                return false;
            }
            this.serverNodeId = authResponse.payload().get("serverNodeId").getAsString();
            this.connected = true;
            handler.onConnected(serverNodeId);

            // 読み取りループ
            while (!closed.get()) {
                Message message = MessageCodec.read(input);
                if (message == null) {
                    break;
                }
                handler.onMessage(message);
            }
            return true;
        } catch (IOException e) {            if (newSocket != null) {
                try {
                    newSocket.close();
                } catch (IOException ignored) {
                }
            }
            return false;
        } catch (Exception e) {
            if (newSocket != null) {
                try {
                    newSocket.close();
                } catch (IOException ignored) {
                }
            }
            return false;
        } finally {
            if (connected) {
                connected = false;
                handler.onDisconnected();
            }
            Socket s = this.socket;
            if (s != null && s != newSocket) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            this.output = null;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        scheduler.shutdownNow();
        heartbeatScheduler.shutdownNow();
    }
}

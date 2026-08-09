package io.velocitybridge.hub;

import com.google.gson.JsonObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * リーダー側のメッセージハブ。
 *
 * <p>フォロワーからの TCP 接続を受け付け、ハンドシェイク認証（{@link AuthHandler}）後に
 * メッセージを中継・転送する。接続ごとに読み取りスレッドを割り当て、ハートビートの
 * タイムアウトで切断を検知する。</p>
 */
public final class HubServer implements Closeable {

    /** 接続ごとのイベントハンドラ。 */
    public interface Handler {
        /**
         * 認証成功時に呼ばれる。
         *
         * @param nodeId 認証済みノードID
         */
        void onAuthenticated(String nodeId);

        /**
         * メッセージ受信時に呼ばれる。
         *
         * @param nodeId 送信元ノードID
         * @param message 受信メッセージ
         */
        void onMessage(String nodeId, Message message);

        /**
         * ノードの切断時に呼ばれる。
         *
         * @param nodeId 切断されたノードID
         */
        void onDisconnect(String nodeId);
    }

    private final Handler handler;
    private final AuthHandler auth;
    private final String serverNodeId;
    private final long heartbeatTimeoutMs;
    private final ExecutorService readExecutor;
    private final ScheduledExecutorService monitorExecutor;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    public HubServer(Handler handler, AuthHandler auth, String serverNodeId) {
        this(handler, auth, serverNodeId, 30_000L);
    }

    public HubServer(Handler handler, AuthHandler auth, String serverNodeId, long heartbeatTimeoutMs) {
        this.handler = handler;
        this.auth = auth;
        this.serverNodeId = serverNodeId;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
        this.readExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "velocitybridge-hub-reader");
            t.setDaemon(true);
            return t;
        });
        this.monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "velocitybridge-hub-monitor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * サーバを起動して待ち受けを開始する。
     *
     * @param port 待ち受けポート
     * @throws IOException 起動失敗時
     */
    public void start(int port) throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        Thread acceptThread = new Thread(this::acceptLoop, "velocitybridge-hub-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        monitorExecutor.scheduleWithFixedDelay(this::monitor, 1, 1, TimeUnit.SECONDS);
    }

    /** 待ち受けポートを返す。 */
    public int getPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    /** 接続中のノードID一覧を返す。 */
    public List<String> connectedNodes() {
        return List.copyOf(connections.keySet());
    }

    /**
     * 指定ノード以外の全ノード（フォロワー + リーダー自身）へメッセージを転送する。
     *
     * @param message 転送メッセージ
     * @param exclude 除外ノードID（送信元など）
     */
    public void broadcast(Message message, String exclude) {
        for (Map.Entry<String, Connection> e : connections.entrySet()) {
            if (e.getKey().equals(exclude)) {
                continue;
            }
            Connection connection = e.getValue();
            try {
                connection.write(message);
            } catch (IOException ex) {
                connection.close();
            }
        }
    }

    /**
     * 指定ノードへメッセージを送信する。
     *
     * @param nodeId  宛先ノードID
     * @param message 送信メッセージ
     * @return 送信に成功すれば {@code true}
     */
    public boolean sendTo(String nodeId, Message message) {
        Connection connection = connections.get(nodeId);
        if (connection == null) {
            return false;
        }
        try {
            connection.write(message);
            return true;
        } catch (IOException e) {
            connection.close();
            return false;
        }
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket socket = serverSocket.accept();
                readExecutor.execute(() -> handleConnection(socket));
            } catch (IOException e) {
                if (!closed.get()) {
                    // 一時的な受付エラー。少し待って再試行。
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        String nodeId = null;
        try {
            socket.setTcpNoDelay(true);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            Message authMessage = MessageCodec.read(input, auth.getCipher());
            if (authMessage == null || !MessageType.AUTH.equals(authMessage.type())) {
                socket.close();
                return;
            }
            JsonObject payload = authMessage.payload();
            nodeId = payload.get("nodeId").getAsString();
            String nonce = payload.get("nonce").getAsString();
            String mac = payload.get("mac").getAsString();
            if (!auth.verify(nodeId, nonce, mac)) {
                socket.close();
                return;
            }

            Connection connection = new Connection(nodeId, socket, input, output, auth.getCipher());
            Connection previous = connections.put(nodeId, connection);
            if (previous != null) {
                previous.close();
            }

            // 認証応答 + リーダーのノードIDを通知
            JsonObject ok = new JsonObject();
            ok.addProperty("serverNodeId", serverNodeId);
            connection.write(Message.of(MessageType.AUTH_OK, serverNodeId, ok));

            handler.onAuthenticated(nodeId);

            while (!closed.get()) {
                Message message = MessageCodec.read(input, auth.getCipher());
                if (message == null) {
                    break;
                }
                connection.lastRead = System.currentTimeMillis();
                handler.onMessage(nodeId, message);
            }
        } catch (IOException e) {
            // 接続断は正常系として扱う
        } catch (Exception e) {
            // デコード失敗など。接続を閉じて続行。
        } finally {
            if (nodeId != null) {
                Connection removed = connections.remove(nodeId);
                if (removed != null) {
                    removed.close();
                    handler.onDisconnect(nodeId);
                }
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void monitor() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Connection> e : connections.entrySet()) {
            if (now - e.getValue().lastRead > heartbeatTimeoutMs) {
                e.getValue().close();
                connections.remove(e.getKey());
                handler.onDisconnect(e.getKey());
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        for (Connection connection : connections.values()) {
            connection.close();
        }
        connections.clear();
        readExecutor.shutdownNow();
        monitorExecutor.shutdownNow();
    }

    private static final class Connection {
        private final String nodeId;
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;
        private final MessageCipher cipher;
        private volatile long lastRead;

        private Connection(String nodeId, Socket socket, InputStream input, OutputStream output, MessageCipher cipher) {
            this.nodeId = nodeId;
            this.socket = socket;
            this.input = input;
            this.output = output;
            this.cipher = cipher;
            this.lastRead = System.currentTimeMillis();
        }

        /** この接続へ1フレーム書き込む。複数スレッドからの書き込みを直列化する。 */
        private synchronized void write(Message message) throws IOException {
            MessageCodec.write(output, message, cipher);
        }

        private void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}

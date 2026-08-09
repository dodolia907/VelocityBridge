package io.velocitybridge.ha;

import com.google.gson.JsonObject;
import io.velocitybridge.config.VelocityBridgeConfig;
import io.velocitybridge.hub.Message;
import io.velocitybridge.hub.MessageCipher;
import io.velocitybridge.hub.MessageCodec;
import io.velocitybridge.hub.MessageType;
import io.velocitybridge.hub.payload.Payloads;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Raft の選挙における通信 (REQUEST_VOTE) を担当するクラス。
 * BridgeCoordinator から分離された通信処理。
 */
public class RaftCommunicator {

    private static final Logger logger = LoggerFactory.getLogger(RaftCommunicator.class);

    private final String nodeId;
    private final VelocityBridgeConfig config;
    private final ExecutorService executor;
    private volatile java.net.ServerSocket serverSocket;
    private final RaftNode raftNode;

    public RaftCommunicator(String nodeId, VelocityBridgeConfig config, RaftNode raftNode) {
        this.nodeId = nodeId;
        this.config = config;
        this.raftNode = raftNode;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "raft-communicator");
            t.setDaemon(true);
            return t;
        });
    }

    public void startListening(int port) {
        executor.execute(() -> {
            try {
                serverSocket = new java.net.ServerSocket(port);
                logger.info("RaftCommunicator listening for votes on port {}", port);
                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    executor.execute(() -> handleConnection(socket));
                }
            } catch (Exception e) {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    logger.error("Unexpected error in RaftCommunicator listener socket", e);
                }
            }
        });
    }

    private void handleConnection(Socket s) {
        try (s) {
            s.setSoTimeout(5000);
            MessageCipher cipher = new MessageCipher(config.secret());
            Message msg = MessageCodec.read(s.getInputStream(), cipher);
            if (msg != null && MessageType.REQUEST_VOTE.equals(msg.type())) {
                Payloads.RequestVote voteReq = MessageCodec.decodePayload(msg.payload(), Payloads.RequestVote.class);
                logger.debug("Received vote request from {}", voteReq.candidateId());
                Payloads.RequestVoteResponse resp = raftNode.handleRequestVote(voteReq);
                JsonObject payload = MessageCodec.encodePayload(resp);
                Message respMsg = Message.of(MessageType.REQUEST_VOTE_RESPONSE, nodeId, payload);
                MessageCodec.write(s.getOutputStream(), respMsg, cipher);
            }
        } catch (Exception e) {
            logger.debug("Error handling Raft connection", e);
        }
    }

    public void sendVoteRequest(String targetProxyId, Payloads.RequestVote voteReq) {
        VelocityBridgeConfig.ProxyInfo targetInfo = config.proxies().stream()
                .filter(p -> p.id().equals(targetProxyId))
                .findFirst().orElse(null);
        if (targetInfo == null) {
            return;
        }

        executor.execute(() -> {
            try (Socket s = new Socket()) {
                String[] parts = targetInfo.address().split(":");
                String host = parts[0];
                int basePort = parts.length > 1 ? Integer.parseInt(parts[1]) : config.hubPort();
                int raftPort = basePort + 1000;
                
                s.connect(new InetSocketAddress(host, raftPort), 2000);
                s.setTcpNoDelay(true);
                MessageCipher cipher = new MessageCipher(config.secret());

                JsonObject payload = MessageCodec.encodePayload(voteReq);
                Message msg = Message.of(MessageType.REQUEST_VOTE, nodeId, payload);
                MessageCodec.write(s.getOutputStream(), msg, cipher);

                Message respMsg = MessageCodec.read(s.getInputStream(), cipher);
                if (respMsg != null && MessageType.REQUEST_VOTE_RESPONSE.equals(respMsg.type())) {
                    Payloads.RequestVoteResponse resp = MessageCodec.decodePayload(respMsg.payload(), Payloads.RequestVoteResponse.class);
                    if (raftNode != null) {
                        raftNode.handleVoteResponse(resp);
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to request vote from {} at {}: {}", targetProxyId, targetInfo.address(), e.getMessage());
            }
        });
    }

    public void shutdown() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

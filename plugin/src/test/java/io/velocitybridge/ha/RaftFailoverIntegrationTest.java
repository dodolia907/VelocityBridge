package io.velocitybridge.ha;

import io.velocitybridge.config.VelocityBridgeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaftFailoverIntegrationTest {

    @Test
    void initialPreferredLeaderIsActiveAndFollowersStartInRaftMode() {
        VelocityBridgeConfig.ProxyInfo p1 = new VelocityBridgeConfig.ProxyInfo("proxy-1", "127.0.0.1:25565", "Local");
        VelocityBridgeConfig.ProxyInfo p2 = new VelocityBridgeConfig.ProxyInfo("proxy-2", "127.0.0.1:25566", "Local");
        VelocityBridgeConfig.ProxyInfo p3 = new VelocityBridgeConfig.ProxyInfo("proxy-3", "127.0.0.1:25567", "Local");

        VelocityBridgeConfig.AutoFailoverConfig haConfig = new VelocityBridgeConfig.AutoFailoverConfig(true, 3000L);

        VelocityBridgeConfig cfg1 = new VelocityBridgeConfig("proxy-1", "leader", "", 51850, "secret", List.of(p1, p2, p3),
                null, VelocityBridgeConfig.ChatConfig.DEFAULT, haConfig);
        VelocityBridgeConfig cfg2 = new VelocityBridgeConfig("proxy-2", "follower", "127.0.0.1:51850", 51851, "secret", List.of(p1, p2, p3),
                null, VelocityBridgeConfig.ChatConfig.DEFAULT, haConfig);
        VelocityBridgeConfig cfg3 = new VelocityBridgeConfig("proxy-3", "follower", "127.0.0.1:51850", 51852, "secret", List.of(p1, p2, p3),
                null, VelocityBridgeConfig.ChatConfig.DEFAULT, haConfig);

        RaftNode node1 = new RaftNode(cfg1.nodeId(), List.of("proxy-1", "proxy-2", "proxy-3"), true, 3000L, new RaftNode.RaftListener() {
            @Override public void onPromotedToLeader(long term) {}
            @Override public void onDemotedToFollower(long term, String leaderId) {}
            @Override public void sendRequestVote(String targetNodeId, io.velocitybridge.hub.payload.Payloads.RequestVote vote) {}
        });

        RaftNode node2 = new RaftNode(cfg2.nodeId(), List.of("proxy-1", "proxy-2", "proxy-3"), false, 3000L, new RaftNode.RaftListener() {
            @Override public void onPromotedToLeader(long term) {}
            @Override public void onDemotedToFollower(long term, String leaderId) {}
            @Override public void sendRequestVote(String targetNodeId, io.velocitybridge.hub.payload.Payloads.RequestVote vote) {}
        });

        RaftNode node3 = new RaftNode(cfg3.nodeId(), List.of("proxy-3", "proxy-2", "proxy-3"), false, 3000L, new RaftNode.RaftListener() {
            @Override public void onPromotedToLeader(long term) {}
            @Override public void onDemotedToFollower(long term, String leaderId) {}
            @Override public void sendRequestVote(String targetNodeId, io.velocitybridge.hub.payload.Payloads.RequestVote vote) {}
        });

        node1.start();
        node2.start();
        node3.start();

        assertEquals(RaftNode.State.LEADER, node1.getState());
        assertEquals(RaftNode.State.FOLLOWER, node2.getState());
        assertEquals(RaftNode.State.FOLLOWER, node3.getState());

        node1.stop();
        node2.stop();
        node3.stop();
    }
}

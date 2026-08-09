package io.velocitybridge.hub.payload;

import java.util.List;
import java.util.UUID;
import java.util.Set;
import io.velocitybridge.permission.PermissionSync;

public interface Payloads {
    record PlayerJoin(UUID uuid, String username, String proxyId) {}
    record PlayerLeave(UUID uuid, String username, String proxyId) {}
    record PlayerListFull(List<PlayerJoin> players) {}
    record GlobalListResponse(String serverNodeId, List<PlayerJoin> players) {}
    record ChatMessage(String username, String message, String converted) {}
    record TransferRequest(String targetProxy, UUID uuid, String username, long timestamp) {}
    record TransferResponse(UUID uuid, boolean success, String reason, String server) {}
    record PermissionVersionRequest(long appliedVersion) {}
    record PermissionVersionResponse(long version) {}
    record RequestVote(long term, String candidateId, boolean isPreferredLeader) {}
    record RequestVoteResponse(long term, boolean voteGranted, String voterId) {}
}

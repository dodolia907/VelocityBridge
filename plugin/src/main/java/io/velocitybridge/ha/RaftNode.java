package io.velocitybridge.ha;

import io.velocitybridge.hub.payload.Payloads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Raft アルゴリズムに準拠した分散選出ノード。
 *
 * <p>3ノード以上の構成において、過半数 (Quorum) の同意によるリーダー自動選出を担う。
 * 平常時は設定上の固定リーダー（Preferred Leader）の投票権を尊重し、リーダー障害時は
 * ランダム化された election timeout により候補者 (Candidate) への昇格・投票要求を行う。</p>
 */
public final class RaftNode {

    private static final Logger logger = LoggerFactory.getLogger(RaftNode.class);

    public enum State {
        FOLLOWER,
        CANDIDATE,
        LEADER
    }

    public interface RaftListener {
        void onPromotedToLeader(long term);
        void onDemotedToFollower(long term, String leaderId);
        void sendRequestVote(String targetNodeId, Payloads.RequestVote vote);
    }

    private final String nodeId;
    private final List<String> peerNodeIds;
    private final boolean preferredLeader;
    private final long baseElectionTimeoutMs;
    private final RaftListener listener;

    private final AtomicLong currentTerm = new AtomicLong(0);
    private volatile String votedFor = null;
    private volatile State state = State.FOLLOWER;
    private volatile String currentLeaderId = null;

    private final Map<String, Boolean> votesReceived = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "velocitybridge-raft");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> electionTimerTask;
    private volatile long lastHeartbeatTime = System.currentTimeMillis();

    public RaftNode(String nodeId, List<String> allNodeIds, boolean preferredLeader,
                    long baseElectionTimeoutMs, RaftListener listener) {
        this.nodeId = nodeId;
        this.peerNodeIds = allNodeIds.stream().filter(id -> !id.equals(nodeId)).toList();
        this.preferredLeader = preferredLeader;
        this.baseElectionTimeoutMs = baseElectionTimeoutMs;
        this.listener = listener;
    }

    public synchronized void start() {
        if (preferredLeader) {
            currentTerm.set(1);
            votedFor = nodeId;
            state = State.LEADER;
            currentLeaderId = nodeId;
            logger.info("RaftNode starting as preferred LEADER (node={}, term=1)", nodeId);
            listener.onPromotedToLeader(1);
        } else {
            state = State.FOLLOWER;
            resetElectionTimer();
        }
    }

    public synchronized void stop() {
        if (electionTimerTask != null) {
            electionTimerTask.cancel(true);
        }
        scheduler.shutdownNow();
    }

    public State getState() {
        return state;
    }

    public long getCurrentTerm() {
        return currentTerm.get();
    }

    public String getCurrentLeaderId() {
        return currentLeaderId;
    }

    public synchronized void onHeartbeatReceived(String leaderId, long term) {
        lastHeartbeatTime = System.currentTimeMillis();
        if (term >= currentTerm.get()) {
            if (term > currentTerm.get() || state != State.FOLLOWER) {
                currentTerm.set(term);
                votedFor = null;
                state = State.FOLLOWER;
                currentLeaderId = leaderId;
                logger.info("RaftNode transitioned to FOLLOWER (leader={}, term={})", leaderId, term);
                listener.onDemotedToFollower(term, leaderId);
            }
            resetElectionTimer();
        }
    }

    public synchronized Payloads.RequestVoteResponse handleRequestVote(Payloads.RequestVote vote) {
        long term = vote.term();
        String candidateId = vote.candidateId();

        if (term < currentTerm.get()) {
            return new Payloads.RequestVoteResponse(currentTerm.get(), false, nodeId);
        }

        if (term > currentTerm.get()) {
            currentTerm.set(term);
            votedFor = null;
            state = State.FOLLOWER;
            listener.onDemotedToFollower(term, candidateId);
        }

        boolean canVote = (votedFor == null || votedFor.equals(candidateId));
        if (preferredLeader && !candidateId.equals(nodeId) && vote.isPreferredLeader()) {
            canVote = true;
        }

        if (canVote) {
            votedFor = candidateId;
            resetElectionTimer();
            logger.info("RaftNode voted for Candidate {} in term {}", candidateId, term);
            return new Payloads.RequestVoteResponse(currentTerm.get(), true, nodeId);
        }

        return new Payloads.RequestVoteResponse(currentTerm.get(), false, nodeId);
    }

    public synchronized void handleVoteResponse(Payloads.RequestVoteResponse response) {
        if (state != State.CANDIDATE || response.term() != currentTerm.get()) {
            return;
        }

        if (response.voteGranted()) {
            votesReceived.put(response.voterId(), true);
            int votes = votesReceived.size();
            int totalClusterSize = peerNodeIds.size() + 1;
            int quorum = (totalClusterSize / 2) + 1;

            if (votes >= quorum) {
                state = State.LEADER;
                currentLeaderId = nodeId;
                if (electionTimerTask != null) {
                    electionTimerTask.cancel(true);
                }
                logger.info("RaftNode elected as LEADER (node={}, term={}, votes={}/{})",
                        nodeId, currentTerm.get(), votes, totalClusterSize);
                listener.onPromotedToLeader(currentTerm.get());
            }
        }
    }

    public synchronized void startElection() {
        int totalClusterSize = peerNodeIds.size() + 1;
        if (totalClusterSize < 3) {
            resetElectionTimer();
            return;
        }

        state = State.CANDIDATE;
        long term = currentTerm.incrementAndGet();
        votedFor = nodeId;
        votesReceived.clear();
        votesReceived.put(nodeId, true);
        currentLeaderId = null;

        logger.info("RaftNode starting election (node={}, term={}, peers={})", nodeId, term, peerNodeIds.size());
        resetElectionTimer();

        Payloads.RequestVote voteReq = new Payloads.RequestVote(term, nodeId, preferredLeader);
        for (String peer : peerNodeIds) {
            listener.sendRequestVote(peer, voteReq);
        }
    }

    private synchronized void resetElectionTimer() {
        if (electionTimerTask != null) {
            electionTimerTask.cancel(false);
        }
        if (state == State.LEADER) {
            return;
        }
        long jitter = ThreadLocalRandom.current().nextLong(150, 400);
        long timeout = baseElectionTimeoutMs + jitter;

        electionTimerTask = scheduler.schedule(() -> {
            synchronized (RaftNode.this) {
                long elapsed = System.currentTimeMillis() - lastHeartbeatTime;
                if (elapsed >= timeout) {
                    logger.warn("RaftNode election timeout triggered after {}ms", elapsed);
                    startElection();
                } else {
                    resetElectionTimer();
                }
            }
        }, timeout, TimeUnit.MILLISECONDS);
    }
}

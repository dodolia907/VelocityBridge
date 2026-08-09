package io.velocitybridge.ha;

import io.velocitybridge.hub.payload.Payloads;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaftNodeTest {

    @Test
    void startsAsLeaderWhenPreferredLeader() {
        List<String> promotions = new CopyOnWriteArrayList<>();
        RaftNode node = new RaftNode("proxy-1", List.of("proxy-1", "proxy-2", "proxy-3"), true, 5000L, new RaftNode.RaftListener() {
            @Override
            public void onPromotedToLeader(long term) {
                promotions.add("promoted-term-" + term);
            }

            @Override
            public void onDemotedToFollower(long term, String leaderId) {}

            @Override
            public void sendRequestVote(String targetNodeId, Payloads.RequestVote vote) {}
        });

        node.start();
        assertEquals(RaftNode.State.LEADER, node.getState());
        assertEquals(1, node.getCurrentTerm());
        assertEquals("proxy-1", node.getCurrentLeaderId());
        assertEquals(1, promotions.size());
        node.stop();
    }

    @Test
    void startsAsFollowerWhenNotPreferredLeader() {
        RaftNode node = new RaftNode("proxy-2", List.of("proxy-1", "proxy-2", "proxy-3"), false, 5000L, new RaftNode.RaftListener() {
            @Override
            public void onPromotedToLeader(long term) {}

            @Override
            public void onDemotedToFollower(long term, String leaderId) {}

            @Override
            public void sendRequestVote(String targetNodeId, Payloads.RequestVote vote) {}
        });

        node.start();
        assertEquals(RaftNode.State.FOLLOWER, node.getState());
        assertEquals(0, node.getCurrentTerm());
        node.stop();
    }

    @Test
    void grantsVoteAndPromotesOnQuorum() {
        List<Payloads.RequestVote> votesSent = new ArrayList<>();
        List<String> promotions = new CopyOnWriteArrayList<>();

        RaftNode candidate = new RaftNode("proxy-2", List.of("proxy-1", "proxy-2", "proxy-3"), false, 100L, new RaftNode.RaftListener() {
            @Override
            public void onPromotedToLeader(long term) {
                promotions.add("promoted-" + term);
            }

            @Override
            public void onDemotedToFollower(long term, String leaderId) {}

            @Override
            public void sendRequestVote(String targetNodeId, Payloads.RequestVote vote) {
                votesSent.add(vote);
            }
        });

        candidate.start();
        candidate.startElection(); // Transition to CANDIDATE with term 1
        assertEquals(RaftNode.State.CANDIDATE, candidate.getState());

        // Receive vote from proxy-3 (quorum = (3/2)+1 = 2, candidate has own vote + proxy-3)
        candidate.handleVoteResponse(new Payloads.RequestVoteResponse(1, true, "proxy-3"));

        // When vote granted by 1 peer, with own vote, candidate reaches quorum (2 out of 3)
        assertEquals(RaftNode.State.LEADER, candidate.getState());
        assertEquals("proxy-2", candidate.getCurrentLeaderId());
        candidate.stop();
    }

    @Test
    void votesForHigherTermCandidate() {
        RaftNode follower = new RaftNode("proxy-3", List.of("proxy-1", "proxy-2", "proxy-3"), false, 5000L, new RaftNode.RaftListener() {
            @Override
            public void onPromotedToLeader(long term) {}
            @Override
            public void onDemotedToFollower(long term, String leaderId) {}
            @Override
            public void sendRequestVote(String targetNodeId, Payloads.RequestVote vote) {}
        });

        follower.start();

        Payloads.RequestVoteResponse response = follower.handleRequestVote(new Payloads.RequestVote(2, "proxy-2", false));

        assertTrue(response.voteGranted());
        assertEquals(2, response.term());
        assertEquals("proxy-3", response.voterId());
        follower.stop();
    }
}

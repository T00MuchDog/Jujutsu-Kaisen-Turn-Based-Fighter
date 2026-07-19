package com.jjktbf.server.challenge;

import com.jjktbf.multiplayer.protocol.ChallengeAcceptRequest;
import com.jjktbf.multiplayer.protocol.ChallengeCreateRequest;
import com.jjktbf.multiplayer.protocol.ChallengeDecisionRequest;
import com.jjktbf.multiplayer.protocol.ChallengeStatus;
import com.jjktbf.multiplayer.protocol.ChallengeSummary;
import com.jjktbf.multiplayer.protocol.SessionIdentity;
import com.jjktbf.server.service.ServiceException;
import com.jjktbf.server.support.ServerTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeConcurrencyTest {
    private ServerTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new ServerTestFixture();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void concurrentRequestersProduceExactlyOnePendingRequest() throws Exception {
        String hostCharacter = fixture.catalog().characterSummaries().get(0).characterId();
        String guestCharacter = fixture.catalog().characterSummaries().get(1).characterId();
        SessionIdentity host = fixture.createGuest("Concurrent Host");
        SessionIdentity first = fixture.createGuest("Concurrent One");
        SessionIdentity second = fixture.createGuest("Concurrent Two");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(hostCharacter));

        ChallengeService firstService = newService();
        ChallengeService secondService = newService();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<JoinOutcome> firstResult = executor.submit(joinRequest(
                firstService, first, challenge.challengeId(), guestCharacter, ready, start));
            Future<JoinOutcome> secondResult = executor.submit(joinRequest(
                secondService, second, challenge.challengeId(), guestCharacter, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<JoinOutcome> outcomes = List.of(
                firstResult.get(10, TimeUnit.SECONDS),
                secondResult.get(10, TimeUnit.SECONDS)
            );
            List<JoinOutcome> successes = outcomes.stream()
                .filter(outcome -> outcome.summary() != null)
                .toList();
            List<JoinOutcome> failures = outcomes.stream()
                .filter(outcome -> outcome.errorCode() != null)
                .toList();

            assertEquals(1, successes.size());
            assertEquals(1, failures.size());
            assertNotNull(successes.get(0).summary().requestedPlayerId());
            assertNull(successes.get(0).errorCode());
            assertEquals("CHALLENGE_REQUEST_PENDING", failures.get(0).errorCode());
            assertEquals(0, count("match_record"));
            assertEquals(ChallengeStatus.OPEN,
                fixture.challengeService().getChallenge(challenge.challengeId()).status());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentHostAcceptRetriesCreateExactlyOneMatch() throws Exception {
        String hostCharacter = fixture.catalog().characterSummaries().get(0).characterId();
        String guestCharacter = fixture.catalog().characterSummaries().get(1).characterId();
        SessionIdentity host = fixture.createGuest("Accept Host");
        SessionIdentity requester = fixture.createGuest("Accept Requester");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(hostCharacter));
        ChallengeSummary pending = fixture.challengeService().requestJoin(
            requester,
            challenge.challengeId(),
            ChallengeAcceptRequest.standard(guestCharacter)
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AcceptedMatchSetup> first = executor.submit(accept(
                newService(), host, challenge.challengeId(), pending, ready, start));
            Future<AcceptedMatchSetup> second = executor.submit(accept(
                newService(), host, challenge.challengeId(), pending, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            AcceptedMatchSetup firstSetup = first.get(10, TimeUnit.SECONDS);
            AcceptedMatchSetup secondSetup = second.get(10, TimeUnit.SECONDS);
            assertEquals(firstSetup, secondSetup);
            assertEquals(1, count("match_record"));
            assertEquals(2, count("match_participant"));
            assertEquals(ChallengeStatus.ACCEPTED,
                fixture.challengeService().getChallenge(challenge.challengeId()).status());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentAcceptanceAndWithdrawalHaveOneConsistentWinner() throws Exception {
        String hostCharacter = fixture.catalog().characterSummaries().get(0).characterId();
        String guestCharacter = fixture.catalog().characterSummaries().get(1).characterId();
        SessionIdentity host = fixture.createGuest("Decision Race Host");
        SessionIdentity requester = fixture.createGuest("Decision Race Requester");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(hostCharacter));
        ChallengeSummary pending = fixture.challengeService().requestJoin(
            requester,
            challenge.challengeId(),
            ChallengeAcceptRequest.standard(guestCharacter)
        );
        ChallengeDecisionRequest decision = ChallengeDecisionRequest.forChallenge(pending);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> accept = executor.submit(() -> raceOperation(ready, start, () ->
                newService().acceptChallenge(host, challenge.challengeId(), decision), "ACCEPTED"));
            Future<String> withdraw = executor.submit(() -> raceOperation(ready, start, () ->
                newService().withdrawJoinRequest(
                    requester, challenge.challengeId(), decision), "WITHDRAWN"));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<String> outcomes = List.of(
                accept.get(10, TimeUnit.SECONDS),
                withdraw.get(10, TimeUnit.SECONDS)
            );
            assertEquals(1, outcomes.stream().filter(
                outcome -> outcome.equals("ACCEPTED") || outcome.equals("WITHDRAWN")).count());

            ChallengeSummary current = fixture.challengeService()
                .getChallenge(challenge.challengeId());
            if (outcomes.contains("ACCEPTED")) {
                assertEquals(ChallengeStatus.ACCEPTED, current.status());
                assertEquals(1, count("match_record"));
            } else {
                assertEquals(ChallengeStatus.OPEN, current.status());
                assertNull(current.joinRequestId());
                assertEquals(0, count("match_record"));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private ChallengeService newService() {
        return new ChallengeService(
            fixture.database(), fixture.config(), fixture.catalog(),
            fixture.clock(), new SecureRandom());
    }

    private static Callable<JoinOutcome> joinRequest(
        ChallengeService service,
        SessionIdentity requester,
        String challengeId,
        String characterId,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Join request start latch timed out");
            }
            try {
                return new JoinOutcome(service.requestJoin(
                    requester,
                    challengeId,
                    ChallengeAcceptRequest.standard(characterId)), null);
            } catch (ServiceException exception) {
                return new JoinOutcome(null, exception.code());
            }
        };
    }

    private static Callable<AcceptedMatchSetup> accept(
        ChallengeService service,
        SessionIdentity host,
        String challengeId,
        ChallengeSummary pending,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Acceptance start latch timed out");
            }
            return service.acceptChallenge(
                host, challengeId, ChallengeDecisionRequest.forChallenge(pending));
        };
    }

    private static String raceOperation(
        CountDownLatch ready,
        CountDownLatch start,
        Callable<?> operation,
        String success
    ) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Race start latch timed out");
        }
        try {
            operation.call();
            return success;
        } catch (ServiceException exception) {
            return "ERROR:" + exception.code();
        }
    }

    private int count(String table) {
        return fixture.database().withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table);
                 var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        });
    }

    private record JoinOutcome(ChallengeSummary summary, String errorCode) {
    }
}

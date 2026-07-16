package com.jjktbf.server.challenge;

import com.jjktbf.multiplayer.protocol.ChallengeAcceptRequest;
import com.jjktbf.multiplayer.protocol.ChallengeCreateRequest;
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
    void concurrentAcceptancesProduceExactlyOneSetupAndOneNonOpenError()
        throws Exception {
        String hostCharacter = fixture.catalog().characterSummaries().get(0).characterId();
        String guestCharacter = fixture.catalog().characterSummaries().get(1).characterId();
        SessionIdentity host = fixture.createGuest("Concurrent Host");
        SessionIdentity first = fixture.createGuest("Concurrent One");
        SessionIdentity second = fixture.createGuest("Concurrent Two");
        ChallengeSummary challenge = fixture.challengeService().createChallenge(
            host, ChallengeCreateRequest.standard(hostCharacter));

        ChallengeService firstService = new ChallengeService(
            fixture.database(), fixture.config(), fixture.catalog(),
            fixture.clock(), new SecureRandom());
        ChallengeService secondService = new ChallengeService(
            fixture.database(), fixture.config(), fixture.catalog(),
            fixture.clock(), new SecureRandom());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AcceptanceOutcome> firstResult = executor.submit(acceptance(
                firstService, first, challenge.challengeId(), guestCharacter, ready, start));
            Future<AcceptanceOutcome> secondResult = executor.submit(acceptance(
                secondService, second, challenge.challengeId(), guestCharacter, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<AcceptanceOutcome> outcomes = List.of(
                firstResult.get(10, TimeUnit.SECONDS),
                secondResult.get(10, TimeUnit.SECONDS)
            );
            List<AcceptanceOutcome> successes = outcomes.stream()
                .filter(outcome -> outcome.setup() != null)
                .toList();
            List<AcceptanceOutcome> failures = outcomes.stream()
                .filter(outcome -> outcome.errorCode() != null)
                .toList();

            assertEquals(1, successes.size());
            assertEquals(1, failures.size());
            assertNotNull(successes.get(0).setup());
            assertNull(successes.get(0).errorCode());
            assertEquals("CHALLENGE_ALREADY_ACCEPTED", failures.get(0).errorCode());
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

    private static Callable<AcceptanceOutcome> acceptance(
        ChallengeService service,
        SessionIdentity accepter,
        String challengeId,
        String characterId,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Acceptance start latch timed out");
            }
            try {
                return new AcceptanceOutcome(
                    service.acceptChallenge(
                        accepter,
                        challengeId,
                        ChallengeAcceptRequest.standard(characterId)),
                    null
                );
            } catch (ServiceException exception) {
                return new AcceptanceOutcome(null, exception.code());
            }
        };
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

    private record AcceptanceOutcome(AcceptedMatchSetup setup, String errorCode) {
    }
}

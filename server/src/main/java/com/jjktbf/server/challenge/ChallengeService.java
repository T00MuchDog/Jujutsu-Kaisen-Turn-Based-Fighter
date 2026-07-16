package com.jjktbf.server.challenge;

import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.multiplayer.protocol.ChallengeAcceptRequest;
import com.jjktbf.multiplayer.protocol.ChallengeCreateRequest;
import com.jjktbf.multiplayer.protocol.ChallengeListResponse;
import com.jjktbf.multiplayer.protocol.ChallengeStatus;
import com.jjktbf.multiplayer.protocol.ChallengeSummary;
import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import com.jjktbf.multiplayer.protocol.ProtocolVersion;
import com.jjktbf.multiplayer.protocol.SessionIdentity;
import com.jjktbf.server.config.ServerConfig;
import com.jjktbf.server.content.ContentCatalog;
import com.jjktbf.server.db.Database;
import com.jjktbf.server.service.ServiceErrorCode;
import com.jjktbf.server.service.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.security.SecureRandom;

/** Validates and persists the public challenge lifecycle. */
public final class ChallengeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChallengeService.class);

    private final Database database;
    private final ContentCatalog catalog;
    private final ChallengeRepository challengeRepository;
    private final MatchRepository matchRepository;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final int expiryMinutes;
    private final int maxOpenChallenges;

    public ChallengeService(
        Database database,
        ServerConfig config,
        ContentCatalog catalog
    ) {
        this(database, config, catalog, Clock.systemUTC(), new SecureRandom());
    }

    public ChallengeService(
        Database database,
        ServerConfig config,
        ContentCatalog catalog,
        Clock clock,
        SecureRandom secureRandom
    ) {
        this.database = Objects.requireNonNull(database, "database");
        Objects.requireNonNull(config, "config");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.challengeRepository = new ChallengeRepository();
        this.matchRepository = new MatchRepository();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.expiryMinutes = config.challengeExpiryMinutes();
        this.maxOpenChallenges = config.maxOpenChallenges();
    }

    public ChallengeSummary createChallenge(
        SessionIdentity creator,
        ChallengeCreateRequest request
    ) {
        requireIdentity(creator);
        if (request == null) {
            throw incompatibleVersion();
        }
        validateCompatibility(
            request.gameVersion(), request.protocolVersion(), request.ruleset());
        requireCharacter(request.characterId());

        long now = clock.millis();
        long expiresAt = Math.addExact(
            now, Duration.ofMinutes(expiryMinutes).toMillis());
        ChallengeRecord challenge = new ChallengeRecord(
            UUID.randomUUID().toString(),
            creator.playerId(),
            creator.displayName(),
            ChallengeStatus.OPEN,
            request.gameVersion(),
            request.protocolVersion(),
            request.ruleset(),
            request.characterId(),
            now,
            expiresAt,
            null,
            null,
            null,
            null
        );

        ChallengeSummary created = database.transaction(connection -> {
            challengeRepository.expireOpen(connection, now);
            if (!challengeRepository.lockCreator(connection, creator.playerId())) {
                throw new ServiceException(
                    ServiceErrorCode.FORBIDDEN, "The guest identity no longer exists.");
            }
            if (challengeRepository.countOpenByCreator(connection, creator.playerId())
                >= maxOpenChallenges) {
                throw new ServiceException(
                    ServiceErrorCode.TOO_MANY_OPEN_CHALLENGES,
                    "The maximum number of open challenges has been reached.");
            }
            challengeRepository.insert(connection, challenge);
            return toSummary(challenge);
        });
        LOGGER.info(
            "Challenge created challengeId={} creatorPlayerId={} characterId={}",
            created.challengeId(),
            creator.playerId(),
            request.characterId()
        );
        return created;
    }

    public ChallengeListResponse listOpenChallenges(SessionIdentity caller) {
        requireIdentity(caller);
        long now = clock.millis();
        List<ChallengeSummary> challenges = database.transaction(connection -> {
            challengeRepository.expireOpen(connection, now);
            return challengeRepository.listCompatibleOpen(
                    connection,
                    caller.playerId(),
                    ProtocolVersion.GAME_VERSION,
                    ProtocolVersion.PROTOCOL_VERSION,
                    ProtocolVersion.STANDARD_RULESET,
                    now
                ).stream()
                .map(this::toSummary)
                .toList();
        });
        return new ChallengeListResponse(challenges, now);
    }

    public ChallengeSummary getChallenge(String challengeId) {
        requireChallengeId(challengeId);
        long now = clock.millis();
        return database.transaction(connection -> {
            challengeRepository.expireOpen(connection, now);
            return toSummary(challengeRepository.findById(connection, challengeId)
                .orElseThrow(ChallengeService::challengeNotFound));
        });
    }

    public ChallengeSummary cancelChallenge(SessionIdentity caller, String challengeId) {
        requireIdentity(caller);
        requireChallengeId(challengeId);
        long now = clock.millis();
        ChallengeSummary cancelled = database.transaction(connection -> {
            challengeRepository.expireOpen(connection, now);
            if (challengeRepository.cancelOpen(
                connection, challengeId, caller.playerId()) == 1) {
                return toSummary(challengeRepository.findById(connection, challengeId)
                    .orElseThrow(ChallengeService::challengeNotFound));
            }

            ChallengeRecord current = challengeRepository.findById(connection, challengeId)
                .orElseThrow(ChallengeService::challengeNotFound);
            if (!current.creatorPlayerId().equals(caller.playerId())) {
                throw new ServiceException(
                    ServiceErrorCode.FORBIDDEN,
                    "Only the challenge creator can cancel it.");
            }
            throw challengeNotOpen(current.status());
        });
        LOGGER.info(
            "Challenge cancelled challengeId={} creatorPlayerId={}",
            cancelled.challengeId(),
            caller.playerId()
        );
        return cancelled;
    }

    public AcceptedMatchSetup acceptChallenge(
        SessionIdentity accepter,
        String challengeId,
        ChallengeAcceptRequest request
    ) {
        requireIdentity(accepter);
        requireChallengeId(challengeId);
        if (request == null) {
            throw incompatibleVersion();
        }
        validateCompatibility(
            request.gameVersion(), request.protocolVersion(), request.ruleset());
        SorcererCharacter accepterCharacter = requireCharacter(request.characterId());
        long now = clock.millis();

        AcceptedMatchSetup accepted = database.transaction(connection -> {
            challengeRepository.expireOpen(connection, now);
            ChallengeRecord challenge = challengeRepository.findById(connection, challengeId)
                .orElseThrow(ChallengeService::challengeNotFound);
            validateCompatibility(
                challenge.gameVersion(), challenge.protocolVersion(), challenge.ruleset());
            if (challenge.creatorPlayerId().equals(accepter.playerId())) {
                throw new ServiceException(
                    ServiceErrorCode.CANNOT_ACCEPT_OWN_CHALLENGE,
                    "A player cannot accept their own challenge.");
            }
            if (challenge.status() != ChallengeStatus.OPEN) {
                throw challengeNotOpen(challenge.status());
            }

            String matchId = UUID.randomUUID().toString();
            long serverSeed = secureRandom.nextLong();
            if (challengeRepository.acceptOpen(
                connection,
                challengeId,
                accepter.playerId(),
                request.characterId(),
                now,
                matchId
            ) != 1) {
                ChallengeRecord current = challengeRepository.findById(connection, challengeId)
                    .orElseThrow(ChallengeService::challengeNotFound);
                throw challengeNotOpen(current.status());
            }

            MatchStatus matchStatus = MatchStatus.WAITING;
            matchRepository.insertMatch(
                connection,
                matchId,
                challengeId,
                matchStatus,
                serverSeed,
                challenge.gameVersion(),
                challenge.protocolVersion(),
                challenge.ruleset(),
                now
            );
            matchRepository.insertParticipant(
                connection,
                matchId,
                challenge.creatorPlayerId(),
                PlayerSide.PLAYER_ONE,
                challenge.hostCharacterId()
            );
            matchRepository.insertParticipant(
                connection,
                matchId,
                accepter.playerId(),
                PlayerSide.PLAYER_TWO,
                request.characterId()
            );

            AcceptedMatchParticipant playerOne = new AcceptedMatchParticipant(
                challenge.creatorPlayerId(),
                challenge.creatorDisplayName(),
                PlayerSide.PLAYER_ONE,
                challenge.hostCharacterId(),
                requireCharacter(challenge.hostCharacterId())
            );
            AcceptedMatchParticipant playerTwo = new AcceptedMatchParticipant(
                accepter.playerId(),
                accepter.displayName(),
                PlayerSide.PLAYER_TWO,
                request.characterId(),
                accepterCharacter
            );
            return new AcceptedMatchSetup(
                matchId,
                challengeId,
                matchStatus,
                serverSeed,
                challenge.gameVersion(),
                challenge.protocolVersion(),
                challenge.ruleset(),
                now,
                playerOne,
                playerTwo
            );
        });
        LOGGER.info(
            "Challenge accepted challengeId={} matchId={} creatorPlayerId={} accepterPlayerId={}",
            accepted.challengeId(),
            accepted.matchId(),
            accepted.playerOne().playerId(),
            accepted.playerTwo().playerId()
        );
        return accepted;
    }

    private ChallengeSummary toSummary(ChallengeRecord challenge) {
        String characterName = catalog.findCharacter(challenge.hostCharacterId())
            .map(SorcererCharacter::getName)
            .orElseThrow(() -> new IllegalStateException(
                "Persisted challenge references unknown canonical character "
                    + challenge.hostCharacterId()));
        return new ChallengeSummary(
            challenge.challengeId(),
            challenge.creatorPlayerId(),
            challenge.creatorDisplayName(),
            challenge.hostCharacterId(),
            characterName,
            challenge.status(),
            challenge.gameVersion(),
            challenge.protocolVersion(),
            challenge.ruleset(),
            challenge.createdAt(),
            challenge.expiresAt(),
            challenge.matchId()
        );
    }

    private SorcererCharacter requireCharacter(String characterId) {
        return catalog.findCharacter(characterId).orElseThrow(() ->
            new ServiceException(
                ServiceErrorCode.INVALID_CHARACTER,
                "The selected character is not in the canonical server catalog."));
    }

    private static void validateCompatibility(
        String gameVersion,
        int protocolVersion,
        String ruleset
    ) {
        if (!ProtocolVersion.isCompatible(gameVersion, protocolVersion, ruleset)) {
            throw incompatibleVersion();
        }
    }

    private static void requireIdentity(SessionIdentity identity) {
        if (identity == null || identity.playerId() == null || identity.playerId().isBlank()) {
            throw new ServiceException(
                ServiceErrorCode.FORBIDDEN, "An authenticated guest identity is required.");
        }
    }

    private static void requireChallengeId(String challengeId) {
        if (challengeId == null || challengeId.isBlank()) {
            throw challengeNotFound();
        }
    }

    private static ServiceException incompatibleVersion() {
        return new ServiceException(
            ServiceErrorCode.INCOMPATIBLE_VERSION,
            "The game, protocol, or ruleset version is incompatible with this server.");
    }

    private static ServiceException challengeNotFound() {
        return new ServiceException(
            ServiceErrorCode.CHALLENGE_NOT_FOUND, "The challenge does not exist.");
    }

    private static ServiceException challengeNotOpen(ChallengeStatus status) {
        ServiceErrorCode code = switch (status) {
            case ACCEPTED -> ServiceErrorCode.CHALLENGE_ALREADY_ACCEPTED;
            case CANCELLED -> ServiceErrorCode.CHALLENGE_CANCELLED;
            case EXPIRED -> ServiceErrorCode.CHALLENGE_EXPIRED;
            case OPEN -> ServiceErrorCode.CHALLENGE_NOT_OPEN;
        };
        return new ServiceException(
            code,
            "The challenge is no longer open.",
            Map.of("status", status.name())
        );
    }
}

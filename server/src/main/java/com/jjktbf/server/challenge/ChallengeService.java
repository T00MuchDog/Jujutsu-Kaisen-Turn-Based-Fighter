package com.jjktbf.server.challenge;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.combat.BattleFormat;
import com.jjktbf.multiplayer.protocol.ChallengeAcceptRequest;
import com.jjktbf.multiplayer.protocol.ChallengeCreateRequest;
import com.jjktbf.multiplayer.protocol.ChallengeDecisionRequest;
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

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
        BattleFormat format = request.format();
        List<String> characterIds = validateRoster(format, request.characterIds());

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
            format,
            characterIds,
            now,
            expiresAt,
            null,
            null,
            null,
            null,
            null,
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
            ChallengeRecord existing = challengeRepository.findMatchingOpenByCreator(
                connection,
                creator.playerId(),
                format,
                characterIds,
                request.gameVersion(),
                request.protocolVersion(),
                request.ruleset()
            ).orElse(null);
            if (existing != null) {
                return toSummary(existing);
            }
            if (challengeRepository.countOpenByCreator(
                connection,
                creator.playerId(),
                format,
                request.gameVersion(),
                request.protocolVersion(),
                request.ruleset()
            )
                >= maxOpenChallenges) {
                throw new ServiceException(
                    ServiceErrorCode.TOO_MANY_OPEN_CHALLENGES,
                    "The maximum number of open challenges has been reached.");
            }
            challengeRepository.insert(connection, challenge);
            return toSummary(challenge);
        });
        LOGGER.info(
            "Challenge created challengeId={} creatorPlayerId={} format={} characterIds={}",
            created.challengeId(),
            creator.playerId(),
            format,
            characterIds
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
                    null,
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

    public ChallengeSummary getRequestedChallenge(SessionIdentity requester) {
        requireIdentity(requester);
        long now = clock.millis();
        return database.transaction(connection -> {
            challengeRepository.expireOpen(connection, now);
            return toSummary(challengeRepository.findRecoverableRequest(
                connection,
                requester.playerId(),
                ProtocolVersion.GAME_VERSION,
                ProtocolVersion.PROTOCOL_VERSION,
                ProtocolVersion.STANDARD_RULESET
            ).orElseThrow(ChallengeService::challengeNotFound));
        });
    }

    public ChallengeSummary getHostedChallenge(SessionIdentity host) {
        requireIdentity(host);
        long now = clock.millis();
        return database.transaction(connection -> {
            challengeRepository.expireOpen(connection, now);
            return toSummary(challengeRepository.findRecoverableHosted(
                connection,
                host.playerId(),
                ProtocolVersion.GAME_VERSION,
                ProtocolVersion.PROTOCOL_VERSION,
                ProtocolVersion.STANDARD_RULESET
            ).orElseThrow(ChallengeService::challengeNotFound));
        });
    }

    public AcceptedMatchSetup getAcceptedMatch(
        SessionIdentity caller,
        String matchId
    ) {
        requireIdentity(caller);
        if (matchId == null || matchId.isBlank()) {
            throw matchNotFound();
        }
        return database.transaction(connection -> {
            ChallengeRecord challenge = challengeRepository.findByMatchId(connection, matchId)
                .orElseThrow(ChallengeService::matchNotFound);
            if (!caller.playerId().equals(challenge.creatorPlayerId())
                && !caller.playerId().equals(challenge.acceptedPlayerId())) {
                throw new ServiceException(
                    ServiceErrorCode.PLAYER_NOT_IN_MATCH,
                    "The player is not a participant in this match.");
            }
            validateCompatibility(
                challenge.gameVersion(), challenge.protocolVersion(), challenge.ruleset());
            return acceptedSetup(connection, challenge);
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

    public ChallengeSummary requestJoin(
        SessionIdentity requester,
        String challengeId,
        ChallengeAcceptRequest request
    ) {
        requireIdentity(requester);
        requireChallengeId(challengeId);
        if (request == null) {
            throw incompatibleVersion();
        }
        validateCompatibility(
            request.gameVersion(), request.protocolVersion(), request.ruleset());
        long now = clock.millis();

        ChallengeSummary requested = database.transaction(connection -> {
            challengeRepository.expireOpen(connection, now);
            ChallengeRecord challenge = challengeRepository.findById(connection, challengeId)
                .orElseThrow(ChallengeService::challengeNotFound);
            validateCompatibility(
                challenge.gameVersion(), challenge.protocolVersion(), challenge.ruleset());
            // The joiner must field the same format as the host.
            if (challenge.format() != request.format()) {
                throw formatMismatch(challenge.format(), request.format());
            }
            List<String> characterIds = validateRoster(challenge.format(), request.characterIds());
            if (challenge.creatorPlayerId().equals(requester.playerId())) {
                throw new ServiceException(
                    ServiceErrorCode.CANNOT_ACCEPT_OWN_CHALLENGE,
                    "A player cannot join their own challenge.");
            }
            if (challenge.status() != ChallengeStatus.OPEN) {
                throw challengeNotOpen(challenge.status());
            }
            if (requester.playerId().equals(challenge.requestedPlayerId())
                && characterIds.equals(challenge.requestedCharacterIds())) {
                return toSummary(challenge);
            }
            if (!challengeRepository.lockCreator(connection, requester.playerId())) {
                throw new ServiceException(
                    ServiceErrorCode.FORBIDDEN, "The guest identity no longer exists.");
            }

            ChallengeRecord current = challengeRepository.findById(connection, challengeId)
                .orElseThrow(ChallengeService::challengeNotFound);
            if (current.status() != ChallengeStatus.OPEN) {
                throw challengeNotOpen(current.status());
            }
            if (requester.playerId().equals(current.requestedPlayerId())
                && characterIds.equals(current.requestedCharacterIds())) {
                return toSummary(current);
            }
            if (current.requestedPlayerId() != null) {
                throw new ServiceException(
                    ServiceErrorCode.CHALLENGE_REQUEST_PENDING,
                    "Another join request is already pending for this challenge.");
            }
            if (challengeRepository.countPendingByRequester(
                connection, requester.playerId()) >= 1) {
                throw new ServiceException(
                    ServiceErrorCode.TOO_MANY_PENDING_REQUESTS,
                    "A player can only wait on one challenge at a time.");
            }

            if (challengeRepository.requestJoinOpen(
                connection,
                challengeId,
                UUID.randomUUID().toString(),
                requester.playerId(),
                characterIds,
                now
            ) == 1) {
                return toSummary(challengeRepository.findById(connection, challengeId)
                    .orElseThrow(ChallengeService::challengeNotFound));
            }

            current = challengeRepository.findById(connection, challengeId)
                .orElseThrow(ChallengeService::challengeNotFound);
            if (current.status() != ChallengeStatus.OPEN) {
                throw challengeNotOpen(current.status());
            }
            if (requester.playerId().equals(current.requestedPlayerId())
                && characterIds.equals(current.requestedCharacterIds())) {
                return toSummary(current);
            }
            throw new ServiceException(
                ServiceErrorCode.CHALLENGE_REQUEST_PENDING,
                "Another join request is already pending for this challenge.");
        });
        LOGGER.info(
            "Challenge join requested challengeId={} requesterPlayerId={} characterIds={}",
            requested.challengeId(),
            requester.playerId(),
            requested.requestedCharacterIds()
        );
        return requested;
    }

    public AcceptedMatchSetup acceptChallenge(
        SessionIdentity host,
        String challengeId,
        ChallengeDecisionRequest decision
    ) {
        requireIdentity(host);
        requireChallengeId(challengeId);
        requireDecision(decision);
        long now = clock.millis();

        AcceptedMatchSetup accepted = database.transaction(connection -> {
            challengeRepository.expireOpen(connection, now);
            ChallengeRecord challenge = challengeRepository.findById(connection, challengeId)
                .orElseThrow(ChallengeService::challengeNotFound);
            requireHost(host, challenge, "accept join requests");
            validateCompatibility(
                challenge.gameVersion(), challenge.protocolVersion(), challenge.ruleset());
            if (challenge.status() == ChallengeStatus.ACCEPTED) {
                requireAcceptedRequest(decision, challenge);
                return acceptedSetup(connection, challenge);
            }
            if (challenge.status() != ChallengeStatus.OPEN) {
                throw challengeNotOpen(challenge.status());
            }
            if (challenge.requestedPlayerId() == null) {
                throw noPendingRequest();
            }

            String matchId = UUID.randomUUID().toString();
            long serverSeed = secureRandom.nextLong();
            if (challengeRepository.acceptPendingOpen(
                connection,
                challengeId,
                host.playerId(),
                decision.expectedRequestId(),
                decision.expectedRequesterId(),
                decision.expectedRequestedAt(),
                now,
                matchId
            ) != 1) {
                ChallengeRecord current = challengeRepository.findById(connection, challengeId)
                    .orElseThrow(ChallengeService::challengeNotFound);
                requireHost(host, current, "accept join requests");
                if (current.status() == ChallengeStatus.ACCEPTED) {
                    requireAcceptedRequest(decision, current);
                    return acceptedSetup(connection, current);
                }
                if (current.status() != ChallengeStatus.OPEN) {
                    throw challengeNotOpen(current.status());
                }
                throw requestChanged();
            }

            ChallengeRecord current = challengeRepository.findById(connection, challengeId)
                .orElseThrow(ChallengeService::challengeNotFound);

            MatchStatus matchStatus = MatchStatus.WAITING;
            matchRepository.insertMatch(
                connection,
                matchId,
                challengeId,
                matchStatus,
                serverSeed,
                current.gameVersion(),
                current.protocolVersion(),
                current.ruleset(),
                now
            );
            matchRepository.insertParticipant(
                connection,
                matchId,
                current.creatorPlayerId(),
                PlayerSide.PLAYER_ONE,
                current.hostCharacterIds()
            );
            matchRepository.insertParticipant(
                connection,
                matchId,
                current.acceptedPlayerId(),
                PlayerSide.PLAYER_TWO,
                current.acceptedCharacterIds()
            );
            return acceptedSetup(connection, current);
        });
        LOGGER.info(
            "Challenge accepted challengeId={} matchId={} creatorPlayerId={} requesterPlayerId={}",
            accepted.challengeId(),
            accepted.matchId(),
            accepted.playerOne().playerId(),
            accepted.playerTwo().playerId()
        );
        return accepted;
    }

    public ChallengeSummary rejectJoinRequest(
        SessionIdentity host,
        String challengeId,
        ChallengeDecisionRequest decision
    ) {
        requireIdentity(host);
        requireChallengeId(challengeId);
        requireDecision(decision);
        long now = clock.millis();

        ChallengeSummary rejected = database.transaction(connection -> {
            challengeRepository.expireOpen(connection, now);
            ChallengeRecord challenge = challengeRepository.findById(connection, challengeId)
                .orElseThrow(ChallengeService::challengeNotFound);
            requireHost(host, challenge, "reject join requests");
            if (challenge.status() != ChallengeStatus.OPEN) {
                throw challengeNotOpen(challenge.status());
            }
            if (challenge.requestedPlayerId() == null) {
                return toSummary(challenge);
            }
            int rejectedCount = challengeRepository.rejectPendingOpen(
                connection,
                challengeId,
                host.playerId(),
                decision.expectedRequestId(),
                decision.expectedRequesterId(),
                decision.expectedRequestedAt(),
                now
            );
            ChallengeRecord current = challengeRepository.findById(connection, challengeId)
                .orElseThrow(ChallengeService::challengeNotFound);
            if (current.status() != ChallengeStatus.OPEN) {
                throw challengeNotOpen(current.status());
            }
            if (rejectedCount != 1 && current.requestedPlayerId() != null) {
                throw requestChanged();
            }
            return toSummary(current);
        });
        LOGGER.info(
            "Challenge join request rejected challengeId={} creatorPlayerId={}",
            rejected.challengeId(),
            host.playerId()
        );
        return rejected;
    }

    public ChallengeSummary withdrawJoinRequest(
        SessionIdentity requester,
        String challengeId,
        ChallengeDecisionRequest decision
    ) {
        requireIdentity(requester);
        requireChallengeId(challengeId);
        requireDecision(decision);
        long now = clock.millis();
        return database.transaction(connection -> {
            challengeRepository.expireOpen(connection, now);
            ChallengeRecord challenge = challengeRepository.findById(connection, challengeId)
                .orElseThrow(ChallengeService::challengeNotFound);
            if (challenge.status() != ChallengeStatus.OPEN) {
                throw challengeNotOpen(challenge.status());
            }
            if (challenge.requestedPlayerId() == null) {
                return toSummary(challenge);
            }
            if (!requester.playerId().equals(challenge.requestedPlayerId())) {
                throw new ServiceException(
                    ServiceErrorCode.FORBIDDEN,
                    "Only the pending requester can withdraw this join request.");
            }
            if (!decision.expectedRequestId().equals(challenge.joinRequestId())) {
                throw requestChanged();
            }
            int withdrawn = challengeRepository.withdrawPendingOpen(
                connection,
                challengeId,
                requester.playerId(),
                decision.expectedRequestId(),
                now
            );
            ChallengeRecord current = challengeRepository.findById(connection, challengeId)
                .orElseThrow(ChallengeService::challengeNotFound);
            if (withdrawn == 1 || (current.status() == ChallengeStatus.OPEN
                && current.requestedPlayerId() == null)) {
                return toSummary(current);
            }
            if (current.status() != ChallengeStatus.OPEN) {
                throw challengeNotOpen(current.status());
            }
            throw requestChanged();
        });
    }

    private AcceptedMatchSetup acceptedSetup(
        Connection connection,
        ChallengeRecord challenge
    ) throws SQLException {
        if (challenge.status() != ChallengeStatus.ACCEPTED
            || challenge.acceptedPlayerId() == null
            || challenge.acceptedCharacterIds() == null
            || challenge.acceptedCharacterIds().isEmpty()) {
            throw new IllegalStateException("Accepted challenge data is incomplete");
        }
        MatchRepository.PersistedMatch match = matchRepository
            .findByChallenge(connection, challenge.challengeId())
            .orElseThrow(() -> new IllegalStateException(
                "Accepted challenge does not have a persisted match"));
        String requesterDisplayName = challengeRepository
            .findPlayerDisplayName(connection, challenge.acceptedPlayerId())
            .orElseThrow(() -> new IllegalStateException(
                "Accepted challenge requester no longer exists"));
        AcceptedMatchParticipant playerOne = new AcceptedMatchParticipant(
            challenge.creatorPlayerId(),
            challenge.creatorDisplayName(),
            PlayerSide.PLAYER_ONE,
            challenge.hostCharacterIds(),
            resolveCharacters(challenge.hostCharacterIds())
        );
        AcceptedMatchParticipant playerTwo = new AcceptedMatchParticipant(
            challenge.acceptedPlayerId(),
            requesterDisplayName,
            PlayerSide.PLAYER_TWO,
            challenge.acceptedCharacterIds(),
            resolveCharacters(challenge.acceptedCharacterIds())
        );
        return new AcceptedMatchSetup(
            match.matchId(),
            challenge.challengeId(),
            match.status(),
            match.serverSeed(),
            challenge.format(),
            challenge.gameVersion(),
            challenge.protocolVersion(),
            challenge.ruleset(),
            match.createdAt(),
            playerOne,
            playerTwo
        );
    }

    private ChallengeSummary toSummary(ChallengeRecord challenge) {
        List<String> hostNames = resolveCharacterNames(challenge.hostCharacterIds());
        return new ChallengeSummary(
            challenge.challengeId(),
            challenge.creatorPlayerId(),
            challenge.creatorDisplayName(),
            challenge.hostCharacterIds(),
            hostNames,
            challenge.status(),
            challenge.format(),
            challenge.gameVersion(),
            challenge.protocolVersion(),
            challenge.ruleset(),
            challenge.createdAt(),
            challenge.expiresAt(),
            challenge.joinRequestId(),
            challenge.requestedPlayerId(),
            challenge.requestedCharacterIds(),
            challenge.requestedAt(),
            challenge.acceptedJoinRequestId(),
            challenge.matchId()
        );
    }

    /**
     * Validate a roster against a format: exactly {@code format.fightersPerSide()}
     * canonical ids, all selectable, no duplicates. Returns a normalized copy.
     */
    private List<String> validateRoster(BattleFormat format, List<String> characterIds) {
        Objects.requireNonNull(format, "format");
        if (characterIds == null || characterIds.size() != format.fightersPerSide()) {
            throw invalidRoster(format);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> normalized = new ArrayList<>(characterIds.size());
        for (String id : characterIds) {
            if (id == null || id.isBlank() || !seen.add(id)) {
                throw invalidRoster(format);
            }
            // Resolve each id through the selectable catalog (rejects hidden /
            // summon-only shikigami definitions a crafted request might name).
            requireCharacter(id);
            normalized.add(id);
        }
        return List.copyOf(normalized);
    }

    private List<Character> resolveCharacters(List<String> characterIds) {
        List<Character> resolved = new ArrayList<>(characterIds.size());
        for (String id : characterIds) {
            resolved.add(requireCharacter(id));
        }
        return List.copyOf(resolved);
    }

    private List<String> resolveCharacterNames(List<String> characterIds) {
        List<String> names = new ArrayList<>(characterIds.size());
        for (String id : characterIds) {
            names.add(catalog.findCharacter(id)
                .map(Character::getName)
                .orElseThrow(() -> new IllegalStateException(
                    "Persisted challenge references unknown canonical character " + id)));
        }
        return List.copyOf(names);
    }

    /**
     * Resolve a canonical character for match construction, rejecting hidden
     * (non-selectable) definitions. A crafted request that names a summon-only
     * shikigami cannot smuggle it into a match as a primary fighter.
     */
    private Character requireCharacter(String characterId) {
        return catalog.findSelectableCharacter(characterId).orElseThrow(() ->
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

    private static void requireDecision(ChallengeDecisionRequest decision) {
        if (decision == null
            || decision.expectedRequestId() == null
            || decision.expectedRequestId().isBlank()) {
            throw new ServiceException(
                ServiceErrorCode.MALFORMED_REQUEST,
                "The challenge decision does not identify a pending request.");
        }
    }

    private static void requireAcceptedRequest(
        ChallengeDecisionRequest decision,
        ChallengeRecord challenge
    ) {
        if (!Objects.equals(
            decision.expectedRequestId(), challenge.acceptedJoinRequestId())) {
            throw requestChanged();
        }
    }

    private static void requireHost(
        SessionIdentity caller,
        ChallengeRecord challenge,
        String operation
    ) {
        if (!challenge.creatorPlayerId().equals(caller.playerId())) {
            throw new ServiceException(
                ServiceErrorCode.FORBIDDEN,
                "Only the challenge creator can " + operation + ".");
        }
    }

    private static ServiceException invalidRoster(BattleFormat format) {
        return new ServiceException(
            ServiceErrorCode.INVALID_CHARACTER,
            "Select exactly " + format.fightersPerSide()
                + " unique fighters for " + format + ".");
    }

    private static ServiceException formatMismatch(
        BattleFormat expected,
        BattleFormat requested
    ) {
        return new ServiceException(
            ServiceErrorCode.INCOMPATIBLE_VERSION,
            "This challenge is " + expected + "; switch formats to join it.",
            Map.of("expected", expected.name(), "requested", requested.name())
        );
    }

    private static ServiceException incompatibleVersion() {
        return new ServiceException(
            ServiceErrorCode.INCOMPATIBLE_VERSION,
            "You are running an outdated version of the game. "
                + "Please download the latest release to play online.");
    }

    private static ServiceException challengeNotFound() {
        return new ServiceException(
            ServiceErrorCode.CHALLENGE_NOT_FOUND, "The challenge does not exist.");
    }

    private static ServiceException matchNotFound() {
        return new ServiceException(
            ServiceErrorCode.MATCH_NOT_FOUND, "The match does not exist or is no longer active.");
    }

    private static ServiceException noPendingRequest() {
        return new ServiceException(
            ServiceErrorCode.CHALLENGE_NO_PENDING_REQUEST,
            "The challenge does not have a pending join request.");
    }

    private static ServiceException requestChanged() {
        return new ServiceException(
            ServiceErrorCode.CHALLENGE_NO_PENDING_REQUEST,
            "The pending join request changed before the decision was applied.");
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

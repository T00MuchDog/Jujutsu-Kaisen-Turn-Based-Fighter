package com.jjktbf.multiplayer.protocol;

import com.jjktbf.model.combat.BattleFormat;
import com.jjktbf.model.combat.BattleStatMode;

import java.util.List;
import java.util.Objects;

/**
 * Request to host a challenge with a battle format and optional legacy roster.
 *
 * <p>New clients leave {@code characterIds} empty and select fighters after the
 * host accepts a requester. A non-empty roster is retained for persisted and
 * older protocol flows and is validated against {@code format}.
 */
public record ChallengeCreateRequest(
    List<String> characterIds,
    BattleFormat format,
    String gameVersion,
    int protocolVersion,
    String ruleset
) {
    public ChallengeCreateRequest {
        characterIds = characterIds == null ? List.of() : List.copyOf(characterIds);
        Objects.requireNonNull(format, "format");
    }

    /**
     * Standard 1v1 host request for a single fighter, kept for legacy callers.
     */
    public static ChallengeCreateRequest standard(String characterId) {
        return new ChallengeCreateRequest(
            List.of(characterId),
            BattleFormat.ONE_V_ONE,
            ProtocolVersion.GAME_VERSION,
            ProtocolVersion.PROTOCOL_VERSION,
            ProtocolVersion.STANDARD_RULESET
        );
    }

    /**
     * Standard host request carrying an ordered roster for the given format.
     */
    public static ChallengeCreateRequest standard(BattleFormat format, List<String> characterIds) {
        return forBattle(format, BattleStatMode.STANDARD, characterIds);
    }

    /** Host request for independent roster-size and runtime-stat choices. */
    public static ChallengeCreateRequest forBattle(
        BattleFormat format,
        BattleStatMode statMode,
        List<String> characterIds
    ) {
        return new ChallengeCreateRequest(
            characterIds,
            format,
            ProtocolVersion.GAME_VERSION,
            ProtocolVersion.PROTOCOL_VERSION,
            Objects.requireNonNull(statMode, "statMode").rulesetId()
        );
    }

    /** New challenge flow: publish rules now and select fighters after pairing. */
    public static ChallengeCreateRequest open(
        BattleFormat format,
        BattleStatMode statMode
    ) {
        return forBattle(format, statMode, List.of());
    }
}

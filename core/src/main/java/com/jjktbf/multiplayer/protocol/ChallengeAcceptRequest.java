package com.jjktbf.multiplayer.protocol;

import com.jjktbf.model.combat.BattleFormat;
import com.jjktbf.model.combat.BattleStatMode;

import java.util.List;
import java.util.Objects;

/**
 * Request to join a challenge with an ordered canonical-character roster.
 *
 * <p>{@code format} must match the host challenge's format; {@code characterIds}
 * must carry exactly {@link BattleFormat#fightersPerSide()} selectable canonical
 * ids in team order. The server re-validates both.
 */
public record ChallengeAcceptRequest(
    List<String> characterIds,
    BattleFormat format,
    String gameVersion,
    int protocolVersion,
    String ruleset
) {
    public ChallengeAcceptRequest {
        characterIds = characterIds == null ? List.of() : List.copyOf(characterIds);
        Objects.requireNonNull(format, "format");
    }

    /** Standard 1v1 join request for a single fighter, kept for legacy callers. */
    public static ChallengeAcceptRequest standard(String characterId) {
        return new ChallengeAcceptRequest(
            List.of(characterId),
            BattleFormat.ONE_V_ONE,
            ProtocolVersion.GAME_VERSION,
            ProtocolVersion.PROTOCOL_VERSION,
            ProtocolVersion.STANDARD_RULESET
        );
    }

    /**
     * Standard join request carrying an ordered roster for the given format.
     */
    public static ChallengeAcceptRequest standard(BattleFormat format, List<String> characterIds) {
        return forBattle(format, BattleStatMode.STANDARD, characterIds);
    }

    /** Join request for independent roster-size and runtime-stat choices. */
    public static ChallengeAcceptRequest forBattle(
        BattleFormat format,
        BattleStatMode statMode,
        List<String> characterIds
    ) {
        return new ChallengeAcceptRequest(
            characterIds,
            format,
            ProtocolVersion.GAME_VERSION,
            ProtocolVersion.PROTOCOL_VERSION,
            Objects.requireNonNull(statMode, "statMode").rulesetId()
        );
    }
}

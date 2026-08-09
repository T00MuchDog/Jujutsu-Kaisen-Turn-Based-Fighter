package com.jjktbf.multiplayer.protocol;

import com.jjktbf.model.combat.BattleFormat;

import java.util.List;
import java.util.Objects;

/**
 * Request to host a challenge with an ordered canonical-character roster.
 *
 * <p>{@code format} fixes how many fighters each side fields; {@code characterIds}
 * must carry exactly {@link BattleFormat#fightersPerSide()} selectable canonical
 * ids in team order. The server re-validates both.
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
        return new ChallengeCreateRequest(
            characterIds,
            format,
            ProtocolVersion.GAME_VERSION,
            ProtocolVersion.PROTOCOL_VERSION,
            ProtocolVersion.STANDARD_RULESET
        );
    }
}

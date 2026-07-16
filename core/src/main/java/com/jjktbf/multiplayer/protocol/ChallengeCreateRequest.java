package com.jjktbf.multiplayer.protocol;

/** Request to host a challenge with a canonical character. */
public record ChallengeCreateRequest(
    String characterId,
    String gameVersion,
    int protocolVersion,
    String ruleset
) {
    public static ChallengeCreateRequest standard(String characterId) {
        return new ChallengeCreateRequest(
            characterId,
            ProtocolVersion.GAME_VERSION,
            ProtocolVersion.PROTOCOL_VERSION,
            ProtocolVersion.STANDARD_RULESET
        );
    }
}

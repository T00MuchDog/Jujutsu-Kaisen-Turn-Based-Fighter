package com.jjktbf.multiplayer.protocol;

/** Request to join a challenge with a canonical character. */
public record ChallengeAcceptRequest(
    String characterId,
    String gameVersion,
    int protocolVersion,
    String ruleset
) {
    public static ChallengeAcceptRequest standard(String characterId) {
        return new ChallengeAcceptRequest(
            characterId,
            ProtocolVersion.GAME_VERSION,
            ProtocolVersion.PROTOCOL_VERSION,
            ProtocolVersion.STANDARD_RULESET
        );
    }
}

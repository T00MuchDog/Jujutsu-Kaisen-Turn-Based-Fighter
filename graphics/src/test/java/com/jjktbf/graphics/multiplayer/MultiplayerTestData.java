package com.jjktbf.graphics.multiplayer;

import com.jjktbf.multiplayer.protocol.BattlePhase;
import com.jjktbf.multiplayer.protocol.MatchSetup;
import com.jjktbf.multiplayer.protocol.MatchState;
import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import com.jjktbf.multiplayer.protocol.ProtocolVersion;
import com.jjktbf.multiplayer.protocol.SessionIdentity;

import java.util.List;

final class MultiplayerTestData {
    static final String MATCH_ID = "11111111-1111-1111-1111-111111111111";
    static final String PLAYER_ID = "22222222-2222-2222-2222-222222222222";

    private MultiplayerTestData() {
    }

    static GuestCredentials credentials(String token) {
        return new GuestCredentials(
            new SessionIdentity(PLAYER_ID, "Guest-1234", 9_999_999L), token);
    }

    static MatchState state(long version) {
        return state(version, MatchStatus.ACTIVE);
    }

    static MatchState state(long version, MatchStatus status) {
        return new MatchState(
            MATCH_ID,
            status,
            ProtocolVersion.GAME_VERSION,
            ProtocolVersion.PROTOCOL_VERSION,
            ProtocolVersion.STANDARD_RULESET,
            status == MatchStatus.ENDED || status == MatchStatus.ABANDONED
                ? BattlePhase.BATTLE_OVER : BattlePhase.PLANNING,
            1,
            0,
            List.of(),
            null,
            null,
            status == MatchStatus.ENDED ? "TEST_END" : null,
            version,
            List.of(),
            1_000L + version
        );
    }

    static MatchSetup setup(long version) {
        MatchState state = state(version);
        return new MatchSetup(
            MATCH_ID,
            "33333333-3333-3333-3333-333333333333",
            state.status(),
            PlayerSide.PLAYER_ONE,
            PLAYER_ID,
            "44444444-4444-4444-4444-444444444444",
            "Guest-5678",
            "character-one",
            "character-two",
            ProtocolVersion.GAME_VERSION,
            ProtocolVersion.PROTOCOL_VERSION,
            ProtocolVersion.STANDARD_RULESET,
            state,
            state.serverTimestamp()
        );
    }
}

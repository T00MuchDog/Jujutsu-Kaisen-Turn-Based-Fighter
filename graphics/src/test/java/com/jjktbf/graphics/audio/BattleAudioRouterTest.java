package com.jjktbf.graphics.audio;

import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.multiplayer.protocol.BattleEventState;
import com.jjktbf.multiplayer.protocol.BattleEventType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleAudioRouterTest {
    @Test
    void moveUnleashCueFollowsTheMovePurpose() {
        assertEquals(SoundCue.BATTLE_ATTACK_UNLEASH,
            BattleAudioRouter.cueForMove(move("ATTACK", "ATTACK", "PHYSICAL")).orElseThrow());
        assertEquals(SoundCue.BATTLE_DEFENSE_UNLEASH,
            BattleAudioRouter.cueForMove(move("DEFENSE", "DEFENSIVE")).orElseThrow());
        assertEquals(SoundCue.BATTLE_UTILITY_UNLEASH,
            BattleAudioRouter.cueForMove(move("UTILITY", "UTILITY")).orElseThrow());
    }

    @Test
    void localOutcomeEventsMapToBattleEffects() {
        CombatEvent blocked = CombatEvent.of(CombatEvent.Type.MOVE_BLOCK_REDUCED).build();
        CombatEvent blackFlash = CombatEvent.of(CombatEvent.Type.BLACK_FLASH).build();

        assertEquals(SoundCue.BATTLE_BLOCK,
            BattleAudioRouter.cueFor(blocked).orElseThrow());
        assertEquals(SoundCue.BATTLE_BLACK_FLASH,
            BattleAudioRouter.cueFor(blackFlash).orElseThrow());
        assertTrue(BattleAudioRouter.cueFor(
            CombatEvent.of(CombatEvent.Type.ROUND_END).build()).isEmpty());
    }

    @Test
    void multiplayerPlaybackUsesTheSameOutcomeMapping() {
        BattleEventState event = event(BattleEventType.DAMAGE_DEALT);

        assertEquals(SoundCue.BATTLE_HIT,
            BattleAudioRouter.cueFor(event, null).orElseThrow());
    }

    private static Move move(String id, String... tags) {
        MoveData data = new MoveData();
        data.id = id;
        data.name = id;
        data.tags = List.of(tags);
        data.apCost = 10;
        data.unleashPoint = 1;
        return data.toMove();
    }

    private static BattleEventState event(BattleEventType type) {
        return new BattleEventState(
            "EVENT", type, 1, 1,
            null, null, null,
            null, null, null,
            null, null, null, null, ""
        );
    }
}

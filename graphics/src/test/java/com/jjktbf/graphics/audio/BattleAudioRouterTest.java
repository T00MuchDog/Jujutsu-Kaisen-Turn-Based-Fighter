package com.jjktbf.graphics.audio;

import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.multiplayer.protocol.BattleEventState;
import com.jjktbf.multiplayer.protocol.BattleEventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        Map<CombatEvent.Type, SoundCue> mappings = Map.ofEntries(
            Map.entry(CombatEvent.Type.MOVE_MISSED, SoundCue.BATTLE_MISS),
            Map.entry(CombatEvent.Type.MOVE_BLOCKED, SoundCue.BATTLE_BLOCK),
            Map.entry(CombatEvent.Type.MOVE_BLOCK_REDUCED, SoundCue.BATTLE_BLOCK),
            Map.entry(CombatEvent.Type.MOVE_DODGED, SoundCue.BATTLE_DODGE),
            Map.entry(CombatEvent.Type.MOVE_PARRIED, SoundCue.BATTLE_PARRY),
            Map.entry(CombatEvent.Type.MOVE_STUNNED, SoundCue.BATTLE_STUN),
            Map.entry(CombatEvent.Type.DAMAGE_DEALT, SoundCue.BATTLE_HIT),
            Map.entry(CombatEvent.Type.DAMAGE_IGNORED, SoundCue.BATTLE_DAMAGE_IGNORED),
            Map.entry(CombatEvent.Type.HP_RESTORED, SoundCue.BATTLE_HEAL),
            Map.entry(CombatEvent.Type.BLACK_FLASH, SoundCue.BATTLE_BLACK_FLASH),
            Map.entry(CombatEvent.Type.STATUS_APPLIED, SoundCue.BATTLE_STATUS_APPLY),
            Map.entry(CombatEvent.Type.STATUS_EXPIRED, SoundCue.BATTLE_STATUS_EXPIRE),
            Map.entry(CombatEvent.Type.BFS_EXPIRED, SoundCue.BATTLE_STATUS_EXPIRE),
            Map.entry(CombatEvent.Type.ABILITY_ACTIVATED, SoundCue.BATTLE_ABILITY),
            Map.entry(CombatEvent.Type.RATIO_TRIGGERED, SoundCue.BATTLE_RATIO),
            Map.entry(CombatEvent.Type.ROUND_END, SoundCue.BATTLE_ROUND_END)
        );

        mappings.forEach((type, expectedCue) -> assertEquals(expectedCue,
            BattleAudioRouter.cueFor(CombatEvent.of(type).intValue(1).build()).orElseThrow(),
            type.name()));
    }

    @Test
    void multiplayerPlaybackUsesTheSameOutcomeMapping() {
        Map<BattleEventType, SoundCue> mappings = Map.ofEntries(
            Map.entry(BattleEventType.MOVE_MISSED, SoundCue.BATTLE_MISS),
            Map.entry(BattleEventType.MOVE_BLOCKED, SoundCue.BATTLE_BLOCK),
            Map.entry(BattleEventType.MOVE_BLOCK_REDUCED, SoundCue.BATTLE_BLOCK),
            Map.entry(BattleEventType.MOVE_STUNNED, SoundCue.BATTLE_STUN),
            Map.entry(BattleEventType.DAMAGE_DEALT, SoundCue.BATTLE_HIT),
            Map.entry(BattleEventType.DAMAGE_IGNORED, SoundCue.BATTLE_DAMAGE_IGNORED),
            Map.entry(BattleEventType.HP_RESTORED, SoundCue.BATTLE_HEAL),
            Map.entry(BattleEventType.BLACK_FLASH, SoundCue.BATTLE_BLACK_FLASH),
            Map.entry(BattleEventType.STATUS_APPLIED, SoundCue.BATTLE_STATUS_APPLY),
            Map.entry(BattleEventType.STATUS_EXPIRED, SoundCue.BATTLE_STATUS_EXPIRE),
            Map.entry(BattleEventType.BFS_ENTERED, SoundCue.BATTLE_STATUS_APPLY),
            Map.entry(BattleEventType.BFS_EXPIRED, SoundCue.BATTLE_STATUS_EXPIRE),
            Map.entry(BattleEventType.ABILITY_ACTIVATED, SoundCue.BATTLE_ABILITY),
            Map.entry(BattleEventType.RATIO_TRIGGERED, SoundCue.BATTLE_RATIO),
            Map.entry(BattleEventType.ROUND_END, SoundCue.BATTLE_ROUND_END)
        );

        mappings.forEach((type, expectedCue) -> assertEquals(expectedCue,
            BattleAudioRouter.cueFor(event(type), null).orElseThrow(), type.name()));
    }

    @Test
    void resourceCuesIgnoreMoveCostsAndBookkeepingEvents() {
        Move paidMove = move("PAID", "ATTACK");
        assertTrue(BattleAudioRouter.cueFor(CombatEvent.of(CombatEvent.Type.CE_DRAINED)
            .move(paidMove).intValue(10).build()).isEmpty());
        assertEquals(SoundCue.BATTLE_CE_DRAIN, BattleAudioRouter.cueFor(
            CombatEvent.of(CombatEvent.Type.CE_DRAINED).intValue(10).build()).orElseThrow());
        assertEquals(SoundCue.BATTLE_CE_RESTORE, BattleAudioRouter.cueFor(
            CombatEvent.of(CombatEvent.Type.CE_RESTORED).intValue(10).build()).orElseThrow());
        assertTrue(BattleAudioRouter.cueFor(
            CombatEvent.of(CombatEvent.Type.CE_RESTORED).intValue(0).build()).isEmpty());
        assertEquals(SoundCue.BATTLE_STUN, BattleAudioRouter.cueFor(
            CombatEvent.of(CombatEvent.Type.CE_DEPLETED).move(paidMove).build()).orElseThrow());
        assertTrue(BattleAudioRouter.cueFor(
            CombatEvent.of(CombatEvent.Type.CE_DEPLETED).build()).isEmpty());
        assertTrue(BattleAudioRouter.cueFor(
            CombatEvent.of(CombatEvent.Type.MAX_HP_CHANGED).intValue(100).build()).isEmpty());

        assertTrue(BattleAudioRouter.cueFor(
            event(BattleEventType.CE_DRAINED, 10, "PAID"), null).isEmpty());
        assertEquals(SoundCue.BATTLE_CE_DRAIN, BattleAudioRouter.cueFor(
            event(BattleEventType.CE_DRAINED, 10, null), null).orElseThrow());
        assertTrue(BattleAudioRouter.cueFor(
            event(BattleEventType.MAX_CE_CHANGED, 100, null), null).isEmpty());
    }

    @Test
    void actionStartAndBattleResultEventsRemainSilent() {
        assertTrue(BattleAudioRouter.cueFor(
            CombatEvent.of(CombatEvent.Type.BATTLE_OVER).build()).isEmpty());
        assertTrue(BattleAudioRouter.cueFor(event(BattleEventType.MOVE_STARTED), null).isEmpty());
        assertTrue(BattleAudioRouter.cueFor(event(BattleEventType.ROUND_START), null).isEmpty());
        assertTrue(BattleAudioRouter.cueFor(event(BattleEventType.BATTLE_OVER), null).isEmpty());
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
        return event(type, 1, null);
    }

    private static BattleEventState event(BattleEventType type, Integer value, String moveId) {
        return new BattleEventState(
            "EVENT", type, 1, 1,
            null, null, null,
            null, null, null,
            moveId, null, value, null, ""
        );
    }
}

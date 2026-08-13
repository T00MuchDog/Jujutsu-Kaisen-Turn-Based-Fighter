package com.jjktbf.graphics.screens;

import com.jjktbf.multiplayer.protocol.BattleEventState;
import com.jjktbf.multiplayer.protocol.BattleEventType;
import com.jjktbf.multiplayer.protocol.BattlePhase;
import com.jjktbf.multiplayer.protocol.CharacterState;
import com.jjktbf.multiplayer.protocol.MatchState;
import com.jjktbf.multiplayer.protocol.MatchStatus;
import com.jjktbf.multiplayer.protocol.PlayerSide;
import com.jjktbf.multiplayer.protocol.PlayerState;
import com.jjktbf.multiplayer.protocol.RoundStartCharacterState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BattleScreenMultiplayerCombatantVisibilityTest {

    @Test
    void summonedCombatantJoinsTheVisualRosterAtItsPlaybackEvent() {
        CharacterState fighter = combatant(
            "MEGUMI", "Megumi", "player-fighter-1", "FIGHTER", null, 0);
        CharacterState nue = combatant(
            "NUE", "Nue", "player-summon-1", "SUMMON", fighter.instanceId(), 1);
        PlayerState player = player(fighter, nue);
        MatchState state = state(
            player,
            List.of(roundStart(fighter)),
            List.of(summonEvent(fighter, nue)));

        List<CharacterState> roundStart = BattleScreen.roundStartOnlineCombatants(
            state, PlayerSide.PLAYER_ONE, player);

        assertEquals(List.of(fighter), roundStart);
        assertEquals(List.of(fighter, nue),
            BattleScreen.withOnlineCombatantVisible(roundStart, nue));
        assertEquals(List.of(fighter, nue),
            BattleScreen.withOnlineCombatantVisible(List.of(fighter, nue), nue));
        assertEquals(List.of(fighter, nue), BattleScreen.activeOnlineCombatants(player));
    }

    @Test
    void existingSummonIsVisibleAtTheStartOfTheNextRound() {
        CharacterState fighter = combatant(
            "MEGUMI", "Megumi", "player-fighter-1", "FIGHTER", null, 0);
        CharacterState nue = combatant(
            "NUE", "Nue", "player-summon-1", "SUMMON", fighter.instanceId(), 1);
        PlayerState player = player(fighter, nue);
        MatchState state = state(
            player,
            List.of(roundStart(fighter), roundStart(nue)),
            List.of());

        assertEquals(List.of(fighter, nue), BattleScreen.roundStartOnlineCombatants(
            state, PlayerSide.PLAYER_ONE, player));
    }

    private static CharacterState combatant(
        String characterId,
        String name,
        String instanceId,
        String role,
        String summonerId,
        int rosterOrder
    ) {
        return new CharacterState(
            characterId, name, 100, 100, 50, 50, 0, 10, 0,
            false, 0, null, List.of(), List.of(), List.of(), null,
            instanceId, "SUMMON".equals(role) ? "SHIKIGAMI" : "SORCERER",
            role, "ACTIVE", summonerId, rosterOrder, null);
    }

    private static PlayerState player(CharacterState... combatants) {
        return new PlayerState(
            "player-1", "Player One", PlayerSide.PLAYER_ONE,
            true, false, false, null, List.of(combatants));
    }

    private static RoundStartCharacterState roundStart(CharacterState combatant) {
        return new RoundStartCharacterState(
            PlayerSide.PLAYER_ONE,
            combatant.currentHp(), combatant.maxHp(),
            combatant.currentCe(), combatant.maxCe(), List.of(), combatant.instanceId());
    }

    private static BattleEventState summonEvent(
        CharacterState summoner,
        CharacterState summon
    ) {
        return new BattleEventState(
            "summon-event", BattleEventType.COMBATANT_SUMMONED, 1, 4,
            PlayerSide.PLAYER_ONE, summoner.characterId(), summoner.name(),
            PlayerSide.PLAYER_ONE, summon.characterId(), summon.name(),
            null, null, null, null, null, "Nue joins the battle!",
            summoner.instanceId(), summon.instanceId());
    }

    private static MatchState state(
        PlayerState player,
        List<RoundStartCharacterState> roundStart,
        List<BattleEventState> events
    ) {
        return new MatchState(
            "match-1", MatchStatus.ACTIVE, "test", 1, "STANDARD",
            BattlePhase.ROUND_END, 1, 0, List.of(player), roundStart,
            null, null, null, 1L, events, 1L);
    }
}

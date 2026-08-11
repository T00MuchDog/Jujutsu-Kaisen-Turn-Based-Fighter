package com.jjktbf.graphics.screens;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeam;
import com.jjktbf.model.combat.BattleTeamId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BattleScreenCombatantVisibilityTest {

    @Test
    void summonedShikigamiOccupiesSecondVisualSlotUntilDismissed() {
        BattleCombatant fighter = fighter("Megumi");
        BattleState state = new BattleState(fighter, fighter("Enemy"));
        BattleCombatant summon = summon(state, fighter, "Divine Dog");

        assertEquals(List.of(fighter, summon),
            BattleScreen.visibleCombatants(state.playerTeam()));

        state.recursivelyDismissSummonsOf(fighter);

        assertEquals(List.of(fighter),
            BattleScreen.visibleCombatants(state.playerTeam()));
    }

    @Test
    void defeatedShikigamiLeavesVisualSlotsAfterReconciliation() {
        BattleCombatant fighter = fighter("Megumi");
        BattleState state = new BattleState(fighter, fighter("Enemy"));
        BattleCombatant summon = summon(state, fighter, "Divine Dog");

        summon.receiveDamage(summon.getCurrentHp());
        state.reconcileDefeats();

        assertEquals(List.of(fighter),
            BattleScreen.visibleCombatants(state.playerTeam()));
    }

    @Test
    void survivingFighterMovesIntoPrimaryVisualSlot() {
        BattleCombatant first = fighter("Yuji");
        BattleCombatant second = fighter("Nanami");
        BattleTeam players = BattleState.teamOfFighters(
            BattleTeamId.PLAYER, List.of(first, second));
        BattleState state = new BattleState(
            players,
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(fighter("Enemy"))));

        first.receiveDamage(first.getCurrentHp());
        state.reconcileDefeats();

        assertEquals(List.of(second), BattleScreen.visibleCombatants(players));
    }

    @Test
    void visualRosterUsesFirstFourActiveCombatantsInStableOrder() {
        BattleCombatant first = fighter("Megumi");
        BattleCombatant second = fighter("Yuji");
        BattleCombatant third = fighter("Nobara");
        BattleCombatant fourth = fighter("Gojo");
        BattleCombatant fifth = fighter("Nanami");
        BattleTeam players = BattleState.teamOfFighters(
            BattleTeamId.PLAYER, List.of(first, second, third, fourth, fifth));

        assertEquals(List.of(first, second, third, fourth),
            BattleScreen.visibleCombatants(players));
    }

    private static BattleCombatant summon(
        BattleState state,
        BattleCombatant summoner,
        String name
    ) {
        state.enqueueSummon(summoner, name.toLowerCase().replace(' ', '-'));
        return state.drainPendingSummons(
            id -> Optional.of(shikigami(id, name))).get(0);
    }

    private static BattleCombatant fighter(String name) {
        SorcererCharacter character = new SorcererCharacter(
            name.toLowerCase(), name, new CharacterStats.Builder().build(),
            null, List.of(), List.of(), false);
        return new BattleCombatant(character, List.of());
    }

    private static ShikigamiCharacter shikigami(String id, String name) {
        return new ShikigamiCharacter(
            id, name, new CharacterStats.Builder().build(),
            null, List.of(), List.of(), false);
    }
}

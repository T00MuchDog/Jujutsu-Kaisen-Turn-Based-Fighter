package com.jjktbf.graphics.screens;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeam;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.jjktbf.model.character.Equipment;

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

    @Test
    void lifecycleRosterRetainsACombatantDefeatedBeforeTheFieldWasBound() {
        BattleCombatant defeated = fighter("Yuji");
        BattleCombatant survivor = fighter("Nanami");
        BattleTeam players = BattleState.teamOfFighters(
            BattleTeamId.PLAYER, List.of(defeated, survivor));
        BattleState state = new BattleState(
            players,
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(fighter("Enemy"))));
        defeated.receiveDamage(defeated.getCurrentHp());
        state.reconcileDefeats();

        assertEquals(List.of(defeated, survivor),
            BattleScreen.lifecycleVisualRoster(players, List.of(), Set.of(defeated)));
    }

    @Test
    void battleEndLifecycleTargetsExcludeFightersWhoseFaintsWereAlreadyPresented() {
        BattleCombatant first = fighter("Yuji");
        BattleCombatant last = fighter("Nanami");
        BattleTeam players = BattleState.teamOfFighters(
            BattleTeamId.PLAYER, List.of(first, last));
        BattleState state = new BattleState(
            players,
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(fighter("Enemy"))));

        first.receiveDamage(first.getCurrentHp());
        state.reconcileDefeats();
        last.receiveDamage(last.getCurrentHp());
        state.reconcileDefeats();
        state.checkAndResolveBattleOver();

        List<CombatEvent> terminalEvents = List.of(
            CombatEvent.of(CombatEvent.Type.COMBATANT_DEFEATED).target(last).build(),
            CombatEvent.of(CombatEvent.Type.BATTLE_OVER).build());

        assertEquals(Set.of(last), BattleScreen.pendingLocalLifecycleTargets(
            terminalEvents, state, Set.of(first.getInstanceId())));
        assertEquals(Set.of(), BattleScreen.pendingLocalLifecycleTargets(
            List.of(), state, Set.of(first.getInstanceId(), last.getInstanceId())));
    }

    @Test
    void visibleRosterBackfillsItsFourthSlotAfterADefeat() {
        BattleCombatant first = fighter("Yuji");
        BattleCombatant second = fighter("Nanami");
        BattleCombatant third = fighter("Nobara");
        BattleCombatant fourth = fighter("Gojo");
        BattleCombatant fifth = fighter("Megumi");
        BattleTeam players = BattleState.teamOfFighters(
            BattleTeamId.PLAYER, List.of(first, second, third, fourth, fifth));
        BattleState state = new BattleState(
            players,
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(fighter("Enemy"))));
        first.receiveDamage(first.getCurrentHp());
        state.reconcileDefeats();
        List<BattleCombatant> displayed = new ArrayList<>(
            List.of(second, third, fourth));

        BattleScreen.backfillLocalVisualRoster(displayed, players);

        assertEquals(List.of(second, third, fourth, fifth), displayed);
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
            null, List.of(), List.of(), Equipment.NONE);
        return new BattleCombatant(character, List.of());
    }

    private static ShikigamiCharacter shikigami(String id, String name) {
        return new ShikigamiCharacter(
            id, name, new CharacterStats.Builder().build(),
            null, List.of(), List.of(), Equipment.NONE);
    }
}

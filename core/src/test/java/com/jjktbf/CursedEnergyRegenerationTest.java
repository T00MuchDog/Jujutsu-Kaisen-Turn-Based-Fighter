package com.jjktbf;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.BattleStatKey;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursedEnergyRegenerationTest {

    @Test
    void fightersRegeneratePointZeroFiveCePerResolutionTick() {
        BattleCombatant fighter = combatant("FIGHTER", List.of(), List.of());
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        new BattleState(fighter, enemy);
        int startingCe = fighter.getCurrentCe();
        fighter.drainCe(10);

        assertEquals(0.05, fighter.getCursedEnergyRegenerationPerTick(), 0.000001);
        for (int tick = 0; tick < 19; tick++) {
            assertEquals(0, fighter.regenerateCursedEnergyForTick());
        }
        assertEquals(1, fighter.regenerateCursedEnergyForTick());
        assertEquals(startingCe - 9, fighter.getCurrentCe());
    }

    @Test
    void activeAbilityEffectsCanAlterCursedEnergyRegeneration() {
        var regeneration = AbilityEffectType.BATTLE_STAT_ADD.createDefault();
        regeneration.stringValue = BattleStatKey.CE_REGENERATION.name();
        regeneration.doubleValue = 0.95;
        regeneration.durationRounds = -1;

        AbilityData data = new AbilityData();
        data.id = "REGENERATION_ABILITY";
        data.name = "Regeneration ability";
        data.category = "ACTIVE";
        data.sourceType = "CHARACTER";
        data.effects = List.of(regeneration);
        data.activationCondition = AbilityConditionData.always();

        BattleCombatant fighter = combatant(
            "FIGHTER", List.of(), List.of(new Ability(data)));
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(fighter, enemy);
        fighter.drainCe(10);

        new CombatResolver(new SeededRandomSource(1L)).processRoundStart(state);

        assertEquals(1.0, fighter.getCursedEnergyRegenerationPerTick(), 0.000001);
        assertEquals(1, fighter.regenerateCursedEnergyForTick());
    }

    @Test
    void moveEffectsCanAlterRegenerationForFollowingTicks() {
        MoveEffectData regeneration =
            AbilityEffectType.BATTLE_STAT_ADD.createDefaultMoveEffect();
        regeneration.effectId = "effect-000000";
        regeneration.trigger = MoveEffectTrigger.ON_FIRE.name();
        regeneration.target = AbilityEffectTarget.SELF.name();
        regeneration.stringValue = BattleStatKey.CE_REGENERATION.name();
        regeneration.doubleValue = 0.95;
        regeneration.durationRounds = 1;
        regeneration.condition = AbilityConditionData.always();

        Move focus = new Move.Builder("REGENERATION_FOCUS")
            .name("Regeneration Focus")
            .category(MoveCategory.UTILITY)
            .tags(Set.of(MoveTag.UTILITY))
            .apCost(2)
            .unleashPoint(1)
            .effects(List.of(regeneration))
            .build();
        BattleCombatant fighter = combatant("FIGHTER", List.of(focus), List.of());
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(fighter, enemy);
        fighter.drainCe(10);
        int before = fighter.getCurrentCe();
        Timeline timeline = new Timeline(10);
        timeline.placeAt(focus, 1, 0);
        fighter.setTimeline(timeline);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new SeededRandomSource(1L))
            .resolveRound(state);

        assertEquals(before + 1, fighter.getCurrentCe());
        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.CE_RESTORED
                && event.getSource() == fighter
                && event.getIntValue() == 1
                && event.getTick() == 2
                && event.getMessage().isEmpty()));
    }

    private static BattleCombatant combatant(
        String id,
        List<Move> moves,
        List<Ability> abilities
    ) {
        return new BattleCombatant(new SorcererCharacter(
            id, id, new CharacterStats.Builder().build(), null, moves, abilities));
    }
}

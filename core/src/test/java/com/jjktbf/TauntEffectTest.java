package com.jjktbf;

import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.BattleTeamId;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime coverage for the TAUNT effect primitive: while a combatant holds an
 * active Taunt, enemies' single-target MELEE attacks are pulled onto the
 * taunter. Area-of-effect attacks and ranged single-target attacks are
 * unaffected. Drives the full CombatResolver via resolveRound.
 */
class TauntEffectTest {

    /**
     * A single-target MELEE attack aimed at the victim is redirected onto the
     * taunter: the victim is unharmed, the taunter takes the hit, a
     * TARGET_RETARGETED event is emitted, and the taunter reports an active Taunt.
     */
    @Test
    void meleeSingleTargetAttackIsRedirectedToTaunter() {
        BattleCombatant attacker = fighter("Attacker", 100);
        BattleCombatant taunter = fighter("Taunter", 200);   // faster: applies Taunt first
        BattleCombatant victim = fighter("Victim", 50);
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(attacker)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(taunter, victim)));

        planTauntAtTick1(state, taunter);
        BattlePlan attackPlan = planFor(attacker);
        attackPlan.place(meleeAttack("MELEE"), 1, 0, victim.getInstanceId());
        attacker.setTimeline(attackPlan.toLegacyTimeline());

        int taunterBefore = taunter.getCurrentHp();
        int victimBefore = victim.getCurrentHp();
        List<CombatEvent> events = resolveRoundEvents(state);

        assertTrue(taunter.getCurrentHp() < taunterBefore,
            "The taunter (not the victim) should take the redirected melee hit.");
        assertEquals(victimBefore, victim.getCurrentHp(),
            "The originally targeted victim should be unharmed.");

        CombatEvent retarget = events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.TARGET_RETARGETED)
            .findFirst().orElse(null);
        assertNotNull(retarget, "A TARGET_RETARGETED event should be emitted.");
        assertEquals(taunter, retarget.getTarget(), "Retarget should point at the taunter.");
        assertTrue(retarget.getMessage().contains("drawn to"),
            "Unexpected retarget message: " + retarget.getMessage());
    }

    /**
     * An area-of-effect attack (ALL_ENEMIES) is not redirected: both the taunter
     * and the victim take damage, and no retarget event is emitted.
     */
    @Test
    void aoeAttackStillHitsEveryEnemyAndIsNotRedirected() {
        BattleCombatant attacker = fighter("Attacker", 100);
        BattleCombatant taunter = fighter("Taunter", 200);
        BattleCombatant victim = fighter("Victim", 50);
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(attacker)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(taunter, victim)));

        planTauntAtTick1(state, taunter);
        BattlePlan attackPlan = planFor(attacker);
        attackPlan.place(aoeAttack("AOE"), 1, 0);
        attacker.setTimeline(attackPlan.toLegacyTimeline());

        int taunterBefore = taunter.getCurrentHp();
        int victimBefore = victim.getCurrentHp();
        List<CombatEvent> events = resolveRoundEvents(state);

        assertTrue(taunter.getCurrentHp() < taunterBefore, "Taunter is hit by the AOE.");
        assertTrue(victim.getCurrentHp() < victimBefore,
            "Victim is still hit by the AOE (not redirected onto the taunter).");
        assertFalse(events.stream().anyMatch(event ->
                event.getType() == CombatEvent.Type.TARGET_RETARGETED),
            "An AOE attack must never be redirected by a Taunt.");
    }

    /**
     * A ranged single-target attack is not redirected: the victim takes the hit
     * and the taunter is unharmed.
     */
    @Test
    void rangedSingleTargetAttackIsNotRedirected() {
        BattleCombatant attacker = fighter("Attacker", 100);
        BattleCombatant taunter = fighter("Taunter", 200);
        BattleCombatant victim = fighter("Victim", 50);
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(attacker)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(taunter, victim)));

        planTauntAtTick1(state, taunter);
        BattlePlan attackPlan = planFor(attacker);
        attackPlan.place(rangedAttack("RANGED"), 1, 0, victim.getInstanceId());
        attacker.setTimeline(attackPlan.toLegacyTimeline());

        int taunterBefore = taunter.getCurrentHp();
        int victimBefore = victim.getCurrentHp();
        List<CombatEvent> events = resolveRoundEvents(state);

        assertTrue(victim.getCurrentHp() < victimBefore,
            "The ranged attack should still hit its selected victim.");
        assertEquals(taunterBefore, taunter.getCurrentHp(),
            "A ranged attack must not be pulled onto the taunter.");
        assertFalse(events.stream().anyMatch(event ->
                event.getType() == CombatEvent.Type.TARGET_RETARGETED),
            "A ranged single-target attack must never be redirected by a Taunt.");
    }

    /** The TAUNT primitive builds a valid, move-eligible, non-passive effect row. */
    @Test
    void tauntPrimitiveProducesAValidMoveEffect() {
        MoveEffectData effect = AbilityEffectType.TAUNT.createDefaultMoveEffect();
        assertEquals(AbilityEffectType.TAUNT.name(), effect.type);
        assertEquals(AbilityEffectTarget.SELF.name(), effect.target);
        assertEquals(20, effect.durationTicks);
        assertNull(AbilityEffectType.TAUNT.validationError(effect),
            "Default TAUNT effect should validate cleanly.");
        assertTrue(AbilityEffectType.TAUNT.isMoveEffect(),
            "TAUNT must be usable from a move effect row.");
        assertFalse(AbilityEffectType.TAUNT.isPassiveOnly());
    }

    /**
     * The runtime query tracks the strongest active TAUNT and reports no taunt
     * once it expires. Applied directly so resolveRound's tick-down does not
     * consume the duration before the assertion.
     */
    @Test
    void runtimeQueryTracksActiveTauntTicks() {
        BattleCombatant combatant = fighter("Solo", 100);
        assertFalse(combatant.hasActiveTaunt(), "No taunt before one is applied.");
        assertEquals(0, combatant.getActiveTauntRemainingTicks());

        AbilityEffectData taunt = AbilityEffectType.TAUNT.createDefault();
        taunt.durationRounds = 0;
        taunt.durationTicks = 20;
        combatant.addRuntimeAbilityEffect(taunt, 0, BattleState.Phase.RESOLUTION);

        assertTrue(combatant.hasActiveTaunt(), "Taunt is active once applied.");
        assertEquals(20, combatant.getActiveTauntRemainingTicks(),
            "Query should report the remaining tick duration.");

        // Re-taunting refreshes the duration instead of stacking a second row.
        combatant.addRuntimeAbilityEffect(taunt, 0, BattleState.Phase.RESOLUTION, "TAUNT");
        assertEquals(20, combatant.getActiveTauntRemainingTicks(),
            "Re-taunting should refresh, not stack.");
    }

    // ----- helpers -----

    private static void planTauntAtTick1(BattleState state, BattleCombatant taunter) {
        BattlePlan plan = planFor(taunter);
        plan.place(tauntMove(), 1, 0);
        taunter.setTimeline(plan.toLegacyTimeline());
    }

    private static List<CombatEvent> resolveRoundEvents(BattleState state) {
        state.transitionTo(BattleState.Phase.RESOLUTION);
        return new CombatResolver(new SeededRandomSource(1L)).resolveRound(state);
    }

    private static BattlePlan planFor(BattleCombatant combatant) {
        return new BattlePlan(combatant.getMaxApBar(), combatant.getCurrentCe(), 60);
    }

    private static BattleCombatant fighter(String name, int speed) {
        CharacterStats stats = new CharacterStats.Builder().vitality(300).speed(speed).build();
        SorcererCharacter character = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), false);
        return new BattleCombatant(character, List.of());
    }

    private static Move tauntMove() {
        return new Move.Builder("TAUNT_MOVE")
            .name("Taunt").category(MoveCategory.UTILITY)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.UTILITY))
            .apCost(2).unleashPoint(1)
            .effects(List.of(tauntEffect()))
            .build();
    }

    private static MoveEffectData tauntEffect() {
        MoveEffectData effect = AbilityEffectType.TAUNT.createDefaultMoveEffect();
        effect.trigger = MoveEffectTrigger.ON_FIRE.name();
        effect.target = AbilityEffectTarget.SELF.name();
        effect.durationRounds = 0;
        effect.durationTicks = 20;
        return effect;
    }

    private static Move meleeAttack(String id) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL).neverMiss(true)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.MELEE))
            .apCost(2).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(20, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }

    private static Move rangedAttack(String id) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL).neverMiss(true)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.RANGED))
            .apCost(2).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(20, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }

    private static Move aoeAttack(String id) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL).neverMiss(true)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.AOE))
            .apCost(2).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(20, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }
}

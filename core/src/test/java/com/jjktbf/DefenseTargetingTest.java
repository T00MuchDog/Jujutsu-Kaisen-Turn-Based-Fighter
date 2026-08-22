package com.jjktbf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.jjktbf.model.combat.CombatantId;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.move.DefenseTargeting;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.jjktbf.model.character.Equipment;

/**
 * Runtime coverage for the defensive targeting system: a defensive move whose
 * {@link DefenseTargeting} is an ally mode confers its active-defense window to
 * the beneficiary's timeline at fire time (a fired clone is inserted and the
 * original on the caster's timeline is marked transferred).
 *
 * <p>The grant is exercised through the full {@link CombatResolver} via
 * resolveRound, using deterministic 100% dodge so the grant (not the
 * probability) is under test.
 */
class DefenseTargetingTest {

    /**
     * SINGLE_ALLY: the protector grants a ranged dodge to the ally. A ranged
     * attack on the ally is DODGED (ally unharmed) and a DEFENSE_GRANTED event
     * is emitted pointing at the ally.
     */
    @Test
    void singleAllyDodgeProtectsTheAlly() {
        BattleCombatant protector = fighter("Protector", 200); // faster: dodge fires first
        BattleCombatant ally = fighter("Ally", 100);
        BattleCombatant attacker = fighter("Attacker", 50);
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(protector, ally)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(attacker)));

        grantAllyDodge(state, protector, ally, DefenseTargeting.SINGLE_ALLY, ally.getInstanceId());
        BattlePlan attackPlan = planFor(attacker);
        attackPlan.place(rangedAttack(), 1, 0, ally.getInstanceId());
        attacker.setTimeline(attackPlan.toLegacyTimeline());

        int allyBefore = ally.getCurrentHp();
        List<CombatEvent> events = resolveRoundEvents(state);

        assertEquals(allyBefore, ally.getCurrentHp(),
            "The ally should DODGE the ranged attack thanks to the granted defense.");

        CombatEvent granted = events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.DEFENSE_GRANTED)
            .findFirst().orElse(null);
        assertNotNull(granted, "A DEFENSE_GRANTED event should be emitted.");
        assertEquals(ally, granted.getTarget(),
            "The granted defense should be conferred to the ally.");
    }

    /**
     * Once the dodge has been conferred to an ally, the caster no longer
     * benefits from it: a ranged attack on the protector lands (the original
     * segment was transferred off the protector's own timeline).
     */
    @Test
    void casterIsNoLongerProtectedAfterGranting() {
        BattleCombatant protector = fighter("Protector", 200);
        BattleCombatant ally = fighter("Ally", 100);
        BattleCombatant attacker = fighter("Attacker", 50);
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(protector, ally)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(attacker)));

        grantAllyDodge(state, protector, ally, DefenseTargeting.SINGLE_ALLY, ally.getInstanceId());
        BattlePlan attackPlan = planFor(attacker);
        attackPlan.place(rangedAttack(), 1, 0, protector.getInstanceId());
        attacker.setTimeline(attackPlan.toLegacyTimeline());

        int protectorBefore = protector.getCurrentHp();
        resolveRoundEvents(state);

        assertTrue(protector.getCurrentHp() < protectorBefore,
            "The protector should NOT be protected once the dodge was granted to an ally.");
    }

    /**
     * A granted RANGED dodge does not stop a MELEE attack on the ally: the
     * dodgeScope filtering still applies on the beneficiary's timeline.
     */
    @Test
    void grantedRangedDodgeDoesNotStopMelee() {
        BattleCombatant protector = fighter("Protector", 200);
        BattleCombatant ally = fighter("Ally", 100);
        BattleCombatant attacker = fighter("Attacker", 50);
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(protector, ally)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(attacker)));

        grantAllyDodge(state, protector, ally, DefenseTargeting.SINGLE_ALLY, ally.getInstanceId());
        BattlePlan attackPlan = planFor(attacker);
        attackPlan.place(meleeAttack(), 1, 0, ally.getInstanceId());
        attacker.setTimeline(attackPlan.toLegacyTimeline());

        int allyBefore = ally.getCurrentHp();
        resolveRoundEvents(state);

        assertTrue(ally.getCurrentHp() < allyBefore,
            "A granted RANGED dodge must not avoid a MELEE attack (scope filtering).");
    }

    /** Regression: a SELF-targeted dodge still protects its own caster. */
    @Test
    void selfTargetedDodgeStillProtectsCaster() {
        BattleCombatant protector = fighter("Protector", 200);
        BattleCombatant attacker = fighter("Attacker", 50);
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(protector)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(attacker)));

        BattlePlan defensePlan = planFor(protector);
        defensePlan.place(dodgeMove(DefenseTargeting.SELF), 1, 0);
        protector.setTimeline(defensePlan.toLegacyTimeline());

        BattlePlan attackPlan = planFor(attacker);
        attackPlan.place(rangedAttack(), 1, 0, protector.getInstanceId());
        attacker.setTimeline(attackPlan.toLegacyTimeline());

        int protectorBefore = protector.getCurrentHp();
        resolveRoundEvents(state);

        assertEquals(protectorBefore, protector.getCurrentHp(),
            "A SELF dodge must still protect its caster.");
    }

    /**
     * ALL_ALLIES_EXCEPT_SELF: every ally is granted the dodge (the ally dodges)
     * while the caster is not (a ranged attack on the protector still lands).
     * Two attackers are used so each attack is planned on its own timeline.
     */
    @Test
    void allAlliesExceptSelfGrantsToAlliesNotCaster() {
        BattleCombatant protector = fighter("Protector", 200);
        BattleCombatant ally = fighter("Ally", 100);
        BattleCombatant allyAttacker = fighter("AllyAttacker", 50);
        BattleCombatant protectorAttacker = fighter("ProtectorAttacker", 40);
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(protector, ally)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(allyAttacker, protectorAttacker)));

        // Auto-resolving mode: no explicit target selection needed.
        BattlePlan defensePlan = planFor(protector);
        defensePlan.place(dodgeMove(DefenseTargeting.ALL_ALLIES_EXCEPT_SELF), 1, 0);
        protector.setTimeline(defensePlan.toLegacyTimeline());
        ally.setTimeline(planFor(ally).toLegacyTimeline());

        BattlePlan allyAttackPlan = planFor(allyAttacker);
        allyAttackPlan.place(rangedAttack(), 1, 0, ally.getInstanceId());
        allyAttacker.setTimeline(allyAttackPlan.toLegacyTimeline());

        BattlePlan protectorAttackPlan = planFor(protectorAttacker);
        protectorAttackPlan.place(rangedAttack(), 1, 0, protector.getInstanceId());
        protectorAttacker.setTimeline(protectorAttackPlan.toLegacyTimeline());

        int protectorBefore = protector.getCurrentHp();
        int allyBefore = ally.getCurrentHp();
        resolveRoundEvents(state);

        assertEquals(allyBefore, ally.getCurrentHp(),
            "The ally should DODGE under ALL_ALLIES_EXCEPT_SELF.");
        assertTrue(protector.getCurrentHp() < protectorBefore,
            "The caster should NOT be protected under ALL_ALLIES_EXCEPT_SELF.");
    }

    /**
     * ALL_ALLIES_INCLUDING_SELF: the caster KEEPS its own protection (the bug
     * fixed alongside ally effect targeting) while the ally is also granted the
     * dodge. Two attackers verify both combatants dodge.
     */
    @Test
    void allAlliesIncludingSelfProtectsCasterAndAllies() {
        BattleCombatant protector = fighter("Protector", 200);
        BattleCombatant ally = fighter("Ally", 100);
        BattleCombatant allyAttacker = fighter("AllyAttacker", 50);
        BattleCombatant protectorAttacker = fighter("ProtectorAttacker", 40);
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(protector, ally)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(allyAttacker, protectorAttacker)));

        BattlePlan defensePlan = planFor(protector);
        defensePlan.place(dodgeMove(DefenseTargeting.ALL_ALLIES_INCLUDING_SELF), 1, 0);
        protector.setTimeline(defensePlan.toLegacyTimeline());
        ally.setTimeline(planFor(ally).toLegacyTimeline());

        BattlePlan allyAttackPlan = planFor(allyAttacker);
        allyAttackPlan.place(rangedAttack(), 1, 0, ally.getInstanceId());
        allyAttacker.setTimeline(allyAttackPlan.toLegacyTimeline());

        BattlePlan protectorAttackPlan = planFor(protectorAttacker);
        protectorAttackPlan.place(rangedAttack(), 1, 0, protector.getInstanceId());
        protectorAttacker.setTimeline(protectorAttackPlan.toLegacyTimeline());

        int protectorBefore = protector.getCurrentHp();
        int allyBefore = ally.getCurrentHp();
        resolveRoundEvents(state);

        assertEquals(allyBefore, ally.getCurrentHp(),
            "The ally should DODGE under ALL_ALLIES_INCLUDING_SELF.");
        assertEquals(protectorBefore, protector.getCurrentHp(),
            "The caster should ALSO be protected under ALL_ALLIES_INCLUDING_SELF.");
    }

    /**
     * An on-fire effect authored with target ALLY lands on the defensive move's
     * granted ally: a SINGLE_ALLY dodge carrying an on-fire HEAL heals the ally
     * it is conferred to.
     */
    @Test
    void onFireAllyEffectHitsTheGrantedAlly() {
        BattleCombatant protector = fighter("Protector", 200);
        BattleCombatant ally = fighter("Ally", 100);
        ally.applyDamage(200); // 300 -> 100 so the heal is observable
        BattleCombatant attacker = fighter("Attacker", 50);
        BattleState state = new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(protector, ally)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(attacker)));

        BattlePlan defensePlan = planFor(protector);
        defensePlan.place(dodgeMoveWithAllyHeal(DefenseTargeting.SINGLE_ALLY, 150),
            1, 0, ally.getInstanceId());
        protector.setTimeline(defensePlan.toLegacyTimeline());
        ally.setTimeline(planFor(ally).toLegacyTimeline());

        resolveRoundEvents(state);

        assertTrue(ally.getCurrentHp() > 100,
            "The on-fire HEAL (target ALLY) should heal the ally the dodge was granted to.");
    }

    // ----- helpers -----

    /**
     * Plan and install an ally-granting dodge on the protector's timeline, and
     * ensure the ally has a timeline that can receive the granted clone.
     */
    private static void grantAllyDodge(
        BattleState state, BattleCombatant protector, BattleCombatant ally,
        DefenseTargeting targeting, CombatantId selectedAlly
    ) {
        BattlePlan defensePlan = planFor(protector);
        defensePlan.place(dodgeMove(targeting), 1, 0, selectedAlly);
        protector.setTimeline(defensePlan.toLegacyTimeline());
        ally.setTimeline(planFor(ally).toLegacyTimeline());
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
            name.toLowerCase(), name, stats, null, List.of(), List.of(), Equipment.NONE);
        return new BattleCombatant(character, List.of());
    }

    /** A DODGE move granting a deterministic 100% ranged dodge for 8 ticks. */
    private static Move dodgeMove(DefenseTargeting targeting) {
        return new Move.Builder("ALLY_DODGE_" + targeting.name())
            .name("Out of the way").category(MoveCategory.DEFENSIVE)
            .tags(Set.of(MoveTag.DEFENSIVE, MoveTag.PHYSICAL))
            .apCost(2).unleashPoint(1)
            .defenseType(DefenseType.DODGE)
            .dodgeChance(100)
            .dodgeScope("RANGED")
            .blockDuration(8)
            .defenseTargeting(targeting)
            .build();
    }

    /** A DODGE move that also fires an on-fire HEAL targeting the granted ally. */
    private static Move dodgeMoveWithAllyHeal(DefenseTargeting targeting, int healAmount) {
        MoveEffectData heal = AbilityEffectType.HEAL_HP.createDefaultMoveEffect();
        heal.trigger = MoveEffectTrigger.ON_FIRE.name();
        heal.target = AbilityEffectTarget.ALLY.name();
        heal.intValue = healAmount;
        return new Move.Builder("ALLY_HEAL_DODGE_" + targeting.name())
            .name("Out of the way").category(MoveCategory.DEFENSIVE)
            .tags(Set.of(MoveTag.DEFENSIVE, MoveTag.PHYSICAL))
            .apCost(2).unleashPoint(1)
            .defenseType(DefenseType.DODGE)
            .dodgeChance(100)
            .dodgeScope("RANGED")
            .blockDuration(8)
            .defenseTargeting(targeting)
            .effects(List.of(heal))
            .build();
    }

    private static Move rangedAttack() {
        return new Move.Builder("RANGED_ATTACK")
            .name("Ranged Attack").category(MoveCategory.PHYSICAL).neverMiss(true)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.RANGED))
            .apCost(2).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(20, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }

    private static Move meleeAttack() {
        return new Move.Builder("MELEE_ATTACK")
            .name("Melee Attack").category(MoveCategory.PHYSICAL).neverMiss(true)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.MELEE))
            .apCost(2).unleashPoint(1)
            .hitComponents(List.of(new HitComponent(20, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .build();
    }
}

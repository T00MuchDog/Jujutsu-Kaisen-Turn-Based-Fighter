package com.jjktbf;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionRuleData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.coded.MiraclesAbility;
import com.jjktbf.model.combat.AbilityTrigger;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.AbilityActivationEngine;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiraclesTechniqueTest {

    @Test
    void miraclesStartFullAndNegateLethalDamageWithoutUsingAStatus() {
        BattleCombatant owner = miracleCombatant("OWNER", List.of());
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);
        List<CombatEvent> startEvents = new CombatResolver(new FixedRandom()).processRoundStart(state);

        int hpBeforeLethalHit = owner.getCurrentHp();
        assertEquals(MiraclesAbility.MAX_MIRACLES, miracleCount(owner));
        assertEquals("OWNER gains 6 Miracles (6/6 remaining).", startEvents.get(0).getMessage());
        assertTrue(owner.getActiveEffects().isEmpty());
        AbilityActivationEngine engine = new AbilityActivationEngine(
            new SeededRandomSource(new FixedRandom()));
        assertEquals(0, owner.receiveDamage(
            hpBeforeLethalHit,
            amount -> engine.preventFatalDamage(state, AbilityTrigger.fatalDamage(
                enemy, owner, null, null, amount, 1))));
        assertEquals(hpBeforeLethalHit, owner.getCurrentHp());
        assertEquals(MiraclesAbility.MAX_MIRACLES - 1, miracleCount(owner));
        List<CombatEvent> aversionEvents = owner.getCodedAbilities().drainPendingEvents(1);
        assertEquals(1, aversionEvents.size());
        assertEquals("OWNER uses 1 Miracle to avert a fatal blow (5/6 remaining).",
            aversionEvents.get(0).getMessage());
        assertEquals(5, aversionEvents.get(0).getCodedAbilityState().currentValue());
        assertTrue(owner.getCodedAbilities().drainPendingEvents(1).isEmpty());

        owner.clearStatusEffects();
        assertEquals(MiraclesAbility.MAX_MIRACLES - 1, miracleCount(owner));
    }

    @Test
    void missesAndBlackFlashesRestoreMiraclesWithoutExceedingTheCap() {
        BattleCombatant owner = miracleCombatant("OWNER", List.of());
        BattleCombatant enemy = combatant("ENEMY", List.of(), List.of());
        BattleState state = new BattleState(owner, enemy);
        new CombatResolver(new FixedRandom()).processRoundStart(state);
        AbilityActivationEngine engine = new AbilityActivationEngine(new SeededRandomSource(new FixedRandom()));
        Move attack = attack("ATTACK");

        avertFatal(owner, enemy, state, engine, 0);
        List<CombatEvent> missedEvents = engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.ATTACK_MISSED, enemy, owner, attack, 1));
        assertEquals(MiraclesAbility.MAX_MIRACLES, miracleCount(owner));
        assertEquals("OWNER gains 1 Miracle (6/6 remaining).", missedEvents.get(0).getMessage());

        avertFatal(owner, enemy, state, engine, 1);
        avertFatal(owner, enemy, state, engine, 1);
        List<CombatEvent> blackFlashEvents = engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.BLACK_FLASH, owner, enemy, attack, 2));
        assertEquals(MiraclesAbility.MAX_MIRACLES - 1, miracleCount(owner));
        assertEquals("OWNER gains 1 Miracle (5/6 remaining).", blackFlashEvents.get(0).getMessage());

        engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.ATTACK_MISSED, enemy, owner, attack, 3));
        engine.process(state, AbilityTrigger.move(
            AbilityTrigger.Type.BLACK_FLASH, owner, enemy, attack, 4));
        assertEquals(MiraclesAbility.MAX_MIRACLES, miracleCount(owner));
    }

    @Test
    void dodgeDoesNotGrantAMiracleOnlyNaturalMissesDo() {
        // Fortune Reclaimed (without Reservoir) starts at 0 Miracles. A DODGE
        // move that avoids an attack must NOT fire ATTACK_MISSED, so it grants
        // nothing; a genuine natural miss still grants one. Drives the full
        // CombatResolver end-to-end so the dodge-vs-miss distinction is real,
        // not asserted by hand-firing a trigger. The defender is faster so its
        // same-tick instant dodge is already fired when the attack resolves
        // (the resolver only lets a defense contest an attack after it fires).
        Move attack = new Move.Builder("ATTACK")
            .name("Attack")
            .basePower(10)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();
        Move dodge = new Move.Builder("DODGE")
            .name("Dodge")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.DODGE)
            .dodgeChance(100)
            .dodgeScope("BOTH")
            .apCost(10)
            .unleashPoint(1)
            .build();

        // --- (a) Dodge: no miracle granted. ---
        BattleCombatant ownerDodge = miracleCombatantWithFortuneOnly(dodge);
        BattleCombatant enemyDodge = combatant("ENEMY", List.of(attack), List.of());
        Timeline enemyTlDodge = new Timeline(30);
        assertNotNull(enemyTlDodge.placeAt(attack, 1, 0));
        enemyDodge.setTimeline(enemyTlDodge);
        BattleState dodgeState = new BattleState(ownerDodge, enemyDodge);
        dodgeState.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> dodgeEvents = new CombatResolver(new FixedRandom()).resolveRound(dodgeState);

        assertTrue(dodgeEvents.stream().anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_DODGED),
            "A dodge should produce a MOVE_DODGED event.");
        assertEquals(0, miracleCount(ownerDodge),
            "A dodge-move miss must NOT grant a miracle (only a natural miss should).");

        // --- (b) Natural miss: a miracle is granted. ---
        // Remove the dodge so the only way to avoid is the hit roll, and force a
        // natural miss with a zero-accuracy attack (RNG-independent).
        Move missableAttack = new Move.Builder("MISSABLE")
            .name("Missable")
            .basePower(10)
            .baseAccuracy(0.0)
            .apCost(10)
            .unleashPoint(1)
            .build();
        BattleCombatant ownerMiss = miracleCombatantWithFortuneOnly(null);
        BattleCombatant enemyMiss = combatant("ENEMY", List.of(missableAttack), List.of());
        Timeline enemyTlMiss = new Timeline(30);
        assertNotNull(enemyTlMiss.placeAt(missableAttack, 1, 0));
        enemyMiss.setTimeline(enemyTlMiss);
        BattleState missState = new BattleState(ownerMiss, enemyMiss);
        missState.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> missEvents = new CombatResolver(new FixedRandom()).resolveRound(missState);

        assertTrue(missEvents.stream().anyMatch(e -> e.getType() == CombatEvent.Type.MOVE_MISSED),
            "A zero-accuracy attack should naturally miss.");
        assertEquals(1, miracleCount(ownerMiss),
            "A natural miss must still grant a miracle.");
    }

    @Test
    void eachCombatantGetsAnIndependentMiraclesRuntime() {
        Character sharedCharacter = new SorcererCharacter(
            "MIRACLE_USER", "Miracle User", new CharacterStats.Builder().build(),
            "Miracles", List.of(), miracleAbilities());
        BattleCombatant first = new BattleCombatant(sharedCharacter);
        BattleCombatant second = new BattleCombatant(sharedCharacter);

        new CombatResolver(new FixedRandom()).processRoundStart(new BattleState(
            first, combatant("ENEMY_ONE", List.of(), List.of())));
        new CombatResolver(new FixedRandom()).processRoundStart(new BattleState(
            second, combatant("ENEMY_TWO", List.of(), List.of())));
        BattleCombatant firstEnemy = combatant("FIRST_ENEMY", List.of(), List.of());
        BattleState firstState = new BattleState(first, firstEnemy);
        avertFatal(first, firstEnemy, firstState, new AbilityActivationEngine(
            new SeededRandomSource(new FixedRandom())), 1);

        assertEquals(MiraclesAbility.MAX_MIRACLES - 1, miracleCount(first));
        assertEquals(MiraclesAbility.MAX_MIRACLES, miracleCount(second));
    }

    private static BattleCombatant miracleCombatant(String id, List<Move> moves) {
        return combatant(id, moves, miracleAbilities());
    }

    private static BattleCombatant combatant(String id, List<Move> moves, List<Ability> abilities) {
        Character character = new SorcererCharacter(
            id, id, new CharacterStats.Builder().build(), "Miracles", moves, abilities);
        return new BattleCombatant(character);
    }

    /**
     * A Miracles wielder with Fortune Reclaimed ONLY (no Reservoir), so it starts
     * the battle at 0/6 Miracles and must earn them through Fortune Reclaimed.
     * Faster than the default enemy (BASELINE speed) so an instant same-tick
     * dodge has already fired before the attack resolves. An optional dodge move,
     * when supplied, is placed on its own timeline.
     */
    private static BattleCombatant miracleCombatantWithFortuneOnly(Move dodge) {
        List<Move> moves = dodge == null ? List.of() : List.of(dodge);
        Character character = new SorcererCharacter(
            "OWNER", "Owner",
            new CharacterStats.Builder().vitality(300).speed(120).build(),
            "Miracles", moves,
            List.of(codedAbility("Fortune Reclaimed", "FORTUNE_RECLAIMED",
                MiraclesAbility.FORTUNE_RECLAIMED, "ACTIVE", fortuneCondition())));
        BattleCombatant owner = new BattleCombatant(character);
        if (dodge != null) {
            Timeline tl = new Timeline(30);
            assertNotNull(tl.placeAt(dodge, 1, 0));
            owner.setTimeline(tl);
        }
        return owner;
    }

    private static List<Ability> miracleAbilities() {        return List.of(
            codedAbility("Miracle Reservoir", "MIRACLE_RESERVOIR",
                MiraclesAbility.RESERVOIR, "PASSIVE", null),
            codedAbility("Fateful Reprieve", "FATEFUL_REPRIEVE",
                MiraclesAbility.FATEFUL_REPRIEVE, "ACTIVE", fatefulCondition()),
            codedAbility("Fortune Reclaimed", "FORTUNE_RECLAIMED",
                MiraclesAbility.FORTUNE_RECLAIMED, "ACTIVE", fortuneCondition())
        );
    }

    private static Ability codedAbility(
        String name,
        String id,
        String feature,
        String category,
        AbilityConditionRuleData condition
    ) {
        AbilityData ability = new AbilityData();
        ability.id = id;
        ability.name = name;
        ability.category = category;
        ability.sourceType = "TECHNIQUE";
        ability.sourceValue = "Miracles";
        AbilityEffectData coded = AbilityEffectType.CODED.createDefault();
        coded.effectId = "effect-000000";
        coded.codedAbilityKey = MiraclesAbility.KEY;
        coded.codedFeature = feature;
        ability.effects = List.of(coded);
        ability.activationConditions = condition == null ? null : List.of(condition);
        return new Ability(ability);
    }

    private static AbilityConditionRuleData fatefulCondition() {
        AbilityConditionData fatal = AbilityConditionType.FATAL_DAMAGE.createDefault();
        AbilityConditionData hasMiracle = AbilityConditionType.CODED_STATE_AT_OR_ABOVE.createDefault();
        hasMiracle.codedAbilityKey = MiraclesAbility.KEY;
        hasMiracle.amount = 1;
        return linkedRule(AbilityConditionData.all(List.of(fatal, hasMiracle)));
    }

    private static AbilityConditionRuleData fortuneCondition() {
        AbilityConditionData belowCap = AbilityConditionType.CODED_STATE_AT_OR_BELOW.createDefault();
        belowCap.codedAbilityKey = MiraclesAbility.KEY;
        belowCap.amount = MiraclesAbility.MAX_MIRACLES - 1;
        AbilityConditionData missed = AbilityConditionType.ATTACK_MISSED.createDefault();
        missed.actor = "ENEMY";
        AbilityConditionData blackFlash = AbilityConditionType.BLACK_FLASH_HIT.createDefault();
        AbilityConditionRuleData rule = linkedRule(AbilityConditionData.all(List.of(
            belowCap, AbilityConditionData.any(List.of(missed, blackFlash)))));
        rule.matchSameTrigger = true;
        return rule;
    }

    private static AbilityConditionRuleData linkedRule(AbilityConditionData condition) {
        AbilityConditionRuleData rule = AbilityConditionRuleData.allEffects(condition);
        rule.targetEffectIds = List.of("effect-000000");
        rule.matchSameTrigger = true;
        return rule;
    }

    private static void avertFatal(
        BattleCombatant owner,
        BattleCombatant enemy,
        BattleState state,
        AbilityActivationEngine engine,
        int tick
    ) {
        owner.receiveDamage(owner.getCurrentHp(), amount -> engine.preventFatalDamage(
            state, AbilityTrigger.fatalDamage(enemy, owner, null, null, amount, tick)));
    }

    private static int miracleCount(BattleCombatant combatant) {
        return combatant.getCodedAbilities().states().stream()
            .filter(state -> MiraclesAbility.KEY.equals(state.key()))
            .findFirst()
            .orElseThrow()
            .currentValue();
    }

    private static Move attack(String id) {
        return new Move.Builder(id)
            .name(id)
            .basePower(10)
            .neverMiss(true)
            .apCost(1)
            .unleashPoint(1)
            .build();
    }

    private static final class FixedRandom extends Random {
        @Override public double nextDouble() { return 0.0; }
        @Override public boolean nextBoolean() { return true; }
    }
}

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
import com.jjktbf.model.character.coded.RatioAbility;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.AbilityActivationEngine;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.DamageCalculator;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.StatusEffect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatioTechniqueTest {

    @Test
    void ratioStacksAreOwnerHeldTargetedCappedAndConsumedOnBlockedContact() {
        BattleCombatant owner = ratioCombatant("OWNER", List.of());
        BattleCombatant target = combatant("TARGET", List.of());
        BattleCombatant otherTarget = combatant("OTHER", List.of());
        BattleState state = new BattleState(owner, target);

        StatusEffect createTwo = ratioEffect(RatioAbility.CREATE_STACKS, 2);
        owner.getCodedAbilities().onEffectFired(state, createTwo, owner, target, 1);
        owner.getCodedAbilities().onEffectFired(state, createTwo, owner, target, 2);
        assertEquals(RatioAbility.MAX_STACKS, ratioCount(owner));

        Move miss = new Move.Builder("MISS")
            .name("Miss")
            .category(MoveCategory.PHYSICAL)
            .basePower(100)
            .baseAccuracy(0.1)
            .apCost(10)
            .unleashPoint(1)
            .build();
        assertTrue(DamageCalculator.resolve(
            owner, target, miss, 1, new ConstantRandom(1.0), 1).isMiss());
        assertEquals(3, ratioCount(owner), "A miss must not consume a Ratio stack");

        DamageCalculator.resolve(owner, otherTarget, plainAttack("OTHER_HIT"), 1,
            new ConstantRandom(0.0), 1);
        assertEquals(3, ratioCount(owner), "A stack tied to another target must not be consumed");

        Move block = fullBlock();
        target.setTimeline(timelineWith(block));
        DamageCalculator.DamageResult blocked = DamageCalculator.resolve(
            owner, target, plainAttack("BLOCKED_HIT"), 1, new ConstantRandom(0.70), 1);

        assertTrue(blocked.isBlocked(), "A failed 70% Ratio roll should leave the full block intact");
        assertEquals(2, ratioCount(owner), "Connected blocked attacks still consume one stack");
    }

    @Test
    void stackRatioUsesSeventyPercentBoundaryAndExistingGuardBreakPath() {
        Move block = fullBlock();
        BattleCombatant target = combatant("TARGET", List.of(block));
        target.setTimeline(timelineWith(block));
        BattleCombatant owner = ratioCombatant("OWNER", List.of());
        BattleState state = new BattleState(owner, target);
        owner.getCodedAbilities().onEffectFired(
            state, ratioEffect(RatioAbility.CREATE_STACKS, 1), owner, target, 1);

        DamageCalculator.DamageResult result = DamageCalculator.resolve(
            owner, target, plainAttack("RATIO_HIT"), 1, new ConstantRandom(0.6999), 1);

        assertTrue(result.isHit());
        assertTrue(result.bypassedBlock());
        assertTrue(result.getFinalDamage() > 0);
        assertEquals(0, ratioCount(owner));
        assertTrue(result.getCodedEvents().stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.RATIO_TRIGGERED
                && event.getMessage().contains("Ratio triggers!")));
    }

    @Test
    void applyingRatioToMoveBypassesBlockAndReducesDefenseToThirtyPercentForOnlyThatHit() {
        BattleCombatant owner = ratioCombatant("OWNER", List.of());
        BattleCombatant target = combatant("TARGET", List.of());
        Move plain = plainAttack("PLAIN");
        Move ratio = new Move.Builder("DIRECT_RATIO")
            .name("Direct Ratio")
            .category(MoveCategory.PHYSICAL)
            .basePower(100)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .onHitEffects(List.of(ratioEffect(RatioAbility.APPLY_TO_MOVE, null)))
            .build();

        int plainDamage = DamageCalculator.resolve(
            owner, target, plain, 1, new ConstantRandom(0.0), 1).getFinalDamage();
        int ratioDamage = DamageCalculator.resolve(
            owner, target, ratio, 1, new ConstantRandom(0.0), 1).getFinalDamage();
        assertTrue(Math.abs(ratioDamage - plainDamage / RatioAbility.DEFENSE_MULTIPLIER) <= 3,
            "Reducing defense to 30% should deal approximately 10/3 of otherwise identical damage");

        Move block = fullBlock();
        target.setTimeline(timelineWith(block));
        DamageCalculator.DamageResult throughBlock = DamageCalculator.resolve(
            owner, target, ratio, 1, new ConstantRandom(1.0), 1);
        assertTrue(throughBlock.isHit());
        assertTrue(throughBlock.bypassedBlock());
    }

    @Test
    void reinforcementPassiveUsesFivePercentBoundaryOnTheCurrentHit() {
        Move reinforcement = new Move.Builder("REINFORCEMENT")
            .name("Reinforcement")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .basePower(100)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();
        Move block = fullBlock();

        BattleCombatant successOwner = ratioCombatant("SUCCESS", List.of(reinforcement));
        BattleCombatant successTarget = combatant("TARGET_ONE", List.of(block));
        successTarget.setTimeline(timelineWith(block));
        ConstantRandom successRandom = new ConstantRandom(0.0499);
        BattleState successState = new BattleState(successOwner, successTarget);
        AbilityActivationEngine successEngine = new AbilityActivationEngine(successRandom);
        DamageCalculator.DamageResult success = DamageCalculator.resolve(
            successOwner, successTarget, reinforcement,
            reinforcement.getHitComponents().get(0), 1, successRandom, 1,
            false, false, trigger -> successEngine.onAttackConnected(successState, trigger));
        assertTrue(success.isHit());
        assertTrue(success.bypassedBlock());
        assertEquals(0, ratioCount(successOwner), "The passive must not create a stack");

        BattleCombatant failOwner = ratioCombatant("FAIL", List.of(reinforcement));
        BattleCombatant failTarget = combatant("TARGET_TWO", List.of(block));
        failTarget.setTimeline(timelineWith(block));
        ConstantRandom failRandom = new ConstantRandom(0.05);
        BattleState failState = new BattleState(failOwner, failTarget);
        AbilityActivationEngine failEngine = new AbilityActivationEngine(failRandom);
        DamageCalculator.DamageResult failure = DamageCalculator.resolve(
            failOwner, failTarget, reinforcement,
            reinforcement.getHitComponents().get(0), 1, failRandom, 1,
            false, false, trigger -> failEngine.onAttackConnected(failState, trigger));
        assertTrue(failure.isBlocked());
        assertEquals(0, ratioCount(failOwner));
    }

    @Test
    void alwaysActiveCodedConditionIsEligibleForEveryConnectedHit() {
        Move attack = plainAttack("ALWAYS_RATIO");
        AbilityConditionRuleData always = AbilityConditionRuleData.allEffects(
            AbilityConditionData.always());
        always.targetEffectIds = List.of("effect-000000");
        BattleCombatant owner = ratioCombatant("ALWAYS", List.of(attack), always);
        BattleCombatant target = combatant("TARGET", List.of());
        BattleState state = new BattleState(owner, target);
        AbilityActivationEngine engine = new AbilityActivationEngine(new ConstantRandom(0.5));

        assertTrue(engine.onAttackConnected(state, com.jjktbf.model.combat.AbilityTrigger
            .attackConnected(owner, target, attack, attack.getHitComponents().get(0), 1))
            .bypassBlock());
        assertTrue(engine.onAttackConnected(state, com.jjktbf.model.combat.AbilityTrigger
            .attackConnected(owner, target, attack, attack.getHitComponents().get(0), 2))
            .bypassBlock());
    }

    @Test
    void codedConditionCanAccumulateFactsBeforeItsNaturalRuntimeHook() {
        Move attack = plainAttack("SEQUENCED_RATIO");
        AbilityConditionData moveUsed = AbilityConditionType.MOVE_USED.createDefault();
        moveUsed.moveId = attack.getId();
        AbilityConditionData connected = AbilityConditionType.ATTACK_CONNECTED.createDefault();
        AbilityConditionRuleData sequence = AbilityConditionRuleData.allEffects(
            AbilityConditionData.all(List.of(moveUsed, connected)));
        sequence.targetEffectIds = List.of("effect-000000");
        BattleCombatant owner = ratioCombatant("SEQUENCE", List.of(attack), sequence);
        BattleCombatant target = combatant("TARGET", List.of());
        BattleState state = new BattleState(owner, target);
        AbilityActivationEngine engine = new AbilityActivationEngine(new ConstantRandom(0.5));

        engine.process(state, com.jjktbf.model.combat.AbilityTrigger.move(
            com.jjktbf.model.combat.AbilityTrigger.Type.MOVE_USED,
            owner, target, attack, 1));

        assertTrue(engine.onAttackConnected(state, com.jjktbf.model.combat.AbilityTrigger
            .attackConnected(owner, target, attack, attack.getHitComponents().get(0), 1))
            .bypassBlock());
    }

    @Test
    void reinforcementTagsAreCheckedOnTheCurrentHitComponent() {
        HitComponent physical = new HitComponent(50, MoveCategory.PHYSICAL, 0);
        HitComponent cursedEnergy = new HitComponent(50, MoveCategory.CURSED_ENERGY, 0);
        Move split = new Move.Builder("SPLIT_TYPES")
            .name("Split Types")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .hitComponents(List.of(physical, cursedEnergy))
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();
        BattleCombatant owner = ratioCombatant("SPLIT", List.of(split));
        BattleCombatant target = combatant("TARGET", List.of());
        BattleState state = new BattleState(owner, target);
        AbilityActivationEngine engine = new AbilityActivationEngine(new ConstantRandom(0.0));

        assertFalse(engine.onAttackConnected(state,
            com.jjktbf.model.combat.AbilityTrigger.attackConnected(
                owner, target, split, physical, 1)).bypassBlock());
        assertFalse(engine.onAttackConnected(state,
            com.jjktbf.model.combat.AbilityTrigger.attackConnected(
                owner, target, split, cursedEnergy, 1)).bypassBlock());
    }

    @Test
    void codedRatioMovesWorkWithoutUnlockingTheReinforcementPassive() {
        Move directRatio = new Move.Builder("DIRECT_RATIO_ONLY")
            .name("Direct Ratio Only")
            .category(MoveCategory.PHYSICAL)
            .basePower(100)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .onHitEffects(List.of(ratioEffect(RatioAbility.APPLY_TO_MOVE, null)))
            .build();
        BattleCombatant owner = combatant("OWNER", List.of(directRatio));
        Move block = fullBlock();
        BattleCombatant target = combatant("TARGET", List.of(block));
        target.setTimeline(timelineWith(block));

        DamageCalculator.DamageResult result = DamageCalculator.resolve(
            owner, target, directRatio, 1, new ConstantRandom(1.0), 1);

        assertTrue(result.isHit());
        assertTrue(result.bypassedBlock());
        assertEquals(0, ratioCount(owner));
    }

    @Test
    void ratioStacksExpireAfterExactlyFiftyUniversalTicksIndependently() {
        BattleCombatant owner = ratioCombatant("OWNER", List.of());
        BattleCombatant target = combatant("TARGET", List.of());
        BattleState state = new BattleState(owner, target);
        StatusEffect createOne = ratioEffect(RatioAbility.CREATE_STACKS, 1);
        owner.getCodedAbilities().onEffectFired(state, createOne, owner, target, 1);

        for (int tick = 1; tick <= 25; tick++) {
            owner.getCodedAbilities().tickTimelineEffects(tick);
        }
        owner.getCodedAbilities().onEffectFired(state, createOne, owner, target, 25);

        for (int tick = 26; tick <= 50; tick++) {
            owner.getCodedAbilities().tickTimelineEffects(tick);
        }
        assertEquals(1, ratioCount(owner), "Only the older independently-timed stack should expire");
        assertEquals(25, owner.getCodedAbilities().getRemainingTimelineEffectTicks());

        for (int tick = 51; tick <= 75; tick++) {
            owner.getCodedAbilities().tickTimelineEffects(tick);
        }
        assertEquals(0, ratioCount(owner));
    }

    @Test
    void ratioTickDurationCarriesAcrossRoundBoundaries() {
        Move codedMove = new Move.Builder("RATIO_RUNTIME")
            .name("Ratio Runtime")
            .category(MoveCategory.UTILITY)
            .apCost(1)
            .unleashPoint(1)
            .selfEffects(List.of(ratioEffect(RatioAbility.CREATE_STACKS, 1)))
            .build();
        BattleCombatant owner = combatant("OWNER", List.of(codedMove));
        BattleCombatant target = combatant("TARGET", List.of());
        BattleState state = new BattleState(owner, target);
        owner.getCodedAbilities().onEffectFired(
            state, ratioEffect(RatioAbility.CREATE_STACKS, 1), owner, target, 0);

        owner.setTimeline(new Timeline(20));
        target.setTimeline(new Timeline(20));
        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(new ConstantRandom(0.0));
        resolver.resolveRound(state);
        assertEquals(1, ratioCount(owner));
        assertEquals(30, owner.getCodedAbilities().getRemainingTimelineEffectTicks());

        resolver.processRoundEnd(state);
        assertEquals(1, ratioCount(owner));
        assertEquals(30, owner.getCodedAbilities().getRemainingTimelineEffectTicks());
    }

    private static StatusEffect ratioEffect(String target, Integer stackCount) {
        return StatusEffect.coded(
            RatioAbility.KEY, RatioAbility.RATIO_EFFECT, target, stackCount);
    }

    private static BattleCombatant ratioCombatant(String id, List<Move> moves) {
        AbilityConditionData connected = AbilityConditionType.ATTACK_CONNECTED.createDefault();
        AbilityConditionData physical = AbilityConditionType.CONNECTED_HIT_HAS_TAG.createDefault();
        physical.moveTag = "PHYSICAL";
        AbilityConditionData cursedEnergy = AbilityConditionType.CONNECTED_HIT_HAS_TAG.createDefault();
        cursedEnergy.moveTag = "CURSED_ENERGY";
        AbilityConditionRuleData rule = AbilityConditionRuleData.allEffects(
            AbilityConditionData.all(List.of(connected, physical, cursedEnergy)));
        rule.targetEffectIds = List.of("effect-000000");
        rule.activationChanceEnabled = true;
        rule.activationChance = 0.05;
        rule.matchSameTrigger = true;
        return ratioCombatant(id, moves, rule);
    }

    private static BattleCombatant ratioCombatant(
        String id,
        List<Move> moves,
        AbilityConditionRuleData rule
    ) {
        AbilityData data = new AbilityData();
        data.id = id + "_RATIO";
        data.name = "Ratio Reinforcement";
        data.category = "ACTIVE";
        data.sourceType = "TECHNIQUE";
        data.sourceValue = "Ratio";
        AbilityEffectData coded = AbilityEffectType.CODED.createDefault();
        coded.effectId = "effect-000000";
        coded.codedAbilityKey = RatioAbility.KEY;
        coded.codedFeature = RatioAbility.REINFORCEMENT_RATIO;
        data.effects = List.of(coded);
        data.activationConditions = List.of(rule);
        Character character = new SorcererCharacter(
            id, id, new CharacterStats.Builder().build(), "Ratio", moves, List.of(new Ability(data)));
        return new BattleCombatant(character);
    }

    private static BattleCombatant combatant(String id, List<Move> moves) {
        Character character = new SorcererCharacter(
            id, id, new CharacterStats.Builder().build(), null, moves);
        return new BattleCombatant(character);
    }

    private static int ratioCount(BattleCombatant combatant) {
        return combatant.getCodedAbilities().states().stream()
            .filter(state -> RatioAbility.KEY.equals(state.key()))
            .findFirst().orElseThrow().currentValue();
    }

    private static Move plainAttack(String id) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.PHYSICAL)
            .basePower(100)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();
    }

    private static Move fullBlock() {
        return new Move.Builder("FULL_BLOCK")
            .name("Full Block")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.BLOCK).blockStyle(BlockStyle.PERCENTAGE)
            .blockDamageReduction(100)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();
    }

    private static Timeline timelineWith(Move move) {
        Timeline timeline = new Timeline(100);
        assertNotNull(timeline.placeAt(move, 1, 0));
        return timeline;
    }

    private static final class ConstantRandom implements RandomSource {
        private final double value;

        private ConstantRandom(double value) {
            this.value = value;
        }

        @Override public int nextInt(int bound) { return 0; }
        @Override public double nextDouble() { return value; }
        @Override public boolean nextBoolean() { return true; }
    }
}

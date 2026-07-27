package com.jjktbf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.coded.CodedAbilityRegistry;
import com.jjktbf.model.character.coded.RatioAbility;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.DamageCalculator;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.technique.InnateTechniqueData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    }

    @Test
    void applyingRatioToMoveBypassesBlockAndHalvesDefenseForOnlyThatHit() {
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
        assertTrue(Math.abs(ratioDamage - plainDamage * 2) <= 1,
            "Halving defense should approximately double otherwise identical damage");

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
        DamageCalculator.DamageResult success = DamageCalculator.resolve(
            successOwner, successTarget, reinforcement, 1, new ConstantRandom(0.0499), 1);
        assertTrue(success.isHit());
        assertTrue(success.bypassedBlock());
        assertEquals(0, ratioCount(successOwner), "The passive must not create a stack");

        BattleCombatant failOwner = ratioCombatant("FAIL", List.of(reinforcement));
        BattleCombatant failTarget = combatant("TARGET_TWO", List.of(block));
        failTarget.setTimeline(timelineWith(block));
        DamageCalculator.DamageResult failure = DamageCalculator.resolve(
            failOwner, failTarget, reinforcement, 1, new ConstantRandom(0.05), 1);
        assertTrue(failure.isBlocked());
        assertEquals(0, ratioCount(failOwner));
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

    @Test
    void bundledRatioContentAndCodedSettingsRoundTrip() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<InnateTechniqueData> techniques = mapper.readValue(
            dataFile("techniques", "all_techniques.json").toFile(), new TypeReference<>() { });
        List<AbilityData> abilities = mapper.readValue(
            dataFile("abilities", "all_abilities.json").toFile(), new TypeReference<>() { });
        List<MoveData> moves = mapper.readValue(
            dataFile("moves", "all_moves.json").toFile(), new TypeReference<>() { });

        assertTrue(techniques.stream().anyMatch(technique -> "Ratio".equals(technique.name)));
        AbilityData passive = abilities.stream()
            .filter(ability -> "Ratio Reinforcement".equals(ability.name))
            .findFirst().orElseThrow();
        assertEquals(RatioAbility.KEY, passive.codedAbilityKey);
        assertEquals(RatioAbility.REINFORCEMENT_RATIO, passive.codedFeature);
        assertTrue(CodedAbilityRegistry.supportsAbility(
            passive.codedAbilityKey, passive.codedFeature));

        MoveData mark = moves.stream().filter(move -> "Ratio Mark".equals(move.name))
            .findFirst().orElseThrow();
        MoveData.StatusEffectData create = mark.selfEffects.get(0);
        assertEquals(RatioAbility.CREATE_STACKS, create.codedTarget);
        assertEquals(1, create.codedStackCount);
        assertNotNull(mark.toMove());

        MoveData strike = moves.stream().filter(move -> "Ratio Strike".equals(move.name))
            .findFirst().orElseThrow();
        MoveData.StatusEffectData apply = strike.onHitEffects.get(0);
        assertEquals(RatioAbility.APPLY_TO_MOVE, apply.codedTarget);
        assertTrue(CodedAbilityRegistry.supportsEffect(
            apply.codedAbilityKey, apply.codedAction, apply.codedTarget, apply.codedStackCount));

        MoveData restored = MoveData.fromMove(strike.toMove());
        assertEquals(RatioAbility.APPLY_TO_MOVE, restored.onHitEffects.get(0).codedTarget);
        assertNull(restored.onHitEffects.get(0).codedStackCount);
    }

    @Test
    void bundledRatioMarkTargetsTheOpponentFromItsCodedSelfEffect() throws IOException {
        List<MoveData> moves = new ObjectMapper().readValue(
            dataFile("moves", "all_moves.json").toFile(), new TypeReference<>() { });
        Move mark = moves.stream().filter(move -> "Ratio Mark".equals(move.name))
            .findFirst().orElseThrow().toMove();
        BattleCombatant owner = ratioCombatant("OWNER", List.of(mark));
        BattleCombatant target = combatant("TARGET", List.of());
        owner.setTimeline(timelineWith(mark));
        target.setTimeline(new Timeline(100));
        BattleState state = new BattleState(owner, target);
        state.transitionTo(BattleState.Phase.RESOLUTION);
        CombatResolver resolver = new CombatResolver(new ConstantRandom(0.70));
        resolver.beginResolution(state);
        for (int tick = 1; tick <= mark.getUnleashPoint(); tick++) resolver.resolveTick(state);
        assertEquals(1, ratioCount(owner));

        Move block = fullBlock();
        target.setTimeline(timelineWith(block));
        DamageCalculator.DamageResult consumed = DamageCalculator.resolve(
            owner, target, plainAttack("FOLLOW_UP"), 1, new ConstantRandom(0.70), 1);
        assertTrue(consumed.isBlocked());
        assertEquals(0, ratioCount(owner), "The mark must be associated with the opponent, not its owner");
    }

    private static StatusEffect ratioEffect(String target, Integer stackCount) {
        return StatusEffect.coded(
            RatioAbility.KEY, RatioAbility.RATIO_EFFECT, target, stackCount);
    }

    private static BattleCombatant ratioCombatant(String id, List<Move> moves) {
        AbilityData data = new AbilityData();
        data.id = id + "_RATIO";
        data.name = "Ratio Reinforcement";
        data.category = "PASSIVE";
        data.sourceType = "TECHNIQUE";
        data.sourceValue = "Ratio";
        data.effects = List.of();
        data.codedAbilityKey = RatioAbility.KEY;
        data.codedFeature = RatioAbility.REINFORCEMENT_RATIO;
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

    private static Path dataFile(String directory, String fileName) throws IOException {
        return List.of(
                Path.of("data", directory, fileName),
                Path.of("..", "data", directory, fileName))
            .stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IOException("Could not locate bundled " + fileName));
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

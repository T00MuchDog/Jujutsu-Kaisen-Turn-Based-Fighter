package com.jjktbf;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.DamageCalculator;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiHitMoveTest {

    @Test
    void legacyPowerSynthesizesOneComponentAndExplicitComponentsRoundTrip() {
        Move legacy = attackBuilder("LEGACY")
            .basePower(37)
            .build();

        assertEquals(1, legacy.getHitComponents().size());
        assertEquals(37, legacy.getHitComponents().get(0).getBasePower());
        assertEquals(0, legacy.getHitComponents().get(0).getDelayTicks());

        Move authored = attackBuilder("AUTHORED")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .basePower(999)
            .hitComponents(List.of(
                component(20, MoveCategory.PHYSICAL, 0, false, true),
                component(30, MoveCategory.CURSED_ENERGY, 4, true, false)))
            .build();

        assertEquals(50, authored.getBasePower());
        assertEquals(50, authored.getTotalBasePower());
        assertEquals(4, authored.getMaxHitDelayTicks());

        MoveData data = MoveData.fromMove(authored);
        Move restored = data.toMove();
        assertEquals(2, data.hitComponents.size());
        assertEquals(2, restored.getHitComponents().size());
        assertEquals(MoveCategory.CURSED_ENERGY,
            restored.getHitComponents().get(1).getCategory());
        assertEquals(4, restored.getHitComponents().get(1).getDelayTicks());
        assertTrue(restored.getHitComponents().get(1).requiresPreviousConnection());
        assertFalse(restored.getHitComponents().get(1).isAvoidable());
    }

    @Test
    void componentValidationRejectsInvalidDamageDefinitions() {
        assertThrows(IllegalArgumentException.class,
            () -> new HitComponent(-1, MoveCategory.PHYSICAL, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new HitComponent(1, MoveCategory.PHYSICAL, -1));
        assertThrows(IllegalArgumentException.class,
            () -> new HitComponent(1, Set.of(MoveTag.MELEE), 0, false, true));
        assertThrows(IllegalArgumentException.class,
            () -> new HitComponent(
                1, Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK), 0, false, true));

        assertThrows(IllegalStateException.class, () -> attackBuilder("EMPTY")
            .hitComponents(List.of())
            .build());
        assertThrows(IllegalStateException.class, () -> attackBuilder("ZERO")
            .hitComponents(List.of(component(
                0, MoveCategory.PHYSICAL, 0, false, true)))
            .build());
        assertThrows(IllegalStateException.class, () -> attackBuilder("FIRST_DEPENDENT")
            .hitComponents(List.of(component(
                1, MoveCategory.PHYSICAL, 0, true, true)))
            .build());
        assertThrows(IllegalStateException.class, () -> attackBuilder("EARLY_DEPENDENT")
            .hitComponents(List.of(
                component(1, MoveCategory.PHYSICAL, 5, false, true),
                component(1, MoveCategory.PHYSICAL, 4, true, true)))
            .build());
        assertThrows(IllegalStateException.class, () -> attackBuilder("MISMATCH")
            .hitComponents(List.of(component(
                1, MoveCategory.CURSED_ENERGY, 0, false, true)))
            .build());
        assertThrows(IllegalStateException.class, () -> new Move.Builder("UTILITY_HITS")
            .name("Utility Hits")
            .category(MoveCategory.UTILITY)
            .hitComponents(List.of(component(
                1, MoveCategory.PHYSICAL, 0, false, true)))
            .apCost(2)
            .unleashPoint(1)
            .build());
    }

    @Test
    void placementAllowsImpactPastApOccupancyButRejectsImpactPastGrid() {
        Move delayed = attackBuilder("DELAYED")
            .apCost(5)
            .unleashPoint(5)
            .hitComponents(List.of(component(
                10, MoveCategory.PHYSICAL, 5, false, true)))
            .build();

        Timeline timeline = new Timeline(10);
        ActionSegment accepted = timeline.placeAt(delayed, 1, 0);
        assertNotNull(accepted);
        assertEquals(5, accepted.getEndTick());
        assertEquals(10, accepted.getFinalImpactTick());
        assertNull(new Timeline(10).placeAt(delayed, 2, 0));
    }

    @Test
    void delaysAreAbsoluteOffsetsAndEventsCarryOrderedComponentIndexes() {
        Move move = attackBuilder("ABSOLUTE")
            .apCost(2)
            .unleashPoint(1)
            .hitComponents(List.of(
                component(1, MoveCategory.PHYSICAL, 2, false, true),
                component(1, MoveCategory.PHYSICAL, 5, false, true)))
            .build();

        Resolution resolution = resolve(move, 1, null, 0, 10, new FixedRandom(0.0));
        List<CombatEvent> damage = damageEvents(resolution.events(), move);

        assertEquals(List.of(3, 6), damage.stream().map(CombatEvent::getTick).toList());
        assertEquals(List.of(0, 1), damage.stream().map(CombatEvent::getComponentIndex).toList());
        assertEquals(1, resolution.events().stream()
            .filter(event -> event.getType() == CombatEvent.Type.MOVE_FIRED)
            .filter(event -> event.getMove() == move)
            .count());
    }

    @Test
    void dependentComponentSkipsAfterMissButFullBlockCountsAsConnection() {
        Move missChain = attackBuilder("MISS_CHAIN")
            .baseAccuracy(0.0)
            .neverMiss(false)
            .hitComponents(List.of(
                component(1, MoveCategory.PHYSICAL, 0, false, true),
                component(1, MoveCategory.PHYSICAL, 0, true, true)))
            .build();

        List<CombatEvent> misses = resolve(
            missChain, 1, null, 0, 10, new FixedRandom(1.0)).events();
        assertEquals(1, misses.stream()
            .filter(event -> event.getType() == CombatEvent.Type.MOVE_MISSED)
            .count());
        assertFalse(misses.stream().anyMatch(event ->
            Objects.equals(event.getComponentIndex(), 1)));

        Move block = fullBlock("FULL_BLOCK", null);
        Move blockChain = attackBuilder("BLOCK_CHAIN")
            .hitComponents(List.of(
                component(1, MoveCategory.PHYSICAL, 0, false, true),
                component(1, MoveCategory.PHYSICAL, 0, true, true)))
            .build();
        List<CombatEvent> blocked = resolve(
            blockChain, 1, block, 1, 10, new FixedRandom(0.0)).events();

        assertEquals(List.of(0, 1), blocked.stream()
            .filter(event -> event.getType() == CombatEvent.Type.MOVE_BLOCKED)
            .map(CombatEvent::getComponentIndex)
            .toList());
    }

    @Test
    void dependentComponentSkipsAfterDodgeAndParry() {
        Move chain = attackBuilder("DEFENSE_CHAIN")
            .hitComponents(List.of(
                component(1, MoveCategory.PHYSICAL, 0, false, true),
                component(1, MoveCategory.PHYSICAL, 0, true, true)))
            .build();
        Move dodge = new Move.Builder("CHAIN_DODGE")
            .name("Chain Dodge")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.DODGE)
            .dodgeChance(100)
            .apCost(5)
            .unleashPoint(1)
            .build();
        Move parry = new Move.Builder("CHAIN_PARRY")
            .name("Chain Parry")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PARRY)
            .apCost(5)
            .unleashPoint(1)
            .build();

        List<CombatEvent> dodged = resolve(
            chain, 1, dodge, 1, 10, new FixedRandom(0.0)).events();
        List<CombatEvent> parried = resolve(
            chain, 1, parry, 1, 10, new FixedRandom(0.0)).events();

        assertTrue(dodged.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.MOVE_DODGED
                && Objects.equals(event.getComponentIndex(), 0)));
        assertTrue(parried.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.MOVE_PARRIED
                && Objects.equals(event.getComponentIndex(), 0)));
        assertFalse(dodged.stream().anyMatch(event ->
            Objects.equals(event.getComponentIndex(), 1)));
        assertFalse(parried.stream().anyMatch(event ->
            Objects.equals(event.getComponentIndex(), 1)));
    }

    @Test
    void unavoidableComponentSkipsAccuracyDodgeAndParryButStillBlocks() {
        HitComponent unavoidable = component(
            10, MoveCategory.PHYSICAL, 0, false, false);
        Move attack = attackBuilder("UNAVOIDABLE")
            .baseAccuracy(0.0)
            .neverMiss(false)
            .hitComponents(List.of(unavoidable))
            .build();

        Move dodge = new Move.Builder("DODGE")
            .name("Dodge")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.DODGE)
            .dodgeChance(100)
            .apCost(5)
            .unleashPoint(1)
            .build();
        Move parry = new Move.Builder("PARRY")
            .name("Parry")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PARRY)
            .potency(5)
            .apCost(5)
            .unleashPoint(1)
            .build();

        assertTrue(resolveDamageAgainstDefense(attack, unavoidable, dodge).isHit());
        assertTrue(resolveDamageAgainstDefense(attack, unavoidable, parry).isHit());
        assertTrue(resolveDamageAgainstDefense(
            attack, unavoidable, fullBlock("BLOCK", null)).isBlocked());
    }

    @Test
    void blockCoverageUsesEachComponentsDamageTags() {
        Move move = attackBuilder("MIXED")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .hitComponents(List.of(
                component(1, MoveCategory.PHYSICAL, 0, false, true),
                component(1, MoveCategory.CURSED_ENERGY, 0, false, true)))
            .build();
        Move physicalBlock = fullBlock("PHYSICAL_BLOCK", List.of("PHYSICAL"));

        List<CombatEvent> events = resolve(
            move, 1, physicalBlock, 1, 10, new FixedRandom(0.0)).events();

        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.MOVE_BLOCKED
                && Objects.equals(event.getComponentIndex(), 0)));
        assertTrue(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.DAMAGE_DEALT
                && Objects.equals(event.getComponentIndex(), 1)));
    }

    @Test
    void techniqueComponentsCanonicalizeImpliedCursedEnergy() {
        HitComponent component = new HitComponent(
            10,
            Set.of(MoveTag.CURSED_ENERGY, MoveTag.INNATE_TECHNIQUE),
            0,
            false,
            true);

        assertEquals(Set.of(MoveTag.INNATE_TECHNIQUE), component.getTags());
        assertEquals(MoveCategory.INNATE_TECHNIQUE, component.getCategory());
    }

    @Test
    void launchWorkRunsOnceAndHitWorkRunsPerComponent() {
        Move move = attackBuilder("EFFECTS")
            .baseCeCost(10)
            .hasCeCost(true)
            .selfEffects(List.of(new StatusEffect(
                StatusEffectType.STRENGTH_INCREASE, 2, 5.0)))
            .onHitEffects(List.of(new StatusEffect(
                StatusEffectType.STRENGTH_INCREASE, 2, 5.0)))
            .hitComponents(List.of(
                component(1, MoveCategory.PHYSICAL, 0, false, true),
                component(1, MoveCategory.PHYSICAL, 0, false, true)))
            .build();

        Resolution resolution = resolve(move, 1, null, 0, 10, new FixedRandom(0.0), 10);
        List<CombatEvent> events = resolution.events();

        assertEquals(1, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.MOVE_FIRED)
            .filter(event -> event.getMove() == move)
            .count());
        assertEquals(1, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.CE_DRAINED)
            .filter(event -> event.getMove() == move)
            .count());
        assertEquals(1, events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.STATUS_APPLIED)
            .filter(event -> event.getSource() == resolution.attacker())
            .filter(event -> event.getTarget() == null)
            .count());
        assertEquals(List.of(0, 1), events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.STATUS_APPLIED)
            .filter(event -> event.getTarget() == resolution.defender())
            .map(CombatEvent::getComponentIndex)
            .toList());
    }

    @Test
    void priorDelayedImpactsResolveBeforeNewLaunchesInAuthoredOrder() {
        Move delayed = attackBuilder("DELAYED_SOURCE")
            .apCost(5)
            .hitComponents(List.of(
                component(1, MoveCategory.PHYSICAL, 3, false, true),
                component(1, MoveCategory.PHYSICAL, 3, false, true)))
            .build();
        Move laterAttack = attackBuilder("LATER_ATTACK")
            .basePower(1)
            .build();

        Resolution resolution = resolve(
            delayed, 1, laterAttack, 4, 10, new FixedRandom(0.0));
        List<CombatEvent> events = resolution.events();
        int firstDelayedImpact = indexOf(events, CombatEvent.Type.DAMAGE_DEALT, delayed, 0);
        int secondDelayedImpact = indexOf(events, CombatEvent.Type.DAMAGE_DEALT, delayed, 1);
        int laterLaunch = indexOf(events, CombatEvent.Type.MOVE_FIRED, laterAttack, null);

        assertTrue(firstDelayedImpact >= 0);
        assertEquals(firstDelayedImpact + 1, secondDelayedImpact,
            "Same-delay components must resolve in authored order.");
        assertTrue(secondDelayedImpact < laterLaunch,
            "Previously committed impacts must resolve before a new launch on that tick.");
    }

    @Test
    void delayedImpactCannotUseDefenseBeforeThatDefenseFires() {
        Move delayed = attackBuilder("DELAYED_BEFORE_BLOCK")
            .apCost(5)
            .hitComponents(List.of(
                component(1, MoveCategory.PHYSICAL, 3, false, true)))
            .build();
        Move lateBlock = new Move.Builder("LATE_BLOCK")
            .name("Late Block")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.BLOCK)
            .blockStyle(BlockStyle.PERCENTAGE)
            .blockDamageReduction(100)
            .apCost(5)
            .unleashPoint(3)
            .build();

        List<CombatEvent> events = resolve(
            delayed, 1, lateBlock, 2, 10, new FixedRandom(0.0)).events();
        int impact = indexOf(events, CombatEvent.Type.DAMAGE_DEALT, delayed, 0);
        int defenseLaunch = indexOf(events, CombatEvent.Type.MOVE_FIRED, lateBlock, null);

        assertTrue(impact >= 0);
        assertTrue(impact < defenseLaunch);
        assertFalse(events.stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.MOVE_BLOCKED
                && event.getMove() == delayed));
    }

    @Test
    void committedDelayedImpactSurvivesAStunLandingAfterLaunch() {
        Move delayed = attackBuilder("COMMITTED")
            .apCost(5)
            .hitComponents(List.of(
                component(1, MoveCategory.PHYSICAL, 3, false, true)))
            .build();
        Move laterStun = attackBuilder("LATER_STUN")
            .stun(true)
            .basePower(1)
            .build();

        Resolution resolution = resolve(
            delayed, 1, laterStun, 2, 10, new FixedRandom(0.0));

        assertTrue(resolution.events().stream().anyMatch(event ->
            event.getType() == CombatEvent.Type.DAMAGE_DEALT
                && event.getMove() == delayed
                && event.getTick() == 4
                && Objects.equals(event.getComponentIndex(), 0)));
    }

    @Test
    void lethalComponentDiscardsRemainingPendingComponents() {
        Move move = attackBuilder("LETHAL_CHAIN")
            .hitComponents(List.of(
                component(1_000_000, MoveCategory.PHYSICAL, 0, false, true),
                component(1, MoveCategory.PHYSICAL, 2, false, true)))
            .build();

        Resolution resolution = resolve(move, 1, null, 0, 10, new FixedRandom(0.0));

        assertTrue(resolution.state().isBattleOver());
        assertFalse(resolution.events().stream().anyMatch(event ->
            Objects.equals(event.getComponentIndex(), 1)));
    }

    @Test
    void stunnedBeforeLaunchDoesNotExtendResolutionForUncommittedHits() {
        Move delayed = attackBuilder("NEVER_LAUNCHED")
            .apCost(2)
            .unleashPoint(2)
            .hitComponents(List.of(component(
                1, MoveCategory.PHYSICAL, 8, false, true)))
            .build();
        BattleCombatant attacker = combatant("A", "Attacker", 100, List.of(delayed));
        BattleCombatant defender = combatant("D", "Defender", 80, List.of());
        Timeline timeline = new Timeline(10);
        ActionSegment segment = timeline.placeAt(delayed, 1, 0);
        assertNotNull(segment);
        segment.stun();
        attacker.setTimeline(timeline);
        defender.setTimeline(new Timeline(10));
        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        new CombatResolver(new FixedRandom(0.0)).resolveRound(state);

        assertEquals(3, state.getCurrentTick());
    }

    private static DamageCalculator.DamageResult resolveDamageAgainstDefense(
        Move attack,
        HitComponent component,
        Move defense
    ) {
        BattleCombatant attacker = combatant("A", "Attacker", 100, List.of(attack));
        BattleCombatant defender = combatant("D", "Defender", 80, List.of(defense));
        Timeline timeline = new Timeline(10);
        assertNotNull(timeline.placeAt(defense, 1, 0));
        defender.setTimeline(timeline);
        return DamageCalculator.resolve(
            attacker, defender, attack, component, 1,
            new com.jjktbf.model.combat.SeededRandomSource(new FixedRandom(1.0)), 1);
    }

    private static Resolution resolve(
        Move attackerMove,
        int attackerStart,
        Move defenderMove,
        int defenderStart,
        int gridLength,
        Random random
    ) {
        return resolve(
            attackerMove, attackerStart, defenderMove, defenderStart, gridLength, random, 0);
    }

    private static Resolution resolve(
        Move attackerMove,
        int attackerStart,
        Move defenderMove,
        int defenderStart,
        int gridLength,
        Random random,
        int actualCeCost
    ) {
        BattleCombatant attacker = combatant(
            "A", "Attacker", 120, List.of(attackerMove));
        BattleCombatant defender = combatant(
            "D", "Defender", 80,
            defenderMove == null ? List.of() : List.of(defenderMove));
        Timeline attackerTimeline = new Timeline(gridLength);
        Timeline defenderTimeline = new Timeline(gridLength);
        assertNotNull(attackerTimeline.placeAt(attackerMove, attackerStart, actualCeCost));
        if (defenderMove != null) {
            assertNotNull(defenderTimeline.placeAt(defenderMove, defenderStart, 0));
        }
        attacker.setTimeline(attackerTimeline);
        defender.setTimeline(defenderTimeline);

        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);
        List<CombatEvent> events = new CombatResolver(random).resolveRound(state);
        return new Resolution(state, attacker, defender, events);
    }

    private static BattleCombatant combatant(
        String id,
        String name,
        int speed,
        List<Move> moves
    ) {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(300)
            .speed(speed)
            .build();
        Character character = new SorcererCharacter(
            id, name, stats, null, moves, List.of(), true);
        return new BattleCombatant(character);
    }

    private static Move.Builder attackBuilder(String id) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.PHYSICAL)
            .neverMiss(true)
            .apCost(2)
            .unleashPoint(1);
    }

    private static HitComponent component(
        int basePower,
        MoveCategory category,
        int delayTicks,
        boolean requiresPreviousConnection,
        boolean avoidable
    ) {
        return new HitComponent(
            basePower,
            category.getTags(),
            delayTicks,
            requiresPreviousConnection,
            avoidable);
    }

    private static Move fullBlock(String id, List<String> affectedTags) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.BLOCK)
            .blockStyle(BlockStyle.PERCENTAGE)
            .blockDamageReduction(100)
            .blockAffectedTags(affectedTags)
            .apCost(5)
            .unleashPoint(1)
            .build();
    }

    private static List<CombatEvent> damageEvents(List<CombatEvent> events, Move move) {
        return events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.DAMAGE_DEALT
                || event.getType() == CombatEvent.Type.DAMAGE_IGNORED)
            .filter(event -> event.getMove() == move)
            .toList();
    }

    private static int indexOf(
        List<CombatEvent> events,
        CombatEvent.Type type,
        Move move,
        Integer componentIndex
    ) {
        for (int index = 0; index < events.size(); index++) {
            CombatEvent event = events.get(index);
            if (event.getType() == type
                && event.getMove() == move
                && Objects.equals(event.getComponentIndex(), componentIndex)) {
                return index;
            }
        }
        return -1;
    }

    private record Resolution(
        BattleState state,
        BattleCombatant attacker,
        BattleCombatant defender,
        List<CombatEvent> events
    ) {
    }

    private static final class FixedRandom extends Random {
        private final double value;

        private FixedRandom(double value) {
            this.value = value;
        }

        @Override
        public double nextDouble() {
            return value;
        }

        @Override
        public boolean nextBoolean() {
            return value < 0.5;
        }
    }
}

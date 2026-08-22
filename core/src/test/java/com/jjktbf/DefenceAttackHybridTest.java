package com.jjktbf;

import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionActor;
import com.jjktbf.model.character.AbilityConditionType;
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
import com.jjktbf.model.move.AttackLaunchMode;
import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.jjktbf.model.character.Equipment;

/**
 * Runtime coverage for Defensive+Attack hybrid moves: defence wins the
 * timeline, and the attack portion launches per its {@link AttackLaunchMode} —
 * at the firing tick (ON_FIRE) or when the move's own defence successfully
 * resolves an incoming attack (ON_DEFENCE), either as a custom attack on the
 * move itself or as a referenced existing move.
 *
 * <p>Exercised through the full {@link CombatResolver} with deterministic
 * inputs: never-miss attacks and a 100% block, so the launch mechanics (not
 * the probability) are under test.</p>
 */
class DefenceAttackHybridTest {

    /** The DEFENSIVE tag wins: a hybrid derives DEFENSIVE and plans on the defensive board. */
    @Test
    void hybridDerivesDefensiveAndPlansOnTheDefensiveBoard() {
        Move hybrid = counterHybrid(AttackLaunchMode.ON_DEFENCE, null, null);
        assertEquals(MoveCategory.DEFENSIVE, hybrid.getCategory());
        assertTrue(hybrid.isDefenceAttackHybrid());
        assertTrue(hybrid.launchesAttackOnDefence());
        assertFalse(hybrid.isHostile(),
            "An on-defence hybrid counter targets its attacker — no planned target needed.");
        assertEquals(BattlePlan.Board.DEFENSIVE, BattlePlan.boardFor(hybrid));

        Move onFire = counterHybrid(AttackLaunchMode.ON_FIRE, null, null);
        assertTrue(onFire.launchesAttackOnFire());
        assertTrue(onFire.isHostile());
        assertEquals(BattlePlan.Board.DEFENSIVE, BattlePlan.boardFor(onFire),
            "Defence wins over attack even when the hybrid launches on fire.");

        Move attack = new Move.Builder("PLAIN")
            .name("Plain Attack").category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK))
            .apCost(2).unleashPoint(1).neverMiss(true)
            .hitComponents(List.of(component(20, 0)))
            .build();
        assertEquals(BattlePlan.Board.OFFENSIVE, BattlePlan.boardFor(attack));
    }

    /** ON_FIRE: the hybrid grants its block AND lands its own strike at the firing tick. */
    @Test
    void onFireHybridGrantsDefenceAndStrikesAtItsFireTick() {
        BattleCombatant defender = fighter("Defender", 200);
        BattleCombatant attacker = fighter("Attacker", 50);
        BattleState state = battle(defender, attacker);

        BattlePlan defensePlan = planFor(defender);
        defensePlan.place(
            counterHybrid(AttackLaunchMode.ON_FIRE, null, null),
            1, 0, attacker.getInstanceId());
        defender.setTimeline(defensePlan.toLegacyTimeline());
        attackAt(state, attacker, defender, 5, 20, 0);

        int defenderBefore = defender.getCurrentHp();
        int attackerBefore = attacker.getCurrentHp();
        resolveRoundEvents(state);

        assertEquals(defenderBefore, defender.getCurrentHp(),
            "The 100% block should negate the incoming attack.");
        assertTrue(attacker.getCurrentHp() < attackerBefore,
            "The hybrid's own strike should launch at its firing tick.");
    }

    /** ON_DEFENCE: the custom attack counters the attacker when the block resolves. */
    @Test
    void onDefenceHybridCountersWhenItsBlockResolves() {
        BattleCombatant defender = fighter("Defender", 200);
        BattleCombatant attacker = fighter("Attacker", 50);
        BattleState state = battle(defender, attacker);

        BattlePlan defensePlan = planFor(defender);
        defensePlan.place(
            counterHybrid(AttackLaunchMode.ON_DEFENCE, null, null), 1, 0);
        defender.setTimeline(defensePlan.toLegacyTimeline());
        attackAt(state, attacker, defender, 5, 20, 0);

        int defenderBefore = defender.getCurrentHp();
        int attackerBefore = attacker.getCurrentHp();
        List<CombatEvent> events = resolveRoundEvents(state);

        assertEquals(defenderBefore, defender.getCurrentHp(),
            "The 100% block should negate the incoming attack.");
        assertTrue(attacker.getCurrentHp() < attackerBefore,
            "The counter should strike the attacker after the block resolves.");
        CombatEvent counter = events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.MOVE_FIRED)
            .filter(event -> event.getMessage() != null
                && event.getMessage().contains("counterattacked"))
            .findFirst().orElse(null);
        assertNotNull(counter, "A counterattack MOVE_FIRED event should be emitted.");
        assertEquals(defender, counter.getSource());
    }

    /** A multi-hit attack blocked component-by-component triggers the counter once. */
    @Test
    void multiHitBlockedAttackTriggersOneCounter() {
        BattleCombatant defender = fighter("Defender", 200);
        BattleCombatant attacker = fighter("Attacker", 50);
        BattleState state = battle(defender, attacker);

        BattlePlan defensePlan = planFor(defender);
        defensePlan.place(
            counterHybrid(AttackLaunchMode.ON_DEFENCE, null, null), 1, 0);
        defender.setTimeline(defensePlan.toLegacyTimeline());
        // Two same-tick components: both get blocked, but the counter fires once.
        attackAt(state, attacker, defender, 5, 20, 0, 0);

        List<CombatEvent> events = resolveRoundEvents(state);
        long counters = events.stream()
            .filter(event -> event.getType() == CombatEvent.Type.MOVE_FIRED)
            .filter(event -> event.getMessage() != null
                && event.getMessage().contains("counterattacked"))
            .count();
        assertEquals(1, counters,
            "One defended incoming execution must trigger at most one counter.");
    }

    /** ON_DEFENCE with a referenced move: the reference launches and pays its own CE. */
    @Test
    void onDefenceReferencedMoveCountersAndPaysItsOwnCe() {
        BattleCombatant defender = fighter("Defender", 200, 200);
        BattleCombatant attacker = fighter("Attacker", 50, 200);
        BattleState state = battle(defender, attacker);

        Move referenced = new Move.Builder("000002")
            .name("Riposte Strike").category(MoveCategory.PHYSICAL).neverMiss(true)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK))
            .apCost(2).unleashPoint(1)
            .baseCeCost(10).hasCeCost(true).minCeCost(10).maxCeCost(10)
            .hitComponents(List.of(component(25, 0)))
            .build();
        BattlePlan defensePlan = planFor(defender);
        defensePlan.place(
            counterHybrid(AttackLaunchMode.ON_DEFENCE, null, referenced), 1, 0);
        defender.setTimeline(defensePlan.toLegacyTimeline());
        attackAt(state, attacker, defender, 5, 20, 0);

        int defenderBefore = defender.getCurrentHp();
        int attackerBefore = attacker.getCurrentHp();
        int ceBefore = defender.getCurrentCe();
        List<CombatEvent> events = resolveRoundEvents(state);

        assertEquals(defenderBefore, defender.getCurrentHp(),
            "The 100% block should negate the incoming attack.");
        assertTrue(attacker.getCurrentHp() < attackerBefore,
            "The referenced move should strike the attacker.");
        assertTrue(defender.getCurrentCe() < ceBefore,
            "The referenced move's own CE cost should be paid at launch.");
        assertTrue(events.stream().anyMatch(event ->
                event.getType() == CombatEvent.Type.CE_DRAINED
                    && event.getMove() != null
                    && "Riposte Strike".equals(event.getMove().getName())),
            "A CE_DRAINED event for the referenced move should be emitted.");
    }

    /** A launch condition that fails suppresses the counter (the block still works). */
    @Test
    void launchConditionSuppressesTheCounter() {
        BattleCombatant defender = fighter("Defender", 200);
        BattleCombatant attacker = fighter("Attacker", 50);
        BattleState state = battle(defender, attacker);

        AbilityConditionData lowHp = AbilityConditionType.HP_VALUE_AT_OR_BELOW.createDefault();
        lowHp.actor = AbilityConditionActor.SELF.name();
        lowHp.amount = 10; // defender is at full HP → condition false

        BattlePlan defensePlan = planFor(defender);
        defensePlan.place(
            counterHybrid(AttackLaunchMode.ON_DEFENCE, lowHp, null), 1, 0);
        defender.setTimeline(defensePlan.toLegacyTimeline());
        attackAt(state, attacker, defender, 5, 20, 0);

        int defenderBefore = defender.getCurrentHp();
        int attackerBefore = attacker.getCurrentHp();
        List<CombatEvent> events = resolveRoundEvents(state);

        assertEquals(defenderBefore, defender.getCurrentHp(),
            "The block itself must still resolve.");
        assertEquals(attackerBefore, attacker.getCurrentHp(),
            "A false launch condition must suppress the counter.");
        assertTrue(events.stream().noneMatch(event ->
                event.getMessage() != null && event.getMessage().contains("counterattacked")));
    }

    /** A zero launch chance roll suppresses the counter. */
    @Test
    void zeroLaunchChanceSuppressesTheCounter() {
        BattleCombatant defender = fighter("Defender", 200);
        BattleCombatant attacker = fighter("Attacker", 50);
        BattleState state = battle(defender, attacker);

        BattlePlan defensePlan = planFor(defender);
        defensePlan.place(
            counterHybrid(AttackLaunchMode.ON_DEFENCE, null, null, true, 0), 1, 0);
        defender.setTimeline(defensePlan.toLegacyTimeline());
        attackAt(state, attacker, defender, 5, 20, 0);

        int attackerBefore = attacker.getCurrentHp();
        resolveRoundEvents(state);

        assertEquals(attackerBefore, attacker.getCurrentHp(),
            "A 0% launch chance must suppress the counter.");
    }

    /** The builder enforces the hybrid authoring invariants. */
    @Test
    void hybridBuilderValidation() {
        IllegalStateException missingMode = assertThrows(IllegalStateException.class,
            () -> new Move.Builder("NO_MODE")
                .name("No Mode").category(MoveCategory.DEFENSIVE)
                .tags(Set.of(MoveTag.DEFENSIVE, MoveTag.ATTACK, MoveTag.PHYSICAL))
                .apCost(2).unleashPoint(1)
                .defenseType(DefenseType.BLOCK)
                .hitComponents(List.of(component(25, 0)))
                .build());
        assertTrue(missingMode.getMessage().contains("must choose an attack launch mode"));

        IllegalStateException bothSources = assertThrows(IllegalStateException.class,
            () -> new Move.Builder("BOTH")
                .name("Both Sources").category(MoveCategory.DEFENSIVE)
                .tags(Set.of(MoveTag.DEFENSIVE, MoveTag.ATTACK, MoveTag.PHYSICAL))
                .apCost(2).unleashPoint(1)
                .defenseType(DefenseType.BLOCK)
                .hitComponents(List.of(component(25, 0)))
                .attackLaunchMode(AttackLaunchMode.ON_DEFENCE)
                .attackLaunchMoveId("000099")
                .build());
        assertTrue(bothSources.getMessage().contains("cannot both reference"));

        IllegalStateException neitherSource = assertThrows(IllegalStateException.class,
            () -> new Move.Builder("NEITHER")
                .name("Neither Source").category(MoveCategory.DEFENSIVE)
                .tags(Set.of(MoveTag.DEFENSIVE, MoveTag.ATTACK, MoveTag.PHYSICAL))
                .apCost(2).unleashPoint(1)
                .defenseType(DefenseType.BLOCK)
                .attackLaunchMode(AttackLaunchMode.ON_DEFENCE)
                .build());
        assertTrue(neitherSource.getMessage().contains("must define hit components or reference"));

        IllegalStateException nonHybrid = assertThrows(IllegalStateException.class,
            () -> new Move.Builder("PLAIN_ATTACK")
                .name("Plain Attack").category(MoveCategory.PHYSICAL)
                .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK))
                .apCost(2).unleashPoint(1)
                .hitComponents(List.of(component(20, 0)))
                .attackLaunchMode(AttackLaunchMode.ON_FIRE)
                .build());
        assertTrue(nonHybrid.getMessage().contains("require a Defensive+Attack hybrid"));

        IllegalStateException tagMismatch = assertThrows(IllegalStateException.class,
            () -> new Move.Builder("TAG_MISMATCH")
                .name("Tag Mismatch").category(MoveCategory.DEFENSIVE)
                .tags(Set.of(MoveTag.DEFENSIVE, MoveTag.ATTACK, MoveTag.PHYSICAL))
                .apCost(2).unleashPoint(1)
                .defenseType(DefenseType.BLOCK)
                .hitComponents(List.of(new HitComponent(
                    25, Set.of(MoveTag.CURSED_ENERGY), 0, false, true)))
                .attackLaunchMode(AttackLaunchMode.ON_DEFENCE)
                .build());
        assertTrue(tagMismatch.getMessage().contains("Hybrid damage tags must match"));
    }

    /** MoveData resolves the referenced launch move through the lookup, cycle-safe. */
    @Test
    void moveDataResolvesTheReferencedLaunchMove() {
        MoveData referenced = referencedAttackData("000002");
        MoveData hybrid = hybridData("000001", "000002");
        Map<String, MoveData> repo = Map.of("000001", hybrid, "000002", referenced);

        Move move = hybrid.toMoveResolved(repo::get);

        assertNotNull(move.getAttackLaunchMove());
        assertEquals("000002", move.getAttackLaunchMove().getId());
        assertTrue(move.referencesAttackMove());

        // A cross-reference cycle (A→B→A) must not recurse forever: one full
        // lap resolves, then the innermost reference stays unresolved and
        // no-ops at runtime.
        MoveData hybridA = hybridData("000001", "000002");
        MoveData hybridB = hybridData("000002", "000001");
        Map<String, MoveData> cyclic = Map.of("000001", hybridA, "000002", hybridB);
        Move cycleA = hybridA.toMoveResolved(cyclic::get);
        assertEquals("000002", cycleA.getAttackLaunchMove().getId());
        assertEquals("000001", cycleA.getAttackLaunchMove().getAttackLaunchMove().getId());
        assertNull(cycleA.getAttackLaunchMove().getAttackLaunchMove().getAttackLaunchMove());
    }

    /**
     * A hybrid may author its custom attack the legacy way — move-level base
     * power, no explicit hit components. The builder synthesizes the fallback
     * component from the hybrid's damage-nature tags, exactly like the legacy
     * path for plain attack moves.
     */
    @Test
    void legacyBasePowerHybridSynthesizesItsCustomAttack() {
        Move hybrid = new Move.Builder("LEGACY")
            .name("Legacy Riposte").category(MoveCategory.DEFENSIVE)
            .tags(Set.of(MoveTag.DEFENSIVE, MoveTag.ATTACK, MoveTag.PHYSICAL))
            .apCost(2).unleashPoint(1).neverMiss(true)
            .defenseType(DefenseType.BLOCK).blockDuration(10)
            .basePower(30).baseAccuracy(1.0)
            .attackLaunchMode(AttackLaunchMode.ON_DEFENCE)
            .build();

        assertEquals(1, hybrid.getHitComponents().size());
        assertEquals(30, hybrid.getHitComponents().get(0).getBasePower());
        assertTrue(hybrid.getHitComponents().get(0).getTags()
            .contains(MoveTag.PHYSICAL), "The synthesized component carries the move's damage nature.");
        assertTrue(hybrid.launchesAttackOnDefence());

        // A legacy hybrid without any authored power still names the problem.
        IllegalStateException noAttack = assertThrows(IllegalStateException.class,
            () -> new Move.Builder("LEGACY_EMPTY")
                .name("Empty Legacy").category(MoveCategory.DEFENSIVE)
                .tags(Set.of(MoveTag.DEFENSIVE, MoveTag.ATTACK, MoveTag.PHYSICAL))
                .apCost(2).unleashPoint(1)
                .defenseType(DefenseType.BLOCK)
                .attackLaunchMode(AttackLaunchMode.ON_DEFENCE)
                .build());
        assertTrue(noAttack.getMessage().contains("must define hit components or reference"));
    }

    /** The legacy hybrid counts as an attacking move for ON_HIT effect rows. */
    @Test
    void legacyHybridAllowsOnHitEffectRows() {
        MoveEffectData stun = AbilityEffectType.STUN_CURRENT_ACTION.createDefaultMoveEffect();
        stun.trigger = MoveEffectTrigger.ON_HIT.name();
        stun.target = AbilityEffectTarget.ENEMY.name();

        Move hybrid = new Move.Builder("LEGACY_ON_HIT")
            .name("Stunning Riposte").category(MoveCategory.DEFENSIVE)
            .tags(Set.of(MoveTag.DEFENSIVE, MoveTag.ATTACK, MoveTag.PHYSICAL))
            .apCost(2).unleashPoint(1).neverMiss(true)
            .defenseType(DefenseType.BLOCK).blockDuration(10)
            .basePower(30)
            .attackLaunchMode(AttackLaunchMode.ON_DEFENCE)
            .effects(List.of(stun))
            .build();

        assertEquals(1, hybrid.getHitComponents().size());
        assertEquals(1, hybrid.effectsFor(MoveEffectTrigger.ON_HIT, -1).size());
    }

    /** MoveData in the legacy shape (base power, no component list) builds too. */
    @Test
    void legacyShapedMoveDataBuildsAHybrid() {
        MoveData data = new MoveData();
        data.id = "000010";
        data.name = "Legacy Hybrid";
        data.tags = List.of(
            MoveTag.DEFENSIVE.name(), MoveTag.ATTACK.name(), MoveTag.PHYSICAL.name());
        data.defenseType = DefenseType.BLOCK.name();
        data.blockDuration = 10;
        data.apCost = 2;
        data.unleashPoint = 1;
        data.basePower = 40;
        data.attackLaunchMode = AttackLaunchMode.ON_DEFENCE.name();

        Move built = data.toMove();
        assertEquals(1, built.getHitComponents().size());
        assertEquals(40, built.getHitComponents().get(0).getBasePower());
        assertTrue(built.launchesAttackOnDefence());
    }

    // ----- helpers -----

    private static HitComponent component(int power, int delayTicks) {
        return new HitComponent(power, Set.of(MoveTag.PHYSICAL), delayTicks, false, true);
    }

    /**
     * A Defensive+Attack hybrid: a 100% percentage block carrying a never-miss
     * custom strike, launching per {@code mode}. A non-null {@code referenced}
     * move replaces the custom strike.
     */
    private static Move counterHybrid(
        AttackLaunchMode mode,
        AbilityConditionData condition,
        Move referenced
    ) {
        return counterHybrid(mode, condition, referenced, false, 100);
    }

    private static Move counterHybrid(
        AttackLaunchMode mode,
        AbilityConditionData condition,
        Move referenced,
        boolean chanceEnabled,
        int chancePercent
    ) {
        Move.Builder builder = new Move.Builder("HYBRID_" + mode.name())
            .name("Iron Riposte").category(MoveCategory.DEFENSIVE)
            .tags(Set.of(MoveTag.DEFENSIVE, MoveTag.ATTACK, MoveTag.PHYSICAL))
            .apCost(2).unleashPoint(1).neverMiss(true)
            .defenseType(DefenseType.BLOCK)
            .blockStyle(BlockStyle.PERCENTAGE).blockDamageReduction(100)
            .blockDuration(10)
            .attackLaunchMode(mode)
            .attackLaunchCondition(condition)
            .attackLaunchChance(chanceEnabled, chancePercent);
        if (referenced != null) {
            builder.attackLaunchMoveId(referenced.getId())
                .attackLaunchMove(referenced);
        } else {
            builder.hitComponents(List.of(component(25, 0)));
        }
        return builder.build();
    }

    private static MoveData referencedAttackData(String id) {
        MoveData data = new MoveData();
        data.id = id;
        data.name = "Riposte Strike";
        data.tags = List.of(MoveTag.PHYSICAL.name(), MoveTag.ATTACK.name());
        data.apCost = 2;
        data.unleashPoint = 1;
        data.hitComponents = List.of(new MoveData.HitComponentData());
        data.hitComponents.get(0).basePower = 25;
        data.hitComponents.get(0).tags = List.of(MoveTag.PHYSICAL.name());
        return data;
    }

    private static MoveData hybridData(String id, String referencedId) {
        MoveData data = new MoveData();
        data.id = id;
        data.name = "Iron Riposte";
        data.tags = List.of(
            MoveTag.DEFENSIVE.name(), MoveTag.ATTACK.name(), MoveTag.PHYSICAL.name());
        data.defenseType = DefenseType.BLOCK.name();
        data.blockDuration = 10;
        data.apCost = 2;
        data.unleashPoint = 1;
        data.attackLaunchMode = AttackLaunchMode.ON_DEFENCE.name();
        data.attackLaunchMoveId = referencedId;
        return data;
    }

    private static BattleState battle(BattleCombatant defender, BattleCombatant attacker) {
        return new BattleState(
            BattleState.teamOfFighters(BattleTeamId.PLAYER, List.of(defender)),
            BattleState.teamOfFighters(BattleTeamId.ENEMY, List.of(attacker)));
    }

    /** Plan a never-miss attack of one component per delay, firing at {@code tick}. */
    private static void attackAt(
        BattleState state,
        BattleCombatant attacker,
        BattleCombatant defender,
        int tick,
        int power,
        int... delayTicks
    ) {
        List<HitComponent> components = java.util.Arrays.stream(delayTicks)
            .mapToObj(delay -> component(power, delay))
            .toList();
        Move attack = new Move.Builder("INCOMING")
            .name("Incoming Strike").category(MoveCategory.PHYSICAL).neverMiss(true)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.MELEE))
            .apCost(tick + 1).unleashPoint(tick)
            .hitComponents(components)
            .build();
        BattlePlan attackPlan = planFor(attacker);
        attackPlan.place(attack, 1, 0, defender.getInstanceId());
        attacker.setTimeline(attackPlan.toLegacyTimeline());
    }

    private static List<CombatEvent> resolveRoundEvents(BattleState state) {
        state.transitionTo(BattleState.Phase.RESOLUTION);
        return new CombatResolver(new SeededRandomSource(1L)).resolveRound(state);
    }

    private static BattlePlan planFor(BattleCombatant combatant) {
        return new BattlePlan(combatant.getMaxApBar(), combatant.getCurrentCe(), 60);
    }

    private static BattleCombatant fighter(String name, int speed) {
        return fighter(name, speed, 0);
    }

    private static BattleCombatant fighter(String name, int speed, int ceReserves) {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(300).speed(speed).cursedEnergyReserves(ceReserves)
            .build();
        SorcererCharacter character = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), Equipment.NONE);
        return new BattleCombatant(character, List.of());
    }
}

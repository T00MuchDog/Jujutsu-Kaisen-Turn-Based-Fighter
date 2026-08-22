package com.jjktbf.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.AbilityEffectTarget;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.ShikigamiCharacter;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.coded.ShikigamiMoveRuntime;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.SeededRandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.HitComponent;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.StatusEffectType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.jjktbf.model.character.Equipment;

/**
 * Unit tests for the Shikigami AI archetype: desummon detection, the
 * defense-aware attack scoring, and the end-to-end plan it produces (bail on
 * low HP, spread + AP spend when healthy, defensive moves ignored).
 *
 * <p>Lives in {@code com.jjktbf.controller} so it can exercise the strategy's
 * package-private scoring helpers directly.
 */
class ShikigamiAIStrategyTest {

    private final ShikigamiAIStrategy strategy = new ShikigamiAIStrategy();
    private final RandomSource rng = new SeededRandomSource(1L);

    // -------------------------------------------------------------------------
    // Desummon detection
    // -------------------------------------------------------------------------

    @Test
    void detectsTheRealDesummonMoveButNotAttacks() throws IOException {
        List<Move> canonical = loadCanonicalMoves();
        assertTrue(ShikigamiAIStrategy.isDesummonSelf(moveById(canonical, "000053")),
            "move 000053 is the shikigami self-desummon");
        assertFalse(ShikigamiAIStrategy.isDesummonSelf(moveById(canonical, "000054")),
            "a normal shikigami attack is not a desummon");
    }

    // -------------------------------------------------------------------------
    // Opponent-awareness: counting the player's committed defenses
    // -------------------------------------------------------------------------

    @Test
    void countsMeleeAndRangedDodgesAndBlocksFromTheOpponentsPlan() {
        BattleCombatant opponent = sorcerer("Opp");
        Timeline timeline = new Timeline(60);
        timeline.placeAt(dodge("MD", "MELEE"), 1, 0);   // melee only
        timeline.placeAt(dodge("RD", "RANGED"), 7, 0);  // ranged only
        timeline.placeAt(block("BK"), 13, 0);           // block
        timeline.placeAt(dodge("BD", "BOTH"), 19, 0);   // covers both
        opponent.setTimeline(timeline);

        ShikigamiAIStrategy.OpponentDefenses defenses =
            ShikigamiAIStrategy.countOpponentDefenses(opponent);

        // MELEE scope + BOTH scope => 2 melee-covering dodges.
        assertEquals(2, defenses.meleeDodge);
        // RANGED scope + BOTH scope => 2 ranged-covering dodges.
        assertEquals(2, defenses.rangedDodge);
        assertEquals(1, defenses.blockParry);
    }

    @Test
    void noOpponentPlanYieldsNoDefenses() {
        BattleCombatant opponent = sorcerer("Opp"); // fresh combatant, no timeline
        ShikigamiAIStrategy.OpponentDefenses defenses =
            ShikigamiAIStrategy.countOpponentDefenses(opponent);
        assertEquals(0, defenses.meleeDodge);
        assertEquals(0, defenses.rangedDodge);
        assertEquals(0, defenses.blockParry);
    }

    // -------------------------------------------------------------------------
    // Scoring
    // -------------------------------------------------------------------------

    @Test
    void attackCarryingEffectsOutscoresAnEqualPowerPlainAttack() {
        Move plain = meleeAttack("PLAIN", 20);
        Move withEffects = meleeAttackWithEffect("EFFECTS", 20);
        BattleCombatant ai = shikigami(plain);
        ShikigamiAIStrategy.OpponentDefenses none = new ShikigamiAIStrategy.OpponentDefenses();

        assertTrue(ShikigamiAIStrategy.scoreAttack(withEffects, ai, none)
                 > ShikigamiAIStrategy.scoreAttack(plain, ai, none));
    }

    @Test
    void higherBasePowerAttackScoresHigher() {
        Move weak = meleeAttack("WEAK", 10);
        Move strong = meleeAttack("STRONG", 40);
        BattleCombatant ai = shikigami(weak);
        ShikigamiAIStrategy.OpponentDefenses none = new ShikigamiAIStrategy.OpponentDefenses();

        assertTrue(ShikigamiAIStrategy.scoreAttack(strong, ai, none)
                 > ShikigamiAIStrategy.scoreAttack(weak, ai, none));
    }

    @Test
    void meleeAttackIsPenalisedWhenOpponentHasMeleeDodgeButRangedIsNot() {
        Move melee = meleeAttack("MELEE", 20);
        Move ranged = rangedAttack("RANGED", 20);
        BattleCombatant ai = shikigami(melee, ranged);
        ShikigamiAIStrategy.OpponentDefenses meleeDodgeOnly =
            new ShikigamiAIStrategy.OpponentDefenses();
        meleeDodgeOnly.meleeDodge = 2;

        double meleeScore = ShikigamiAIStrategy.scoreAttack(melee, ai, meleeDodgeOnly);
        double rangedScore = ShikigamiAIStrategy.scoreAttack(ranged, ai, meleeDodgeOnly);

        assertTrue(rangedScore > meleeScore,
            "ranged should beat melee when only melee dodges are committed");
        // And symmetrically for ranged dodges.
        ShikigamiAIStrategy.OpponentDefenses rangedDodgeOnly =
            new ShikigamiAIStrategy.OpponentDefenses();
        rangedDodgeOnly.rangedDodge = 2;
        assertTrue(ShikigamiAIStrategy.scoreAttack(melee, ai, rangedDodgeOnly)
                 > ShikigamiAIStrategy.scoreAttack(ranged, ai, rangedDodgeOnly));
    }

    @Test
    void blocksMildlyPenaliseBothRanges() {
        Move attack = meleeAttack("M", 20);
        BattleCombatant ai = shikigami(attack);
        ShikigamiAIStrategy.OpponentDefenses none = new ShikigamiAIStrategy.OpponentDefenses();
        ShikigamiAIStrategy.OpponentDefenses withBlock = new ShikigamiAIStrategy.OpponentDefenses();
        withBlock.blockParry = 2;

        assertTrue(ShikigamiAIStrategy.scoreAttack(attack, ai, none)
                 > ShikigamiAIStrategy.scoreAttack(attack, ai, withBlock));
    }

    // -------------------------------------------------------------------------
    // selectPlan — bail behaviour
    // -------------------------------------------------------------------------

    @Test
    void lowHpBailsWithOnlyTheDesummonMove() {
        Move desummon = desummonMove();
        Move attack = meleeAttack("ATK", 30, 10);
        BattleCombatant ai = shikigami(desummon, attack);
        // Drop to 1 HP => well below the 10% bail threshold.
        ai.applyDamage(ai.getMaxHp() - 1);

        BattlePlan plan = strategy.selectPlan(ai, sorcerer("Opp"), rng);

        assertEquals(1, plan.allSegments().size(), "a bailing shikigami places only its desummon");
        assertTrue(ShikigamiAIStrategy.isDesummonSelf(plan.allSegments().get(0).getMove()));
        assertTrue(plan.allSegments().stream().noneMatch(s -> s.getMove().hasTag("ATTACK")));
    }

    // -------------------------------------------------------------------------
    // selectPlan — healthy offence: spread + AP spend, no defenses
    // -------------------------------------------------------------------------

    @Test
    void healthyShikigamiSpreadsAttacksSpendsApAndIgnoresDefenses() {
        // AP budget is 60 (speed/combatAbility 80/80). Each attack costs 15 AP =>
        // exactly four attacks. The strong opponent (300 AP) lifts the grid to
        // 300 dots so the four attacks spread out rather than pack.
        Move attackA = meleeAttack("ATA", 30, 15);
        Move attackB = meleeAttack("ATB", 25, 15);
        Move ignoredBlock = block("IGN");           // defensive move must be dropped
        BattleCombatant ai = shikigami(attackA, attackB, ignoredBlock);

        BattlePlan plan = strategy.selectPlan(ai, strongSorcerer("Opp"), rng);

        List<ActionSegment> placed = plan.allSegments();
        assertFalse(placed.isEmpty());
        // Pure offence: every placed move is an attack, no defensive move placed.
        assertTrue(placed.stream().allMatch(s -> s.getMove().hasTag("ATTACK")),
            "no defensive or utility moves placed");
        assertTrue(placed.stream().noneMatch(s -> s.getMove().isDefensive()));

        // Uses as much AP as possible: the leftover is smaller than one attack.
        assertTrue(ai.getMaxApBar() - plan.totalApUsed() < 15,
            "leftover AP (" + (ai.getMaxApBar() - plan.totalApUsed()) + ") must be < one attack's cost");

        // Attacks are spread, not packed: some consecutive pair has a real gap.
        List<Integer> starts = placed.stream().map(ActionSegment::getStartTick).sorted().toList();
        assertTrue(starts.size() >= 2);
        boolean hasGap = false;
        for (int i = 0; i < placed.size(); i++) {
            int start = starts.get(i);
            int end = start + 15 - 1;
            if (i + 1 < starts.size() && starts.get(i + 1) > end + 1) {
                hasGap = true;
                break;
            }
        }
        assertTrue(hasGap, "attacks are spread across the round, not clumped: " + starts);
    }

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private static BattleCombatant shikigami(Move... moves) {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(100).speed(80).combatAbility(80).build();
        ShikigamiCharacter c = new ShikigamiCharacter(
            "shikigami", "Shikigami", stats, null, List.of(moves));
        return new BattleCombatant(c, List.of());
    }

    private static BattleCombatant sorcerer(String name) {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(100).speed(80).combatAbility(80).build();
        SorcererCharacter c = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), Equipment.NONE);
        return new BattleCombatant(c, List.of());
    }

    /** A strong opponent (300 AP) so the battle grid widens to 300 dots. */
    private static BattleCombatant strongSorcerer(String name) {
        CharacterStats stats = new CharacterStats.Builder()
            .vitality(300).speed(300).combatAbility(300).build();
        SorcererCharacter c = new SorcererCharacter(
            name.toLowerCase(), name, stats, null, List.of(), List.of(), Equipment.NONE);
        return new BattleCombatant(c, List.of());
    }

    private static Move meleeAttack(String id, int basePower) {
        return meleeAttack(id, basePower, 15);
    }

    private static Move meleeAttack(String id, int basePower, int apCost) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.MELEE))
            .hitComponents(List.of(new HitComponent(
                basePower, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .apCost(apCost).unleashPoint(1)
            .build();
    }

    private static Move rangedAttack(String id, int basePower) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.RANGED))
            .hitComponents(List.of(new HitComponent(
                basePower, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .apCost(15).unleashPoint(1)
            .build();
    }

    private static Move meleeAttackWithEffect(String id, int basePower) {
        MoveEffectData effect = AbilityEffectType.APPLY_STATUS.createDefaultMoveEffect();
        effect.effectId = "effect-" + id;
        effect.trigger = MoveEffectTrigger.ON_HIT.name();
        effect.target = AbilityEffectTarget.ENEMY.name();
        effect.stringValue = StatusEffectType.STRENGTH_DECREASE.name();
        effect.durationRounds = 2;
        effect.magnitude = 10.0;
        return new Move.Builder(id)
            .name(id).category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.ATTACK, MoveTag.MELEE))
            .hitComponents(List.of(new HitComponent(
                basePower, Set.of(MoveTag.PHYSICAL), 0, false, true)))
            .effects(List.of(effect))
            .apCost(15).unleashPoint(1)
            .build();
    }

    private static Move dodge(String id, String scope) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.DEFENSIVE)
            .tags(Set.of(MoveTag.DEFENSIVE))
            .defenseType(DefenseType.DODGE).dodgeScope(scope).dodgeChance(50)
            .apCost(5).unleashPoint(1)
            .build();
    }

    private static Move block(String id) {
        return new Move.Builder(id)
            .name(id).category(MoveCategory.DEFENSIVE)
            .tags(Set.of(MoveTag.DEFENSIVE, MoveTag.PHYSICAL))
            .defenseType(DefenseType.BLOCK).blockStyle(BlockStyle.PERCENTAGE)
            .blockDamageReduction(50).blockAffectedTags(List.of("PHYSICAL"))
            .apCost(5).unleashPoint(1)
            .build();
    }

    /** A shikigami self-desummon move (utility + coded SHIKIGAMI/DESUMMON_SELF row). */
    private static Move desummonMove() {
        // Built fresh (not via createDefaultMoveEffect) so no leftover coded
        // parameters from another action leak in — matches the real move 000053.
        MoveEffectData effect = new MoveEffectData();
        effect.effectId = "effect-desummon";
        effect.type = AbilityEffectType.CODED_MOVE_ACTION.name();
        effect.trigger = MoveEffectTrigger.ON_FIRE.name();
        effect.codedAbilityKey = ShikigamiMoveRuntime.KEY;
        effect.codedAction = ShikigamiMoveRuntime.DESUMMON_SELF;
        effect.target = AbilityEffectTarget.SELF.name();
        return new Move.Builder("DESUMMON")
            .name("Desummon").category(MoveCategory.UTILITY)
            .tags(Set.of(MoveTag.UTILITY))
            .effects(List.of(effect))
            .apCost(1).unleashPoint(1)
            .build();
    }

    private static List<Move> loadCanonicalMoves() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<MoveData> datas = mapper.readValue(
            movesPath().toFile(), new TypeReference<>() { });
        List<Move> moves = new ArrayList<>();
        for (MoveData data : datas) {
            moves.add(data.toMove());
        }
        return moves;
    }

    private static Move moveById(List<Move> moves, String id) {
        return moves.stream()
            .filter(m -> m.getId().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Missing canonical move " + id));
    }

    private static Path movesPath() throws IOException {
        return List.of(
                Path.of("data", "moves", "all_moves.json"),
                Path.of("..", "data", "moves", "all_moves.json"))
            .stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IOException("Could not locate canonical moves"));
    }
}

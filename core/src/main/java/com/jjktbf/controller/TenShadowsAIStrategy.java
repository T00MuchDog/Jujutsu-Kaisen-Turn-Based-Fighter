package com.jjktbf.controller;

import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.MoveAvailability;
import com.jjktbf.model.combat.PowerCalculator;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI archetype for Megumi Fushiguro (Ten Shadows) — shared by both versions.
 *
 * <p>A cautious, strategic summoner who fights through his shikigami rather than
 * brute force:
 * <ul>
 *   <li>Assesses a <b>danger rating</b> from the opponent's base-stat total
 *       ({@link #dangerTier}).</li>
 *   <li><b>Opens by summoning</b> — one shikigami normally, two immediately
 *       against high-danger opponents — then tops up toward his active cap a
 *       summon per round.</li>
 *   <li>The <b>type</b> of shikigami scales with danger: high danger summons the
 *       strongest available; low danger the cheapest (to conserve CE).</li>
 *   <li>After a loss he <b>only resummons above 35% CE</b> (all non-opening
 *       summons require CE &gt; 35%).</li>
 *   <li>With shikigami out he weaves a <b>mix of technique and non-technique
 *       offence</b>, leaning on cheap non-technique attacks to conserve CE.</li>
 *   <li>Cautious: invests in defense and avoids overcommitting.</li>
 * </ul>
 *
 * <p>State-aware ({@link #buildPlan}) because summon decisions need round number,
 * active/pending counts, destroyed/cooldown state, CE, and the enemy roster for
 * the danger rating. {@link #selectPlan} is a single-opponent fallback.
 */
public class TenShadowsAIStrategy implements AIStrategy {

    // --- Danger thresholds (opponent base-stat total) ---
    static final int DANGER_HIGH_BST = 600;
    static final int DANGER_LOW_BST = 480;

    /** CE fraction required to summon after the opening round (resummon rule). */
    private static final double RESUMMON_CE_FRACTION = 0.35;
    /** De-weight technique attacks while shikigami are out (conserve CE, let them fight). */
    private static final double TECHNIQUE_PENALTY_WITH_SUMMON = 0.6;
    private static final int ATTACK_CAP = 3;
    private static final int DEFENSE_CAP = 2;

    /**
     * Hardcoded shikigami power (base-stat total) — code-only knowledge of the
     * fixed roster, used to pick the right summon for the danger tier.
     */
    private static final Map<String, Integer> SHIKIGAMI_POWER = Map.of(
        "000007", 375, // Divine Dog White
        "000008", 395, // Divine Dog Black
        "000009", 495, // Nue
        "000010", 295, // Toad Gamma
        "000011", 475, // Great Serpent Orochi
        "000012", 660, // Divine Dog Totality
        "000013", 601, // Max Elephant
        "000014", 471  // Toad/Nue Fusion (Well's Unknown Abyss)
    );

    /** Danger tier derived from an opponent's base-stat total. */
    enum DangerTier { LOW, MEDIUM, HIGH }

    // -------------------------------------------------------------------------
    // Entries
    // -------------------------------------------------------------------------

    /** State-aware entry used by the dispatcher's team-plan build. */
    public BattlePlan buildPlan(BattleState state, BattleCombatant ai, RandomSource rng) {
        return placeMoves(state, ai, state.firstActiveEnemyOf(ai), rng);
    }

    /** Single-opponent fallback (interface contract). */
    @Override
    public BattlePlan selectPlan(BattleCombatant ai, BattleCombatant opponent, RandomSource rng) {
        return placeMoves(null, ai, opponent, rng);
    }

    // -------------------------------------------------------------------------
    // Placement
    // -------------------------------------------------------------------------

    private BattlePlan placeMoves(BattleState state, BattleCombatant ai,
                                  BattleCombatant opponent, RandomSource rng) {
        int gridLength = Timeline.gridLengthForStrongestAp(
            Math.max(ai.getMaxApBar(), opponent == null ? 0 : opponent.getMaxApBar()));
        BattlePlan plan = new BattlePlan(ai.getMaxApBar(), ai.getCurrentCe(), gridLength);
        OpponentIntel intel = OpponentIntel.forOpponent(opponent);

        List<Move> summons = new ArrayList<>();
        List<Move> technique = new ArrayList<>();
        List<Move> nonTechnique = new ArrayList<>();
        List<Move> defenses = new ArrayList<>();
        for (Move move : ai.getCharacter().getKnownMoves()) {
            if (state != null && !MoveAvailability.isAvailable(state, ai, move)) continue;
            if (!MoveAvailability.isAvailable(null, ai, move)) continue;
            if (move.summonsCharacter()) {
                summons.add(move);
            } else if (move.hasTag("ATTACK")) {
                (move.hasTag("INNATE_TECHNIQUE") ? technique : nonTechnique).add(move);
            } else if (move.isActiveDefense()) {
                defenses.add(move);
            }
        }

        int round = state == null ? 1 : state.getRoundNumber();
        int cap = activeSummonCap(ai);
        int active = state == null ? 0
            : state.directActiveSummonCount(ai) + state.directPendingSummonCount(ai);
        boolean shikigamiOut = active > 0;
        double ceFraction = ceFraction(ai);
        DangerTier danger = dangerTier(strongestEnemyBst(state, ai));

        // --- Summons (placed early so they materialise as soon as possible). ---
        List<Move> summonPicks = pickSummons(state, ai, plan, summons, danger,
            summonsToPlace(round, cap, active, ceFraction, danger));
        for (Move summon : summonPicks) {
            SmartAIScoring.placeAtOrAfter(plan, summon, ai.computeMoveCeCost(summon), 1);
        }

        // --- Mixed offence (light on the opening round when CE goes to summons). ---
        int attackBudget = (round == 1 && !summonPicks.isEmpty()) ? 1 : ATTACK_CAP;
        placeOffence(ai, plan, technique, nonTechnique, intel, shikigamiOut, attackBudget, rng);

        // --- Cautious defense. ---
        placeDefenses(ai, plan, defenses, intel);
        return plan;
    }

    // -------------------------------------------------------------------------
    // Summon decisions
    // -------------------------------------------------------------------------

    /** How many summons to commit this round. Package-private for testing. */
    static int summonsToPlace(int round, int cap, int active, double ceFraction, DangerTier danger) {
        int room = Math.max(0, cap - active);
        if (room <= 0) return 0;
        if (round <= 1) {
            // Opening: two against high danger, one otherwise. Unconditional.
            return danger == DangerTier.HIGH ? Math.min(2, room) : Math.min(1, room);
        }
        // Later rounds: pace one summon per round, only above the resummon CE threshold.
        if (ceFraction > RESUMMON_CE_FRACTION) return Math.min(1, room);
        return 0;
    }

    /**
     * Pick up to {@code count} distinct, restriction-free, affordable summon moves.
     * High danger ranks by shikigami power (strongest first); otherwise by CE cost
     * (cheapest first, to conserve CE). Package-private for testing.
     */
    static List<Move> pickSummons(
        BattleState state, BattleCombatant ai, BattlePlan plan,
        List<Move> summonMoves, DangerTier danger, int count
    ) {
        List<Move> candidates = new ArrayList<>();
        if (count <= 0) return candidates;
        for (Move m : summonMoves) {
            String id = m.getSummonCharacterId();
            if (id == null) continue;
            if (state != null && state.summonRestrictionReason(ai, id) != null) continue;
            if (!plan.canPlace(m, ai.computeMoveCeCost(m))) continue;
            candidates.add(m);
        }
        Comparator<Move> ranking = danger == DangerTier.HIGH
            ? Comparator.comparingInt((Move m) -> -SHIKIGAMI_POWER.getOrDefault(m.getSummonCharacterId(), 0))
            : Comparator.comparingInt(ai::computeMoveCeCost);
        candidates.sort(ranking);

        List<Move> chosen = new ArrayList<>();
        Set<String> chosenIds = new HashSet<>();
        for (Move m : candidates) {
            String id = m.getSummonCharacterId();
            if (!chosenIds.add(id)) continue;
            chosen.add(m);
            if (chosen.size() >= count) break;
        }
        return chosen;
    }

    // -------------------------------------------------------------------------
    // Danger + state helpers
    // -------------------------------------------------------------------------

    /** Danger tier from an opponent base-stat total. */
    static DangerTier dangerTier(int bst) {
        if (bst >= DANGER_HIGH_BST) return DangerTier.HIGH;
        if (bst < DANGER_LOW_BST) return DangerTier.LOW;
        return DangerTier.MEDIUM;
    }

    /** Sum of a combatant's 10 effective base stats. */
    private static int baseStatTotal(BattleCombatant c) {
        if (c == null) return 0;
        CharacterStats s = c.getEffectiveStats();
        return s.getVitality() + s.getStrength() + s.getDurability() + s.getSpeed()
            + s.getCursedEnergyReserves() + s.getCursedEnergyEfficiency()
            + s.getCursedEnergyOutput() + s.getJujutsuSkill()
            + s.getCombatAbility() + s.getCursedTechniqueMastery();
    }

    /** Highest base-stat total among active enemies (the danger driver). */
    private static int strongestEnemyBst(BattleState state, BattleCombatant ai) {
        if (state == null) return 0;
        int best = 0;
        for (BattleCombatant e : state.activeEnemiesOf(ai)) {
            best = Math.max(best, baseStatTotal(e));
        }
        return best;
    }

    private static int activeSummonCap(BattleCombatant ai) {
        Integer cap = ai.getAbilityFlags().maxActiveSummons;
        return cap == null ? Integer.MAX_VALUE : cap;
    }

    private static double ceFraction(BattleCombatant ai) {
        int max = ai.getMaxCursedEnergy();
        return max <= 0 ? 0.0 : ai.getCurrentCe() / (double) max;
    }

    // -------------------------------------------------------------------------
    // Offence
    // -------------------------------------------------------------------------

    private void placeOffence(
        BattleCombatant ai, BattlePlan plan, List<Move> technique, List<Move> nonTechnique,
        OpponentIntel intel, boolean shikigamiOut, int attackBudget, RandomSource rng
    ) {
        Set<Move> stuck = new HashSet<>();
        int placed = 0;
        while (placed < attackBudget) {
            List<Move> pool = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            for (Move m : technique) {
                if (stuck.contains(m) || !plan.canPlace(m, ai.computeMoveCeCost(m))) continue;
                pool.add(m);
                weights.add(attackWeight(m, ai, intel, true, shikigamiOut));
            }
            for (Move m : nonTechnique) {
                if (stuck.contains(m) || !plan.canPlace(m, ai.computeMoveCeCost(m))) continue;
                pool.add(m);
                weights.add(attackWeight(m, ai, intel, false, shikigamiOut));
            }
            Move pick = SmartAIScoring.weightedRandomPick(pool, weights, rng);
            if (pick == null) break;
            int ceCost = ai.computeMoveCeCost(pick);
            ActionSegment seg = SmartAIScoring.placeAtOrAfter(plan, pick, ceCost, 1);
            if (seg == null) {
                stuck.add(pick);
            } else {
                placed++;
            }
        }
    }

    private static double attackWeight(
        Move move, BattleCombatant ai, OpponentIntel intel, boolean isTechnique, boolean shikigamiOut
    ) {
        double basePower = Math.max(1, move.getTotalBasePower());
        double power = Math.max(1, PowerCalculator.compute(move.getCategory(), ai.getEffectiveStats()));
        double weight = basePower * power;
        weight *= SmartAIScoring.effectMultiplier(move);
        weight *= SmartAIScoring.dodgeExposureMultiplier(move, intel);
        // Conserve CE once shikigami are carrying the offence: lean on non-technique.
        if (isTechnique && shikigamiOut) weight *= TECHNIQUE_PENALTY_WITH_SUMMON;
        return weight;
    }

    // -------------------------------------------------------------------------
    // Defense
    // -------------------------------------------------------------------------

    private void placeDefenses(BattleCombatant ai, BattlePlan plan, List<Move> defenses, OpponentIntel intel) {
        List<Move> useful = new ArrayList<>();
        for (Move d : defenses) {
            if (SmartAIScoring.defenseValue(d, intel) > 0
                && plan.canPlace(d, ai.computeMoveCeCost(d))) {
                useful.add(d);
            }
        }
        useful.sort(Comparator.comparingDouble(
            (Move d) -> SmartAIScoring.defenseValue(d, intel)).reversed());
        int placed = 0;
        for (Move d : useful) {
            if (placed >= DEFENSE_CAP) break;
            int ceCost = ai.computeMoveCeCost(d);
            ActionSegment seg = (placed == 0)
                ? SmartAIScoring.placeAtOrAfter(plan, d, ceCost, 1)
                : SmartAIScoring.placeAtOrAfter(plan, d, ceCost, 1);
            if (seg != null) placed++;
        }
    }
}

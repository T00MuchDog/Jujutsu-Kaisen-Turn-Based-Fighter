package com.jjktbf.controller;

import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.character.coded.CursedSpeechAbility;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatantId;
import com.jjktbf.model.combat.MoveAvailability;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AI archetype for Toge Inumaki (Cursed Speech).
 *
 * <p>A passive support caster who commands multiple targets but is gated by his
 * own recoil. Behaviour:
 * <ul>
 *   <li>Prefers low-cost commands (Don't Move, Plummet); his CE is limited.</li>
 *   <li>1–3 Cursed Speech commands per round plus a light spattering of melee.</li>
 *   <li>Likes to dodge/deflect; support-oriented, not a primary damage dealer.</li>
 *   <li>Higher-damage commands are weighted up once a target drops below 40% HP.</li>
 *   <li><b>Never</b> commits a command whose recoil would kill him.</li>
 *   <li>Status commands are weighted up vs opponents with many committed attacks;
 *       damaging commands vs opponents turtling behind defenses.</li>
 *   <li>When Sleep is placed, the next attack is placed at the far end of the
 *       timeline to maximise the disable window.</li>
 *   <li>High chance to open each round with Don't Move (instant interrupt).</li>
 * </ul>
 *
 * <p><b>Multitargeting.</b> Cursed Speech commands hit up to {@code aoeTargetCount}
 * (3) foes. Damaging commands prefer to target sorcerers; status commands prefer
 * shikigami. The first target of each command is always committed (he uses the
 * move), but extra targets are gated by a round-level recoil budget of 20% of his
 * current HP — once accumulated recoil crosses that, he stops adding targets.
 *
 * <p>Multitargeting needs the full enemy roster, which {@link AIStrategy#selectPlan}
 * does not receive. So {@link #buildPlan} is the state-aware entry used by the
 * dispatcher's {@code selectTeamPlan}; {@link #selectPlan} is a single-opponent
 * fallback (it stamps only that one opponent, so multitarget tests should go via
 * the team-plan path).
 */
public class CursedSpeechAIStrategy implements AIStrategy {

    // --- Tunables (code-only) ---
    private static final double OPEN_DONT_MOVE_CHANCE = 0.70;
    private static final int CS_MOVE_CAP = 3;
    private static final int MELEE_CAP = 2;
    private static final int DEFENSE_CAP = 2;
    private static final double MULTITARGET_RECOIL_FRACTION = 0.20;
    private static final double CHEAPNESS_REF = 40.0;
    private static final int STATUS_VS_ATTACKS_THRESHOLD = 2;
    private static final double STATUS_VS_ATTACKS_BOOST = 1.8;
    private static final int DAMAGING_VS_DEFENSES_THRESHOLD = 1;
    private static final double DAMAGING_VS_DEFENSES_BOOST = 1.6;
    private static final double LOW_HP_TARGET_THRESHOLD = 0.40;
    private static final double LOW_HP_TARGET_BOOST = 1.7;
    private static final double INUMAKI_LOW_HP_THRESHOLD = 0.30;
    private static final int HIGH_RECOIL = 30;
    private static final double RECOIL_CAUTION_FACTOR = 0.5;
    private static final double RETURN_VS_SUMMON_BOOST = 2.0;
    private static final double DODGE_PREFERENCE = 1.5;

    // -------------------------------------------------------------------------
    // Entries
    // -------------------------------------------------------------------------

    /** State-aware entry: places moves and stamps full multitarget CS targets. */
    public BattlePlan buildPlan(BattleState state, BattleCombatant ai, RandomSource rng) {
        BattleCombatant opponent = state.firstActiveEnemyOf(ai);
        BattlePlan plan = placeMoves(ai, opponent, rng);
        stampTargets(state.activeEnemiesOf(ai), plan, ai);
        return plan;
    }

    /** Single-opponent fallback (interface contract); stamps only that opponent. */
    @Override
    public BattlePlan selectPlan(BattleCombatant ai, BattleCombatant opponent, RandomSource rng) {
        BattlePlan plan = placeMoves(ai, opponent, rng);
        stampTargets(opponent == null ? List.of() : List.of(opponent), plan, ai);
        return plan;
    }

    // -------------------------------------------------------------------------
    // Placement (no targets yet)
    // -------------------------------------------------------------------------

    private BattlePlan placeMoves(BattleCombatant ai, BattleCombatant opponent, RandomSource rng) {
        int gridLength = Timeline.gridLengthForStrongestAp(
            Math.max(ai.getMaxApBar(), opponent == null ? 0 : opponent.getMaxApBar()));
        BattlePlan plan = new BattlePlan(ai.getMaxApBar(), ai.getCurrentCe(), gridLength);
        OpponentIntel intel = OpponentIntel.forOpponent(opponent);

        List<Move> commands = new ArrayList<>();
        List<Move> melee = new ArrayList<>();
        List<Move> defenses = new ArrayList<>();
        for (Move move : ai.getCharacter().getKnownMoves()) {
            if (!MoveAvailability.isAvailable(null, ai, move)) continue;
            if (CursedSpeechPlanning.isCursedSpeech(move)) {
                commands.add(move);
            } else if (move.hasTag("ATTACK")) {
                melee.add(move);
            } else if (move.isActiveDefense()) {
                defenses.add(move);
            }
        }

        Set<Move> stuck = new HashSet<>();
        int csPlaced = 0;
        Integer sleepFireTick = null;
        boolean nextAttackFar = false;

        // Opening Don't Move (instant, low-recoil interrupt).
        Move dontMove = commands.stream()
            .filter(m -> CursedSpeechAbility.DONT_MOVE.equalsIgnoreCase(CursedSpeechPlanning.commandMode(m)))
            .findFirst().orElse(null);
        if (dontMove != null && rng.nextDouble() < OPEN_DONT_MOVE_CHANCE
                && plan.canPlace(dontMove, ai.computeMoveCeCost(dontMove))) {
            ActionSegment seg = SmartAIScoring.placeAtOrAfter(
                plan, dontMove, ai.computeMoveCeCost(dontMove), 1);
            if (seg != null) {
                csPlaced++;
            } else {
                stuck.add(dontMove);
            }
        }

        // Cursed Speech loop (up to CS_MOVE_CAP total).
        while (csPlaced < CS_MOVE_CAP) {
            Move pick = pickCommand(commands, ai, opponent, plan, intel, stuck, rng);
            if (pick == null) break;
            int ceCost = ai.computeMoveCeCost(pick);
            ActionSegment seg = placeAttack(
                plan, pick, ceCost, gridLength, rng, true, nextAttackFar, sleepFireTick);
            if (seg == null) {
                stuck.add(pick);
            } else {
                csPlaced++;
                nextAttackFar = false;
                if (CursedSpeechAbility.SLEEP.equalsIgnoreCase(CursedSpeechPlanning.commandMode(pick))) {
                    sleepFireTick = seg.getFireTick();
                    nextAttackFar = true; // the next attack goes far to protect the sleep window
                }
            }
        }

        // Light melee spattering.
        Set<Move> meleeStuck = new HashSet<>();
        int meleePlaced = 0;
        while (meleePlaced < MELEE_CAP) {
            Move pick = pickMelee(melee, ai, plan, intel, meleeStuck, rng);
            if (pick == null) break;
            int ceCost = ai.computeMoveCeCost(pick);
            ActionSegment seg = placeAttack(
                plan, pick, ceCost, gridLength, rng, false, nextAttackFar, sleepFireTick);
            if (seg == null) {
                meleeStuck.add(pick);
            } else {
                meleePlaced++;
                nextAttackFar = false;
            }
        }

        placeDefenses(ai, plan, defenses, intel);
        return plan;
    }

    // -------------------------------------------------------------------------
    // Command selection
    // -------------------------------------------------------------------------

    private Move pickCommand(
        List<Move> commands, BattleCombatant ai, BattleCombatant opponent,
        BattlePlan plan, OpponentIntel intel, Set<Move> stuck, RandomSource rng
    ) {
        List<Move> pool = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (Move m : commands) {
            if (stuck.contains(m) || !plan.canPlace(m, ai.computeMoveCeCost(m))) continue;
            int cePost = Math.max(0, ai.getCurrentCe() - ai.computeMoveCeCost(m));
            int recoil = opponent == null ? 0
                : CursedSpeechPlanning.predictedRecoil(m, ai, opponent, cePost);
            if (recoil >= ai.getCurrentHp()) continue; // never pick a lethal-recoil command
            pool.add(m);
            weights.add(commandWeight(m, ai, opponent, intel, recoil));
        }
        return SmartAIScoring.weightedRandomPick(pool, weights, rng);
    }

    private static double commandWeight(
        Move move, BattleCombatant ai, BattleCombatant opponent, OpponentIntel intel, int recoilEstimate
    ) {
        double cheapness = CHEAPNESS_REF / (move.getApCost() + Math.max(1, recoilEstimate));
        double weight = cheapness;
        weight *= contextMultiplier(move, intel);
        weight *= lowHpTargetBoost(move, opponent);
        weight *= SmartAIScoring.effectMultiplier(move);
        weight *= SmartAIScoring.dodgeExposureMultiplier(move, intel);
        weight *= lowHpRecoilCaution(move, ai);
        weight *= returnVsSummonBoost(move, opponent);
        return weight;
    }

    private static double contextMultiplier(Move move, OpponentIntel intel) {
        double mult = 1.0;
        if (CursedSpeechPlanning.isStatusCommand(move)
            && intel.committedAttackFireTicks.size() >= STATUS_VS_ATTACKS_THRESHOLD) {
            mult *= STATUS_VS_ATTACKS_BOOST;
        }
        if (CursedSpeechPlanning.isDamagingCommand(move)
            && (intel.committedBlock + intel.committedParry) >= DAMAGING_VS_DEFENSES_THRESHOLD) {
            mult *= DAMAGING_VS_DEFENSES_BOOST;
        }
        return mult;
    }

    private static double lowHpTargetBoost(Move move, BattleCombatant opponent) {
        if (!CursedSpeechPlanning.isDamagingCommand(move) || opponent == null) return 1.0;
        double ratio = (double) opponent.getCurrentHp() / Math.max(1, opponent.getMaxHp());
        return ratio < LOW_HP_TARGET_THRESHOLD ? LOW_HP_TARGET_BOOST : 1.0;
    }

    private static double lowHpRecoilCaution(Move move, BattleCombatant ai) {
        double hpRatio = (double) ai.getCurrentHp() / Math.max(1, ai.getMaxHp());
        if (hpRatio < INUMAKI_LOW_HP_THRESHOLD && CursedSpeechPlanning.baseRecoilOf(move) >= HIGH_RECOIL) {
            return RECOIL_CAUTION_FACTOR;
        }
        return 1.0;
    }

    private static double returnVsSummonBoost(Move move, BattleCombatant opponent) {
        if (CursedSpeechAbility.RETURN.equalsIgnoreCase(CursedSpeechPlanning.commandMode(move))
            && opponent != null && opponent.isSummon()) {
            return RETURN_VS_SUMMON_BOOST;
        }
        return 1.0;
    }

    // -------------------------------------------------------------------------
    // Melee + defenses
    // -------------------------------------------------------------------------

    private Move pickMelee(
        List<Move> melee, BattleCombatant ai, BattlePlan plan,
        OpponentIntel intel, Set<Move> stuck, RandomSource rng
    ) {
        List<Move> pool = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (Move m : melee) {
            if (stuck.contains(m) || !plan.canPlace(m, ai.computeMoveCeCost(m))) continue;
            pool.add(m);
            double basePower = Math.max(1, m.getTotalBasePower());
            weights.add(basePower
                * SmartAIScoring.effectMultiplier(m)
                * SmartAIScoring.dodgeExposureMultiplier(m, intel));
        }
        return SmartAIScoring.weightedRandomPick(pool, weights, rng);
    }

    private void placeDefenses(BattleCombatant ai, BattlePlan plan, List<Move> defenses, OpponentIntel intel) {
        List<Move> useful = new ArrayList<>();
        for (Move d : defenses) {
            double value = SmartAIScoring.defenseValue(d, intel);
            if (d.isDodge()) value *= DODGE_PREFERENCE; // "likes to dodge or deflect"
            if (value > 0) useful.add(d);
        }
        useful.sort((a, b) -> Double.compare(
            (b.isDodge() ? DODGE_PREFERENCE : 1.0) * SmartAIScoring.defenseValue(b, intel),
            (a.isDodge() ? DODGE_PREFERENCE : 1.0) * SmartAIScoring.defenseValue(a, intel)));
        int placed = 0;
        for (Move d : useful) {
            if (placed >= DEFENSE_CAP) break;
            int ceCost = ai.computeMoveCeCost(d);
            if (!plan.canPlace(d, ceCost)) continue;
            ActionSegment seg = SmartAIScoring.placeAtOrAfter(plan, d, ceCost, 1);
            if (seg != null) placed++;
        }
    }

    // -------------------------------------------------------------------------
    // Placement helper
    // -------------------------------------------------------------------------

    /**
     * Place an attack. Commands pack near the start; melee scatters. When
     * {@code farForced} (the previous placed move was Sleep), place at whichever
     * end of the grid is farthest from the sleep's fire tick.
     */
    private ActionSegment placeAttack(
        BattlePlan plan, Move move, int ceCost, int gridLength, RandomSource rng,
        boolean packStart, boolean farForced, Integer sleepFireTick
    ) {
        if (farForced && sleepFireTick != null) {
            if (sleepFireTick <= gridLength / 2.0) {
                return SmartAIScoring.placeBunchedAtEnd(plan, move, ceCost, gridLength);
            }
            return SmartAIScoring.placeAtOrAfter(plan, move, ceCost, 1);
        }
        if (packStart) {
            return SmartAIScoring.placeAtOrAfter(plan, move, ceCost, 1);
        }
        return SmartAIScoring.placeAtFreeRandom(plan, move, ceCost, gridLength, rng);
    }

    // -------------------------------------------------------------------------
    // Multitarget stamping
    // -------------------------------------------------------------------------

    private void stampTargets(List<BattleCombatant> enemies, BattlePlan plan, BattleCombatant ai) {
        if (enemies.isEmpty()) return;
        int recoilBudget = (int) Math.round(MULTITARGET_RECOIL_FRACTION * ai.getCurrentHp());
        int cumulativeRecoil = 0;
        int cumulativeCsCeCost = 0;

        for (ActionSegment segment : new ArrayList<>(plan.allSegments())) {
            Move move = segment.getMove();
            if (!CursedSpeechPlanning.isCursedSpeech(move)) continue;

            int thisCeCost = ai.computeMoveCeCost(move);
            int userCePost = Math.max(0, ai.getCurrentCe() - cumulativeCsCeCost - thisCeCost);
            cumulativeCsCeCost += thisCeCost;

            List<BattleCombatant> eligible = new ArrayList<>();
            for (BattleCombatant e : enemies) {
                if (CursedSpeechAbility.canTarget(move, e)) eligible.add(e);
            }
            if (eligible.isEmpty()) {
                plan.remove(segment);
                continue;
            }

            boolean damaging = CursedSpeechPlanning.isDamagingCommand(move);
            eligible.sort((a, b) -> {
                int rankA = preferredTypeRank(a, damaging);
                int rankB = preferredTypeRank(b, damaging);
                if (rankA != rankB) return Integer.compare(rankA, rankB);
                return Integer.compare(
                    CursedSpeechPlanning.predictedRecoil(move, ai, a, userCePost),
                    CursedSpeechPlanning.predictedRecoil(move, ai, b, userCePost));
            });

            List<CombatantId> chosen = new ArrayList<>();
            // First target: lowest-recoil preferred target that won't kill him.
            int mandatory = -1;
            for (int i = 0; i < eligible.size(); i++) {
                int r = CursedSpeechPlanning.predictedRecoil(move, ai, eligible.get(i), userCePost);
                if (r < ai.getCurrentHp()) {
                    chosen.add(eligible.get(i).getInstanceId());
                    cumulativeRecoil += r;
                    mandatory = i;
                    break;
                }
            }
            if (mandatory == -1) {
                plan.remove(segment); // every target's recoil would be lethal
                continue;
            }
            // Extra (multitarget) picks, gated by the accumulated-recoil budget.
            for (int i = 0; i < eligible.size(); i++) {
                if (i == mandatory || chosen.size() >= move.getAoeTargetCount()) break;
                int r = CursedSpeechPlanning.predictedRecoil(move, ai, eligible.get(i), userCePost);
                if (cumulativeRecoil + r <= recoilBudget) {
                    chosen.add(eligible.get(i).getInstanceId());
                    cumulativeRecoil += r;
                }
            }
            segment.setTargets(chosen);
        }
    }

    /** Sorcerers rank first for damaging commands; shikigami first for status commands. */
    private static int preferredTypeRank(BattleCombatant target, boolean damaging) {
        boolean shikigami = target.getCharacter().getType() == CharacterType.SHIKIGAMI;
        if (damaging) return shikigami ? 1 : 0;  // prefer sorcerers
        return shikigami ? 0 : 1;                 // prefer shikigami
    }
}

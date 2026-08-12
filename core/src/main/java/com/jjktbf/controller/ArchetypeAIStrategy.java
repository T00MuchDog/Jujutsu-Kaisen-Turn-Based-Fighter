package com.jjktbf.controller;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.MoveAvailability;
import com.jjktbf.model.combat.RandomSource;
import com.jjktbf.model.combat.TeamBattlePlan;
import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Routes AI planning to a per-archetype strategy based on the combatant's
 * character definition.
 *
 * <p>AI type is a <b>code-only</b> mapping — it is never a data or editor field:
 * <ul>
 *   <li>Shikigami → {@link ShikigamiAIStrategy} (offense-first summon).</li>
 *   <li>Technique-less sorcerer → {@link AggressiveSorcererAIStrategy} or
 *       {@link PassiveSorcererAIStrategy}, decided by {@link #archetypeFor}.</li>
 *   <li>Cursed Speech sorcerer → {@link CursedSpeechAIStrategy} (state-aware
 *       multitarget planning; routed through {@link #selectTeamPlan}).</li>
 *   <li>Everyone else → {@link GreedyAIStrategy}.</li>
 * </ul>
 *
 * <p>This dispatcher owns {@link #selectTeamPlan} because Cursed Speech needs the
 * full enemy roster (state) for recoil-budgeted multitargeting, which the
 * single-opponent {@link AIStrategy#selectPlan} does not receive. Each combatant
 * is planned with its archetype, then uniformly pruned, normalised, and given
 * explicit targets.
 */
public class ArchetypeAIStrategy implements AIStrategy {

    private static final String CURSED_SPEECH = "Cursed Speech";

    private final GreedyAIStrategy sorcererStrategy = new GreedyAIStrategy();
    private final ShikigamiAIStrategy shikigamiStrategy = new ShikigamiAIStrategy();
    private final AggressiveSorcererAIStrategy aggressiveStrategy = new AggressiveSorcererAIStrategy();
    private final PassiveSorcererAIStrategy passiveStrategy = new PassiveSorcererAIStrategy();
    private final CursedSpeechAIStrategy cursedSpeechStrategy = new CursedSpeechAIStrategy();

    /** Hardcoded archetype assignment for the final technique-less sorcerer roster. */
    private static final Set<String> AGGRESSIVE_IDS = Set.of("000003", "000005"); // Yuji Itadori, Maki Zenin
    private static final Set<String> PASSIVE_IDS = Set.of("000002");              // Miwa Kasumi

    // -------------------------------------------------------------------------
    // Team plan (the real entry point) — owns pruning/normalisation/targeting
    // -------------------------------------------------------------------------

    @Override
    public TeamBattlePlan selectTeamPlan(
        BattleState state, List<BattleCombatant> aiTeam, RandomSource rng
    ) {
        int commonGridLength = TeamBattlePlan.gridLengthForRound(state);
        TeamBattlePlan teamPlan = new TeamBattlePlan(
            aiTeam.isEmpty() ? null : aiTeam.get(0).getTeamId(), commonGridLength);

        for (BattleCombatant ai : aiTeam) {
            BattlePlan plan = planFor(state, ai, rng);

            List<Move> alreadyPlanned = new ArrayList<>();
            for (ActionSegment segment : new ArrayList<>(plan.allSegments())) {
                if (MoveAvailability.restrictionReason(state, ai, segment.getMove(), alreadyPlanned) != null) {
                    plan.remove(segment);
                } else {
                    alreadyPlanned.add(segment.getMove());
                }
            }
            if (plan.gridLength() != commonGridLength) {
                plan = normalise(plan, commonGridLength);
            }
            plan = SmartAIScoring.promoteGuaranteedKillOpening(state, ai, plan, rng);
            alreadyPlanned.clear();
            for (ActionSegment segment : new ArrayList<>(plan.allSegments())) {
                if (MoveAvailability.restrictionReason(
                    state, ai, segment.getMove(), alreadyPlanned) != null) {
                    plan.remove(segment);
                } else {
                    alreadyPlanned.add(segment.getMove());
                }
            }
            assignExplicitTargets(state, plan, ai, rng);
            teamPlan.put(ai.getInstanceId(), plan);
        }
        return teamPlan;
    }

    /** Build one combatant's plan with the archetype that matches its definition. */
    private BattlePlan planFor(BattleState state, BattleCombatant ai, RandomSource rng) {
        BattleCombatant opponent = state.firstActiveEnemyOf(ai);
        Character character = ai == null ? null : ai.getCharacter();
        if (character == null) {
            return sorcererStrategy.selectPlan(ai, opponent, rng);
        }
        if (character.getType() == CharacterType.SHIKIGAMI) {
            return shikigamiStrategy.selectPlan(ai, opponent, rng);
        }
        if (character.getType() == CharacterType.SORCERER && !character.hasInnateTechnique()) {
            return archetypeFor(character).selectPlan(ai, opponent, rng);
        }
        if (CURSED_SPEECH.equalsIgnoreCase(character.getInnateTechniqueName())) {
            return cursedSpeechStrategy.buildPlan(state, ai, rng); // state-aware multitarget
        }
        return sorcererStrategy.selectPlan(ai, opponent, rng);
    }

    private static BattlePlan normalise(BattlePlan plan, int commonGridLength) {
        BattlePlan normalized = new BattlePlan(plan.apBudget(), plan.ceBudget(), commonGridLength);
        for (ActionSegment segment : plan.allSegments()) {
            ActionSegment ns = normalized.place(segment.getMove(), segment.getStartTick(), segment.getActualCeCost());
            if (ns == null) {
                throw new IllegalArgumentException("AI plan cannot be normalized to the battle grid");
            }
            ns.setTargets(segment.getTargets());
        }
        return normalized;
    }

    // -------------------------------------------------------------------------
    // Single-opponent router (interface contract / direct calls / tests)
    // -------------------------------------------------------------------------

    @Override
    public BattlePlan selectPlan(BattleCombatant ai, BattleCombatant opponent, RandomSource rng) {
        Character character = ai == null ? null : ai.getCharacter();
        if (character == null) {
            return sorcererStrategy.selectPlan(ai, opponent, rng);
        }
        if (character.getType() == CharacterType.SHIKIGAMI) {
            return shikigamiStrategy.selectPlan(ai, opponent, rng);
        }
        if (character.getType() == CharacterType.SORCERER && !character.hasInnateTechnique()) {
            return archetypeFor(character).selectPlan(ai, opponent, rng);
        }
        if (CURSED_SPEECH.equalsIgnoreCase(character.getInnateTechniqueName())) {
            // No state here: degrade to single-opponent CS planning.
            return cursedSpeechStrategy.selectPlan(ai, opponent, rng);
        }
        return sorcererStrategy.selectPlan(ai, opponent, rng);
    }

    /**
     * Pick the technique-less sorcerer archetype: the hardcoded id map decides
     * for the known roster; any unmapped technique-less sorcerer falls back to a
     * stat-derived pick (offense-leaning → Aggressive, defense-leaning → Passive).
     */
    private AIStrategy archetypeFor(Character character) {
        String id = character.getId();
        if (AGGRESSIVE_IDS.contains(id)) return aggressiveStrategy;
        if (PASSIVE_IDS.contains(id)) return passiveStrategy;
        return statLeansAggressive(character) ? aggressiveStrategy : passiveStrategy;
    }

    private static boolean statLeansAggressive(Character character) {
        CharacterStats s = character.getBaseStats();
        int offense = s.getStrength() + s.getCombatAbility() + s.getCursedEnergyOutput();
        int defense = s.getDurability() + s.getVitality() + s.getJujutsuSkill();
        return offense >= defense;
    }
}

package com.jjktbf.model.combat;

import com.jjktbf.model.character.coded.CursedSpeechAbility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A team's atomic round plan: one {@link BattlePlan} per active controlled
 * combatant, keyed by actor {@link CombatantId}.
 *
 * <p>Team submission is atomic — every living controlled combatant's page must be
 * locked before the round begins. This container holds those per-actor drafts
 * together so the controller/view can submit them as one unit and so a rejected
 * submission restores every page's state.
 *
 * <p>Draft state is keyed by combatant instance id, never by Java object identity
 * or character-definition id (the latter cannot distinguish duplicate summons).
 */
public final class TeamBattlePlan {

    private final BattleTeamId teamId;
    private final int gridLength;
    private final Map<CombatantId, BattlePlan> plansByActor = new LinkedHashMap<>();

    public TeamBattlePlan(BattleTeamId teamId, int gridLength) {
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.gridLength = gridLength;
    }

    public BattleTeamId teamId() {
        return teamId;
    }

    /** The common planning-grid length shared by every plan in this team plan. */
    public int gridLength() {
        return gridLength;
    }

    /** Associate (or replace) the plan for an actor. */
    public void put(CombatantId actor, BattlePlan plan) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(plan, "plan");
        plansByActor.put(actor, plan);
    }

    /** The plan for an actor, or {@code null} if none is drafted. */
    public BattlePlan get(CombatantId actor) {
        return actor == null ? null : plansByActor.get(actor);
    }

    public boolean has(CombatantId actor) {
        return actor != null && plansByActor.containsKey(actor);
    }

    /** Every actor id with a draft, in insertion order. */
    public List<CombatantId> actors() {
        return List.copyOf(plansByActor.keySet());
    }

    /** An unmodifiable view of actor → plan. */
    public Map<CombatantId, BattlePlan> plans() {
        return Collections.unmodifiableMap(plansByActor);
    }

    public int size() {
        return plansByActor.size();
    }

    public boolean isEmpty() {
        return plansByActor.isEmpty();
    }

    /**
     * If any actor's plan is missing a required single-target selection, return
     * a human-readable description; otherwise {@code null}. Used to reject atomic
     * team submission when one page is incomplete.
     */
    public String missingTargetError() {
        for (Map.Entry<CombatantId, BattlePlan> entry : plansByActor.entrySet()) {
            String error = entry.getValue().missingTargetError();
            if (error != null) {
                return error + " (combatant " + entry.getKey() + ")";
            }
        }
        return null;
    }

    /**
     * Validate this submission against the authoritative current battle state.
     * A valid team plan covers exactly every active member of its team, uses the
     * round's common grid, and contains only currently valid opposing targets.
     */
    public String validationError(BattleState state) {
        if (state == null) return "Battle state is required";
        BattleTeam team = state.teamOf(teamId);
        if (team == null) return "Unknown team " + teamId;

        int expectedGridLength = gridLengthForRound(state);
        if (gridLength != expectedGridLength) {
            return "Team plan grid length must be " + expectedGridLength;
        }

        List<BattleCombatant> active = team.active();
        if (plansByActor.size() != active.size()) {
            return "Team plan must include every active combatant exactly once";
        }
        for (BattleCombatant combatant : active) {
            if (!plansByActor.containsKey(combatant.getInstanceId())) {
                return "Missing plan for active combatant " + combatant.getInstanceId();
            }
        }

        for (Map.Entry<CombatantId, BattlePlan> entry : plansByActor.entrySet()) {
            BattleCombatant actor = state.combatant(entry.getKey());
            if (actor == null || !actor.isActive() || state.teamOf(actor) != team) {
                return "Plan actor " + entry.getKey() + " is not active on team " + teamId;
            }
            BattlePlan plan = entry.getValue();
            if (plan.gridLength() != gridLength) {
                return "Plan for " + entry.getKey() + " does not use the common grid";
            }
            List<com.jjktbf.model.move.Move> alreadyPlannedMoves = new ArrayList<>();
            for (ActionSegment segment : plan.allSegments()) {
                String restriction = MoveAvailability.restrictionReason(
                    state, actor, segment.getMove(), alreadyPlannedMoves);
                if (restriction != null) {
                    return "Move '" + segment.getMove().getName() + "' is restricted: "
                        + restriction;
                }
                alreadyPlannedMoves.add(segment.getMove());
                MoveTargeting targeting = MoveTargeting.forMove(segment.getMove());
                List<CombatantId> targetIds = segment.getTargets();
                int minimumTargets = targeting == MoveTargeting.SINGLE_ENEMY ? 1
                    : targeting == MoveTargeting.MULTIPLE_ENEMIES ? 1 : 0;
                int maximumTargets = targeting == MoveTargeting.SINGLE_ENEMY ? 1
                    : targeting == MoveTargeting.MULTIPLE_ENEMIES
                        ? segment.getMove().getAoeTargetCount() : 0;
                if (targetIds.size() < minimumTargets || targetIds.size() > maximumTargets) {
                    return "Move '" + segment.getMove().getName()
                        + "' has an invalid target count (combatant " + entry.getKey() + ")";
                }
                for (CombatantId targetId : targetIds) {
                    BattleCombatant target = state.combatant(targetId);
                    if (target == null || !target.isActive() || state.teamOf(target) == team
                        || !CursedSpeechAbility.canTarget(segment.getMove(), target)) {
                        return "Move '" + segment.getMove().getName()
                            + "' has an invalid target (combatant " + entry.getKey() + ")";
                    }
                }
            }
        }
        return null;
    }

    /**
     * Recompute the common planning-grid length for a round: the grid tier of the
     * maximum AP among all active combatants on both teams. Per the spec, the
     * grid length is recomputed each round from the max AP of all active
     * combatants on both teams (so a summon or KO can shrink or grow the grid).
     */
    public static int gridLengthForRound(BattleState state) {
        int maxAp = 0;
        for (BattleCombatant c : state.activeCombatants()) {
            maxAp = Math.max(maxAp, c.getMaxApBar());
        }
        return Timeline.gridLengthForStrongestAp(maxAp);
    }
}

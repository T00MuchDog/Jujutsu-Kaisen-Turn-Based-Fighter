package com.jjktbf.model.combat;

import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.StatusEffect;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared battle-time policy for whether an actor may currently select a move. */
public final class MoveAvailability {

    private MoveAvailability() { }

    public static boolean isAvailable(BattleState state, BattleCombatant actor, Move move) {
        return restrictionReason(state, actor, move) == null;
    }

    public static String restrictionReason(
        BattleState state,
        BattleCombatant actor,
        Move move
    ) {
        return restrictionReason(state, actor, move, List.of());
    }

    /**
     * As {@link #restrictionReason(BattleState, BattleCombatant, Move)}, with
     * summons already reserved by earlier placements in the actor's round plan.
     */
    public static String restrictionReason(
        BattleState state,
        BattleCombatant actor,
        Move move,
        List<Move> alreadyPlannedMoves
    ) {
        if (actor == null || move == null) return "A valid actor and move are required.";
        if (actor.getAbilityFlags().lockedMoveTags.stream().anyMatch(move::hasTag)) {
            return "Restricted by an active ability.";
        }
        if (state != null) {
            for (String definitionId : summonedDefinitionIds(move)) {
                String reason = state.summonRestrictionReason(actor, definitionId);
                if (reason != null) return reason;
            }
        }
        int occupiedSlots = state == null ? 0
            : state.directActiveSummonCount(actor) + state.directPendingSummonCount(actor);
        return plannedSummonRestrictionReason(
            move, alreadyPlannedMoves, actor.getAbilityFlags().maxActiveSummons, occupiedSlots);
    }

    /** Restriction contributed only by summons reserved in the current draft. */
    public static String plannedSummonRestrictionReason(
        Move move,
        List<Move> alreadyPlannedMoves,
        Integer maximumActiveSummons,
        int occupiedSlots
    ) {
        if (move == null || maximumActiveSummons == null) return null;
        Set<String> reservedDefinitions = new LinkedHashSet<>();
        if (alreadyPlannedMoves != null) {
            for (Move planned : alreadyPlannedMoves) {
                reservedDefinitions.addAll(summonedDefinitionIds(planned));
            }
        }
        Set<String> candidateDefinitions = new LinkedHashSet<>();
        for (String definitionId : summonedDefinitionIds(move)) {
            if (reservedDefinitions.contains(definitionId)
                || !candidateDefinitions.add(definitionId)) {
                return "This shikigami is already active or pending in this round's plan.";
            }
            if (occupiedSlots + reservedDefinitions.size() + candidateDefinitions.size()
                > maximumActiveSummons) {
                return "Maximum active summons reached.";
            }
        }
        return null;
    }

    /** Every shikigami definition this move may summon, in effect order. */
    public static List<String> summonedDefinitionIds(Move move) {
        List<String> ids = new ArrayList<>();
        if (move == null) return List.of();
        if (move.summonsCharacter()) ids.add(move.getSummonCharacterId());
        List<StatusEffect> effects = new ArrayList<>(move.getSelfEffects());
        effects.addAll(move.getOnHitEffects());
        effects.addAll(move.getOnBlockEffects());
        effects.addAll(move.getOnParryEffects());
        effects.addAll(move.getOnDodgeEffects());
        for (StatusEffect effect : effects) {
            if (effect != null && effect.isSummon()) ids.add(effect.getSummonCharacterId());
        }
        return List.copyOf(ids);
    }
}

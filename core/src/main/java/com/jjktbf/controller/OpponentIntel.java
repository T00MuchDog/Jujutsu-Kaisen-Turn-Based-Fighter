package com.jjktbf.controller;

import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only snapshot of what an opponent brings to a round — the "smart AI"
 * opponent model, shared by every archetype.
 *
 * <p>Computed once per plan from two sources:
 * <ul>
 *   <li>The opponent's <em>authored</em> move pool
 *       ({@code opponent.getCharacter().getKnownMoves()}) — what they <em>can</em>
 *       do. This drives the "knows the player's moves" rules: whether they can
 *       only use physical attacks, whether they own cursed-energy/reinforcement
 *       attacks, their highest attack potency, guard-break/intangible presence,
 *       and whether their blocks are physical-only.</li>
 *   <li>The opponent's <em>committed</em> timeline this round
 *       ({@code opponent.getTimeline()}) — what they <em>are</em> doing right
 *       now. This drives dodge/block counters and the fire-ticks of committed
 *       attacks (for defense placement).</li>
 * </ul>
 *
 * <p>All fields are package-private; this is an internal collaboration type for
 * the AI strategies and their tests.
 */
final class OpponentIntel {

    static final OpponentIntel EMPTY = new OpponentIntel(
        false, false, false, false, 0, false,
        0, 0, 0, 0, List.of(), List.of(), 0);

    // --- Authored attack profile (the opponent's known moves) ---
    /** The opponent's known attack moves (for block-coverage checks). */
    final List<Move> attacks;
    /** Any known attack carries cursed energy (reinforcement or pure CE). */
    final boolean hasCursedEnergy;
    /** Has attacks and none carry cursed energy (purely physical arsenal). */
    final boolean physicalOnly;
    final boolean hasGuardBreak;
    final boolean hasIntangible;
    final int maxAttackPotency;

    // --- Authored block profile ---
    /** Owns blocks, and none of them cover cursed energy (CE attacks slip through). */
    final boolean blocksPhysicalOnly;

    // --- Committed this round (the opponent's locked timeline) ---
    final int committedMeleeDodge;
    final int committedRangedDodge;
    final int committedBlock;
    final int committedParry;
    /** Fire-ticks of the opponent's committed attacks, ascending. */
    final List<Integer> committedAttackFireTicks;

    final int evasion;

    private OpponentIntel(
        boolean hasCursedEnergy, boolean physicalOnly,
        boolean hasGuardBreak, boolean hasIntangible, int maxAttackPotency,
        boolean blocksPhysicalOnly,
        int committedMeleeDodge, int committedRangedDodge,
        int committedBlock, int committedParry,
        List<Integer> committedAttackFireTicks,
        List<Move> attacks, int evasion
    ) {
        this.hasCursedEnergy = hasCursedEnergy;
        this.physicalOnly = physicalOnly;
        this.hasGuardBreak = hasGuardBreak;
        this.hasIntangible = hasIntangible;
        this.maxAttackPotency = maxAttackPotency;
        this.blocksPhysicalOnly = blocksPhysicalOnly;
        this.committedMeleeDodge = committedMeleeDodge;
        this.committedRangedDodge = committedRangedDodge;
        this.committedBlock = committedBlock;
        this.committedParry = committedParry;
        this.committedAttackFireTicks = committedAttackFireTicks;
        this.attacks = attacks;
        this.evasion = evasion;
    }

    static OpponentIntel forOpponent(BattleCombatant opponent) {
        if (opponent == null || opponent.getCharacter() == null) return EMPTY;

        List<Move> attacks = new ArrayList<>();
        boolean hasCe = false;
        boolean hasGuardBreak = false;
        boolean hasIntangible = false;
        int maxPotency = 0;
        for (Move move : opponent.getCharacter().getKnownMoves()) {
            if (!move.hasTag("ATTACK")) continue;
            attacks.add(move);
            if (move.hasTag("CURSED_ENERGY")) hasCe = true;
            if (move.isGuardBreak()) hasGuardBreak = true;
            if (move.isIntangible()) hasIntangible = true;
            maxPotency = Math.max(maxPotency, move.getPotency());
        }
        boolean physicalOnly = !attacks.isEmpty() && !hasCe;

        // Block profile: do any of the opponent's blocks cover cursed energy?
        boolean anyBlock = false;
        boolean anyBlockCoversCe = false;
        for (Move move : opponent.getCharacter().getKnownMoves()) {
            if (move.getDefenseType() != DefenseType.BLOCK) continue;
            anyBlock = true;
            if (SmartAIScoring.blockCoversCursedEnergy(move)) anyBlockCoversCe = true;
        }
        boolean blocksPhysicalOnly = anyBlock && !anyBlockCoversCe;

        // Committed timeline.
        int meleeDodge = 0;
        int rangedDodge = 0;
        int block = 0;
        int parry = 0;
        List<Integer> fireTicks = new ArrayList<>();
        Timeline timeline = opponent.getTimeline();
        if (timeline != null) {
            for (ActionSegment segment : timeline.getSegments()) {
                Move move = segment.getMove();
                DefenseType type = move.getDefenseType();
                if (type == DefenseType.DODGE) {
                    String scope = move.getDodgeScope();
                    if ("MELEE".equalsIgnoreCase(scope) || "BOTH".equalsIgnoreCase(scope)) meleeDodge++;
                    if ("RANGED".equalsIgnoreCase(scope) || "BOTH".equalsIgnoreCase(scope)) rangedDodge++;
                } else if (type == DefenseType.BLOCK) {
                    block++;
                } else if (type == DefenseType.PARRY) {
                    parry++;
                }
                if (move.hasTag("ATTACK")) {
                    fireTicks.add(segment.getFireTick());
                }
            }
        }
        Collections.sort(fireTicks);

        return new OpponentIntel(
            hasCe, physicalOnly, hasGuardBreak, hasIntangible, maxPotency,
            blocksPhysicalOnly,
            meleeDodge, rangedDodge, block, parry,
            fireTicks, attacks, opponent.getEvasion());
    }
}

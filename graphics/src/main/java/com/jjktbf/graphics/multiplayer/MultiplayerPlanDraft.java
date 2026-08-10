package com.jjktbf.graphics.multiplayer;

import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.move.AoeType;
import com.jjktbf.multiplayer.protocol.MoveState;
import com.jjktbf.multiplayer.protocol.PlanBoard;
import com.jjktbf.multiplayer.protocol.PlanPlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** LibGDX-independent preview of player intent for one authoritative round. */
public final class MultiplayerPlanDraft {
    public enum AddStatus {
        ADDED,
        INVALID_MOVE,
        MOVE_RESTRICTED,
        MOVE_CAP_REACHED,
        INSUFFICIENT_AP,
        INSUFFICIENT_CE,
        INVALID_TARGET_SELECTION,
        BOARD_FULL
    }

    public record DraftPlacement(
        MoveState move,
        int startTick,
        String actorId,
        List<String> targetIds
    ) {
        public DraftPlacement {
            Objects.requireNonNull(move, "move");
            targetIds = targetIds == null ? List.of() : List.copyOf(targetIds);
        }

        public DraftPlacement(MoveState move, int startTick) {
            this(move, startTick, null, List.of());
        }

        public int endTick() {
            return startTick + move.apCost() - 1;
        }

        public PlanPlacement toIntent() {
            return TargetListSupport.placement(move.moveId(), startTick, actorId, targetIds);
        }
    }

    public record AddResult(AddStatus status, DraftPlacement placement) {
        public boolean added() {
            return status == AddStatus.ADDED;
        }
    }

    private final List<DraftPlacement> placements = new ArrayList<>();
    private int roundNumber = -1;
    private int apBudget;
    private int ceBudget;
    private int apUsed;
    private int ceUsed;
    /**
     * Battle-wide timeline grid length (dot count) for the current round, from
     * the stronger fighter's AP tier. Bounds placement validation so the
     * client preview matches the server's authoritative tier length.
     */
    private int gridLength = BattlePlan.GRID_LENGTH;

    /** Clears intent only when a different authoritative round is observed. */
    public boolean beginRound(int roundNumber, int apBudget, int ceBudget) {
        return beginRound(roundNumber, apBudget, ceBudget, BattlePlan.GRID_LENGTH);
    }

    /** Overload carrying the battle-wide grid length (stronger fighter's tier). */
    public boolean beginRound(int roundNumber, int apBudget, int ceBudget, int gridLength) {
        if (roundNumber < 0 || apBudget < 0 || ceBudget < 0) {
            throw new IllegalArgumentException("Round and budgets must not be negative");
        }
        if (gridLength < 1) {
            throw new IllegalArgumentException("Grid length must be positive");
        }
        boolean changed = this.roundNumber != roundNumber;
        if (changed) {
            placements.clear();
            apUsed = 0;
            ceUsed = 0;
        }
        this.roundNumber = roundNumber;
        this.apBudget = apBudget;
        this.ceBudget = ceBudget;
        this.gridLength = gridLength;
        return changed;
    }

    /** Places the move at the first free range on its server-declared board. */
    public AddResult addFirstFit(MoveState move) {
        return addFirstFit(move, null, List.of());
    }

    /** Places target-aware intent while the server remains authoritative. */
    public AddResult addFirstFit(MoveState move, String actorId, List<String> targetIds) {
        if (!valid(move)) {
            return new AddResult(AddStatus.INVALID_MOVE, null);
        }
        if (!move.available()) {
            return new AddResult(AddStatus.MOVE_RESTRICTED, null);
        }
        if (!hasRemainingUses(move)) {
            return new AddResult(AddStatus.MOVE_CAP_REACHED, null);
        }
        if (apUsed + move.apCost() > apBudget) {
            return new AddResult(AddStatus.INSUFFICIENT_AP, null);
        }
        if (ceUsed + move.effectiveCeCost() > ceBudget) {
            return new AddResult(AddStatus.INSUFFICIENT_CE, null);
        }
        List<String> selectedTargets = distinctTargets(targetIds);
        if (!validTargetSelection(move, targetIds, selectedTargets)) {
            return new AddResult(AddStatus.INVALID_TARGET_SELECTION, null);
        }

        int lastStart = lastStartTick(move);
        for (int startTick = 1; startTick <= lastStart; startTick++) {
            int endTick = startTick + move.apCost() - 1;
            if (rangeFree(move.board(), startTick, endTick)) {
                DraftPlacement placement = new DraftPlacement(
                    move, startTick, actorId, selectedTargets);
                placements.add(placement);
                apUsed += move.apCost();
                ceUsed += move.effectiveCeCost();
                return new AddResult(AddStatus.ADDED, placement);
            }
        }
        return new AddResult(AddStatus.BOARD_FULL, null);
    }

    public boolean canAdd(MoveState move) {
        return canAdd(move, List.of());
    }

    public boolean canAdd(MoveState move, List<String> targetIds) {
        List<String> selectedTargets = distinctTargets(targetIds);
        if (!valid(move) || !move.available() || !hasRemainingUses(move)
            || apUsed + move.apCost() > apBudget
            || ceUsed + move.effectiveCeCost() > ceBudget
            || !validTargetSelection(move, targetIds, selectedTargets)) {
            return false;
        }
        int lastStart = lastStartTick(move);
        for (int startTick = 1; startTick <= lastStart; startTick++) {
            if (rangeFree(move.board(), startTick, startTick + move.apCost() - 1)) {
                return true;
            }
        }
        return false;
    }

    public boolean undo() {
        if (placements.isEmpty()) {
            return false;
        }
        DraftPlacement removed = placements.remove(placements.size() - 1);
        apUsed -= removed.move().apCost();
        ceUsed -= removed.move().effectiveCeCost();
        return true;
    }

    public void clear() {
        placements.clear();
        apUsed = 0;
        ceUsed = 0;
    }

    public List<DraftPlacement> placements() {
        return List.copyOf(placements);
    }

    public List<PlanPlacement> toIntent() {
        return placements.stream().map(DraftPlacement::toIntent).toList();
    }

    public int roundNumber() {
        return roundNumber;
    }

    public int apBudget() {
        return apBudget;
    }

    public int ceBudget() {
        return ceBudget;
    }

    public int apUsed() {
        return apUsed;
    }

    public int ceUsed() {
        return ceUsed;
    }

    public int remainingAp() {
        return apBudget - apUsed;
    }

    public int remainingCe() {
        return ceBudget - ceUsed;
    }

    private boolean hasRemainingUses(MoveState move) {
        if (move.moveCap() == 0) return true;
        long selected = placements.stream()
            .filter(placement -> move.moveId().equals(placement.move().moveId()))
            .count();
        return selected < move.moveCap();
    }

    private boolean rangeFree(PlanBoard board, int startTick, int endTick) {
        for (DraftPlacement placement : placements) {
            if (placement.move().board() == board
                && startTick <= placement.endTick()
                && endTick >= placement.startTick()) {
                return false;
            }
        }
        return true;
    }

    private static boolean valid(MoveState move) {
        return move != null
            && move.moveId() != null
            && !move.moveId().isBlank()
            && move.board() != null
            && move.apCost() >= 1
            && move.apCost() <= BattlePlan.GRID_LENGTH
            && move.unleashPoint() >= 1
            && move.unleashPoint() <= move.apCost()
            && move.hitComponents().stream().allMatch(component ->
                component.basePower() >= 0 && component.delayTicks() >= 0)
            && move.effectiveCeCost() >= 0;
    }

    private static boolean validTargetSelection(
        MoveState move,
        List<String> requestedTargets,
        List<String> selectedTargets
    ) {
        int requestedCount = requestedTargets == null ? 0 : requestedTargets.size();
        if (requestedCount != selectedTargets.size()) return false;
        boolean hostile = move.tags().contains("ATTACK");
        if (!hostile) return selectedTargets.isEmpty();
        AoeType aoeType = TargetListSupport.moveStateAoeType(move);
        if (aoeType != AoeType.MULTIPLE) {
            return aoeType != null || move.tags().contains("AOE")
                ? selectedTargets.isEmpty()
                : selectedTargets.size() == 1;
        }
        int cap = TargetListSupport.moveStateAoeTargetCount(move);
        return !selectedTargets.isEmpty() && selectedTargets.size() <= cap;
    }

    private static List<String> distinctTargets(List<String> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String targetId : targetIds) {
            if (targetId != null && !targetId.isBlank() && !result.contains(targetId)) {
                result.add(targetId);
            }
        }
        return List.copyOf(result);
    }

    /** Latest start tick at which {@code move} fits within this round's battle grid. */
    int lastStartTick(MoveState move) {
        long occupancyLastStart = (long) gridLength - move.apCost() + 1L;
        int maxDelay = move.hitComponents().stream()
            .mapToInt(component -> component.delayTicks())
            .max()
            .orElse(0);
        long impactLastStart = (long) gridLength - move.unleashPoint() + 1L
            - maxDelay;
        long lastStart = Math.min(occupancyLastStart, impactLastStart);
        return lastStart < 1L ? 0 : (int) lastStart;
    }
}

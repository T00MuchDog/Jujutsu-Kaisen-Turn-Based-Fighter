package com.jjktbf.graphics.multiplayer;

import com.jjktbf.model.combat.BattlePlan;
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
        INSUFFICIENT_AP,
        INSUFFICIENT_CE,
        BOARD_FULL
    }

    public record DraftPlacement(MoveState move, int startTick) {
        public DraftPlacement {
            Objects.requireNonNull(move, "move");
        }

        public int endTick() {
            return startTick + move.apCost() - 1;
        }

        public PlanPlacement toIntent() {
            return new PlanPlacement(move.moveId(), startTick);
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

    /** Clears intent only when a different authoritative round is observed. */
    public boolean beginRound(int roundNumber, int apBudget, int ceBudget) {
        if (roundNumber < 0 || apBudget < 0 || ceBudget < 0) {
            throw new IllegalArgumentException("Round and budgets must not be negative");
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
        return changed;
    }

    /** Places the move at the first free range on its server-declared board. */
    public AddResult addFirstFit(MoveState move) {
        if (!valid(move)) {
            return new AddResult(AddStatus.INVALID_MOVE, null);
        }
        if (!move.available()) {
            return new AddResult(AddStatus.MOVE_RESTRICTED, null);
        }
        if (apUsed + move.apCost() > apBudget) {
            return new AddResult(AddStatus.INSUFFICIENT_AP, null);
        }
        if (ceUsed + move.effectiveCeCost() > ceBudget) {
            return new AddResult(AddStatus.INSUFFICIENT_CE, null);
        }

        int lastStart = BattlePlan.GRID_LENGTH - move.apCost() + 1;
        for (int startTick = 1; startTick <= lastStart; startTick++) {
            int endTick = startTick + move.apCost() - 1;
            if (rangeFree(move.board(), startTick, endTick)) {
                DraftPlacement placement = new DraftPlacement(move, startTick);
                placements.add(placement);
                apUsed += move.apCost();
                ceUsed += move.effectiveCeCost();
                return new AddResult(AddStatus.ADDED, placement);
            }
        }
        return new AddResult(AddStatus.BOARD_FULL, null);
    }

    public boolean canAdd(MoveState move) {
        if (!valid(move) || !move.available()
            || apUsed + move.apCost() > apBudget
            || ceUsed + move.effectiveCeCost() > ceBudget) {
            return false;
        }
        int lastStart = BattlePlan.GRID_LENGTH - move.apCost() + 1;
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
            && move.effectiveCeCost() >= 0;
    }
}

package com.jjktbf.controller;

import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.CeEfficiencyCalculator;
import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple greedy AI: fills the AP bar with the highest-base-power moves
 * the AI can currently afford (AP and CE).
 *
 * This is the default strategy used when no other AIStrategy is provided.
 * It is intentionally straightforward — replace or extend it when more
 * sophisticated AI behaviour is needed.
 */
public class GreedyAIStrategy implements AIStrategy {

    @Override
    public List<Move> selectMoves(BattleCombatant ai, BattleCombatant opponent) {
        List<Move> selected  = new ArrayList<>();
        int        remainAp  = ai.getMaxApBar();
        int        currentCe = ai.getCurrentCe();

        // Sort moves by base power descending, then pick greedily
        List<Move> sorted = new ArrayList<>(ai.getCharacter().getKnownMoves());
        sorted.sort((a, b) -> b.getBasePower() - a.getBasePower());

        for (Move move : sorted) {
            if (ai.getAbilityFlags().lockedMoveTags.stream().anyMatch(move::hasTag)) continue;
            if (move.getApCost() > remainAp) continue;

            int ceCost = CeEfficiencyCalculator.computeActualCost(
                move, ai.getEffectiveStats().getCursedEnergyEfficiency(), ai.getAbilityFlags()
            );
            if (ceCost > currentCe) continue;

            selected.add(move);
            remainAp  -= move.getApCost();
            currentCe -= ceCost;

            if (remainAp <= 0) break;
        }

        return selected;
    }
}

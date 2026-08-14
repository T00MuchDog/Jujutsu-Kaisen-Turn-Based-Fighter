package com.jjktbf.controller;

import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.character.coded.CursedSpeechAbility;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveEffectData;

import java.util.Map;

/**
 * Planning-time model of Cursed Speech, used by {@link CursedSpeechAIStrategy}.
 *
 * <p>The runtime recoil math lives inside {@link CursedSpeechAbility} and needs a
 * live battle state + fired hit component, so there is no public predictor. This
 * helper replicates the pure formula so the AI can forecast a command's recoil
 * <em>before</em> committing to it (the "won't use a move if the recoil will kill
 * him" rule and the multitarget recoil cap), and classifies commands.
 *
 * <p>Recoil (per target, matching runtime):
 * <pre>
 *   baseRecoil       = the move's authored base recoil (codedParameters["baseRecoil"])
 *   userReinforcedCe = min(userCePostCost, scale(user.ceOutput)  × 0.5)
 *   targetReinforcedCe = min(target.currentCe, scale(target.ceOutput) × 0.5)
 *   recoilPerTarget  = round(baseRecoil × targetReinforcedCe / max(1, userReinforcedCe))
 * </pre>
 * The user's CE is taken <em>after</em> paying the move's cost ({@code userCePostCost}),
 * because the cost is drained before the recoil check at runtime. Across several
 * commands in a round the AI passes a CE value that already accounts for earlier
 * commands' costs (an approximation of fire-time CE ordering, which is timeline
 * dependent). Recoil is applied even if the command is resisted, so it is always
 * counted.
 */
final class CursedSpeechPlanning {

    private CursedSpeechPlanning() { }

    /** The command mode of a move (e.g. DONT_MOVE), or null if it isn't Cursed Speech. */
    static String commandMode(Move move) {
        return CursedSpeechAbility.commandMode(move);
    }

    /** True if this move is a Cursed Speech command. */
    static boolean isCursedSpeech(Move move) {
        return commandMode(move) != null;
    }

    /** A Cursed Speech command with no base power — Don't Move, Sleep, Return, Die. */
    static boolean isStatusCommand(Move move) {
        return isCursedSpeech(move) && move.getTotalBasePower() == 0;
    }

    /** A Cursed Speech command that deals damage — Blast Away, Plummet, Get Twisted, Explode. */
    static boolean isDamagingCommand(Move move) {
        return isCursedSpeech(move) && move.getTotalBasePower() > 0;
    }

    /** The move's authored base recoil (0 when unset, e.g. Return). */
    static int baseRecoilOf(Move move) {
        if (move == null) return 0;
        for (MoveEffectData effect : move.getEffects()) {
            if (CursedSpeechAbility.KEY.equalsIgnoreCase(effect.codedAbilityKey)
                && CursedSpeechAbility.COMMAND.equalsIgnoreCase(effect.codedAction)) {
                Map<String, Integer> params = effect.codedParameters;
                if (params == null) return 0;
                Integer recoil = params.get(CursedSpeechAbility.BASE_RECOIL);
                return recoil == null ? 0 : recoil;
            }
        }
        return 0;
    }

    /**
     * Predicted recoil damage the user takes from commanding a single target,
     * replicating the runtime CE-ratio scaling.
     *
     * @param userCePostCost the user's CE after paying this move's cost (and,
     *                       across a round, earlier commands' costs)
     */
    static int predictedRecoil(Move move, BattleCombatant user, BattleCombatant target, int userCePostCost) {
        int baseRecoil = baseRecoilOf(move);
        if (baseRecoil <= 0 || target == null) return 0;
        double userReinforcedCe = Math.min(
            Math.max(0, userCePostCost),
            CombatStats.computeCeReinforcementCap(
                user.getEffectiveStats(), user.getStatMode()));
        double targetReinforcedCe = Math.min(
            target.getCurrentCe(),
            CombatStats.computeCeReinforcementCap(
                target.getEffectiveStats(), target.getStatMode()));
        return (int) Math.round(baseRecoil * targetReinforcedCe / Math.max(1.0, userReinforcedCe));
    }
}

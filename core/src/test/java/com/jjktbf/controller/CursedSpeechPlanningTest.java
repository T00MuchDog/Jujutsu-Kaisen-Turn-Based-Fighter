package com.jjktbf.controller;

import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleStatMode;
import com.jjktbf.model.character.coded.CursedSpeechAbility;
import com.jjktbf.model.move.Move;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the Cursed Speech planning helper (recoil prediction + classification). */
class CursedSpeechPlanningTest {

    private final List<Move> canonical = loadMoves();

    @Test
    void readsBaseRecoilFromAuthoredCommands() {
        assertEquals(12, CursedSpeechPlanning.baseRecoilOf(move("000072"))); // Plummet
        assertEquals(6, CursedSpeechPlanning.baseRecoilOf(move("000069")));  // Don't Move
        assertEquals(0, CursedSpeechPlanning.baseRecoilOf(move("000074")));  // Return (free)
    }

    @Test
    void classifiesStatusVersusDamagingCommands() {
        assertTrue(CursedSpeechPlanning.isDamagingCommand(move("000072")));   // Plummet (power 65)
        assertFalse(CursedSpeechPlanning.isStatusCommand(move("000072")));
        assertTrue(CursedSpeechPlanning.isStatusCommand(move("000069")));    // Don't Move (power 0)
        assertTrue(CursedSpeechPlanning.isStatusCommand(move("000074")));    // Return
        assertEquals(CursedSpeechAbility.PLUMMET, CursedSpeechPlanning.commandMode(move("000072")));
    }

    @Test
    void returnCommandPredictsZeroRecoil() {
        BattleCombatant user = AIFixtures.cursedSpeechSorcerer("u", move("000072"));
        BattleCombatant target = AIFixtures.lowCeSorcererEnemy("t");
        assertEquals(0, CursedSpeechPlanning.predictedRecoil(
            move("000074"), user, target, user.getCurrentCe()));
    }

    @Test
    void recoilScalesWithTargetReinforcedCe() {
        Move plummet = move("000072");
        BattleCombatant user = AIFixtures.cursedSpeechSorcerer("u", plummet);
        BattleCombatant lowCeTarget = AIFixtures.lowCeSorcererEnemy("low");
        BattleCombatant highCeTarget = AIFixtures.cursedSpeechSorcerer("hi", plummet);
        int userCePost = user.getCurrentCe();

        int low = CursedSpeechPlanning.predictedRecoil(plummet, user, lowCeTarget, userCePost);
        int high = CursedSpeechPlanning.predictedRecoil(plummet, user, highCeTarget, userCePost);

        assertTrue(low > 0, "Plummet still recoils against a low-CE target");
        assertTrue(high > low, "a higher-CE target inflicts more recoil");
    }

    @Test
    void recoilGrowsAsUserCursedEnergyDrops() {
        Move plummet = move("000072");
        BattleCombatant user = AIFixtures.cursedSpeechSorcerer("u", plummet);
        BattleCombatant target = AIFixtures.lowCeSorcererEnemy("t");

        int freshCe = user.getCurrentCe();
        int drainedCe = Math.max(1, freshCe / 4);
        int withFullCe = CursedSpeechPlanning.predictedRecoil(plummet, user, target, freshCe);
        int withLowCe = CursedSpeechPlanning.predictedRecoil(plummet, user, target, drainedCe);

        assertTrue(withLowCe >= withFullCe, "less user CE => at least as much recoil");
    }

    @Test
    void recoilUsesEqualizedOutputForBothFighters() {
        Move plummet = move("000072");
        BattleCombatant authoredUser = AIFixtures.cursedSpeechSorcerer("u", plummet);
        BattleCombatant authoredTarget = AIFixtures.lowCeSorcererEnemy("t");
        BattleCombatant standardUser = new BattleCombatant(
            authoredUser.getCharacter(), List.of(), BattleStatMode.STANDARD);
        BattleCombatant standardTarget = new BattleCombatant(
            authoredTarget.getCharacter(), List.of(), BattleStatMode.STANDARD);
        BattleCombatant equalizedUser = new BattleCombatant(
            authoredUser.getCharacter(), List.of(), BattleStatMode.EQUALIZED);
        BattleCombatant equalizedTarget = new BattleCombatant(
            authoredTarget.getCharacter(), List.of(), BattleStatMode.EQUALIZED);

        assertEquals(1, CursedSpeechPlanning.predictedRecoil(
            plummet, standardUser, standardTarget, standardUser.getCurrentCe()));
        assertEquals(4, CursedSpeechPlanning.predictedRecoil(
            plummet, equalizedUser, equalizedTarget, equalizedUser.getCurrentCe()));
    }

    private Move move(String id) {
        return AIFixtures.canonicalMoveById(canonical, id);
    }

    private static List<Move> loadMoves() {
        try {
            return AIFixtures.loadCanonicalMoves();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not load canonical moves", e);
        }
    }
}

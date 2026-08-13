package com.jjktbf;

import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.combat.BattleState;
import com.jjktbf.model.combat.CombatEvent;
import com.jjktbf.model.combat.CombatResolver;
import com.jjktbf.model.combat.Timeline;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Landing a Black Flash grants the wielder a timed Cursed Energy Output buff
 * (+{@link CombatStats#BF_CE_OUTPUT_BUFF_FRACTION}, i.e. an effective ×1.2) for
 * {@link CombatStats#BF_CE_OUTPUT_BUFF_TICKS} AP ticks. Re-procuring it refreshes
 * the timer rather than stacking.
 */
class BlackFlashCeOutputBuffTest {

    private static final int BASELINE = CharacterStats.BASELINE; // 80

    @Test
    void buffDescriptorRaisesCursedEnergyOutputToOnePointTwo() {
        BattleCombatant attacker = combatant("ATTACKER");
        assertEquals(BASELINE, attacker.getEffectiveStats().getCursedEnergyOutput());

        attacker.addRuntimeAbilityEffect(buff(), 0, BattleState.Phase.PLANNING,
            CombatStats.BF_CE_OUTPUT_BUFF_REFRESH_GROUP);

        assertEquals(Math.round(BASELINE * 1.20),
            attacker.getEffectiveStats().getCursedEnergyOutput());
    }

    @Test
    void reapplyWithTheRefreshGroupDoesNotStack() {
        BattleCombatant attacker = combatant("ATTACKER");
        attacker.addRuntimeAbilityEffect(buff(), 0, BattleState.Phase.PLANNING,
            CombatStats.BF_CE_OUTPUT_BUFF_REFRESH_GROUP);
        attacker.addRuntimeAbilityEffect(buff(), 0, BattleState.Phase.PLANNING,
            CombatStats.BF_CE_OUTPUT_BUFF_REFRESH_GROUP);

        // Two +20% modifiers would combine to ×1.4 (112) if they stacked; the
        // refresh group keeps a single instance, so it stays ×1.2 (96).
        assertEquals(Math.round(BASELINE * 1.20),
            attacker.getEffectiveStats().getCursedEnergyOutput());
    }

    @Test
    void reapplyResetsTheCountdown() {
        BattleCombatant attacker = combatant("ATTACKER");
        attacker.addRuntimeAbilityEffect(buff(), 0, BattleState.Phase.PLANNING,
            CombatStats.BF_CE_OUTPUT_BUFF_REFRESH_GROUP);
        for (int i = 0; i < 10; i++) attacker.tickTimelineEffects();

        assertEquals(CombatStats.BF_CE_OUTPUT_BUFF_TICKS - 10,
            attacker.getRemainingTimelineEffectTicks());

        // A second application refreshes the timer back to full instead of stacking.
        attacker.addRuntimeAbilityEffect(buff(), 0, BattleState.Phase.PLANNING,
            CombatStats.BF_CE_OUTPUT_BUFF_REFRESH_GROUP);

        assertEquals(CombatStats.BF_CE_OUTPUT_BUFF_TICKS,
            attacker.getRemainingTimelineEffectTicks());
        assertEquals(Math.round(BASELINE * 1.20),
            attacker.getEffectiveStats().getCursedEnergyOutput());
    }

    @Test
    void tickOnlyBuffPersistsAcrossRoundBoundariesAndExpiresAfterItsTicks() {
        BattleCombatant attacker = combatant("ATTACKER");
        attacker.addRuntimeAbilityEffect(buff(), 0, BattleState.Phase.PLANNING,
            CombatStats.BF_CE_OUTPUT_BUFF_REFRESH_GROUP);

        // Round-end only advances round-duration effects; a tick-only buff is untouched.
        attacker.tickRoundEffects(0);
        assertEquals(CombatStats.BF_CE_OUTPUT_BUFF_TICKS,
            attacker.getRemainingTimelineEffectTicks());
        assertEquals(Math.round(BASELINE * 1.20),
            attacker.getEffectiveStats().getCursedEnergyOutput());

        // It expires after exactly its AP-tick count, not rounds.
        for (int i = 0; i < CombatStats.BF_CE_OUTPUT_BUFF_TICKS; i++) {
            attacker.tickTimelineEffects();
        }
        assertEquals(0, attacker.getRemainingTimelineEffectTicks());
        assertEquals(BASELINE, attacker.getEffectiveStats().getCursedEnergyOutput());
    }

    @Test
    void landingABlackFlashThroughTheResolverAppliesTheBuffToTheAttacker() {
        Move finisher = new Move.Builder("FINISHER")
            .name("Finisher")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .basePower(10)
            .neverMiss(true)
            .apCost(1)
            .unleashPoint(1)
            .build();
        BattleCombatant attacker = combatant("ATTACKER", List.of(finisher));
        BattleCombatant defender = combatant("DEFENDER");
        Timeline timeline = new Timeline(1);
        timeline.placeAt(finisher, 1, 0);
        attacker.setTimeline(timeline);
        defender.setTimeline(new Timeline(1));
        BattleState state = new BattleState(attacker, defender);
        state.transitionTo(BattleState.Phase.RESOLUTION);

        List<CombatEvent> events = new CombatResolver(new FixedRandom()).resolveRound(state);

        assertTrue(events.stream().anyMatch(event -> event.getType() == CombatEvent.Type.BLACK_FLASH));
        assertEquals(Math.round(BASELINE * 1.20),
            attacker.getEffectiveStats().getCursedEnergyOutput());
        assertTrue(attacker.getRemainingTimelineEffectTicks() > 0);
    }

    private static AbilityEffectData buff() {
        return AbilityEffectData.tempStatPercent(
            StatKey.CURSED_ENERGY_OUTPUT.fieldName,
            CombatStats.BF_CE_OUTPUT_BUFF_FRACTION,
            0, CombatStats.BF_CE_OUTPUT_BUFF_TICKS);
    }

    private static BattleCombatant combatant(String id) {
        return combatant(id, List.of());
    }

    private static BattleCombatant combatant(String id, List<Move> moves) {
        Character character = new SorcererCharacter(
            id, id, new CharacterStats.Builder().build(), null, moves, List.of());
        return new BattleCombatant(character);
    }

    /** nextDouble() == 0.0 forces the Black Flash roll to succeed. */
    private static final class FixedRandom extends Random {
        @Override public double nextDouble() { return 0.0; }
        @Override public boolean nextBoolean() { return true; }
    }
}

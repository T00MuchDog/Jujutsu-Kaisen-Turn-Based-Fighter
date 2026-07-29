package com.jjktbf;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityApplicator;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.StatusEffect;
import com.jjktbf.model.move.StatusEffectType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoisonSoulTemplateTest {

    @Test
    void poisonTemplateUsesRoundDurationAndDamageMagnitude() {
        StatusEffect poison = StatusEffect.poison(2, 7.5);

        assertEquals(StatusEffectType.POISON, poison.getType());
        assertEquals(2, poison.getDurationRounds());
        assertEquals(0, poison.getDurationTicks());
        assertEquals(7.5, poison.getMagnitude());
        assertTrue(StatusEffectType.POISON.usesMagnitude());
        assertTrue(StatusEffectType.POISON.requiresRoundDuration());
        assertThrows(IllegalArgumentException.class,
            () -> new StatusEffect(StatusEffectType.POISON, 0, 2, 5.0));
    }

    @Test
    void soulMarkerRoundTripsWithoutChangingCategoryOrBlackFlash() {
        MoveData data = new MoveData();
        data.id = "SOUL_MOVE";
        data.name = "Soul Move";
        data.tags = List.of("PHYSICAL", "ATTACK", "MELEE", "SOUL");
        data.basePower = 20;
        data.apCost = 5;
        data.unleashPoint = 1;

        Move move = data.toMove();
        MoveData restored = MoveData.fromMove(move);

        assertEquals(MoveCategory.PHYSICAL, move.getCategory());
        assertTrue(move.hasTag("SOUL"));
        assertFalse(move.isBlackFlashEligible());
        assertTrue(restored.tags.contains("SOUL"));
    }

    @Test
    void poisonAndSoulAbilityTemplatesProduceRuntimeMarkers() {
        AbilityData data = new AbilityData();
        data.id = "MARKERS";
        data.name = "Markers";
        data.category = "PASSIVE";
        data.sourceType = "CHARACTER";
        data.effects = List.of(
            AbilityEffectType.POISON_IMMUNITY.createDefault(),
            AbilityEffectType.SOUL_AWARE_ATTACKS.createDefault());

        AbilityApplicator.ApplicationResult applied = AbilityApplicator.apply(
            new CharacterStats.Builder().build(), List.of(new Ability(data)));

        assertTrue(applied.flags.poisonImmune);
        assertTrue(applied.flags.soulAwareAttacks);

        BattleCombatant combatant = new BattleCombatant(new SorcererCharacter(
            "MARKED", "Marked", new CharacterStats.Builder().build(),
            null, List.of(), List.of(new Ability(data))));
        assertFalse(combatant.addStatusEffect(StatusEffect.poison(2, 5.0)));
        assertFalse(combatant.hasEffect(StatusEffectType.POISON));
        assertTrue(combatant.hasSoulAwareAttacks());
    }
}

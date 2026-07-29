package com.jjktbf;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityApplicator;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityResolver;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.StatKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatAllocationMinimumTest {

    @Test
    void allocationMinimumsUseTheHighestAssignedPassiveFloor() {
        AbilityData floor = ability("FLOOR", List.of(
            AbilityEffectData.statAllocationMinimum("strength", 60),
            AbilityEffectData.statAllocationMinimum("strength", 70),
            AbilityEffectData.statAllocationMinimum("speed", 60)));
        CharacterData character = new CharacterData();
        character.abilityIds = List.of("FLOOR");
        character.moveIds = List.of();

        AbilityResolver.Result resolved = AbilityResolver.resolve(character, List.of(floor));

        assertEquals(70, resolved.statAllocationMinimum(StatKey.STRENGTH));
        assertEquals(60, resolved.statAllocationMinimum(StatKey.SPEED));
        assertEquals(CharacterStats.MIN_STAT,
            resolved.statAllocationMinimum(StatKey.JUJUTSU_SKILL));
    }

    @Test
    void allocationFloorValidatesRawStatsButDoesNotChangeCombatStats() {
        AbilityData floor = ability("FLOOR", List.of(
            AbilityEffectData.statAllocationMinimum("strength", 60)));
        CharacterData character = new CharacterData();
        character.strength = 40;
        character.abilityIds = List.of("FLOOR");
        character.moveIds = List.of();
        AbilityResolver.Result resolved = AbilityResolver.resolve(character, List.of(floor));

        assertThrows(IllegalArgumentException.class,
            () -> character.validateStatAllocationMinimums(resolved));
        character.strength = 60;
        assertDoesNotThrow(() -> character.validateStatAllocationMinimums(resolved));

        CharacterStats base = new CharacterStats.Builder().strength(40).build();
        AbilityApplicator.ApplicationResult applied = AbilityApplicator.apply(
            base, List.of(new Ability(floor)));
        assertEquals(40, applied.modifiedStats.getStrength());
    }

    @Test
    void allocationAndMarkerEffectsArePassiveTemplatesWithValidDefaults() {
        AbilityEffectData minimum = AbilityEffectType.STAT_ALLOCATION_MINIMUM.createDefault();

        assertNull(AbilityEffectType.STAT_ALLOCATION_MINIMUM.validationError(minimum));
        assertTrue(AbilityEffectType.STAT_ALLOCATION_MINIMUM.isPassiveOnly());
        assertFalse(AbilityEffectType.STAT_ALLOCATION_MINIMUM.requiresActivation());
        assertTrue(AbilityEffectType.POISON_IMMUNITY.isPassiveOnly());
        assertTrue(AbilityEffectType.SOUL_AWARE_ATTACKS.isPassiveOnly());
    }

    private static AbilityData ability(String id, List<AbilityEffectData> effects) {
        AbilityData ability = new AbilityData();
        ability.id = id;
        ability.name = id;
        ability.category = "PASSIVE";
        ability.sourceType = "CHARACTER";
        ability.effects = effects;
        return ability;
    }
}

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
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
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

    @Test
    void allocationMaximumsUseLowestCeilingAndValidateRawStats() {
        AbilityData cap = ability("CAP", List.of(
            AbilityEffectData.statAllocationMaximum("jujutsuSkill", 20),
            AbilityEffectData.statAllocationMaximum("jujutsuSkill", 10)));
        CharacterData character = new CharacterData();
        character.abilityIds = List.of("CAP");
        character.moveIds = List.of();
        AbilityResolver.Result resolved = AbilityResolver.resolve(character, List.of(cap));

        assertEquals(10, resolved.statAllocationMaximum(StatKey.JUJUTSU_SKILL));
        character.jujutsuSkill = 20;
        assertThrows(IllegalArgumentException.class,
            () -> character.validateStatAllocationMaximums(resolved));
        character.jujutsuSkill = 10;
        assertDoesNotThrow(() -> character.validateStatAllocationMaximums(resolved));
    }

    @Test
    void passiveCanRemoveJujutsuSlotsAndReplaceDefenseFormula() {
        AbilityEffectData slots = new AbilityEffectData();
        slots.type = AbilityEffectType.SET_JUJUTSU_ART_SLOTS.name();
        slots.intValue = 0;
        AbilityEffectData defense = new AbilityEffectData();
        defense.type = AbilityEffectType.DEFENSE_FROM_DURABILITY.name();
        defense.doubleValue = 4.0 / 3.0;
        AbilityData restriction = ability("RESTRICTION", List.of(slots, defense));
        CharacterStats stats = new CharacterStats.Builder().durability(84).build();
        SorcererCharacter character = new SorcererCharacter(
            "MAKI", "Maki", stats, null, List.of(), List.of(new Ability(restriction)));
        BattleCombatant combatant = new BattleCombatant(character);

        assertEquals(0, character.getCombatStats().getJujutsuArtsSlots());
        assertEquals(0, combatant.getEffectiveCombatStats().getJujutsuArtsSlots());
        assertEquals(108, combatant.computeCurrentDefense(1));
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

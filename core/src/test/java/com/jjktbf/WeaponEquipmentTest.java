package com.jjktbf;

import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.Equipment;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.weapon.CursedToolData;
import com.jjktbf.model.weapon.WeaponType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Weapon equipment mechanics: base weapons and cursed tools gate weapon-tagged
 * moves, cursed tools make their weapon type's moves cost no cursed energy, and
 * tool-granted content bypasses learning requirements while equipped.
 */
class WeaponEquipmentTest {

    private static Move katanaCeMove() {
        return new Move.Builder("KATANA_CE")
            .name("Cursed Sword Slash")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.CURSED_ENERGY, MoveTag.ATTACK, MoveTag.KATANA))
            .apCost(1).unleashPoint(1)
            .baseCeCost(20).hasCeCost(true).minCeCost(4).maxCeCost(85)
            .build();
    }

    @Test
    void cursedToolMakesWeaponMovesFreeButBaseWeaponDoesNot() {
        CharacterStats stats = new CharacterStats.Builder().build();
        Move move = katanaCeMove();
        Character toolUser = new SorcererCharacter("TOOL", "Tool User", stats, null,
            List.of(move), List.of(), Equipment.cursedTool(WeaponType.KATANA));
        Character baseUser = new SorcererCharacter("BASE", "Base User", stats, null,
            List.of(move), List.of(), Equipment.base(WeaponType.KATANA));

        assertEquals(0, new BattleCombatant(toolUser).computeMoveCeCost(move),
            "A cursed tool channels its own CE: its weapon type's moves are free.");
        assertEquals(20, new BattleCombatant(baseUser).computeMoveCeCost(move),
            "A base weapon does not waive the CE cost.");
    }

    @Test
    void cursedToolOnlyFreesItsOwnWeaponType() {
        CharacterStats stats = new CharacterStats.Builder().build();
        Move katanaMove = katanaCeMove();
        // A bow cursed tool equips the bow type, but the katana move still costs CE.
        Character bowUser = new SorcererCharacter("BOW", "Bow User", stats, null,
            List.of(), List.of(), Equipment.cursedTool(WeaponType.BOW));

        assertEquals(20, new BattleCombatant(bowUser).computeMoveCeCost(katanaMove));
    }

    @Test
    void toolGrantedMoveBypassesPrerequisitesAndWeaponGate() {
        CursedToolData tool = new CursedToolData();
        tool.id = "000000";
        tool.name = "Test Katana";
        tool.weaponType = "KATANA";
        tool.grantedMoveIds = List.of("HARD_MOVE");
        Move hardMove = new Move.Builder("HARD_MOVE")
            .name("Hard Move")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.KATANA))
            .prerequisites(Map.of("strength", 300))
            .basePower(10).apCost(5).unleashPoint(1)
            .build();
        Equipment equipment = Equipment.resolve(
            List.of(), List.of(tool.id), List.of(tool),
            moveId -> hardMove, abilityId -> null);

        CharacterStats stats = new CharacterStats.Builder().build();
        Character wielder = assertDoesNotThrow(() -> new SorcererCharacter(
            "WIELDER", "Wielder", stats, null, List.of(), List.of(), equipment),
            "A tool-granted move bypasses stat prerequisites and the weapon gate.");
        assertTrue(wielder.getKnownMoves().stream()
            .anyMatch(move -> "HARD_MOVE".equals(move.getId())),
            "The equipped tool's granted move joins the known move list.");
    }

    @Test
    void resolvingUnknownEquippedToolFailsLoudly() {
        CursedToolData tool = new CursedToolData();
        tool.id = "000000";
        tool.name = "Test Katana";
        tool.weaponType = "KATANA";

        assertThrows(IllegalArgumentException.class, () -> Equipment.resolve(
                List.of(), List.of("999999"), List.of(tool),
                moveId -> null, abilityId -> null),
            "An equipped tool id that does not exist must fail loudly.");
        assertThrows(IllegalArgumentException.class, () -> Equipment.resolve(
                List.of("LIGHTSABER"), List.of(), List.of(tool),
                moveId -> null, abilityId -> null),
            "An unknown weapon type name must fail loudly.");
    }
}

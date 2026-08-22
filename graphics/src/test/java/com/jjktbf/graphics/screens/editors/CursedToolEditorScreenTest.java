package com.jjktbf.graphics.screens.editors;

import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.weapon.CursedToolData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursedToolEditorScreenTest {

    @Test
    void flatNodesContainOnlyContentExplicitlyAssignedFromItsEditor() {
        CursedToolData tool = new CursedToolData();
        tool.id = "000002";
        tool.weaponType = "KATANA";

        MoveData assignedMove = new MoveData();
        assignedMove.requiredCursedToolId = tool.id;
        MoveData weaponTypeMove = new MoveData();
        weaponTypeMove.tags = java.util.List.of("KATANA");

        AbilityData assignedAbility = new AbilityData();
        assignedAbility.sourceType = "CURSED_TOOL";
        assignedAbility.sourceValue = tool.id;
        AbilityData ordinaryAbility = new AbilityData();
        ordinaryAbility.sourceType = "CHARACTER";

        assertTrue(CursedToolEditorScreen.moveAssignedTo(tool, assignedMove));
        assertFalse(CursedToolEditorScreen.moveAssignedTo(tool, weaponTypeMove),
            "A weapon-type tag is an eligibility gate, not an explicit tool grant.");
        assertTrue(CursedToolEditorScreen.abilityAssignedTo(tool, assignedAbility));
        assertFalse(CursedToolEditorScreen.abilityAssignedTo(tool, ordinaryAbility));
    }
}

package com.jjktbf.graphics.screens.editors;

import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.move.StatusEffectType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AbilityEditorScreenTest {

    @Test
    void legacyNegativeStatusAmountsNormalizeBeforeValidation() {
        AbilityEffectData apply = AbilityEffectType.APPLY_STATUS.createDefault();
        apply.stringValue = "FOCUS";
        apply.magnitude = -0.10;
        AbilityData draft = new AbilityData();
        draft.effects = new ArrayList<>(List.of(apply));

        AbilityEditorScreen.normalizeLegacyStatusAmounts(draft);

        assertEquals(StatusEffectType.ACCURACY_DECREASE.name(), apply.stringValue);
        assertEquals(10.0, apply.magnitude);
    }

    @Test
    void statusReferencesRetainTheirLegacyTwoDirectionMeaning() {
        AbilityEffectData remove = AbilityEffectType.REMOVE_STATUS.createDefault();
        remove.stringValue = "FOCUS";
        AbilityData draft = new AbilityData();
        draft.effects = new ArrayList<>(List.of(remove));

        AbilityEditorScreen.normalizeLegacyStatusAmounts(draft);
        AbilityEffectType.REMOVE_STATUS.prepare(remove);

        assertEquals("FOCUS", remove.stringValue);
    }

    @Test
    void abilityEffectsAcceptTickOnlyAndCombinedDurations() {
        AbilityEffectData effect = AbilityEffectType.APPLY_STATUS.createDefault();
        effect.durationRounds = 0;
        effect.durationTicks = 5;
        assertNull(AbilityEffectType.APPLY_STATUS.validationError(effect));

        effect.durationRounds = 3;
        effect.durationTicks = 20;
        assertNull(AbilityEffectType.APPLY_STATUS.validationError(effect));
    }
}

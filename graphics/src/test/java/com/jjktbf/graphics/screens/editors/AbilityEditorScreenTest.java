package com.jjktbf.graphics.screens.editors;

import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionRuleData;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.move.StatusEffectType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    void staggerAbilityStatusRequiresApTicksAndNoMagnitude() {
        AbilityEffectData effect = AbilityEffectType.APPLY_STATUS.createDefault();
        effect.stringValue = StatusEffectType.STAGGER.name();
        effect.durationRounds = 0;
        effect.durationTicks = 2;
        effect.magnitude = 0.0;

        assertNull(AbilityEffectType.APPLY_STATUS.validationError(effect));

        effect.durationRounds = 1;
        assertEquals("Stagger must use 0 rounds and at least 1 AP tick.",
            AbilityEffectType.APPLY_STATUS.validationError(effect));
    }

    @Test
    void recordSectionsSeparatePassiveAndActiveAbilities() {
        AbilityData ability = new AbilityData();
        ability.category = "PASSIVE";
        assertEquals("PASSIVE", AbilityEditorScreen.abilityRecordSection(ability));

        ability.category = "active";
        assertEquals("ACTIVE", AbilityEditorScreen.abilityRecordSection(ability));
    }

    @Test
    void temporarilySwitchingToPassivePreservesAuthoredConditions() {
        AbilityConditionRuleData rule = AbilityConditionRuleData.allEffects(
            AbilityConditionData.manualActivation());
        AbilityData draft = new AbilityData();
        draft.category = "ACTIVE";
        draft.activationConditions = new ArrayList<>(List.of(rule));

        draft.category = "PASSIVE";
        AbilityEditorScreen.initialiseCategoryDefaults(draft);
        draft.category = "ACTIVE";
        AbilityEditorScreen.initialiseCategoryDefaults(draft);

        assertEquals(1, draft.activationConditions.size());
        assertSame(rule, draft.activationConditions.get(0));
    }

    @Test
    void malformedConditionDraftGetsAFailClosedEditablePredicate() {
        AbilityConditionRuleData rule = new AbilityConditionRuleData();
        AbilityData draft = new AbilityData();
        draft.category = "ACTIVE";
        draft.activationConditions = new ArrayList<>(List.of(rule));

        AbilityEditorScreen.initialiseActivationDefaults(draft);

        assertEquals("MANUAL_ACTIVATION", rule.condition.type);
    }

    @Test
    void summonEffectsOnlyAcceptExistingShikigamiDefinitions() {
        CharacterData sorcerer = character("000001", null);
        CharacterData shikigami = character("000002", CharacterType.SHIKIGAMI.name());

        assertNull(AbilityEditorScreen.summonReferenceValidationError(
            "000002", List.of(sorcerer, shikigami)));
        assertEquals("The summon target must be a Shikigami.",
            AbilityEditorScreen.summonReferenceValidationError(
                "000001", List.of(sorcerer, shikigami)));
        assertEquals("The summon target does not exist.",
            AbilityEditorScreen.summonReferenceValidationError(
                "000003", List.of(sorcerer, shikigami)));
    }

    private static CharacterData character(String id, String type) {
        CharacterData character = new CharacterData();
        character.id = id;
        character.type = type;
        return character;
    }
}

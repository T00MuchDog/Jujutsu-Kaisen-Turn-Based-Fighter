package com.jjktbf.graphics.screens.editors;

import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.StatusEffectType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveEditorScreenTest {

    @Test
    void retaggingBeforeSaveRestoresTheExistingSectionDetails() {
        MoveData draft = moveWithAllSectionDetails();

        draft.tags.remove(MoveTag.ATTACK.name());

        assertEquals(75, draft.basePower);
        assertFalse(draft.onHitEffects.isEmpty());

        draft.tags.add(MoveTag.ATTACK.name());
        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertEquals(75, saved.basePower);
        assertEquals(0.8, saved.baseAccuracy);
        assertTrue(saved.neverMiss);
        assertEquals(1, saved.onHitEffects.size());
    }

    @Test
    void saveCopyDiscardsOnlyDetailsForInactiveSections() {
        MoveData draft = moveWithAllSectionDetails();
        draft.tags.remove(MoveTag.ATTACK.name());

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertEquals(0, saved.basePower);
        assertEquals(1.0, saved.baseAccuracy);
        assertFalse(saved.neverMiss);
        assertTrue(saved.onHitEffects.isEmpty());

        assertEquals(DefenseType.BLOCK.name(), saved.defenseType);
        assertEquals(BlockStyle.FLAT.name(), saved.blockStyle);
        assertEquals(4, saved.blockDuration);
        assertEquals(List.of(MoveTag.PHYSICAL.name()), saved.blockAffectedTags);
        assertEquals(20, saved.blockFlatReduction);
        assertEquals(1, saved.selfEffects.size());

        // Preparing a save must not mutate the live draft. If persistence
        // fails, retagging still has the original values to restore.
        assertEquals(75, draft.basePower);
        assertEquals(1, draft.onHitEffects.size());
    }

    @Test
    void retaggingUtilityBeforeSaveRestoresSelfEffects() {
        MoveData draft = moveWithAllSectionDetails();

        draft.tags.remove(MoveTag.UTILITY.name());
        assertEquals(1, draft.selfEffects.size());

        draft.tags.add(MoveTag.UTILITY.name());
        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertEquals(1, saved.selfEffects.size());
        assertEquals(StatusEffectType.ACCURACY_INCREASE.name(), saved.selfEffects.get(0).type);
        assertEquals(5, saved.selfEffects.get(0).durationTicks);
    }

    @Test
    void savingAnAttackOnlyMoveDiscardsDefenseDetailsButKeepsSelfEffects() {
        MoveData draft = moveWithAllSectionDetails();
        draft.tags = new ArrayList<>(List.of(MoveTag.ATTACK.name(), MoveTag.PHYSICAL.name()));

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertEquals(DefenseType.NONE.name(), saved.defenseType);
        assertEquals(0, saved.blockDuration);
        assertNull(saved.blockAffectedTags);
        assertEquals(100, saved.blockDamageReduction);
        assertEquals(0, saved.blockFlatReduction);
        assertEquals(1, saved.selfEffects.size());

        assertEquals(75, saved.basePower);
        assertEquals(1, saved.onHitEffects.size());
    }

    @Test
    void saveCopyMigratesLegacyStatEffectsAndDropsRemovedOnes() {
        MoveData draft = moveWithAllSectionDetails();
        draft.selfEffects.clear();
        draft.selfEffects.add(effect("FOCUS", 0.10));
        draft.selfEffects.add(effect("POISON", 5.0));

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertEquals(1, saved.selfEffects.size());
        assertEquals(StatusEffectType.ACCURACY_INCREASE.name(), saved.selfEffects.get(0).type);
        assertEquals(10.0, saved.selfEffects.get(0).magnitude);
    }

    @Test
    void saveCopyRepresentsStatDecreasesWithANegativeMagnitude() {
        MoveData draft = moveWithAllSectionDetails();
        draft.selfEffects.clear();
        draft.selfEffects.add(effect(StatusEffectType.STRENGTH_DECREASE.name(), 10.0));

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertEquals(StatusEffectType.STRENGTH_INCREASE.name(), saved.selfEffects.get(0).type);
        assertEquals(-10.0, saved.selfEffects.get(0).magnitude);
    }

    @Test
    void saveCopyPreservesTickOnlyStaggerWithoutAStatMagnitude() {
        MoveData draft = moveWithAllSectionDetails();
        draft.selfEffects.clear();
        MoveData.StatusEffectData stagger = effect(StatusEffectType.STAGGER.name(), 25.0);
        stagger.durationRounds = 0;
        stagger.durationTicks = 3;
        draft.selfEffects.add(stagger);

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertEquals(StatusEffectType.STAGGER.name(), saved.selfEffects.get(0).type);
        assertEquals(0, saved.selfEffects.get(0).durationRounds);
        assertEquals(3, saved.selfEffects.get(0).durationTicks);
        assertEquals(0.0, saved.selfEffects.get(0).magnitude);
    }

    @Test
    void categoryTagsMustDescribeOneExecutableMovePurpose() {
        MoveData attack = new MoveData();
        attack.tags = new ArrayList<>(List.of(MoveTag.ATTACK.name()));
        assertEquals("An Attack needs a Physical, Cursed Energy, or Technique tag.",
            MoveEditorScreen.categoryTagValidationError(attack));

        attack.tags.add(MoveTag.PHYSICAL.name());
        assertNull(MoveEditorScreen.categoryTagValidationError(attack));

        attack.tags.add(MoveTag.UTILITY.name());
        assertEquals("Select exactly one of Attack, Utility, or Defensive.",
            MoveEditorScreen.categoryTagValidationError(attack));

        MoveData defense = new MoveData();
        defense.tags = new ArrayList<>(List.of(MoveTag.DEFENSIVE.name()));
        defense.defenseType = DefenseType.NONE.name();
        assertEquals("A Defensive move needs a defense type (Block, Parry, or Dodge) or a coded self effect.",
            MoveEditorScreen.categoryTagValidationError(defense));
    }

    @Test
    void saveCopyPreservesMustBeGrantedAndDefensiveCodedSelfEffects() {
        MoveData defense = new MoveData();
        defense.tags = new ArrayList<>(List.of(MoveTag.DEFENSIVE.name()));
        defense.defenseType = DefenseType.NONE.name();
        defense.mustBeGranted = true;
        MoveData.StatusEffectData coded = new MoveData.StatusEffectData();
        coded.codedAbilityKey = "NEW_SHADOW_STYLE";
        coded.codedAction = "ACTIVATE_SIMPLE_DOMAIN";
        coded.codedTarget = "000027";
        defense.selfEffects = new ArrayList<>(List.of(coded));

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(defense);

        assertTrue(saved.mustBeGranted);
        assertEquals(1, saved.selfEffects.size());
        assertNull(MoveEditorScreen.categoryTagValidationError(saved));
    }

    @Test
    void moveResequencingRemapsCodedReactionMoveReferences() {
        MoveData domain = new MoveData();
        MoveData.StatusEffectData reaction = new MoveData.StatusEffectData();
        reaction.codedAbilityKey = "NEW_SHADOW_STYLE";
        reaction.codedAction = "ACTIVATE_SIMPLE_DOMAIN";
        reaction.codedTarget = "000027";
        domain.selfEffects = new ArrayList<>(List.of(reaction));

        MoveEditorScreen.remapCodedMoveTargets(
            List.of(domain), Map.of("000027", "000026"));

        assertEquals("000026", reaction.codedTarget);
    }

    private static MoveData moveWithAllSectionDetails() {
        MoveData data = new MoveData();
        data.tags = new ArrayList<>(List.of(
            MoveTag.ATTACK.name(), MoveTag.DEFENSIVE.name(), MoveTag.UTILITY.name(),
            MoveTag.PHYSICAL.name()));

        data.basePower = 75;
        data.baseAccuracy = 0.8;
        data.neverMiss = true;
        data.onHitEffects = new ArrayList<>(List.of(
            effect(StatusEffectType.STRENGTH_DECREASE)));

        data.defenseType = DefenseType.BLOCK.name();
        data.blockStyle = BlockStyle.FLAT.name();
        data.blockDuration = 4;
        data.blockAffectedTags = new ArrayList<>(List.of(MoveTag.PHYSICAL.name()));
        data.blockDamageReduction = 35;
        data.blockFlatReduction = 20;

        data.selfEffects = new ArrayList<>(List.of(
            effect(StatusEffectType.ACCURACY_INCREASE)));
        data.selfEffects.get(0).durationTicks = 5;
        return data;
    }

    private static MoveData.StatusEffectData effect(StatusEffectType type) {
        return effect(type.name(), 1.0);
    }

    private static MoveData.StatusEffectData effect(String type, double amount) {
        MoveData.StatusEffectData effect = new MoveData.StatusEffectData();
        effect.type = type;
        effect.magnitude = amount;
        return effect;
    }
}

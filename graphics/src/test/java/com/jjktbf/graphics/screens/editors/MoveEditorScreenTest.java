package com.jjktbf.graphics.screens.editors;

import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.StatusEffectType;
import com.jjktbf.model.progression.TechniqueMasteryProgressionData;
import com.jjktbf.model.progression.TechniqueMasteryProgressions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveEditorScreenTest {

    @Test
    void saveCopyDeepCopiesAndPreservesAuthoritativeHitComponents() {
        MoveData draft = attackWithHitComponents();
        draft.basePower = 999;

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertEquals(70, saved.basePower);
        assertEquals(2, saved.hitComponents.size());
        assertEquals(List.of(MoveTag.CURSED_ENERGY.name()),
            saved.hitComponents.get(1).tags);
        assertEquals(4, saved.hitComponents.get(1).delayTicks);
        assertTrue(saved.hitComponents.get(1).requiresPreviousConnection);
        assertFalse(saved.hitComponents.get(1).avoidable);
        assertNotSame(draft.hitComponents, saved.hitComponents);
        assertNotSame(draft.hitComponents.get(1), saved.hitComponents.get(1));
        assertNotSame(draft.hitComponents.get(1).tags, saved.hitComponents.get(1).tags);

        saved.hitComponents.get(1).tags.add(MoveTag.PHYSICAL.name());
        assertEquals(List.of(MoveTag.CURSED_ENERGY.name()),
            draft.hitComponents.get(1).tags);
        assertEquals(999, draft.basePower);
    }

    @Test
    void saveCopyDeepCopiesMoveEffectMasteryProgression() {
        MoveData draft = new MoveData();
        draft.tags = new ArrayList<>(List.of(
            MoveTag.UTILITY.name(), MoveTag.INNATE_TECHNIQUE.name(),
            MoveTag.CURSED_ENERGY.name()));
        MoveData.StatusEffectData effect = effect("STRENGTH_INCREASE", 10);
        TechniqueMasteryProgressionData progression = new TechniqueMasteryProgressionData();
        progression.mode = TechniqueMasteryProgressionData.FORMULA;
        progression.formula = "10 + ctm / 20";
        effect.masteryProgression = Map.of(
            TechniqueMasteryProgressions.MAGNITUDE, progression);
        draft.selfEffects = new ArrayList<>(List.of(effect));

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertNotSame(draft.selfEffects.get(0).masteryProgression,
            saved.selfEffects.get(0).masteryProgression);
        assertNotSame(progression,
            saved.selfEffects.get(0).masteryProgression
                .get(TechniqueMasteryProgressions.MAGNITUDE));
        assertEquals(15, saved.selfEffects.get(0).masteryProgression
            .get(TechniqueMasteryProgressions.MAGNITUDE).resolve(100));
    }

    @Test
    void saveCopyDerivesParentDamageTagsFromComponents() {
        MoveData draft = attackWithHitComponents();
        draft.id = "COMPONENT_TAGS";
        draft.apCost = 5;
        draft.unleashPoint = 1;
        draft.tags.remove(MoveTag.CURSED_ENERGY.name());

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertTrue(saved.tags.contains(MoveTag.PHYSICAL.name()));
        assertTrue(saved.tags.contains(MoveTag.CURSED_ENERGY.name()));
        assertEquals(70, saved.toMove().getBasePower());
    }

    @Test
    void moveTypeEditsApplyToEveryHitComponent() {
        MoveData draft = attackWithHitComponents();
        draft.id = "COMPONENT_TYPE_EDIT";
        draft.apCost = 5;
        draft.unleashPoint = 1;

        MoveEditorScreen.applyMoveDamageTagsToComponents(
            draft, Set.of(MoveTag.PHYSICAL));

        assertEquals(List.of(MoveTag.PHYSICAL.name()), draft.hitComponents.get(0).tags);
        assertEquals(List.of(MoveTag.PHYSICAL.name()), draft.hitComponents.get(1).tags);
        assertEquals(List.of(MoveTag.ATTACK.name(), MoveTag.PHYSICAL.name()), draft.tags);
        assertEquals(70, draft.toMove().getBasePower());
    }

    @Test
    void saveCopyKeepsLegacyAttacksUnmigrated() {
        MoveData draft = new MoveData();
        draft.tags = new ArrayList<>(List.of(
            MoveTag.ATTACK.name(), MoveTag.PHYSICAL.name()));
        draft.basePower = 45;
        draft.hitComponents = null;

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertEquals(45, saved.basePower);
        assertNull(saved.hitComponents);
    }

    @Test
    void nonAttackSaveNormalizationClearsHitComponents() {
        MoveData draft = attackWithHitComponents();
        draft.tags = new ArrayList<>(List.of(MoveTag.UTILITY.name()));

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertEquals(0, saved.basePower);
        assertTrue(saved.hitComponents.isEmpty());
        assertEquals(2, draft.hitComponents.size());
    }

    @Test
    void enablingComponentEditingSeedsTheEquivalentLegacyHit() {
        MoveData draft = new MoveData();
        draft.tags = List.of(
            MoveTag.ATTACK.name(),
            MoveTag.CURSED_ENERGY.name(),
            MoveTag.INNATE_TECHNIQUE.name());
        draft.basePower = 55;

        MoveEditorScreen.enableHitComponentEditing(draft);

        assertEquals(1, draft.hitComponents.size());
        assertEquals(55, draft.hitComponents.get(0).basePower);
        assertEquals(List.of(MoveTag.INNATE_TECHNIQUE.name()),
            draft.hitComponents.get(0).tags);
        assertEquals(55, MoveEditorScreen.combinedBasePower(draft));

        MoveEditorScreen.addHitComponent(draft);
        assertEquals(2, draft.hitComponents.size());
        assertEquals(List.of(MoveTag.INNATE_TECHNIQUE.name()),
            draft.hitComponents.get(1).tags);
        assertNotSame(draft.hitComponents.get(0).tags, draft.hitComponents.get(1).tags);
    }

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
    void saveCopyPreservesMoveCapAndParryAffectedTags() {
        MoveData draft = new MoveData();
        draft.tags = new ArrayList<>(List.of(
            MoveTag.DEFENSIVE.name(), MoveTag.PHYSICAL.name()));
        draft.defenseType = DefenseType.PARRY.name();
        draft.blockAffectedTags = new ArrayList<>(List.of(
            MoveTag.PHYSICAL.name(), MoveTag.CURSED_ENERGY.name()));
        draft.moveCap = 1;

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertEquals(1, saved.moveCap);
        assertEquals(List.of(MoveTag.PHYSICAL.name(), MoveTag.CURSED_ENERGY.name()),
            saved.blockAffectedTags);
        assertNotSame(draft.blockAffectedTags, saved.blockAffectedTags);
    }

    @Test
    void saveCopyPreservesSummonCharacterId() {
        MoveData draft = new MoveData();
        draft.tags = new ArrayList<>(List.of(MoveTag.UTILITY.name()));
        draft.summonCharacterId = "000004";

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertEquals("000004", saved.summonCharacterId);
    }

    @Test
    void saveCopyPreservesAoeTypeAndTargetCount() {
        MoveData draft = new MoveData();
        draft.tags = new ArrayList<>(List.of(
            MoveTag.ATTACK.name(), MoveTag.PHYSICAL.name(), MoveTag.AOE.name()));
        draft.aoeType = "MULTIPLE";
        draft.aoeTargetCount = 3;
        draft.hitComponents = new ArrayList<>();
        MoveData.HitComponentData hit = new MoveData.HitComponentData();
        hit.basePower = 20;
        hit.tags = new ArrayList<>(List.of(MoveTag.PHYSICAL.name()));
        draft.hitComponents.add(hit);

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertEquals("MULTIPLE", saved.aoeType);
        assertEquals(3, saved.aoeTargetCount);
    }

    @Test
    void deepCopyPreservesSummonEffectRow() {
        MoveData draft = new MoveData();
        draft.tags = new ArrayList<>(List.of(MoveTag.UTILITY.name()));
        MoveData.StatusEffectData summon = new MoveData.StatusEffectData();
        summon.summonCharacterId = "000010";
        draft.selfEffects = new ArrayList<>(List.of(summon));

        MoveData copy = MoveEditorScreen.deepCopy(draft);

        assertEquals(1, copy.selfEffects.size());
        assertTrue(copy.selfEffects.get(0).isSummon());
        assertEquals("000010", copy.selfEffects.get(0).summonCharacterId);
        assertNotSame(draft.selfEffects.get(0), copy.selfEffects.get(0));
    }

    @Test
    void summonReferenceMustResolveToAShikigamiDefinition() {
        CharacterData sorcerer = character("000001", null);
        CharacterData shikigami = character("000002", CharacterType.SHIKIGAMI.name());

        assertNull(MoveEditorScreen.summonReferenceValidationError(
            "000002", List.of(sorcerer, shikigami)));
        assertEquals("Summon target \"Test\" must be a Shikigami.",
            MoveEditorScreen.summonReferenceValidationError(
                "000001", List.of(sorcerer, shikigami)));
        assertEquals("Summon target 000003 does not exist.",
            MoveEditorScreen.summonReferenceValidationError(
                "000003", List.of(sorcerer, shikigami)));
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
        draft.selfEffects.add(effect("REMOVED_EFFECT", 5.0));

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
    void aoeAndFriendlyFireAreAttackTargetingTags() {
        MoveData utility = new MoveData();
        utility.tags = new ArrayList<>(List.of(
            MoveTag.UTILITY.name(), MoveTag.AOE.name()));
        assertEquals("Melee, Ranged, AOE, and Friendly Fire tags require Attack.",
            MoveEditorScreen.categoryTagValidationError(utility));

        MoveData attack = new MoveData();
        attack.tags = new ArrayList<>(List.of(
            MoveTag.ATTACK.name(), MoveTag.PHYSICAL.name(),
            MoveTag.FRIENDLY_FIRE.name()));
        assertEquals("Friendly Fire requires AOE.",
            MoveEditorScreen.categoryTagValidationError(attack));

        attack.tags.add(MoveTag.AOE.name());
        assertNull(MoveEditorScreen.categoryTagValidationError(attack));
    }

    @Test
    void recordSectionsPrioritizeAttackThenDefenseThenUtility() {
        MoveData move = new MoveData();
        move.tags = new ArrayList<>(List.of(
            MoveTag.ATTACK.name(), MoveTag.DEFENSIVE.name(), MoveTag.UTILITY.name()));
        assertEquals("ATTACK", MoveEditorScreen.moveRecordSection(move));

        move.tags.remove(MoveTag.ATTACK.name());
        assertEquals("DEFENSE", MoveEditorScreen.moveRecordSection(move));

        move.tags.remove(MoveTag.DEFENSIVE.name());
        assertEquals("UTILITY", MoveEditorScreen.moveRecordSection(move));
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
    void shikigamiMoveGroupingIsPreservedBySaveCopy() {
        MoveData move = new MoveData();
        move.tags = new ArrayList<>(List.of(MoveTag.UTILITY.name()));
        move.shikigamiMove = true;

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(move);

        assertEquals("SHIKIGAMI", MoveEditorScreen.moveRecordGroup(move));
        assertTrue(Boolean.TRUE.equals(saved.shikigamiMove));

        move.shikigamiMove = null;
        assertEquals("SORCERER", MoveEditorScreen.moveRecordGroup(move));
    }

    @Test
    void saveCopyForcesWeaponRequirementForSwordTaggedMoves() {
        MoveData swordMove = new MoveData();
        swordMove.tags = new ArrayList<>(List.of(MoveTag.ATTACK.name(), MoveTag.SWORD.name()));
        swordMove.weaponRequired = false;

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(swordMove);

        assertTrue(saved.weaponRequired);
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

    private static MoveData attackWithHitComponents() {
        MoveData data = new MoveData();
        data.tags = new ArrayList<>(List.of(
            MoveTag.ATTACK.name(),
            MoveTag.PHYSICAL.name(),
            MoveTag.CURSED_ENERGY.name()));
        data.hitComponents = new ArrayList<>();

        MoveData.HitComponentData first = new MoveData.HitComponentData();
        first.basePower = 40;
        first.tags = new ArrayList<>(List.of(MoveTag.PHYSICAL.name()));
        data.hitComponents.add(first);

        MoveData.HitComponentData second = new MoveData.HitComponentData();
        second.basePower = 30;
        second.tags = new ArrayList<>(List.of(MoveTag.CURSED_ENERGY.name()));
        second.delayTicks = 4;
        second.requiresPreviousConnection = true;
        second.avoidable = false;
        data.hitComponents.add(second);
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

    private static CharacterData character(String id, String type) {
        CharacterData character = new CharacterData();
        character.id = id;
        character.name = "Test";
        character.type = type;
        return character;
    }
}

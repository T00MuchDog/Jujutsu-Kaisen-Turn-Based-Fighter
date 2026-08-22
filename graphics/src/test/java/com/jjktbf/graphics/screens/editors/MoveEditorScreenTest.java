package com.jjktbf.graphics.screens.editors;

import com.jjktbf.graphics.ui.profile.UiProfile;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.move.AttackLaunchMode;
import com.jjktbf.model.move.BlockStyle;
import com.jjktbf.model.move.DefenseType;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.move.MoveEffectTrigger;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.move.MoveType;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveEditorScreenTest {

    @Test
    void windowsWrapsPrerequisitesIntoNarrowRows() {
        assertEquals(2, MoveEditorScreen.prerequisiteColumnsPerRow(UiProfile.WINDOWS));
        assertEquals(Integer.MAX_VALUE,
            MoveEditorScreen.prerequisiteColumnsPerRow(UiProfile.MAC));
    }

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
    void saveCopyPreservesDefenseTargetingAndCount() {
        MoveData draft = new MoveData();
        draft.tags = new ArrayList<>(List.of(
            MoveTag.DEFENSIVE.name(), MoveTag.PHYSICAL.name()));
        draft.defenseType = DefenseType.DODGE.name();
        draft.defenseTargeting = "SINGLE_ALLY";
        draft.defenseTargetCount = 4;

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(draft);

        assertEquals("SINGLE_ALLY", saved.defenseTargeting,
            "Saving must not reset an authored defense targeting back to SELF.");
        assertEquals(4, saved.defenseTargetCount);

        // A move without the DEFENSIVE tag is normalized back to SELF.
        MoveData attack = new MoveData();
        attack.tags = new ArrayList<>(List.of(
            MoveTag.ATTACK.name(), MoveTag.PHYSICAL.name()));
        attack.hitComponents = new ArrayList<>();
        attack.defenseTargeting = "SINGLE_ALLY";
        attack.defenseTargetCount = 4;

        MoveData savedAttack = MoveEditorScreen.normalizedCopyForSave(attack);

        assertEquals("SELF", savedAttack.defenseTargeting);
        assertEquals(2, savedAttack.defenseTargetCount);
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
    void categoryTagsAllowDefenceAttackAndUtilityHybrids() {
        MoveData attack = new MoveData();
        attack.tags = new ArrayList<>(List.of(MoveTag.ATTACK.name()));
        assertEquals("An Attack needs a Physical, Cursed Energy, or Technique tag.",
            MoveEditorScreen.categoryTagValidationError(attack));

        attack.tags.add(MoveTag.PHYSICAL.name());
        assertNull(MoveEditorScreen.categoryTagValidationError(attack));

        // UTILITY combines with ATTACK: the on-fire rows live in the UTILITY section.
        attack.tags.add(MoveTag.UTILITY.name());
        assertNull(MoveEditorScreen.categoryTagValidationError(attack));

        // ATTACK combines with DEFENSIVE: the hybrid needs a defence type like
        // any other defensive move, but the tag combination itself is valid.
        attack.tags.add(MoveTag.DEFENSIVE.name());
        assertEquals("A Defensive move needs a defense type (Block, Parry, or Dodge) or a coded self effect.",
            MoveEditorScreen.categoryTagValidationError(attack));
        attack.defenseType = DefenseType.BLOCK.name();
        assertNull(MoveEditorScreen.categoryTagValidationError(attack));

        MoveData none = new MoveData();
        none.tags = new ArrayList<>(List.of(MoveTag.PHYSICAL.name()));
        assertEquals("Select at least one of Attack, Utility, or Defensive.",
            MoveEditorScreen.categoryTagValidationError(none));

        MoveData defense = new MoveData();
        defense.tags = new ArrayList<>(List.of(MoveTag.DEFENSIVE.name()));
        defense.defenseType = DefenseType.NONE.name();
        assertEquals("A Defensive move needs a defense type (Block, Parry, or Dodge) or a coded self effect.",
            MoveEditorScreen.categoryTagValidationError(defense));

        // UTILITY combines with DEFENSIVE the same way.
        defense.tags.add(MoveTag.UTILITY.name());
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
    void recordSectionsPrioritizeDefenseThenAttackThenUtility() {
        MoveData move = new MoveData();
        move.tags = new ArrayList<>(List.of(
            MoveTag.ATTACK.name(), MoveTag.DEFENSIVE.name(), MoveTag.UTILITY.name()));
        // Defence wins over attack: the hybrid lists under DEFENSE.
        assertEquals("DEFENSE", MoveEditorScreen.moveRecordSection(move));

        move.tags.remove(MoveTag.DEFENSIVE.name());
        assertEquals("ATTACK", MoveEditorScreen.moveRecordSection(move));

        move.tags.remove(MoveTag.ATTACK.name());
        assertEquals("UTILITY", MoveEditorScreen.moveRecordSection(move));
    }

    @Test
    void derivedTimelineIsDefenceFirst() {
        MoveData hybrid = new MoveData();
        hybrid.tags = new ArrayList<>(List.of(
            MoveTag.ATTACK.name(), MoveTag.DEFENSIVE.name(), MoveTag.PHYSICAL.name()));
        assertEquals("DEFENSIVE", MoveEditorScreen.derivedTimelineName(hybrid));

        hybrid.tags.remove(MoveTag.DEFENSIVE.name());
        assertEquals("OFFENSIVE", MoveEditorScreen.derivedTimelineName(hybrid));

        MoveData utility = new MoveData();
        utility.tags = new ArrayList<>(List.of(MoveTag.UTILITY.name()));
        assertEquals("DEFENSIVE", MoveEditorScreen.derivedTimelineName(utility));
    }

    @Test
    void attackLaunchReferenceMustExistAndNotBeSelf() {
        MoveData move = new MoveData();
        move.id = "000001";
        move.tags = new ArrayList<>(List.of(
            MoveTag.ATTACK.name(), MoveTag.DEFENSIVE.name(), MoveTag.PHYSICAL.name()));
        move.defenseType = DefenseType.BLOCK.name();

        move.attackLaunchMoveId = "000001";
        assertEquals("The attack cannot reference the move itself.",
            MoveEditorScreen.attackLaunchReferenceValidationError(move, List.of(move)));

        move.attackLaunchMoveId = "999999";
        assertEquals("Referenced attack move 999999 does not exist.",
            MoveEditorScreen.attackLaunchReferenceValidationError(move, List.of(move)));

        move.attackLaunchMoveId = null;
        assertNull(MoveEditorScreen.attackLaunchReferenceValidationError(move, List.of(move)));

        MoveData other = new MoveData();
        other.id = "000002";
        move.attackLaunchMoveId = "000002";
        assertNull(MoveEditorScreen.attackLaunchReferenceValidationError(move, List.of(move, other)));
    }

    @Test
    void saveCopyPreservesHybridLaunchFieldsAndClearsThemOffHybrids() {
        MoveData hybrid = new MoveData();
        hybrid.id = "000001";
        hybrid.tags = new ArrayList<>(List.of(
            MoveTag.ATTACK.name(), MoveTag.DEFENSIVE.name(), MoveTag.PHYSICAL.name()));
        hybrid.defenseType = DefenseType.BLOCK.name();
        hybrid.attackLaunchMode = AttackLaunchMode.ON_DEFENCE.name();
        hybrid.attackLaunchCondition = AbilityConditionData.always();
        hybrid.attackLaunchChanceEnabled = Boolean.TRUE;
        hybrid.attackLaunchChance = 50;

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(hybrid);
        assertEquals(AttackLaunchMode.ON_DEFENCE.name(), saved.attackLaunchMode);
        assertNotNull(saved.attackLaunchCondition);
        assertEquals(Boolean.TRUE, saved.attackLaunchChanceEnabled);
        assertEquals(50, saved.attackLaunchChance);

        // Dropping either purpose tag clears the launch settings entirely.
        hybrid.tags.remove(MoveTag.ATTACK.name());
        saved = MoveEditorScreen.normalizedCopyForSave(hybrid);
        assertNull(saved.attackLaunchMode);
        assertNull(saved.attackLaunchCondition);
        assertNull(saved.attackLaunchChanceEnabled);
        assertNull(saved.attackLaunchChance);
        assertNull(saved.attackLaunchMoveId);
    }

    @Test
    void saveCopyDiscardsOwnAttackFieldsWhenALaunchMoveIsReferenced() {
        MoveData hybrid = new MoveData();
        hybrid.id = "000001";
        hybrid.tags = new ArrayList<>(List.of(
            MoveTag.ATTACK.name(), MoveTag.DEFENSIVE.name(), MoveTag.PHYSICAL.name()));
        hybrid.defenseType = DefenseType.BLOCK.name();
        hybrid.attackLaunchMode = AttackLaunchMode.ON_DEFENCE.name();
        hybrid.attackLaunchMoveId = "000002";
        hybrid.basePower = 30;
        hybrid.hitComponents = new ArrayList<>(List.of(new MoveData.HitComponentData()));
        MoveEffectData onHit = new MoveEffectData();
        onHit.type = AbilityEffectType.APPLY_STATUS.name();
        onHit.trigger = MoveEffectTrigger.ON_HIT.name();
        onHit.stringValue = StatusEffectType.STAGGER.name();
        hybrid.effects = new ArrayList<>(List.of(onHit));

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(hybrid);

        assertEquals("000002", saved.attackLaunchMoveId);
        assertEquals(0, saved.basePower);
        assertTrue(saved.hitComponents.isEmpty());
        assertTrue(saved.effects.stream().noneMatch(effect ->
            MoveEffectTrigger.ON_HIT.name().equalsIgnoreCase(effect.trigger)));
    }

    @Test
    void deepCopyDuplicatesHybridLaunchFields() {
        MoveData hybrid = new MoveData();
        hybrid.tags = new ArrayList<>(List.of(
            MoveTag.ATTACK.name(), MoveTag.DEFENSIVE.name(), MoveTag.PHYSICAL.name()));
        hybrid.attackLaunchMode = AttackLaunchMode.ON_FIRE.name();
        hybrid.attackLaunchCondition = AbilityConditionData.always();
        hybrid.attackLaunchChanceEnabled = Boolean.TRUE;
        hybrid.attackLaunchChance = 75;
        hybrid.attackLaunchMoveId = "000009";

        MoveData copy = MoveEditorScreen.deepCopy(hybrid);

        assertEquals(AttackLaunchMode.ON_FIRE.name(), copy.attackLaunchMode);
        assertEquals("000009", copy.attackLaunchMoveId);
        assertEquals(Integer.valueOf(75), copy.attackLaunchChance);
        assertNotSame(hybrid.attackLaunchCondition, copy.attackLaunchCondition);
        copy.attackLaunchCondition.children = new ArrayList<>();
        assertNull(hybrid.attackLaunchCondition.children);
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
    void canonicalMoveTypesControlEditorGroupingAndSurviveSaveCopy() {
        MoveData move = new MoveData();
        move.tags = new ArrayList<>(List.of(MoveTag.UTILITY.name()));
        move.moveType = MoveType.CURSED_SPIRIT.name();

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(move);

        assertEquals("CURSED SPIRIT", MoveEditorScreen.moveRecordGroup(move));
        assertEquals(MoveType.CURSED_SPIRIT.name(), saved.moveType);

        move.moveType = MoveType.SHIKIGAMI.name();
        assertEquals("SHIKIGAMI", MoveEditorScreen.moveRecordGroup(move));
    }

    @Test
    void cursedTechniqueMovesAreGroupedByTechniqueUnlessTheyBelongToShikigami() {
        MoveData move = new MoveData();
        move.requiredTechniqueId = "Ratio";

        assertEquals("CURSED TECHNIQUES/Ratio", MoveEditorScreen.moveRecordGroup(move));

        move.shikigamiMove = true;
        assertEquals("SHIKIGAMI", MoveEditorScreen.moveRecordGroup(move));
    }

    @Test
    void cursedTechniqueSectionsAreAlphabeticalAndContainEveryPurpose() {
        List<String> sections = MoveEditorScreen.moveRecordSections(List.of(
            "Ten Shadows", "Ratio", "Miracles", "ratio"));

        assertTrue(sections.indexOf("CURSED TECHNIQUES/Miracles")
            < sections.indexOf("CURSED TECHNIQUES/Ratio"));
        assertTrue(sections.indexOf("CURSED TECHNIQUES/Ratio")
            < sections.indexOf("CURSED TECHNIQUES/Ten Shadows"));
        assertEquals(1, sections.stream()
            .filter("CURSED TECHNIQUES/Ratio"::equals).count());
        assertTrue(sections.contains("CURSED TECHNIQUES/Ratio/ATTACK"));
        assertTrue(sections.contains("CURSED TECHNIQUES/Ratio/DEFENSE"));
        assertTrue(sections.contains("CURSED TECHNIQUES/Ratio/UTILITY"));
        assertTrue(sections.contains("CURSED SPIRIT/ATTACK"));
    }

    @Test
    void saveCopyKeepsExactlyOneWeaponTag() {
        MoveData weaponMove = new MoveData();
        // Hand-edited data can carry several weapon tags; the save copy keeps
        // the first and drops the rest.
        weaponMove.tags = new ArrayList<>(List.of(
            MoveTag.ATTACK.name(), MoveTag.KATANA.name(), MoveTag.BOW.name()));

        MoveData saved = MoveEditorScreen.normalizedCopyForSave(weaponMove);

        assertTrue(saved.tags.contains(MoveTag.KATANA.name()));
        assertFalse(saved.tags.contains(MoveTag.BOW.name()));

        MoveData unarmed = new MoveData();
        unarmed.tags = new ArrayList<>(List.of(MoveTag.ATTACK.name()));
        assertTrue(MoveEditorScreen.normalizedCopyForSave(unarmed).tags.stream()
            .noneMatch(tag -> MoveTag.WEAPON_TAGS.stream()
                .anyMatch(weapon -> weapon.name().equals(tag))));
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

    @Test
    void hitComponentChangesKeepCanonicalEffectsAttachedToTheirHits() {
        MoveData move = attackWithHitComponents();
        MoveEffectData first = AbilityEffectType.APPLY_STATUS.createDefaultMoveEffect();
        first.effectId = "effect-000000";
        first.trigger = MoveEffectTrigger.ON_HIT.name();
        first.hitComponentIndex = 0;
        MoveEffectData second = first.copy();
        second.effectId = "effect-000001";
        second.hitComponentIndex = 1;
        MoveEffectData every = first.copy();
        every.effectId = "effect-000002";
        every.hitComponentIndex = null;
        move.effects = new ArrayList<>(List.of(first, second, every));

        MoveEditorScreen.swapHitComponents(move, 0, 1);

        assertEquals(1, first.hitComponentIndex);
        assertEquals(0, second.hitComponentIndex);
        assertNull(every.hitComponentIndex);

        MoveEditorScreen.removeHitComponent(move, 0);

        assertEquals(1, move.hitComponents.size());
        assertEquals(List.of(first, every), move.effects);
        assertEquals(0, first.hitComponentIndex);
        assertNull(every.hitComponentIndex);
    }

    @Test
    void moveResequencingRemapsNestedCanonicalEffectConditions() {
        MoveData move = new MoveData();
        MoveEffectData effect = AbilityEffectType.APPLY_STATUS.createDefaultMoveEffect();
        AbilityConditionData used = AbilityConditionType.MOVE_USED.createDefault();
        used.moveId = "000027";
        effect.condition = AbilityConditionData.all(List.of(used));
        move.effects = new ArrayList<>(List.of(effect));

        MoveEditorScreen.remapCodedMoveTargets(
            List.of(move), Map.of("000027", "000026"));

        assertEquals("000026", effect.condition.children.get(0).moveId);
    }

    @Test
    void saveCopyPreservesAccuracyPrioritiesOnlyInTheirApplicableSections() {
        MoveData attack = attackWithHitComponents();
        attack.setAccuracyPriorityTier(AbilityEffectType.NEVER_MISS, 4);

        MoveData savedAttack = MoveEditorScreen.normalizedCopyForSave(attack);
        assertEquals(4, savedAttack.getNeverMissTier());

        MoveData dodge = new MoveData();
        dodge.tags = new ArrayList<>(List.of(MoveTag.DEFENSIVE.name()));
        dodge.defenseType = DefenseType.DODGE.name();
        dodge.setAccuracyPriorityTier(AbilityEffectType.NEVER_HIT, 3);

        MoveData savedDodge = MoveEditorScreen.normalizedCopyForSave(dodge);
        assertEquals(3, savedDodge.getNeverHitTier());

        dodge.defenseType = DefenseType.BLOCK.name();
        MoveData savedBlock = MoveEditorScreen.normalizedCopyForSave(dodge);
        assertEquals(0, savedBlock.getNeverHitTier());
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

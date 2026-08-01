package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.graphics.Color;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoveCardViewTest {

    @Test
    void defensiveCardsUseTypeNamesWithTheDefensePalette() {
        Move move = moveWithTags("DEFENSIVE", "PHYSICAL", "CURSED_ENERGY");

        assertEquals("REINFORCEMENT", MoveCardView.typeNameFor(move));
        assertEquals(new Color(0.940f, 0.690f, 0.140f, 1f), MoveCardView.typeColorFor(move));
    }

    @Test
    void utilityCardsUseTypeNamesWithTheUtilityPalette() {
        Move move = moveWithTags("UTILITY", "CURSED_ENERGY");

        assertEquals("CURSED ENERGY", MoveCardView.typeNameFor(move));
        assertEquals(new Color(0.450f, 0.510f, 0.610f, 1f), MoveCardView.typeColorFor(move));
    }

    @Test
    void nonInnateTechniqueCardsKeepTheirPaletteAcrossRoles() {
        Color nonInnate = new Color(0.180f, 0.450f, 0.800f, 1f);

        Move defensive = moveWithTags(
            "DEFENSIVE", "NON_INNATE_TECHNIQUE", "CURSED_ENERGY");
        Move utility = moveWithTags(
            "UTILITY", "NON_INNATE_TECHNIQUE", "CURSED_ENERGY");

        assertEquals("NON-INNATE TECHNIQUE", MoveCardView.typeNameFor(defensive));
        assertEquals(nonInnate, MoveCardView.typeColorFor(defensive));
        assertEquals(nonInnate, MoveCardView.typeColorFor(utility));
    }

    @Test
    void attackingPhysicalCardsKeepThePhysicalAttackPalette() {
        Move move = moveWithTags("ATTACK", "PHYSICAL");

        assertEquals("PHYSICAL", MoveCardView.typeNameFor(move));
        assertEquals(new Color(0.850f, 0.380f, 0.190f, 1f), MoveCardView.typeColorFor(move));
    }

    @Test
    void cursedToolCardsUseTheDarkCrimsonWeaponReinforcementPalette() {
        Move move = moveWithTags(true, "ATTACK", "PHYSICAL", "CURSED_ENERGY");
        Move swordMove = moveWithTags("ATTACK", "PHYSICAL", "CURSED_ENERGY", "SWORD");

        assertEquals("CURSED TOOL", MoveCardView.typeNameFor(move));
        assertEquals(new Color(0.545f, 0.000f, 0.000f, 1f), MoveCardView.typeColorFor(move));
        assertEquals("REINFORCEMENT", MoveCardView.typeNameFor(swordMove));
    }

    @Test
    void multiHitPowerLabelUsesCombinedPowerAndHitCount() {
        MoveData data = new MoveData();
        data.id = "MULTI_HIT_CARD";
        data.name = "Multi Hit Card";
        data.tags = List.of("ATTACK", "PHYSICAL", "CURSED_ENERGY");
        data.apCost = 10;
        data.unleashPoint = 1;
        data.hitComponents = List.of(
            component(40, "PHYSICAL"),
            component(35, "CURSED_ENERGY"));

        assertEquals("PWR 75 | 2 HITS", MoveCardView.powerLabel(data.toMove()));
    }

    private static Move moveWithTags(String... tags) {
        return moveWithTags(false, tags);
    }

    private static Move moveWithTags(boolean weaponRequired, String... tags) {
        MoveData data = new MoveData();
        data.id = "CARD_TAG_TEST";
        data.name = "Card Tag Test";
        data.tags = List.of(tags);
        data.weaponRequired = weaponRequired;
        data.apCost = 10;
        data.unleashPoint = 1;
        if (data.tags.contains("NON_INNATE_TECHNIQUE")) {
            data.prerequisites = java.util.Map.of("jujutsuSkill", 0);
        }
        return data.toMove();
    }

    private static MoveData.HitComponentData component(int power, String tag) {
        MoveData.HitComponentData component = new MoveData.HitComponentData();
        component.basePower = power;
        component.tags = List.of(tag);
        return component;
    }
}

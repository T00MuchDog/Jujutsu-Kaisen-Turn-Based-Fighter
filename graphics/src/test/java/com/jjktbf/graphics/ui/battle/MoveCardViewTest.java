package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.graphics.Color;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveCardViewTest {

    @Test
    void windowsGeometryEnlargesCardBoundsWithoutChangingDefaultBounds() {
        Move move = moveWithTags("ATTACK", "PHYSICAL");

        MoveCardView defaultCard = new MoveCardView(move, 0f, 0f);
        MoveCardView windowsCard = new MoveCardView(move, 0f, 0f, 1.5f);

        assertEquals(240f, defaultCard.getBounds().width, 0.0001f);
        assertEquals(224f, defaultCard.getBounds().height, 0.0001f);
        assertEquals(360f, windowsCard.getBounds().width, 0.0001f);
        assertEquals(336f, windowsCard.getBounds().height, 0.0001f);
    }

    @Test
    void reinforcementDefenseUsesTheDeepLimePalette() {
        Move move = moveWithTags("DEFENSIVE", "PHYSICAL", "CURSED_ENERGY");

        assertEquals("REINFORCEMENT", MoveCardView.typeNameFor(move));
        assertEquals(new Color(0.310f, 0.540f, 0.140f, 1f), MoveCardView.typeColorFor(move));
    }

    @Test
    void cursedEnergyUtilityUsesAGreyBlueFusion() {
        Move move = moveWithTags("UTILITY", "CURSED_ENERGY");

        assertEquals("CURSED ENERGY", MoveCardView.typeNameFor(move));
        assertEquals(new Color(0.510f, 0.650f, 0.750f, 1f), MoveCardView.typeColorFor(move));
    }

    @Test
    void innateTechniqueRolePalettesKeepPurpleLavenderAndNearBlack() {
        Move attack = moveWithTags("ATTACK", "INNATE_TECHNIQUE", "CURSED_ENERGY");
        Move defensive = moveWithTags("DEFENSIVE", "INNATE_TECHNIQUE", "CURSED_ENERGY");
        Move utility = moveWithTags("UTILITY", "INNATE_TECHNIQUE", "CURSED_ENERGY");

        assertEquals(new Color(0.560f, 0.280f, 0.820f, 1f), MoveCardView.typeColorFor(attack));
        assertEquals(new Color(0.105f, 0.115f, 0.135f, 1f), MoveCardView.typeColorFor(defensive));
        assertEquals(new Color(0.640f, 0.560f, 0.760f, 1f), MoveCardView.typeColorFor(utility));
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
    void everyValidRoleAndNatureCombinationHasAUniqueColor() {
        String[][] natures = {
            {"PHYSICAL"},
            {"CURSED_ENERGY"},
            {"INNATE_TECHNIQUE", "CURSED_ENERGY"},
            {"NON_INNATE_TECHNIQUE", "CURSED_ENERGY"},
            {"PHYSICAL", "CURSED_ENERGY"},
            {"PHYSICAL", "INNATE_TECHNIQUE", "CURSED_ENERGY"},
            {"PHYSICAL", "NON_INNATE_TECHNIQUE", "CURSED_ENERGY"},
            {"INNATE_TECHNIQUE", "NON_INNATE_TECHNIQUE", "CURSED_ENERGY"},
            {"PHYSICAL", "INNATE_TECHNIQUE", "NON_INNATE_TECHNIQUE", "CURSED_ENERGY"}
        };
        List<Move> combinations = new ArrayList<>();
        for (String role : List.of("ATTACK", "DEFENSIVE", "UTILITY")) {
            for (String[] nature : natures) {
                combinations.add(moveWithTags(withRole(role, nature)));
            }
        }
        combinations.add(moveWithTags("DEFENSIVE"));
        combinations.add(moveWithTags("UTILITY"));
        combinations.add(moveWithTags(true, "ATTACK", "PHYSICAL", "CURSED_ENERGY"));
        combinations.add(moveWithTags(true, "DEFENSIVE", "PHYSICAL", "CURSED_ENERGY"));
        combinations.add(moveWithTags(true, "UTILITY", "PHYSICAL", "CURSED_ENERGY"));

        Set<Integer> colors = new HashSet<>();
        for (Move move : combinations) {
            Color color = MoveCardView.typeColorFor(move);
            assertTrue(colors.add(Color.rgba8888(color)),
                () -> "Duplicate move-card color " + color + " for " + move.getTags());
        }
        assertEquals(32, colors.size());
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

    @Test
    void accuracyLabelOnlyAppearsForMovesWithHitChecks() {
        Move attack = moveWithTags("ATTACK", "PHYSICAL");
        Move defensive = moveWithTags("DEFENSIVE", "PHYSICAL");
        Move utility = moveWithTags("UTILITY", "CURSED_ENERGY");

        assertEquals("ACC 100%", MoveCardView.accuracyLabel(attack));
        assertNull(MoveCardView.accuracyLabel(defensive));
        assertNull(MoveCardView.accuracyLabel(utility));
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
        Map<String, Integer> prerequisites = new HashMap<>();
        if (data.tags.contains("INNATE_TECHNIQUE")) {
            data.requiredTechniqueId = "TEST_TECHNIQUE";
            prerequisites.put("cursedTechniqueMastery", 0);
        }
        if (data.tags.contains("NON_INNATE_TECHNIQUE")) {
            prerequisites.put("jujutsuSkill", 0);
        }
        data.prerequisites = prerequisites;
        return data.toMove();
    }

    private static String[] withRole(String role, String[] nature) {
        String[] tags = new String[nature.length + 1];
        tags[0] = role;
        System.arraycopy(nature, 0, tags, 1, nature.length);
        return tags;
    }

    private static MoveData.HitComponentData component(int power, String tag) {
        MoveData.HitComponentData component = new MoveData.HitComponentData();
        component.basePower = power;
        component.tags = List.of(tag);
        return component;
    }
}

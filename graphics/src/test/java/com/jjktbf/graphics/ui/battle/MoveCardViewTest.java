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
    void attackingPhysicalCardsKeepThePhysicalAttackPalette() {
        Move move = moveWithTags("ATTACK", "PHYSICAL");

        assertEquals("PHYSICAL", MoveCardView.typeNameFor(move));
        assertEquals(new Color(0.850f, 0.380f, 0.190f, 1f), MoveCardView.typeColorFor(move));
    }

    private static Move moveWithTags(String... tags) {
        MoveData data = new MoveData();
        data.id = "CARD_TAG_TEST";
        data.name = "Card Tag Test";
        data.tags = List.of(tags);
        data.apCost = 10;
        data.unleashPoint = 1;
        return data.toMove();
    }
}

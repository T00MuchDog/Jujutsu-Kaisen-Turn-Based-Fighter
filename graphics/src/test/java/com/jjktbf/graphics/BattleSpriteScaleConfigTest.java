package com.jjktbf.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BattleSpriteScaleConfigTest {

    @Test
    void matchingBackSpriteUsesItsFrontSpritesScale() {
        assertEquals(1.2f, BattleSpriteScaleConfig.factorFor(
            "assets/sprites/shikigami/DivineDogTotality_frontsprite.png"), 0.0001f);
        assertEquals(1.2f, BattleSpriteScaleConfig.factorFor(
            "assets/sprites/shikigami/DivineDogTotality_backsprite.png"), 0.0001f);
    }

    @Test
    void unlistedSpritesUseNormalScale() {
        assertEquals(1f, BattleSpriteScaleConfig.factorFor(
            "assets/sprites/characters/yuji_frontsprite.png"), 0.0001f);
    }

}

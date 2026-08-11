package com.jjktbf.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BattleSpriteScaleConfigTest {

    @Test
    void backspriteEntryOverridesItsFrontspriteScale() {
        assertEquals(1.5f, BattleSpriteScaleConfig.factorFor(
            "assets/sprites/shikigami/DivineDogTotality_frontsprite.png"));
        assertEquals(1.2f, BattleSpriteScaleConfig.factorFor(
            "assets/sprites/shikigami/DivineDogTotality_backsprite.png"));
    }

    @Test
    void backspriteInheritsItsFrontspriteScaleWithoutAnOverride() {
        assertEquals(1.5f, BattleSpriteScaleConfig.factorFor(
            "assets/sprites/shikigami/MaxElephant_backsprite.png"));
    }
}

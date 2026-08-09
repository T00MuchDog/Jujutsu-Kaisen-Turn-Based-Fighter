package com.jjktbf.graphics;

import java.util.Map;

/** Code-only size settings for sprites drawn on the battle field. */
public final class BattleSpriteScaleConfig {

    public enum Scale {
        X_0_6(0.6f),
        X_0_8(0.8f),
        X_1_0(1f),
        X_1_2(1.2f),
        X_1_5(1.5f);

        private final float factor;

        Scale(float factor) {
            this.factor = factor;
        }

        public float factor() {
            return factor;
        }
    }

    private static final Scale DEFAULT_SCALE = Scale.X_1_0;

    /**
     * Add or change a front-sprite path here to set that sprite family's battle
     * size. Its matching _backsprite variant inherits the same setting.
     */
    private static final Map<String, Scale> SCALE_BY_SPRITE_ASSET = Map.ofEntries(
        Map.entry(
            "assets/sprites/shikigami/DivineDogTotality_frontsprite.png",
            Scale.X_1_2)
    );

    private BattleSpriteScaleConfig() {}

    /** Returns the configured factor, or the normal 1x size for unlisted sprites. */
    public static float factorFor(String spriteAsset) {
        if (spriteAsset == null || spriteAsset.isBlank()) return DEFAULT_SCALE.factor();

        Scale direct = SCALE_BY_SPRITE_ASSET.get(spriteAsset);
        if (direct != null) return direct.factor();
        return SCALE_BY_SPRITE_ASSET
            .getOrDefault(frontSpriteAsset(spriteAsset), DEFAULT_SCALE)
            .factor();
    }

    private static String frontSpriteAsset(String spriteAsset) {
        int extension = spriteAsset.lastIndexOf('.');
        if (extension <= spriteAsset.lastIndexOf('/')) return spriteAsset;

        String stem = spriteAsset.substring(0, extension);
        String suffix = spriteAsset.substring(extension);
        if (stem.endsWith("_backsprite")) {
            return stem.substring(0, stem.length() - "_backsprite".length())
                + "_frontsprite" + suffix;
        }
        if (stem.endsWith("_back")) {
            return stem.substring(0, stem.length() - "_back".length()) + suffix;
        }
        return spriteAsset;
    }
}

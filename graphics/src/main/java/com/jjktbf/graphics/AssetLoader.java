package com.jjktbf.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.graphics.ui.pixel.PixelSkin;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralised asset loading and disposal.
 *
 * All textures and fonts are loaded once when the game starts (via load())
 * and disposed when the game closes (via dispose()).
 *
 * Every Screen receives a reference to the single shared AssetLoader instance.
 * Screens must NEVER dispose these assets themselves.
 */
public class AssetLoader {

    /** Change this path to select a different battle execution backdrop. */
    public static final String BATTLE_EXECUTION_BACKGROUND_PATH =
        "assets/backgrounds/BattleSceneDay.png";

    // ── Fonts ─────────────────────────────────────────────────────────────────

    /** Small pixel font — used for body text, labels, move cards. */
    public BitmapFont fontSmall;

    /** Medium pixel font — used for panel headers and combatant names. */
    public BitmapFont fontMedium;

    /** Large pixel font — used for round banners and titles. */
    public BitmapFont fontLarge;

    /** Extra-large pixel font — used for prominent page titles (e.g. character name). */
    public BitmapFont fontXLarge;

    /** Medium-plus pixel font — used for the execution battle log body text. */
    public BitmapFont fontLog;

    // ── Sprites ───────────────────────────────────────────────────────────────

    public Texture playerSprite;
    public Texture enemySprite;
    /** Full-screen backdrop shown while a battle round is executing. */
    public Texture battleExecutionBackground;
    /** Ground plate drawn beneath both execution sprites. */
    public Texture stoneBasePlate;
    private final Map<String, Texture> characterSprites = new HashMap<>();

    // ── UI panels ─────────────────────────────────────────────────────────────

    /** Pixel-art textures dedicated to the drag-and-drop battle planner. */
    public BattleUiAssets battleUi;

    // ── Scene2D skin for the editors ───────────────────────────────────────────

    /**
     * Code-generated Scene2D skin matching the battle UI (panels, buttons,
     * sliders, textfields, scrollbars, etc.). Used by the menu and editors.
     */
    public Skin editorSkin;

    // ── Internal ──────────────────────────────────────────────────────────────

    private FreeTypeFontGenerator fontGenerator;

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    /**
     * Load all assets. Must be called from JJKGame.create() after the
     * LibGDX OpenGL context exists.
     */
    public void load() {
        loadFonts();
        loadSprites();
        loadBackgrounds();
        loadUi();
        loadSkin();
    }

    /**
     * Build the code-generated menu/editor skin. Uses the bundled TTF directly
     * so disposing the skin later cannot affect the battle UI fonts.
     */
    private void loadSkin() {
        editorSkin = PixelSkin.create();
    }

    /**
     * Glyphs are rendered this many times larger than their logical size, then
     * downscaled by the GPU so small text keeps every stroke. Exposed because
     * Scene2D's {@code setFontScale} is absolute (it overwrites the font's base
     * scale), so callers must multiply their layout scale by {@code 1/this} to
     * stay relative to the oversampled base.
     */
    public static final float FONT_OVERSAMPLE = 4f;

    /**
     * Generates a font oversampled so small text keeps every stroke.
     *
     * <p>FreeType rasters each glyph into a bitmap exactly {@code p.size} pixels
     * tall. At small sizes the pixel grid cannot represent thin strokes, so
     * crossbars and dots get dropped. To avoid this we render the glyphs
     * {@code FONT_OVERSAMPLE}× larger than requested (where the grid has ample
     * resolution), then set the font's base scale to {@code 1/FONT_OVERSAMPLE}
     * so its logical/visual size is unchanged, and let the GPU downscale with
     * mipmap + linear filtering for clean edges. Logical font sizes and all
     * layout stay identical to the non-oversampled case; only rendering quality
     * improves.
     *
     * <p>This is a stateless helper: it touches neither AssetLoader fields nor
     * the PixelSkin's private generator, so it does not break the existing
     * isolation between the two font owners.
     */
    public static BitmapFont generateOversampled(FreeTypeFontGenerator gen,
                                                 FreeTypeFontParameter p,
                                                 int logicalSize) {
        final float OVERSAMPLE = FONT_OVERSAMPLE;
        p.size = Math.round(logicalSize * OVERSAMPLE);
        p.genMipMaps = true;
        p.minFilter = Texture.TextureFilter.MipMapLinearNearest;
        p.magFilter = Texture.TextureFilter.Linear;
        BitmapFont font = gen.generateFont(p);
        // Restore logical size: glyphs were rendered OVERSAMPLE× too large.
        font.getData().setScale(1f / OVERSAMPLE);
        return font;
    }

    private void loadFonts() {
        com.badlogic.gdx.files.FileHandle ttf =
            Gdx.files.internal("assets/fonts/AtlantisInternational-jen0.ttf");
        fontGenerator = new FreeTypeFontGenerator(ttf);

        // One shared parameter; generateOversampled sets size + filtering per call.
        FreeTypeFontParameter p = new FreeTypeFontParameter();
        p.borderWidth = 0f;

        // Logical sizes match the on-screen size of the previous font; layout is
        // unchanged. generateOversampled renders them at 4x for crisp small text.
        fontSmall  = generateOversampled(fontGenerator, p, 15);
        fontMedium = generateOversampled(fontGenerator, p, 23);
        fontLarge  = generateOversampled(fontGenerator, p, 35);
        fontXLarge = generateOversampled(fontGenerator, p, 50);
        fontLog    = generateOversampled(fontGenerator, p, 31);

        // TTF no longer needed after bitmap generation
        fontGenerator.dispose();
        fontGenerator = null;
    }

    private void loadSprites() {
        playerSprite = new Texture(Gdx.files.internal("assets/sprites/player_placeholder.png"));
        enemySprite  = new Texture(Gdx.files.internal("assets/sprites/enemy_placeholder.png"));
    }

    private void loadBackgrounds() {
        battleExecutionBackground = new Texture(
            Gdx.files.internal(BATTLE_EXECUTION_BACKGROUND_PATH));
        battleExecutionBackground.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        stoneBasePlate = new Texture(
            Gdx.files.internal("assets/ui/common/baseplate/stone_baseplate.png"));
        stoneBasePlate.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
    }

    /** Load and cache a character sprite by its relative asset path. */
    public Texture characterSprite(String spriteAsset, Texture fallback) {
        if (spriteAsset == null || spriteAsset.isBlank()) return fallback;
        com.badlogic.gdx.files.FileHandle file = Gdx.files.internal(spriteAsset);
        if (!file.exists()) return fallback;
        return characterSprites.computeIfAbsent(spriteAsset, ignored -> new Texture(file));
    }

    /**
     * Load the forward sprite for the opponent, or a matching {@code _back}
     * variant for the player when one is bundled beside the character sprite.
     */
    public Texture characterBattleSprite(String spriteAsset, boolean opponent, Texture fallback) {
        Texture forward = characterSprite(spriteAsset, fallback);
        if (opponent || spriteAsset == null || spriteAsset.isBlank()) return forward;

        int extension = spriteAsset.lastIndexOf('.');
        if (extension <= spriteAsset.lastIndexOf('/')) return forward;
        String stem = spriteAsset.substring(0, extension);
        String suffix = spriteAsset.substring(extension);
        // Paired sprite sheets use the _frontsprite/_backsprite naming, e.g.
        // "yuji_frontsprite.png" pairs with "yuji_backsprite.png". Anything
        // else falls back to the legacy "_back" convention.
        String backAsset = stem.endsWith("_frontsprite")
            ? stem.substring(0, stem.length() - "_frontsprite".length()) + "_backsprite" + suffix
            : stem + "_back" + suffix;
        return characterSprite(backAsset, forward);
    }

    private void loadUi() {
        battleUi      = new BattleUiAssets();
    }

    // -------------------------------------------------------------------------
    // Dispose
    // -------------------------------------------------------------------------

    /**
     * Release all native GPU resources. Called once from JJKGame.dispose().
     */
    public void dispose() {
        if (fontSmall  != null) fontSmall.dispose();
        if (fontMedium != null) fontMedium.dispose();
        if (fontLarge  != null) fontLarge.dispose();
        if (fontXLarge != null) fontXLarge.dispose();
        if (fontLog    != null) fontLog.dispose();

        if (playerSprite != null) playerSprite.dispose();
        if (enemySprite  != null) enemySprite.dispose();
        if (battleExecutionBackground != null) battleExecutionBackground.dispose();
        if (stoneBasePlate != null) stoneBasePlate.dispose();
        characterSprites.values().forEach(Texture::dispose);
        characterSprites.clear();

        if (battleUi      != null) battleUi.dispose();

        if (editorSkin    != null) editorSkin.dispose();
    }
}

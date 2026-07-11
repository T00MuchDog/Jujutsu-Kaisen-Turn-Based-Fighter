package com.jjktbf.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.graphics.ui.pixel.PixelSkin;

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

    // ── Fonts ─────────────────────────────────────────────────────────────────

    /** Small pixel font — used for body text, labels, move cards. */
    public BitmapFont fontSmall;

    /** Medium pixel font — used for panel headers and combatant names. */
    public BitmapFont fontMedium;

    /** Large pixel font — used for round banners and titles. */
    public BitmapFont fontLarge;

    // ── Sprites ───────────────────────────────────────────────────────────────

    public Texture playerSprite;
    public Texture enemySprite;

    // ── UI panels ─────────────────────────────────────────────────────────────

    public Texture cardNormal;
    public Texture cardSelected;
    public Texture cardDisabled;

    /** Pixel-art textures dedicated to the drag-and-drop battle planner. */
    public BattleUiAssets battleUi;

    // ── Scene2D skin for the editors ───────────────────────────────────────────

    /**
     * Code-generated FRLG-style Scene2D skin (panels, buttons, sliders,
     * textfields, scrollbars, etc.). Used by all editor screens.
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
        loadUi();
        loadSkin();
    }

    /**
     * Build the code-generated editor skin. Uses the bundled TTF directly —
     * independent of the battle fonts above, so disposing the skin later
     * cannot affect the battle UI.
     */
    private void loadSkin() {
        editorSkin = PixelSkin.create();
    }

    private void loadFonts() {
        com.badlogic.gdx.files.FileHandle ttf =
            Gdx.files.internal("assets/fonts/PressStart2P-Regular.ttf");
        fontGenerator = new FreeTypeFontGenerator(ttf);

        FreeTypeFontParameter p = new FreeTypeFontParameter();

        p.size = 8;
        fontSmall = fontGenerator.generateFont(p);

        p.size = 12;
        fontMedium = fontGenerator.generateFont(p);

        p.size = 18;
        fontLarge = fontGenerator.generateFont(p);

        // TTF no longer needed after bitmap generation
        fontGenerator.dispose();
        fontGenerator = null;
    }

    private void loadSprites() {
        playerSprite = new Texture(Gdx.files.internal("assets/sprites/player_placeholder.png"));
        enemySprite  = new Texture(Gdx.files.internal("assets/sprites/enemy_placeholder.png"));
    }

    private void loadUi() {
        cardNormal    = new Texture(Gdx.files.internal("assets/ui/card_normal.png"));
        cardSelected  = new Texture(Gdx.files.internal("assets/ui/card_selected.png"));
        cardDisabled  = new Texture(Gdx.files.internal("assets/ui/card_disabled.png"));
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

        if (playerSprite != null) playerSprite.dispose();
        if (enemySprite  != null) enemySprite.dispose();

        if (cardNormal    != null) cardNormal.dispose();
        if (cardSelected  != null) cardSelected.dispose();
        if (cardDisabled  != null) cardDisabled.dispose();

        if (battleUi      != null) battleUi.dispose();

        if (editorSkin    != null) editorSkin.dispose();
    }
}

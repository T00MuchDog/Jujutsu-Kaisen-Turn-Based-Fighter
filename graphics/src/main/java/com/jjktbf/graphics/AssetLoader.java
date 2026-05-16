package com.jjktbf.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;

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

    public Texture panel;
    public Texture buttonNormal;
    public Texture buttonHover;
    public Texture buttonPressed;
    public Texture cardNormal;
    public Texture cardSelected;
    public Texture cardDisabled;

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
    }

    private void loadFonts() {
        // Try the Google Fonts export name first, fall back to the plain name
        com.badlogic.gdx.files.FileHandle ttf =
            Gdx.files.internal("assets/fonts/PressStart2P-Regular.ttf");
        if (!ttf.exists()) ttf = Gdx.files.internal("assets/fonts/PressStart2P-Regular.ttf");
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
        panel         = new Texture(Gdx.files.internal("assets/ui/panel.png"));
        buttonNormal  = new Texture(Gdx.files.internal("assets/ui/button_normal.png"));
        buttonHover   = new Texture(Gdx.files.internal("assets/ui/button_hover.png"));
        buttonPressed = new Texture(Gdx.files.internal("assets/ui/button_pressed.png"));
        cardNormal    = new Texture(Gdx.files.internal("assets/ui/card_normal.png"));
        cardSelected  = new Texture(Gdx.files.internal("assets/ui/card_selected.png"));
        cardDisabled  = new Texture(Gdx.files.internal("assets/ui/card_disabled.png"));
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

        if (panel         != null) panel.dispose();
        if (buttonNormal  != null) buttonNormal.dispose();
        if (buttonHover   != null) buttonHover.dispose();
        if (buttonPressed != null) buttonPressed.dispose();
        if (cardNormal    != null) cardNormal.dispose();
        if (cardSelected  != null) cardSelected.dispose();
        if (cardDisabled  != null) cardDisabled.dispose();
    }
}

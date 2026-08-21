package com.jjktbf.graphics.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.model.combat.BattleCombatant;

/** Pokemon-style field sprite and separate resource card for one combatant. */
public class CombatantPanel {

    private static final float DAMAGE_FLASH_DURATION_SECONDS = 0.72f;
    private static final float DAMAGE_FLASH_INTERVAL_SECONDS = 0.12f;
    /** Size the growing summon entrance starts from, as a fraction of the sprite. */
    private static final float SUMMON_GROW_MIN_SCALE = 0.1f;

    private final GlyphLayout nameLayout = new GlyphLayout();

    private final Texture sprite;
    private final Texture basePlate;
    private final BattleUiAssets ui;
    private final StatusBar hpBar;
    private final StatusBar ceBar;
    private final Rectangle plateBounds;
    private final Rectangle spriteBounds;
    private final Rectangle hudBounds;
    private final float hpBarTop;
    private final float hudScale;
    private final float textGeometryScale;
    private final boolean showResourceValues;
    private float damageFlashRemaining;

    /**
     * The plate and sprite occupy the battlefield while the HUD is positioned on
     * the opposite side, matching the classic monster-battle composition.
     */
    public CombatantPanel(Texture sprite, Texture basePlate, BattleUiAssets ui,
                           Rectangle plateBounds, Rectangle spriteBounds, Rectangle hudBounds,
                           float hudScale, boolean showResourceValues) {
        this(sprite, basePlate, ui, plateBounds, spriteBounds, hudBounds,
            hudScale, showResourceValues, 1f);
    }

    public CombatantPanel(Texture sprite, Texture basePlate, BattleUiAssets ui,
                           Rectangle plateBounds, Rectangle spriteBounds, Rectangle hudBounds,
                           float hudScale, boolean showResourceValues, float textGeometryScale) {
        this.sprite = sprite;
        this.basePlate = basePlate;
        this.ui = ui;
        this.plateBounds = new Rectangle(plateBounds);
        this.spriteBounds = new Rectangle(spriteBounds);
        this.hudBounds = new Rectangle(hudBounds);
        this.hudScale = hudScale;
        this.textGeometryScale = Math.max(1f, textGeometryScale);
        this.showResourceValues = showResourceValues;

        hpBar = new StatusBar("HP", new Color(0.260f, 0.820f, 0.360f, 1f),
            this.textGeometryScale);
        ceBar = new StatusBar("CE", new Color(0.220f, 0.500f, 0.940f, 1f),
            this.textGeometryScale);

        float inset = Math.max(scaled(10f) * hudScale, hudBounds.height * 0.11f);
        float gap = Math.max(scaled(4f) * hudScale, hudBounds.height * 0.045f);
        float nameBandHeight = scaled(25f) * hudScale;
        float barHeight = Math.max(scaled(18f) * hudScale,
            Math.min(scaled(25f) * hudScale,
                (hudBounds.height - inset * 2f - nameBandHeight - gap) / 2f));
        float barWidth = hudBounds.width - inset * 2f;
        float ceY = hudBounds.y + inset;
        ceBar.setBounds(hudBounds.x + inset, ceY, barWidth, barHeight);
        hpBar.setBounds(hudBounds.x + inset, ceY + barHeight + gap, barWidth, barHeight);
        hpBarTop = ceY + barHeight * 2f + gap;
    }

    private float scaled(float value) {
        return value * textGeometryScale;
    }

    public void update(BattleCombatant combatant) {
        update(
            combatant.getCurrentHp(),
            combatant.getMaxHp(),
            combatant.getCurrentCe(),
            combatant.getMaxCursedEnergy()
        );
    }

    /** Updates the shared HUD from an immutable authoritative snapshot. */
    public void update(int currentHp, int maxHp, int currentCe, int maxCe) {
        hpBar.setValues(currentHp, maxHp);
        ceBar.setValues(currentCe, maxCe);
    }

    /** Starts the rapid visible/invisible flicker used when this combatant takes damage. */
    public void flashDamage() {
        damageFlashRemaining = DAMAGE_FLASH_DURATION_SECONDS;
    }

    /** Ends damage flicker before the uninterrupted faint slide begins. */
    public void prepareFaint() {
        damageFlashRemaining = 0f;
    }

    /** Completes visual-only damage flicker and resource-bar trails immediately. */
    public void snapAnimations() {
        damageFlashRemaining = 0f;
        hpBar.snapToCurrent();
        ceBar.snapToCurrent();
    }

    public float spriteCenterX() {
        return spriteBounds.x + spriteBounds.width / 2f;
    }

    public float spriteCenterY() {
        return spriteBounds.y + spriteBounds.height / 2f;
    }

    /** The fighter's sprite texture, e.g. to derive a tinted copy for entrances. */
    public Texture spriteTexture() {
        return sprite;
    }

    public void draw(Batch batch, BitmapFont nameFont, BitmapFont barFont,
                     String name, float delta) {
        drawPlate(batch);
        drawSprite(batch, delta);
        drawHud(batch, nameFont, barFont, name, delta);
    }

    /** Draws the field plate separately so team layouts can share one plate. */
    public void drawPlate(Batch batch) {
        batch.setColor(Color.WHITE);
        if (basePlate != null) {
            batch.draw(basePlate, plateBounds.x, plateBounds.y, plateBounds.width, plateBounds.height);
        }
    }

    /** Draws only the fighter sprite, including its damage flicker. */
    public void drawSprite(Batch batch, float delta) {
        batch.setColor(Color.WHITE);
        boolean spriteVisible = damageFlashRemaining <= 0f
            || (int) ((DAMAGE_FLASH_DURATION_SECONDS - damageFlashRemaining)
                / DAMAGE_FLASH_INTERVAL_SECONDS) % 2 != 0;
        if (spriteVisible) {
            batch.draw(sprite, spriteBounds.x, spriteBounds.y, spriteBounds.width, spriteBounds.height);
        }
        damageFlashRemaining = Math.max(0f, damageFlashRemaining - Math.max(0f, delta));
    }

    /**
     * Slides the sprite down while cropping everything below its original foot
     * line, making the fighter disappear into the plate instead of painting over
     * the log area beneath it.
     */
    public void drawFaintingSprite(Batch batch, float slideRatio) {
        float clamped = Math.max(0f, Math.min(1f, slideRatio));
        float visibleRatio = 1f - clamped;
        if (visibleRatio <= 0f) return;

        float visibleHeight = spriteBounds.height * visibleRatio;
        int sourceHeight = Math.max(1, Math.min(sprite.getHeight(),
            Math.round(sprite.getHeight() * visibleRatio)));
        batch.setColor(Color.WHITE);
        batch.draw(sprite,
            spriteBounds.x, spriteBounds.y, spriteBounds.width, visibleHeight,
            0, 0, sprite.getWidth(), sourceHeight, false, false);
    }

    /**
     * The shared entrance animation for anyone arriving or changing on the
     * field — summoned or transformed, front or back sprite: the fighter
     * grows from tiny to full size anchored at its foot line while a white
     * silhouette overlay fades out, glowing into its true palette as it
     * arrives. {@code progress} is the raw 0 → 1 entrance progress.
     */
    public void drawEnteringSpriteGrow(Batch batch, float progress, Texture whiteSprite) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        float scale = entranceGrowScale(clamped);
        float width = spriteBounds.width * scale;
        float height = spriteBounds.height * scale;
        float x = spriteBounds.x + (spriteBounds.width - width) / 2f;
        float y = spriteBounds.y;
        batch.setColor(Color.WHITE);
        batch.draw(sprite, x, y, width, height);
        if (whiteSprite != null && clamped < 1f) {
            batch.setColor(1f, 1f, 1f, 1f - clamped);
            batch.draw(whiteSprite, x, y, width, height);
            batch.setColor(Color.WHITE);
        }
    }

    /** Scale of the growing entrance: from a tiny flash to the full sprite. */
    public static float entranceGrowScale(float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        return SUMMON_GROW_MIN_SCALE + (1f - SUMMON_GROW_MIN_SCALE) * clamped;
    }

    /** Draws only the resource card so it can be layered above a fighter pair. */
    public void drawHud(Batch batch, BitmapFont nameFont, BitmapFont barFont,
                        String name, float delta) {
        // Offset dark frame creates the hard lower-right shadow used by the reference HUD.
        ui.palette.draw(batch, hudBounds.x + 7f * hudScale, hudBounds.y - 7f * hudScale,
            hudBounds.width, hudBounds.height);
        ui.card.draw(batch, hudBounds.x, hudBounds.y, hudBounds.width, hudBounds.height);

        float originalScaleX = nameFont.getData().scaleX;
        float originalScaleY = nameFont.getData().scaleY;
        nameFont.getData().setScale(originalScaleX * hudScale, originalScaleY * hudScale);
        nameLayout.setText(nameFont, name);
        float availableNameWidth = hudBounds.width - scaled(28f) * hudScale;
        if (nameLayout.width > availableNameWidth) {
            float fittedScale = availableNameWidth / nameLayout.width;
            nameFont.getData().setScale(
                originalScaleX * hudScale * fittedScale,
                originalScaleY * hudScale * fittedScale);
            nameLayout.setText(nameFont, name);
        }
        nameFont.setColor(BattleUiAssets.TEXT);
        float originalNameY = hudBounds.y + hudBounds.height - scaled(10f) * hudScale;
        float currentGap = originalNameY - nameFont.getCapHeight() - hpBarTop;
        float nameY = currentGap > 0f ? originalNameY - currentGap / 2f : originalNameY;
        nameFont.draw(batch, name, hudBounds.x + scaled(15f) * hudScale, nameY);
        nameFont.getData().setScale(originalScaleX, originalScaleY);

        hpBar.update(delta);
        ceBar.update(delta);
        float originalBarScaleX = barFont.getData().scaleX;
        float originalBarScaleY = barFont.getData().scaleY;
        barFont.getData().setScale(originalBarScaleX * hudScale, originalBarScaleY * hudScale);
        hpBar.draw(batch, barFont, ui, showResourceValues);
        ceBar.draw(batch, barFont, ui, showResourceValues);
        barFont.getData().setScale(originalBarScaleX, originalBarScaleY);
    }
}

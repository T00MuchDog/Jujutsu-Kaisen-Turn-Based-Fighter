package com.jjktbf.graphics.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.jjktbf.model.combat.BattleCombatant;

/**
 * A panel that shows one combatant's sprite, name, HP bar, and CE bar.
 *
 * The panel is positioned at (x, y) — the bottom-left corner of the sprite.
 * Bars are drawn below the sprite; name is drawn above.
 */
public class CombatantPanel {

    private static final float BAR_W = 120f;
    private static final float BAR_H = 8f;
    private static final float BAR_GAP = 4f;
    private static final float SPRITE_W = 64f;
    private static final float SPRITE_H = 96f;

    private final Texture    sprite;
    private final StatusBar  hpBar;
    private final StatusBar  ceBar;
    private final float      x, y;

    public CombatantPanel(Texture sprite, float x, float y) {
        this.sprite = sprite;
        this.x      = x;
        this.y      = y;

        float barX = x - (BAR_W - SPRITE_W) / 2f; // centre bars under sprite
        float barY = y - BAR_H * 2 - BAR_GAP * 2 - 4;

        this.hpBar = new StatusBar("HP", new Color(0.2f, 0.8f, 0.2f, 1f));
        this.hpBar.setBounds(barX, barY + BAR_H + BAR_GAP, BAR_W, BAR_H);

        this.ceBar = new StatusBar("CE", new Color(0.2f, 0.4f, 1.0f, 1f));
        this.ceBar.setBounds(barX, barY, BAR_W, BAR_H);
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    public void update(BattleCombatant combatant) {
        hpBar.setValues(combatant.getCurrentHp(),
                        combatant.getEffectiveCombatStats().getMaxHp());
        ceBar.setValues(combatant.getCurrentCe(),
                        combatant.getEffectiveCombatStats().getMaxCursedEnergy());
    }

    // -------------------------------------------------------------------------
    // Draw
    // -------------------------------------------------------------------------

    /** Draw bars. Call inside ShapeRenderer.begin(ShapeRenderer.ShapeType.Filled). */
    public void drawShapes(ShapeRenderer sr) {
        hpBar.drawShapes(sr);
        ceBar.drawShapes(sr);
    }

    /** Draw sprite and text labels. Call inside SpriteBatch.begin(). */
    public void draw(SpriteBatch batch, BitmapFont font, String name) {
        batch.draw(sprite, x, y, SPRITE_W, SPRITE_H);
        hpBar.drawLabel(batch, font);
        ceBar.drawLabel(batch, font);

        // Name above the sprite
        font.setColor(Color.WHITE);
        font.draw(batch, name, x, y + SPRITE_H + 14);
    }
}

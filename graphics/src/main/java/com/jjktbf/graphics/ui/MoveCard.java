package com.jjktbf.graphics.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.model.move.Move;

/**
 * A clickable move card shown at the bottom of the battle screen during the
 * planning phase.
 *
 * Each card displays:
 *   - Move name (truncated to fit)
 *   - AP cost
 *   - CE cost
 *   - Selected / disabled state via different textures
 */
public class MoveCard {

    public static final float CARD_W = 120f;
    public static final float CARD_H = 60f;

    private final Move     move;
    private final Texture  texNormal;
    private final Texture  texSelected;
    private final Texture  texDisabled;
    private final Rectangle bounds;

    private boolean selected  = false;
    private boolean disabled  = false;

    public MoveCard(Move move, float x, float y,
                    Texture normal, Texture selected, Texture disabled) {
        this.move        = move;
        this.texNormal   = normal;
        this.texSelected = selected;
        this.texDisabled = disabled;
        this.bounds      = new Rectangle(x, y, CARD_W, CARD_H);
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    public void setSelected(boolean v)  { this.selected = v; }
    public void setDisabled(boolean v)  { this.disabled = v; }
    public boolean isSelected()         { return selected; }
    public boolean isDisabled()         { return disabled; }
    public Move getMove()               { return move; }

    /** Returns true if the screen-space point (x, y) is inside this card. */
    public boolean contains(float x, float y) { return bounds.contains(x, y); }

    public float getX() { return bounds.x; }
    public float getY() { return bounds.y; }

    // -------------------------------------------------------------------------
    // Draw
    // -------------------------------------------------------------------------

    /** Draw the card background and text. Call inside SpriteBatch.begin(). */
    public void draw(SpriteBatch batch, BitmapFont font) {
        // Background texture
        Texture bg = disabled ? texDisabled : (selected ? texSelected : texNormal);
        batch.draw(bg, bounds.x, bounds.y, CARD_W, CARD_H);

        // Move name — truncate at 12 characters to fit
        String name = move.getName();
        if (name.length() > 12) name = name.substring(0, 11) + ".";

        float textX = bounds.x + 4;
        float lineH = 12f;

        font.setColor(disabled ? Color.DARK_GRAY : Color.WHITE);
        font.draw(batch, name,                    textX, bounds.y + CARD_H - 8);
        font.draw(batch, "AP:" + move.getApCost(),textX, bounds.y + CARD_H - 8 - lineH);
        font.draw(batch, "CE:" + move.getBaseCeCost(), textX, bounds.y + CARD_H - 8 - lineH * 2);

        font.setColor(Color.WHITE); // reset
    }
}

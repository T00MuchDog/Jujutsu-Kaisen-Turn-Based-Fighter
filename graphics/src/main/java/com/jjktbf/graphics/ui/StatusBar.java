package com.jjktbf.graphics.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * A labelled bar widget that draws a filled rectangle for current / max.
 *
 * Used for HP bars and CE bars on the battle screen.
 * Draw order: ShapeRenderer pass first, then SpriteBatch label pass.
 */
public class StatusBar {

    private final String label;
    private final Color  fillColor;
    private final Color  emptyColor;

    private float x, y, width, height;
    private int   current, max;

    /**
     * @param label     text prefix (e.g. "HP", "CE")
     * @param fillColor colour of the filled portion
     */
    public StatusBar(String label, Color fillColor) {
        this.label      = label;
        this.fillColor  = fillColor;
        this.emptyColor = new Color(0.15f, 0.15f, 0.15f, 1f);
    }

    public void setBounds(float x, float y, float width, float height) {
        this.x = x; this.y = y; this.width = width; this.height = height;
    }

    public void setValues(int current, int max) {
        this.current = Math.max(0, current);
        this.max     = Math.max(1, max);
    }

    // -------------------------------------------------------------------------
    // Draw
    // -------------------------------------------------------------------------

    /**
     * Draw the bar background and fill. Call inside a ShapeRenderer.begin() block.
     */
    public void drawShapes(ShapeRenderer sr) {
        // Background (empty portion)
        sr.setColor(emptyColor);
        sr.rect(x, y, width, height);

        // Fill (current portion)
        float fillW = width * ((float) current / max);
        sr.setColor(fillColor);
        sr.rect(x, y, fillW, height);
    }

    /**
     * Draw the text label. Call inside a SpriteBatch.begin() block.
     */
    public void drawLabel(SpriteBatch batch, BitmapFont font) {
        String text = label + " " + current + "/" + max;
        font.draw(batch, text, x, y + height + 2);
    }
}

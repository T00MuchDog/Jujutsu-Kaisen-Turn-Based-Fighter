package com.jjktbf.graphics.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;

/** A compact pixel-framed resource bar used by the execution combatants. */
public class StatusBar {

    private static final Color DAMAGE_COLOR = new Color(0.920f, 0.220f, 0.180f, 1f);
    /** Fraction of the remaining gap eased per second toward the live value. */
    private static final float EASE_RATE = 1.8f;
    private static final float TEXT_INSET = 8f;
    private static final float TEXT_GAP = 4f;

    private final String label;
    private final Color fillColor;

    private float x;
    private float y;
    private float width;
    private float height;
    private int current;
    private int max;
    private final GlyphLayout labelLayout = new GlyphLayout();
    private final GlyphLayout valueLayout = new GlyphLayout();

    /** Smoothed value that trails {@code current}; the gap is drawn red as damage. */
    private float displayed = -1f;

    public StatusBar(String label, Color fillColor) {
        this.label = label;
        this.fillColor = fillColor;
    }

    public void setBounds(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void setValues(int current, int max) {
        this.current = Math.max(0, current);
        this.max = Math.max(1, max);
    }

    /** Advance the easing animation by {@code delta} seconds. */
    public void update(float delta) {
        if (displayed < 0f) {
            displayed = current; // first frame — snap to the live value
            return;
        }
        if (delta <= 0f) return;
        if (current > displayed) {
            // Healing — snap up so recovery reads instantly.
            displayed = current;
        } else if (current < displayed) {
            // Damage — ease the trailing value down toward the new current.
            float gap = displayed - current;
            gap -= gap * Math.min(1f, EASE_RATE * delta);
            displayed = current + gap;
        }
    }

    public void draw(Batch batch, BitmapFont font, BattleUiAssets ui) {
        ui.header.draw(batch, x, y, width, height);

        float inset = 5f;
        float inner = width - inset * 2f;
        float fillHeight = height - inset * 2f;

        // Live fill (green/blue) up to the current value.
        float fillWidth = Math.max(0f, inner * current / max);
        batch.setColor(fillColor);
        batch.draw(ui.pixel, x + inset, y + inset, fillWidth, fillHeight);

        // Damage trail: the portion between current and the still-easing
        // displayed value flashes red, then shrinks away as it catches up.
        if (displayed > current) {
            float trailWidth = Math.max(0f, inner * (displayed - current) / max);
            batch.setColor(DAMAGE_COLOR);
            batch.draw(ui.pixel, x + inset + fillWidth, y + inset, trailWidth, fillHeight);
        }
        batch.setColor(Color.WHITE);

        String value = current + "/" + max;
        float originalScaleX = font.getData().scaleX;
        float originalScaleY = font.getData().scaleY;
        labelLayout.setText(font, label);
        valueLayout.setText(font, value);

        // Preserve a gap between the label and value on compact battle layouts.
        float availableTextWidth = Math.max(1f, width - TEXT_INSET * 2f - TEXT_GAP);
        float combinedTextWidth = labelLayout.width + valueLayout.width;
        if (combinedTextWidth > availableTextWidth) {
            float fittedScale = availableTextWidth / combinedTextWidth;
            font.getData().setScale(originalScaleX * fittedScale, originalScaleY * fittedScale);
            labelLayout.setText(font, label);
            valueLayout.setText(font, value);
        }

        font.setColor(Color.WHITE);
        float textY = y + (height + font.getCapHeight()) / 2f;
        font.draw(batch, label, x + TEXT_INSET, textY);
        font.draw(batch, value, x + width - TEXT_INSET - valueLayout.width, textY);
        font.getData().setScale(originalScaleX, originalScaleY);
    }
}

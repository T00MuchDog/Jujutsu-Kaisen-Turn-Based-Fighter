package com.jjktbf.graphics.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;

/** A compact pixel-framed resource bar used by the execution combatants. */
public class StatusBar {

    private static final Color DAMAGE_COLOR = new Color(0.920f, 0.220f, 0.180f, 1f);
    private static final Color TRACK_COLOR = new Color(0.770f, 0.790f, 0.720f, 1f);
    /** Fraction of the remaining gap eased per second toward the live value. */
    private static final float EASE_RATE = 1.8f;

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
        float labelWidth = Math.max(38f, height * 1.55f);
        float trackX = x + labelWidth;
        float trackWidth = Math.max(1f, width - labelWidth);

        batch.setColor(BattleUiAssets.INK);
        batch.draw(ui.pixel, x, y, width, height);
        batch.setColor(TRACK_COLOR);
        batch.draw(ui.pixel, trackX + 3f, y + 3f, trackWidth - 6f, height - 6f);

        float fillX = trackX + 3f;
        float inner = trackWidth - 6f;
        float fillHeight = height - 6f;

        // Live fill (green/blue) up to the current value.
        float fillWidth = Math.max(0f, inner * current / max);
        batch.setColor(fillColor);
        batch.draw(ui.pixel, fillX, y + 3f, fillWidth, fillHeight);

        // Damage trail: the portion between current and the still-easing
        // displayed value flashes red, then shrinks away as it catches up.
        if (displayed > current) {
            float trailWidth = Math.max(0f, inner * (displayed - current) / max);
            batch.setColor(DAMAGE_COLOR);
            batch.draw(ui.pixel, fillX + fillWidth, y + 3f, trailWidth, fillHeight);
        }
        batch.setColor(Color.WHITE);

        String value = current + "/" + max;
        float originalScaleX = font.getData().scaleX;
        float originalScaleY = font.getData().scaleY;
        labelLayout.setText(font, label);
        valueLayout.setText(font, value);

        float availableValueWidth = Math.max(1f, trackWidth - 12f);
        if (valueLayout.width > availableValueWidth) {
            float fittedScale = availableValueWidth / valueLayout.width;
            font.getData().setScale(originalScaleX * fittedScale, originalScaleY * fittedScale);
            labelLayout.setText(font, label);
            valueLayout.setText(font, value);
        }

        font.setColor(Color.WHITE);
        float textY = y + (height + font.getCapHeight()) / 2f;
        font.draw(batch, label, x + (labelWidth - labelLayout.width) / 2f, textY);
        font.setColor(BattleUiAssets.INK);
        font.draw(batch, value, x + width - 7f - valueLayout.width, textY);
        font.getData().setScale(originalScaleX, originalScaleY);
        batch.setColor(Color.WHITE);
    }
}

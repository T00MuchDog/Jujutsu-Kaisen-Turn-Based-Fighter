package com.jjktbf.graphics.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;

/** A compact pixel-framed resource bar used by the execution combatants. */
public class StatusBar {

    private static final Color DAMAGE_COLOR = new Color(0.920f, 0.220f, 0.180f, 1f);
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

        font.setColor(Color.WHITE);
        font.draw(batch, label, x + 8f, y + height - 7f);
        font.draw(batch, current + "/" + max, x + width * 0.45f, y + height - 7f);
    }
}

package com.jjktbf.graphics.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;

/** A compact pixel-framed resource bar used by the execution combatants. */
public class StatusBar {

    private final String label;
    private final Color fillColor;

    private float x;
    private float y;
    private float width;
    private float height;
    private int current;
    private int max;

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

    public void draw(Batch batch, BitmapFont font, BattleUiAssets ui) {
        ui.header.draw(batch, x, y, width, height);

        float inset = 5f;
        float fillWidth = Math.max(0f, (width - inset * 2f) * current / max);
        batch.setColor(fillColor);
        batch.draw(ui.pixel, x + inset, y + inset, fillWidth, height - inset * 2f);
        batch.setColor(Color.WHITE);

        font.setColor(Color.WHITE);
        font.draw(batch, label, x + 8f, y + height - 7f);
        font.draw(batch, current + "/" + max, x + width * 0.45f, y + height - 7f);
    }
}

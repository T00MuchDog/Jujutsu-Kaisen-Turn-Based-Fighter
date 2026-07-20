package com.jjktbf.graphics.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.model.character.coded.CodedAbilityState;
import com.jjktbf.model.character.coded.MiraclesAbility;

/** Compact, player-visible resource meter for the Miracles coded ability. */
public final class MiraclesMeter {

    private static final String LABEL = "Miracles:";
    private static final float DISPLAY_SCALE = 2f;
    private static final Color GLOW = new Color(0.600f, 0.830f, 1.000f, 0.500f);
    private static final Color SPENT_FILL = new Color(0.410f, 0.440f, 0.520f, 1f);

    private final GlyphLayout labelLayout = new GlyphLayout();
    private float x;
    private float centerY;
    private int activeSegments;
    private boolean visible;

    /** Positions the meter immediately to the right of the player profile. */
    public void setPosition(float x, float centerY) {
        this.x = x;
        this.centerY = centerY;
    }

    /** Shows the meter only when the player has a live Miracles runtime. */
    public void setState(CodedAbilityState state) {
        if (state == null || !MiraclesAbility.KEY.equals(state.key())) {
            clear();
            return;
        }
        visible = true;
        activeSegments = Math.max(0, Math.min(MiraclesAbility.MAX_MIRACLES, state.currentValue()));
    }

    public void clear() {
        visible = false;
        activeSegments = 0;
    }

    public boolean isVisible() {
        return visible;
    }

    int activeSegments() {
        return activeSegments;
    }

    /** Draws six enlarged left-to-right vertical bars whose height matches the label text. */
    public void draw(Batch batch, BitmapFont font, BattleUiAssets ui) {
        if (!visible) return;

        float originalScaleX = font.getData().scaleX;
        float originalScaleY = font.getData().scaleY;
        font.getData().setScale(originalScaleX * DISPLAY_SCALE, originalScaleY * DISPLAY_SCALE);
        labelLayout.setText(font, LABEL);
        float segmentHeight = Math.max(1f, labelLayout.height);
        float segmentWidth = Math.max(4f, segmentHeight * 0.42f);
        float segmentGap = Math.max(2f, segmentWidth * 0.35f);
        float segmentX = x + labelLayout.width + segmentGap * 1.5f;
        float segmentY = centerY - segmentHeight / 2f;

        Color previousBatchColor = new Color(batch.getColor());
        Color previousFontColor = new Color(font.getColor());
        font.setColor(Color.BLACK);
        font.draw(batch, LABEL, x, centerY + labelLayout.height / 2f);

        for (int index = 0; index < MiraclesAbility.MAX_MIRACLES; index++) {
            drawSegment(batch, ui, segmentX + index * (segmentWidth + segmentGap), segmentY,
                segmentWidth, segmentHeight, index < activeSegments);
        }

        batch.setColor(previousBatchColor);
        font.setColor(previousFontColor);
        font.getData().setScale(originalScaleX, originalScaleY);
    }

    private static void drawSegment(
        Batch batch,
        BattleUiAssets ui,
        float x,
        float y,
        float width,
        float height,
        boolean active
    ) {
        float glow = Math.max(1f, height * 0.08f);
        float border = Math.max(1f, height * 0.04f);
        if (active) {
            batch.setColor(GLOW);
            batch.draw(ui.pixel, x - glow, y - glow, width + glow * 2f, height + glow * 2f);
        }
        batch.setColor(Color.BLACK);
        batch.draw(ui.pixel, x - border, y - border, width + border * 2f, height + border * 2f);
        batch.setColor(active ? Color.WHITE : SPENT_FILL);
        batch.draw(ui.pixel, x, y, width, height);
    }
}

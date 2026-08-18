package com.jjktbf.graphics.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.model.character.coded.CodedAbilityState;
import com.jjktbf.model.character.coded.RatioAbility;

/** Player-visible Ratio stack graphic for the Ratio coded ability. */
public final class RatioMeter {

    private static final float GRAPHIC_ASPECT_RATIO = 2f;

    private float x;
    private float y;
    private float height;
    private float textGeometryScale = 1f;
    private int stackCount;
    private boolean visible;

    /** Returns a responsive height for the authored 2:1 Ratio stack graphic. */
    public static float heightForViewport(float viewportHeight) {
        return heightForViewport(viewportHeight, 1f);
    }

    /** Windows overload that reserves explicit height for the enlarged multiplier. */
    public static float heightForViewport(float viewportHeight, float geometryScale) {
        float scale = Math.max(1f, geometryScale);
        return Math.min(90f * scale,
            Math.max(54f * scale, viewportHeight * 0.08f * scale));
    }

    /** Returns the width that preserves the Ratio stack graphic's authored aspect ratio. */
    public static float widthForHeight(float height) {
        return Math.max(0f, height) * GRAPHIC_ASPECT_RATIO;
    }

    /** Positions the 2:1 Ratio stack graphic. */
    public void setBounds(float x, float y, float height) {
        setBounds(x, y, height, 1f);
    }

    public void setBounds(float x, float y, float height, float textGeometryScale) {
        this.x = x;
        this.y = y;
        this.height = height;
        this.textGeometryScale = Math.max(1f, textGeometryScale);
    }

    /** Shows the stack graphic only while the player has one or more active Ratio stacks. */
    public void setState(CodedAbilityState state) {
        if (state == null || !RatioAbility.KEY.equals(state.key()) || state.currentValue() <= 0) {
            clear();
            return;
        }
        visible = true;
        stackCount = Math.max(1,
            Math.min(Math.max(1, state.maximumValue()), state.currentValue()));
    }

    public void clear() {
        visible = false;
        stackCount = 0;
    }

    public boolean isVisible() {
        return visible;
    }

    int stackCount() {
        return stackCount;
    }

    /** Draws the Ratio graphic and, for multiple stacks, a large multiplier beneath it. */
    public void draw(Batch batch, BattleUiAssets ui, BitmapFont multiplierFont) {
        if (!visible) return;

        float width = widthForHeight(height);
        Color previousBatchColor = new Color(batch.getColor());
        Color previousFontColor = new Color(multiplierFont.getColor());
        batch.setColor(Color.WHITE);
        batch.draw(ui.ratioStack, x, y, width, height);

        if (stackCount > 1) {
            String label = stackCount + "x";
            GlyphLayout layout = new GlyphLayout(multiplierFont, label);
            float labelX = x + (width - layout.width) / 2f;
            float labelY = y - Math.max(5f * textGeometryScale,
                multiplierFont.getCapHeight() * 0.2f);
            multiplierFont.setColor(Color.BLACK);
            multiplierFont.draw(batch, label, labelX + 2f * textGeometryScale,
                labelY - 2f * textGeometryScale);
            multiplierFont.setColor(Color.WHITE);
            multiplierFont.draw(batch, label, labelX, labelY);
        }

        multiplierFont.setColor(previousFontColor);
        batch.setColor(previousBatchColor);
    }
}
